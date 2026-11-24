package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.checkedBody
import com.xcloud.tclnative.net.readResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class XboxAchievement(
    val name: String, val gamerscore: Int, val iconUrl: String?, val description: String? = null
)

data class XboxGameProgress(
    val gamerscore: Int, val totalGamerscore: Int,
    val achievements: Int, val totalAchievements: Int,
    val recent: List<XboxAchievement>
)

/** Read-only, per-account and per-title. Progress is derived only from a complete results list. */
internal class XboxGameProgressService(
    client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
    private val endpoint: String = "https://achievements.xboxlive.com"
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun load(authorization: String, xuid: String, xboxTitleId: String, language: String): XboxGameProgress? =
        withContext(Dispatchers.IO) {
            require(xuid.matches(Regex("[0-9]{1,20}")))
            val titleId = requireNotNull(normalizeXboxTitleId(xboxTitleId))
            val modern = try {
                achievements(authorization, xuid, titleId, language, legacy = false)
            } catch (error: CloudHttpException) {
                if (error.status != 404) throw error
                emptyList()
            }
            val entries = modern.ifEmpty { achievements(authorization, xuid, titleId, language, legacy = true) }
            if (entries.isEmpty()) return@withContext null
            val unlocked = entries.filter { it.unlocked }
            XboxGameProgress(
                unlocked.sumOf { it.achievement.gamerscore }, entries.sumOf { it.achievement.gamerscore },
                unlocked.size, entries.size,
                unlocked.sortedByDescending { it.unlockedAt }.take(4).map { it.achievement }
            )
        }

    private data class Entry(val key: String, val achievement: XboxAchievement, val unlocked: Boolean, val unlockedAt: String)

    private suspend fun achievements(
        authorization: String, xuid: String, titleId: String, language: String, legacy: Boolean
    ): List<Entry> {
        val resource = if (legacy) "titleachievements" else "achievements"
        val base = "$endpoint/users/xuid($xuid)/$resource".toHttpUrl()
        val found = linkedMapOf<String, Entry>()
        val tokens = hashSetOf<String>()
        var cursor: String? = null
        repeat(12) {
            val url = base.newBuilder().addQueryParameter("titleId", titleId).addQueryParameter("maxItems", "100").apply {
                if (!legacy) addQueryParameter("orderBy", "UnlockTime")
                cursor?.let { addQueryParameter("continuationToken", it) }
            }.build()
            val request = Request.Builder().url(url).header("Authorization", authorization)
                .header("x-xbl-contract-version", if (legacy) "1" else "2")
                .header("Accept-Language", language).header("Accept", "application/json").build()
            val page = http.newCall(request).readResponse { JSONObject(it.checkedBody("ACHIEVEMENTS", 2L * 1024 * 1024)) }
            val items = page.optJSONArray("achievements") ?: throw IOException("ACHIEVEMENTS_INVALID_RESPONSE")
            if (items.length() > 250) throw IOException("ACHIEVEMENTS_RESPONSE_TOO_LARGE")
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: throw IOException("ACHIEVEMENTS_INVALID_RESPONSE")
                parse(item, titleId, legacy)?.let { found[it.key] = it }
            }
            cursor = page.optJSONObject("pagingInfo")?.opt("continuationToken")
                ?.takeUnless { it == JSONObject.NULL }?.let { raw ->
                    (raw as? String)?.takeIf { it.length <= 512 } ?: throw IOException("ACHIEVEMENTS_INVALID_CURSOR")
                }?.takeIf { it.isNotEmpty() }
            if (cursor == null) return found.values.toList()
            if (!tokens.add(cursor!!)) throw IOException("ACHIEVEMENTS_REPEATED_CURSOR")
        }
        throw IOException("ACHIEVEMENTS_INCOMPLETE")
    }

    private fun parse(item: JSONObject, titleId: String, legacy: Boolean): Entry? {
        if (item.optBoolean("isRevoked") || item.optString("achievementType").equals("Challenge", true)) return null
        val associations = item.optJSONArray("titleAssociations")
        if (associations != null && (0 until associations.length()).none {
                normalizeXboxTitleId(associations.optJSONObject(it)?.optString("id")) == titleId
            }) return null
        if (legacy && item.has("titleId") && normalizeXboxTitleId(item.optString("titleId")) != titleId) return null
        val id = item.optString("id").takeIf { it.isNotBlank() && it.length <= 80 }
            ?: throw IOException("ACHIEVEMENTS_INVALID_ID")
        val unlocked = if (legacy) item.opt("unlocked") as? Boolean ?: throw IOException("ACHIEVEMENTS_INVALID_STATE")
            else when (item.optString("progressState").lowercase(java.util.Locale.ROOT)) {
                "achieved" -> true
                "notstarted", "inprogress" -> false
                else -> throw IOException("ACHIEVEMENTS_INVALID_STATE")
            }
        val rewards = item.optJSONArray("rewards")
        val score = if (legacy) item.optString("gamerscore").toIntOrNull()?.takeIf { it in 0..10_000 }
                ?: throw IOException("ACHIEVEMENTS_INVALID_SCORE")
            else (0 until (rewards?.length() ?: 0)).asSequence().mapNotNull { rewards?.optJSONObject(it) }
                .firstOrNull { it.optString("type").equals("Gamerscore", true) }
                ?.let { reward -> reward.optString("value").toIntOrNull()?.takeIf { it in 0..10_000 }
                    ?: throw IOException("ACHIEVEMENTS_INVALID_SCORE") } ?: 0
        val media = item.optJSONArray("mediaAssets")
        val icon = (0 until (media?.length() ?: 0)).asSequence().mapNotNull { media?.optJSONObject(it) }
            .firstOrNull { it.optString("type").equals("Icon", true) }?.optString("url")
        val image = CatalogService.imageUrl(icon?.let {
            // Old responses advertise this Microsoft image endpoint with an HTTP scheme.
            if (it.startsWith("http://images-eds.xboxlive.com/")) "https://" + it.substringAfter("http://") else it
        })
        fun text(key: String, maxLength: Int) = (item.opt(key) as? String)?.trim()?.take(maxLength)?.takeIf(String::isNotEmpty)
        val name = if (unlocked) text("name", 150).orEmpty() else ""
        val description = if (unlocked) text("description", 2000) else null
        val date = (if (legacy) text("timeUnlocked", 50)
            else item.optJSONObject("progression")?.opt("timeUnlocked") as? String).orEmpty()
        return Entry(item.optString("serviceConfigId").take(80) + ":" + id,
            XboxAchievement(name, score, image, description), unlocked, date)
    }
}
