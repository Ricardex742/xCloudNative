package com.xcloud.tclnative.auth

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

data class OAuthToken(val ticket: String, val refreshToken: String?, val expiresAtMs: Long?, val clientId: String)
internal data class SavedXboxAccount(val id: String, val name: String?, val active: Boolean)

interface OAuthTokenStore {
    val coordinationKey: Any get() = this
    fun read(): OAuthToken?
    fun write(token: OAuthToken)
    fun clear()
}

class PreferencesTokenStore(context: Context) : OAuthTokenStore {
    private val prefs = context.getSharedPreferences("xcloud_native_auth", Context.MODE_PRIVATE)
    override val coordinationKey: Any get() = prefs
    private val activeId get() = prefs.getString("active_id", null) ?: "legacy"
    private fun archive() = JSONObject(prefs.getString("saved_accounts", null) ?: "{}")
    override fun read(): OAuthToken? {
        val ticket = prefs.getString("rps_ticket", null)?.takeIf { it.isNotBlank() } ?: return null
        return OAuthToken(ticket, prefs.getString("refresh_token", null)?.takeIf { it.isNotBlank() },
            prefs.getLong("expires_at_ms", prefs.getLong("expires_at", 0)).takeIf { it > 0 },
            prefs.getString("client_id", null) ?: XalAuthenticator.DEVICE_CLIENT_ID)
    }
    override fun write(token: OAuthToken) {
        prefs.edit().writeToken(token).apply()
    }
    override fun clear() {
        prefs.edit().remove("rps_ticket").remove("refresh_token").remove("expires_at_ms").remove("expires_at")
            .remove("client_id").remove("active_id").remove("gamertag").remove("xuid").remove("user_hash").apply()
    }

    internal fun accounts(): List<SavedXboxAccount> {
        val saved = archive()
        return listOfNotNull(read()?.let { SavedXboxAccount(activeId, prefs.getString("gamertag", null), true) }) +
            saved.keys().asSequence().mapNotNull { id ->
                saved.optJSONObject(id)?.let { SavedXboxAccount(id, it.name(), false) }
            }.toList()
    }

    internal fun addAndActivate(token: OAuthToken) {
        val saved = archive()
        saveActive(saved)
        prefs.edit().writeToken(token).putString("active_id", UUID.randomUUID().toString())
            .remove("gamertag").remove("xuid").remove("user_hash").putString("saved_accounts", saved.toString()).apply()
    }

    internal fun activate(id: String): Boolean {
        if (id == activeId && read() != null) return true
        val saved = archive()
        val next = saved.optJSONObject(id) ?: return false
        val token = OAuthToken(next.getString("ticket"), next.value("refresh"),
            next.optLong("expiry").takeIf { it > 0 }, next.getString("client"))
        saved.remove(id)
        saveActive(saved)
        prefs.edit().writeToken(token).putString("active_id", id).putString("gamertag", next.name())
            .putString("xuid", next.value("xuid")).putString("user_hash", next.value("hash"))
            .putString("saved_accounts", saved.toString()).apply()
        return true
    }

    internal fun bindIdentity(hash: String, xuid: String?, gamertag: String?) {
        if (read() == null) return
        val saved = archive()
        var name = gamertag ?: prefs.getString("gamertag", null)
        val knownXuid = xuid ?: prefs.getString("xuid", null).takeIf { hash == prefs.getString("user_hash", null) }
        val ids = saved.keys()
        while (ids.hasNext()) {
            val account = saved.getJSONObject(ids.next())
            val otherXuid = account.value("xuid")
            val same = if (knownXuid != null && otherXuid != null) knownXuid == otherXuid else hash == account.value("hash")
            if (same) {
                name = name ?: account.name()
                ids.remove()
            }
        }
        prefs.edit().putString("gamertag", name).putString("xuid", knownXuid).putString("user_hash", hash)
            .putString("saved_accounts", saved.toString()).apply()
    }

    private fun saveActive(saved: JSONObject) {
        val token = read() ?: return
        saved.put(activeId, JSONObject().put("ticket", token.ticket).put("refresh", token.refreshToken)
            .put("expiry", token.expiresAtMs).put("client", token.clientId).put("gamertag", prefs.getString("gamertag", null))
            .put("xuid", prefs.getString("xuid", null)).put("hash", prefs.getString("user_hash", null)))
    }

    private fun SharedPreferences.Editor.writeToken(token: OAuthToken): SharedPreferences.Editor =
        putString("rps_ticket", token.ticket).putString("refresh_token", token.refreshToken)
            .putLong("expires_at_ms", token.expiresAtMs ?: 0).putString("client_id", token.clientId)

    private fun JSONObject.value(key: String) = (opt(key) as? String)?.takeIf(String::isNotBlank)
    private fun JSONObject.name() = value("gamertag")
}
