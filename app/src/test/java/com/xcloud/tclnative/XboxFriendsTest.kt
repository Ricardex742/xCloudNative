package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy

class XboxFriendsTest {
    @Test fun primaryCurrentGameUsesTitleIdentityWhileOfflineAndHiddenPresenceStayDistinct() {
        val friends=XboxFriends.parse(JSONObject("""{"people":[
            {"xuid":"1","gamertag":"Offline","presenceState":"Offline","presenceDetails":[{"IsPrimary":true,"IsGame":true,"TitleId":"123"}]},
            {"xuid":"2","gamertag":"Hidden"},
            {"xuid":"3","gamertag":"Playing","presenceState":"Online","displayPicRaw":"https://example.test/avatar.png","presenceDetails":[
                {"IsPrimary":false,"IsGame":true,"TitleId":"456"},
                {"IsPrimary":true,"IsGame":true,"State":"Active","TitleId":"0x7b","PresenceText":"Test game","RichPresenceText":"In a match"}]},
            {"xuid":"4","gamertag":"Away","presenceState":"Away"},
            {"xuid":"not-an-id","gamertag":"Invalid"},
            {"xuid":"3","gamertag":"Duplicate"}
        ]}"""))
        assertEquals(listOf("Playing","Away","Offline","Hidden"),friends.map { it.gamertag })
        assertEquals("In a match",friends[0].activity)
        assertEquals("https://example.test/avatar.png",friends[0].avatarUrl)
        val wrong=GameTitle("wrong","Test game",xboxTitleId="456")
        val right=GameTitle("right","Different localized name",xboxTitleId="123")
        assertEquals(right,friends[0].cloudGame(listOf(wrong,right)))
        assertNull(friends[2].xboxTitleId);assertEquals(FriendPresence.UNKNOWN,friends[3].presence)
        assertNull(friends[3].cloudGame(listOf(right)))
    }
    @Test fun invalidPayloadIsAnErrorRatherThanAnEmptyFriendList() {
        assertTrue(runCatching { XboxFriends.parse(JSONObject("{}")) }.isFailure)
        assertEquals(emptyList<XboxFriend>(),XboxFriends.parse(JSONObject("""{"people":[]}""")))
        assertNull(normalizeXboxTitleId("-1"));assertNull(normalizeXboxTitleId("4294967296"))
    }
    @Test fun serviceUsesXboxHeadersRejectsRedirectAndReportsHttpFailureWithoutBody()=runBlocking {
        val server=MockWebServer().apply { start() }
        try {
            val service=XboxFriendsService(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(),server.url("/friends").toString())
            server.enqueue(MockResponse().setBody("""{"people":[]}"""))
            assertTrue(service.load("XBL3.0 x=hash;social-token","pt-BR").isEmpty())
            val request=server.takeRequest()
            assertEquals("XBL3.0 x=hash;social-token",request.getHeader("Authorization"))
            assertEquals("3",request.getHeader("x-xbl-contract-version"));assertEquals("pt-BR",request.getHeader("Accept-Language"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("secret body"))
            val denied=runCatching { service.load("token","en-US") }.exceptionOrNull() as CloudHttpException
            assertEquals(403,denied.status);assertFalse(denied.message!!.contains("secret"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/other")))
            assertEquals(302,(runCatching { service.load("token","en-US") }.exceptionOrNull() as CloudHttpException).status)
            assertEquals(3,server.requestCount)
            server.enqueue(MockResponse().setBody(" ".repeat(4*1024*1024+1)))
            assertTrue(runCatching { service.load("token","en-US") }.isFailure)
        } finally { server.shutdown() }
    }
}
