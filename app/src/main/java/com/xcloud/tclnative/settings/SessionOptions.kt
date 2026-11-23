package com.xcloud.tclnative.settings

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest

enum class IpPreference(private val sourceLabel: String,val preferredFamily: Int? = null) {
    AUTO("Automático — IPv4/IPv6 durante a partida"),
    IPV4("Preferir IPv4 — com IPv6 disponível",4),
    IPV6("Preferir IPv6 — experimental, com IPv4 disponível",6);
    val label: String get()=UiText.text(sourceLabel)
}
enum class DesktopInputMode(private val sourceLabel: String) { OFF("Desativado"), EMULATED("Emular controle com mouse e teclado"), NATIVE("Mouse e teclado nativos — jogos compatíveis") ;
    val label: String get()=UiText.text(sourceLabel)
}
enum class GyroMode(private val sourceLabel: String) { OFF("Desativado"), LT("Enquanto segurar LT"), LB("Enquanto segurar LB"), ALWAYS("Sempre durante a partida") ;
    val label: String get()=UiText.text(sourceLabel)
}

data class SessionOptions(val maxBitrateKbps: Int=0,val locale: String="pt-BR",val region: String="") {
    fun normalized()=copy(maxBitrateKbps=if(maxBitrateKbps==0) 0 else maxBitrateKbps.coerceIn(1000,50000),
        locale=locale.takeIf { it in LOCALES } ?: "pt-BR",region=region.take(100))
    companion object {
        val LOCALES=linkedMapOf("pt-BR" to "Português (Brasil)","en-US" to "English (US)","es-MX" to "Español (México)",
            "es-ES" to "Español (España)","fr-FR" to "Français","de-DE" to "Deutsch","it-IT" to "Italiano","ja-JP" to "日本語",
            "zh-CN" to "简体中文（中国大陆）", "zh-TW" to "繁體中文（台灣）")
    }
}

enum class SessionOptionField(val key: String) { BITRATE("bitrate"), REGION("region"), LOCALE("locale") }

/** Account-scoped, field-level overrides. Existing full snapshots remain explicit overrides. */
class SessionOptionsStore(private val context: Context, account: String, legacyAccount: String? = null) {
    private val accountPrefs = preferences(account)
    private val prefs get() = accountPrefs.getString("migrated_to", null)
        ?.takeIf(verifiedAccount::matches)?.let(::preferences) ?: accountPrefs

    init {
        if (legacyAccount != null && legacyAccount != account && verifiedAccount.matches(account)) {
            synchronized(writes) { migrate(preferences(legacyAccount), account) }
        }
    }

    private fun preferences(account: String): SharedPreferences {
        val scope = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
        return context.getSharedPreferences("session_options_$scope", Context.MODE_PRIVATE)
    }

    private fun migrate(previous: SharedPreferences, account: String) {
        if (previous.contains("migrated_to")) return
        val editor = accountPrefs.edit()
        for ((key, raw) in previous.all) {
            if ((key != "default" && !key.startsWith("game:")) || raw !is String) continue
            val old = parse(raw)
            val saved = parse(accountPrefs.getString(key, null))
            for (field in SessionOptionField.entries) {
                if (!saved.has(field.key) && old.has(field.key)) saved.put(field.key, old.get(field.key))
            }
            if (saved.length() > 0) editor.putString(key, saved.toString())
        }
        if (editor.commit()) previous.edit().putString("migrated_to", account).commit()
    }

    private companion object {
        val verifiedAccount = Regex("xuid:[0-9]{1,20}")
        val writes = Any()
        fun parse(raw: String?) = runCatching { JSONObject(raw ?: "{}") }.getOrElse { JSONObject() }
    }
    fun read(game: String?=null): SessionOptions {
        val stored = prefs
        val defaults=decode(stored.getString("default",null),SessionOptions())
        return if(game==null) defaults else decode(stored.getString("game:$game",null),defaults)
    }
    fun hasOverride(game: String)=prefs.contains("game:$game")
    fun hasOverride(game: String,field: SessionOptionField)=raw(game).has(field.key)
    fun set(game: String?,field: SessionOptionField,value: Any) = synchronized(writes) {
        val current=read(game)
        val normalized=when(field) {
            SessionOptionField.BITRATE -> current.copy(maxBitrateKbps=value as Int).normalized().maxBitrateKbps
            SessionOptionField.REGION -> current.copy(region=value as String).normalized().region
            SessionOptionField.LOCALE -> current.copy(locale=value as String).normalized().locale
        }
        val key=if(game==null) "default" else "game:$game"
        prefs.edit().putString(key,raw(game).put(field.key,normalized).toString()).apply()
    }
    fun inherit(game: String,field: SessionOptionField) = synchronized(writes) {
        val value=raw(game).apply { remove(field.key) }
        val edit=prefs.edit()
        if(value.length()==0) edit.remove("game:$game") else edit.putString("game:$game",value.toString())
        edit.apply()
    }
    private fun raw(game: String?): JSONObject = parse(prefs.getString(if(game==null) "default" else "game:$game",null))
    fun save(game: String?,options: SessionOptions) = synchronized(writes) {
        val value=options.normalized()
        prefs.edit().putString(if(game==null) "default" else "game:$game",JSONObject().put("bitrate",value.maxBitrateKbps)
            .put("locale",value.locale).put("region",value.region).toString()).apply()
    }
    fun reset(game: String) = synchronized(writes) { prefs.edit().remove("game:$game").apply() }
    private fun decode(raw: String?,defaults: SessionOptions): SessionOptions = runCatching {
        if(raw==null) defaults else JSONObject(raw).let { SessionOptions(it.optInt("bitrate",defaults.maxBitrateKbps),
            it.optString("locale",defaults.locale),it.optString("region",defaults.region)).normalized() }
    }.getOrDefault(defaults)
}
