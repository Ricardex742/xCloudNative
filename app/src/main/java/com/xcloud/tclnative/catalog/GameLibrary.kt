package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.content.SharedPreferences
import com.xcloud.tclnative.signaling.GameTitle
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

enum class LibraryTab(private val sourceLabel: String) {
    RECENT("VOLTAR A JOGAR"), FAVORITES("FAVORITOS"), GAME_PASS("GAME PASS"), PURCHASED("TRANSMITA SEU JOGO"),
    ADDED("ADIÇÕES RECENTES"), ALL("TODOS OS JOGOS");
    val label: String get()=UiText.text(sourceLabel)
}

data class PlayRecord(val lastPlayed: Long, val launches: Int)

/** Cloud access programs (including GPULTIMATE) also cover BYOG; only the official gallery proves membership. */
object GamePassCatalog {
    fun productKey(id: String) = id.trim().uppercase(Locale.ROOT)
    fun contains(game: GameTitle, normalizedProducts: Set<String>, resolvedTitles: Set<String> = emptySet()): Boolean =
        (game.productId?.let { productKey(it) in normalizedProducts } == true ||
            productKey(game.titleId) in resolvedTitles)
}

/** Only a rendered first frame counts as played; a cancelled cloud queue does not. */
class GameLibrary(private val context: Context, accountId: String, legacyAccountId: String? = null) {
    // The UI reads the last committed favorites independently of the writer's disk lock.
    private class FavoriteSnapshot(@Volatile var saved: Set<String>)
    private class Account(val prefs: SharedPreferences, val favorites: FavoriteSnapshot)

    @Volatile private var account = preferences(accountId).let { old ->
        old.getString("migrated_to", null)?.takeIf { it.matches(Regex("xuid:[0-9]{1,20}")) }
            ?.let(::preferences) ?: old
    }.let(::openAccount)
    init {
        if (legacyAccountId != null && legacyAccountId != accountId) synchronized(libraryWrites) {
            migrate(preferences(legacyAccountId), accountId, account)
        }
    }
    private fun preferences(account: String) = context.getSharedPreferences("library_" + accountKey(account), Context.MODE_PRIVATE)
    private fun openAccount(prefs: SharedPreferences): Account {
        synchronized(favoriteSnapshots) { favoriteSnapshots[prefs] }?.let { return Account(prefs, it) }
        val saved = Collections.unmodifiableSet(LinkedHashSet(prefs.getStringSet("favorites", emptySet()).orEmpty()))
        val snapshot = synchronized(favoriteSnapshots) { favoriteSnapshots.getOrPut(prefs) { FavoriteSnapshot(saved) } }
        return Account(prefs, snapshot)
    }
    /** A verified XUID replaces a relying-party hash without discarding this account's old library. */
    fun bindXboxAccount(xuid: String): Unit = synchronized(libraryWrites) {
        require(xuid.matches(Regex("[0-9]{1,20}")))
        val accountId = "xuid:$xuid"
        val previous = account.prefs
        val target = preferences(accountId)
        if (previous !== target) {
            val verified = openAccount(target)
            migrate(previous, accountId, verified)
            account = verified
        }
    }
    private fun migrate(old: SharedPreferences, accountId: String, target: Account) {
        val prefs = target.prefs
        if (old === prefs) return
        val owner = old.getString("migrated_to", null)
        if (owner != null) return // Never resurrect a removed favorite on the next login.
        val merged = target.favorites.saved + openAccount(old).favorites.saved
        val plays = runCatching { JSONObject(prefs.getString("plays", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val legacy = runCatching { JSONObject(old.getString("plays", "{}") ?: "{}") }.getOrDefault(JSONObject())
        legacy.keys().forEach { id -> if (!plays.has(id)) plays.put(id, legacy.get(id)) }
        val editor=prefs.edit().putStringSet("favorites",merged).putString("plays",plays.toString())
        if(old.contains("cloud_recent") && (!prefs.contains("cloud_recent") || old.getLong("cloud_recent_at",0)>prefs.getLong("cloud_recent_at",0)))
            editor.putString("cloud_recent",old.getString("cloud_recent",null)).putLong("cloud_recent_at",old.getLong("cloud_recent_at",0))
        check(editor.commit())
        target.favorites.saved = Collections.unmodifiableSet(merged)
        check(old.edit().putString("migrated_to", accountId).commit())
    }
    val favorites: Set<String> get() = account.favorites.saved
    fun isFavorite(id: String) = favorites.any { it.equals(id, true) }
    fun toggleFavorite(id: String): Boolean = synchronized(libraryWrites) {
        val target = account
        val previous = target.favorites.saved
        val next = previous.toMutableSet()
        val existing = next.firstOrNull { it.equals(id, true) }
        val added = if (existing != null) { next.remove(existing); false } else { next.add(id); true }
        // Complete the disk write before acknowledging the star, including immediate app exits.
        if (!target.prefs.edit().putStringSet("favorites", next).commit()) {
            // A failed commit has already changed SharedPreferences' in-process map.
            target.prefs.edit().putStringSet("favorites", previous).apply()
            throw java.io.IOException(UiText.text("Não foi possível salvar os favoritos no aparelho."))
        }
        target.favorites.saved = Collections.unmodifiableSet(next)
        added
    }
    fun recordPlayed(id: String, now: Long = System.currentTimeMillis()) {
        val prefs = account.prefs
        val data = runCatching { JSONObject(prefs.getString("plays", "{}")!!) }.getOrDefault(JSONObject())
        val old = data.optJSONObject(id)?.optInt("launches") ?: 0
        data.put(id, JSONObject().put("lastPlayed", now).put("launches", old + 1))
        prefs.edit().putString("plays", data.toString()).apply()
    }
    fun records(): Map<String, PlayRecord> {
        val prefs = account.prefs
        val data = runCatching { JSONObject(prefs.getString("plays", "{}")!!) }.getOrDefault(JSONObject())
        return data.keys().asSequence().mapNotNull { id -> data.optJSONObject(id)?.let {
            id to PlayRecord(it.optLong("lastPlayed"), it.optInt("launches"))
        } }.toMap()
    }
    fun cloudRecent(): List<String>? = runCatching {
        val text=account.prefs.getString("cloud_recent",null) ?: return null
        if(text.length>16384) return null
        val array=org.json.JSONArray(text)
        (0 until minOf(array.length(),25)).mapNotNull { array.optString(it)?.takeIf { id -> id.isNotBlank() && id!="null" && id.length<=256 } }
    }.getOrNull()
    fun saveCloudRecent(ids: List<String>): Unit = synchronized(libraryWrites) {
        val prefs = account.prefs
        val data=ids.filter { it.isNotBlank() && it.length<=256 }.distinctBy { it.uppercase(Locale.ROOT) }.take(25)
        if(!prefs.edit().putString("cloud_recent",org.json.JSONArray(data).toString()).putLong("cloud_recent_at",System.currentTimeMillis()).commit())
            throw java.io.IOException(UiText.text("Não foi possível salvar o histórico do Xbox neste aparelho."))
    }
    companion object {
        private val libraryWrites = Any()
        private val favoriteSnapshots = WeakHashMap<SharedPreferences, FavoriteSnapshot>()
        fun accountKey(account: String): String = MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        @Suppress("UNUSED_PARAMETER")
        fun select(games: List<GameTitle>, tab: LibraryTab, favorites: Set<String>, records: Map<String, PlayRecord>,
                   gamePass: List<String>?, added: List<String>?, query: String = "",
                   gamePassTitles: Set<String> = emptySet(),cloudRecent: List<String>? = null): List<GameTitle> {
            val selected = when (tab) {
                LibraryTab.ALL -> games
                LibraryTab.FAVORITES -> games.filter { game -> favorites.any { it.equals(game.titleId, true) } }
                LibraryTab.RECENT -> CloudPlayHistory.select(games,cloudRecent ?: emptyList())
                LibraryTab.GAME_PASS -> {
                    val products=(gamePass ?: emptyList()).map(GamePassCatalog::productKey).toSet()
                    games.filter { GamePassCatalog.contains(it,products,gamePassTitles) }.sortedBy { it.name.lowercase(Locale.ROOT) }
                }
                LibraryTab.ADDED -> ordered(games, added ?: emptyList())
                LibraryTab.PURCHASED -> emptyList() // The public purchased gallery is not inferred from native access programs.
            }
            return selected.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        }
        private fun ordered(games: List<GameTitle>, products: List<String>): List<GameTitle> {
            val index = products.map(GamePassCatalog::productKey).withIndex().associate { it.value to it.index }
            return games.filter { it.productId?.let(GamePassCatalog::productKey) in index }
                .sortedBy { index[it.productId?.let(GamePassCatalog::productKey)] }
        }
    }
}
