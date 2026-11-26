package com.xcloud.tclnative.auth

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

data class OAuthToken(val ticket: String, val refreshToken: String?, val expiresAtMs: Long?, val clientId: String) {
    override fun toString() = "OAuthToken(redacted)"
}
internal data class SavedXboxAccount(val id: String, val name: String?, val active: Boolean)

interface OAuthTokenStore {
    val coordinationKey: Any get() = this
    fun read(): OAuthToken?
    fun write(token: OAuthToken)
    fun clear()
}

internal interface SavedAccountStore : OAuthTokenStore {
    fun accounts(): List<SavedXboxAccount>
    fun addAndActivate(token: OAuthToken)
    fun activate(id: String): Boolean
    fun bindIdentity(hash: String, xuid: String?, gamertag: String?)
}

internal class PreferencesTokenStore(context: Context) : SavedAccountStore {
    private data class Identity(val name: String? = null, val xuid: String? = null, val hash: String? = null)
    private data class Account(val id: String, val token: OAuthToken, val identity: Identity = Identity())
    private val prefs = context.getSharedPreferences("xcloud_native_auth", Context.MODE_PRIVATE)
    override val coordinationKey: Any get() = prefs
    private val activeId get() = prefString("active_id") ?: "legacy"
    private fun archive(): JSONObject? = try {
        JSONObject(prefString("saved_accounts") ?: "{}")
    } catch (_: JSONException) {
        null
    }

    override fun read(): OAuthToken? {
        val ticket = prefString("rps_ticket") ?: return null
        return OAuthToken(ticket, prefString("refresh_token"),
            if (prefs.contains("expires_at_ms")) prefExpiry("expires_at_ms") else prefExpiry("expires_at"),
            prefString("client_id") ?: XalAuthenticator.DEVICE_CLIENT_ID)
    }
    override fun write(token: OAuthToken) {
        prefs.edit().writeToken(token).apply()
    }
    override fun clear() {
        prefs.edit().remove("rps_ticket").remove("refresh_token").remove("expires_at_ms").remove("expires_at")
            .remove("client_id").remove("active_id").remove("gamertag").remove("xuid").remove("user_hash").apply()
    }

    override fun accounts(): List<SavedXboxAccount> {
        val active = activeAccount()
        val saved = archive()?.let { archive ->
            archive.keys().asSequence().filter { it != active?.id }
                .mapNotNull { id -> archive.account(id)?.let { SavedXboxAccount(id, it.identity.name, false) } }.toList()
        }.orEmpty()
        return listOfNotNull(active?.let { SavedXboxAccount(it.id, it.identity.name, true) }) + saved
    }

    override fun addAndActivate(token: OAuthToken) {
        swapActive(Account(UUID.randomUUID().toString(), token), archive() ?: JSONObject())
    }

    override fun activate(id: String): Boolean {
        if (id == activeId && read() != null) return true
        val saved = archive() ?: return false
        val next = saved.account(id) ?: return false
        saved.remove(id)
        swapActive(next, saved)
        return true
    }

    override fun bindIdentity(hash: String, xuid: String?, gamertag: String?) {
        val active = activeAccount() ?: return
        val saved = archive()
        var name = gamertag ?: active.identity.name
        val knownXuid = xuid ?: active.identity.xuid.takeIf { hash == active.identity.hash }
        saved?.let { archive ->
            val ids = archive.keys()
            while (ids.hasNext()) {
                val account = archive.account(ids.next()) ?: continue
                val otherXuid = account.identity.xuid
                val same = if (knownXuid != null && otherXuid != null) knownXuid == otherXuid else hash == account.identity.hash
                if (same) {
                    name = name ?: account.identity.name
                    ids.remove()
                }
            }
        }
        // Updating the profile is safe even if the old archive cannot be read. Leave it in place.
        prefs.edit().writeIdentity(Identity(name, knownXuid, hash)).apply {
            if (saved != null) putString("saved_accounts", saved.toString())
        }.apply()
    }

    private fun activeAccount() = read()?.let {
        Account(activeId, it, Identity(prefString("gamertag"), prefString("xuid"), prefString("user_hash")))
    }

    private fun JSONObject.account(id: String): Account? = optJSONObject(id)?.let { stored ->
        stored.token()?.let { Account(id, it, Identity(stored.value("gamertag"), stored.value("xuid"), stored.value("hash"))) }
    }

    /** One editor publishes the previous account and the new active login together. */
    private fun swapActive(next: Account, saved: JSONObject) {
        activeAccount()?.let { old ->
            saved.put(old.id, JSONObject().put("ticket", old.token.ticket).put("refresh", old.token.refreshToken)
                .put("expiry", old.token.expiresAtMs).put("client", old.token.clientId)
                .put("gamertag", old.identity.name).put("xuid", old.identity.xuid).put("hash", old.identity.hash))
        }
        prefs.edit().writeToken(next.token).writeIdentity(next.identity).putString("active_id", next.id)
            .putString("saved_accounts", saved.toString()).apply()
    }

    private fun SharedPreferences.Editor.writeIdentity(identity: Identity): SharedPreferences.Editor =
        putString("gamertag", identity.name).putString("xuid", identity.xuid).putString("user_hash", identity.hash)

    private fun SharedPreferences.Editor.writeToken(token: OAuthToken): SharedPreferences.Editor =
        putString("rps_ticket", token.ticket).putString("refresh_token", token.refreshToken)
            .putLong("expires_at_ms", token.expiresAtMs ?: 0).putString("client_id", token.clientId)

    private fun JSONObject.value(key: String) = (opt(key) as? String)?.takeIf(String::isNotBlank)
    private fun JSONObject.token(): OAuthToken? {
        val ticket = value("ticket") ?: return null
        val client = value("client") ?: return null
        return OAuthToken(ticket, value("refresh"), optLong("expiry").takeIf { it > 0 }, client)
    }

    private fun prefString(key: String) = try {
        prefs.getString(key, null)?.takeIf(String::isNotBlank)
    } catch (_: ClassCastException) {
        null
    }

    private fun prefExpiry(key: String) = try {
        prefs.getLong(key, 0).takeIf { it > 0 }
    } catch (_: ClassCastException) {
        null
    }
}
