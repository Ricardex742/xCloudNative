package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.awaitResponse
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class FriendPresence { ONLINE, AWAY, OFFLINE, UNKNOWN }

data class XboxFriend(val xuid: String,val gamertag: String,val presence: FriendPresence,
                      val activity: String?,val xboxTitleId: String?) {
    /** Identity matching only: a similar display name does not establish a game license or title. */
    fun cloudGame(games: List<GameTitle>): GameTitle? = xboxTitleId?.let { id ->
        games.firstOrNull { normalizeXboxTitleId(it.xboxTitleId)==id }
    }
}

internal fun normalizeXboxTitleId(value: String?): String? {
    val raw=value?.trim() ?: return null
    val number=if(raw.startsWith("0x",true)) raw.drop(2).toLongOrNull(16) else raw.toLongOrNull()
    return number?.takeIf { it in 1..0xffff_ffffL }?.toString()
}

object XboxFriends {
    fun parse(json: JSONObject): List<XboxFriend> {
        val people=json.optJSONArray("people") ?: throw IOException("FRIENDS_INVALID_RESPONSE")
        require(people.length()<=2000) { "FRIENDS_RESPONSE_TOO_LARGE" }
        return (0 until people.length()).mapNotNull { index ->
            val person=people.optJSONObject(index) ?: return@mapNotNull null
            val xuid=person.optString("xuid").takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: return@mapNotNull null
            val tag=person.optString("gamertag").ifBlank { person.optString("modernGamertag") }.trim()
                .takeIf { it.isNotEmpty() && it!="null" }?.take(100) ?: return@mapNotNull null
            val state=when(person.optString("presenceState").lowercase(Locale.ROOT)) {
                "online" -> FriendPresence.ONLINE
                "away" -> FriendPresence.AWAY
                "offline" -> FriendPresence.OFFLINE
                else -> FriendPresence.UNKNOWN
            }
            val details=person.optJSONArray("presenceDetails")
            val primary=if(state==FriendPresence.ONLINE || state==FriendPresence.AWAY)
                (0 until (details?.length() ?: 0)).asSequence().mapNotNull { details?.optJSONObject(it) }
                    .firstOrNull { it.optBoolean("IsPrimary") && it.optBoolean("IsGame") &&
                        it.optString("State").lowercase(Locale.ROOT) !in setOf("lastseen","offline") }
                else null
            fun text(value: String?)=value?.trim()?.takeIf { it.isNotEmpty() && it!="null" }?.take(300)
            val activity=primary?.let { text(it.optString("RichPresenceText")) ?: text(it.optString("PresenceText")) }
                ?: text(person.optString("presenceText"))
            XboxFriend(xuid,tag,state,activity,primary?.let { normalizeXboxTitleId(it.optString("TitleId")) })
        }.distinctBy { it.xuid }.sortedWith(compareBy<XboxFriend> { it.presence.ordinal }.thenBy { it.gamertag.lowercase(Locale.ROOT) })
    }
}

/** Fetched on demand. No stored presence, background polling, invites or social mutations. */
class XboxFriendsService(client: OkHttpClient=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS).build(),
                         private val endpoint: String="https://peoplehub.xboxlive.com/users/me/people/social/decoration/detail,multiplayersummary,presencedetail") {
    private val http=client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    suspend fun load(authorization: String,language: String): List<XboxFriend> = withContext(Dispatchers.IO) {
        val request=Request.Builder().url(endpoint).header("Authorization",authorization)
            .header("x-xbl-contract-version","3").header("Accept","application/json")
            .header("Accept-Language",language).build()
        http.newCall(request).awaitResponse().use { response ->
            if(!response.isSuccessful) throw CloudHttpException("FRIENDS",response.code,null,null)
            val body=response.body ?: throw IOException("FRIENDS_EMPTY_RESPONSE")
            val limit=4L*1024*1024
            if(body.contentLength()>limit) throw IOException("FRIENDS_RESPONSE_TOO_LARGE")
            val source=body.source()
            source.request(limit+1)
            if(source.buffer.size>limit) throw IOException("FRIENDS_RESPONSE_TOO_LARGE")
            XboxFriends.parse(JSONObject(source.buffer.readUtf8()))
        }
    }
}
