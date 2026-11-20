package com.xcloud.tclnative

import com.xcloud.tclnative.auth.AuthEndpoints
import com.xcloud.tclnative.auth.OAuthToken
import com.xcloud.tclnative.auth.RemotePlaySession
import com.xcloud.tclnative.auth.XalAuthenticator
import com.xcloud.tclnative.diagnostics.PlaybackStage
import com.xcloud.tclnative.settings.RegionBypass
import com.xcloud.tclnative.settings.SessionOptions
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.webrtc.ChannelHandshake
import com.xcloud.tclnative.webrtc.StreamResolution
import com.xcloud.tclnative.webrtc.StreamingPeer
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.IceCandidate

class RemotePlayFlowTest {
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(3, TimeUnit.SECONDS).build()
    private val list = """{"results":[
        {"serverId":"series-1","deviceName":"Sala","consoleType":"XboxSeriesS","powerState":"On"},
        {"serverId":"one-1","deviceName":"Quarto","consoleType":"XboxOne","powerState":"Off"},
        {"serverId":"series-1","deviceName":"Duplicado"}, {"serverId":"../bad"}]}"""
    private val path = "/v5/sessions/home/test-session"
    private val peer = object : StreamingPeer {
        override suspend fun createOfferSdp() = "v=0\r\noffer" to
            listOf(IceCandidate("0", 0, "candidate:local 1 UDP 1 192.0.2.1 9002 typ host"))
        override suspend fun setRemoteAnswer(answerSdp: String) { assertEquals("v=0\r\nanswer", answerSdp) }
        override suspend fun addRemoteCandidates(candidates: List<IceCandidate>) { assertEquals(1, candidates.size) }
        override suspend fun awaitReady() {}
    }
    private fun MockWebServer.reply(body: String, code: Int = 200) = enqueue(MockResponse().setResponseCode(code).setBody(body))
    private fun MockWebServer.answer() {
        reply("", 204)
        reply(JSONObject().put("exchangeResponse", JSONObject().put("sdp", "v=0\r\nanswer").put("input", 8).toString()).toString())
        reply("", 204)
        reply("""{"exchangeResponse":[{"candidate":"candidate:remote 1 UDP 1 192.0.2.2 9002 typ host","sdpMid":"0","sdpMLineIndex":0}]}""")
    }
    private fun MockWebServer.auth(host: String = url("/").toString()): XalAuthenticator {
        reply("""{"Token":"user-token","DisplayClaims":{"xui":[{"uhs":"test-hash"}]}}""")
        reply("""{"Token":"xsts-token"}""")
        reply("""{"gsToken":"home-token","offeringSettings":{"regions":[{"baseUri":"$host","isDefault":true}]}}""")
        val tokens = MemoryTokens(OAuthToken("d=test", null, null, XalAuthenticator.XCLOUD_CLIENT_ID))
        return XalAuthenticator(tokens, http, AuthEndpoints(userAuthUrl = url("/xbl").toString(),
            xstsAuthorizeUrl = url("/xsts").toString(), remotePlayLoginUrl = url("/home-login").toString()),
            regionBypass = { RegionBypass.entries.first { it != RegionBypass.OFF } })
    }

    @Test fun homeAuthorizationDiscoveryPlaybackReconnectAndStopUseOnlyTheConsoleSession() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val session = server.auth().authenticateToRemotePlay().getOrThrow()
            server.reply(list)
            server.reply("""{"sessionPath":"$path"}""", 202)
            server.reply("""{"state":"WaitingForServerToRegister"}""")
            server.reply("""{"state":"ReadyToConnect"}""")
            server.reply("", 204)
            server.reply("""{"state":"Provisioned"}""")
            server.answer()
            server.reply("", 204) // reconnect keepalive
            server.answer()
            server.reply("", 204) // delete
            var now = 0L
            val client = XCloudSignalingClient(session, http, { now += it }, { now }, transferTokenProvider = { "transfer" })
            val consoles = client.fetchRemoteConsoles()
            assertEquals(listOf("Sala", "Quarto"), consoles.map { it.name })
            assertTrue(consoles[0].canConnect)
            assertFalse(consoles[1].canConnect)
            val stages = mutableListOf<PlaybackStage>()
            client.startRemotePlay(consoles[0], peer, StreamResolution.HD_1080, SessionOptions(),
                { stages += it }, {}).getOrThrow()
            client.reconnectExistingSession(peer).getOrThrow()
            client.stop()
            client.stop()
            val requests = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)!! }
            val login = requests.single { it.path == "/home-login" }
            assertEquals("xhome", JSONObject(login.body.readUtf8()).getString("offeringId"))
            assertNull(login.getHeader("X-Forwarded-For"))
            assertEquals("/v6/servers/home?mr=50", requests[3].path)
            assertTrue(requests.drop(3).all { it.getHeader("Authorization") == "Bearer home-token" })
            val play = requests.single { it.path == "/v5/sessions/home/play" }
            assertEquals("series-1", JSONObject(play.body.clone().readUtf8()).getString("serverId"))
            assertEquals("", JSONObject(play.body.readUtf8()).getString("titleId"))
            assertEquals("transfer", JSONObject(requests.single { it.path == "$path/connect" }.body.readUtf8()).getString("userToken"))
            assertEquals(PlaybackStage.PLAY_REQUEST, stages.first())
            assertEquals(PlaybackStage.TRANSPORT_READY, stages.last())
            assertFalse(stages.contains(PlaybackStage.QUEUE_CONFIRMED))
            assertTrue(requests.none { it.path.orEmpty().contains("/cloud/") || it.path.orEmpty().startsWith("/v1/waittime") })
            assertEquals(path, requests.single { it.method == "DELETE" }.path)
        }
    }

    @Test fun cancelWhileWaitingReleasesTheHomeAllocation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.reply(list)
            server.reply("""{"sessionPath":"$path"}""", 202)
            server.reply("""{"state":"WaitingForServerToRegister"}""")
            server.reply("", 204)
            val waiting = CompletableDeferred<Unit>()
            val client = XCloudSignalingClient(RemotePlaySession("home-token", server.url("/").toString(), 1L), http,
                pause = { waiting.complete(Unit); awaitCancellation() })
            val console = client.fetchRemoteConsoles().first()
            val job = launch { client.startRemotePlay(console, peer, StreamResolution.HD_1080, SessionOptions(), {}, {}) }
            withTimeout(5_000) { waiting.await() }
            job.cancelAndJoin()
            client.stop()
            assertEquals(4, server.requestCount)
            repeat(3) { server.takeRequest() }
            val stop = server.takeRequest()
            assertEquals("DELETE", stop.method)
            assertEquals(path, stop.path)
        }
    }

    @Test fun unexpectedAuthorityAndCloudAllocationAreRejected() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val auth = server.auth("https://example.org")
            assertTrue(auth.authenticateToRemotePlay().isFailure)
            server.reply(list)
            server.reply("""{"sessionPath":"/v5/sessions/cloud/other"}""", 202)
            val client = XCloudSignalingClient(RemotePlaySession("home-token", server.url("/").toString(), 1L), http)
            val console = client.fetchRemoteConsoles().first()
            assertTrue(client.startRemotePlay(console, peer, StreamResolution.HD_1080, SessionOptions(), {}, {}).isFailure)
            client.stop()
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun cancellationDuringPlayWaitsForTheOwnedAllocationToBeDeleted() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.reply(list)
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"sessionPath":"$path"}""")
                .setBodyDelay(500, TimeUnit.MILLISECONDS))
            server.reply("", 204)
            val client = XCloudSignalingClient(RemotePlaySession("home-token", server.url("/").toString(), 1L), http)
            val console = client.fetchRemoteConsoles().first()
            server.takeRequest()
            val job = launch { client.startRemotePlay(console, peer, StreamResolution.HD_1080, SessionOptions(), {}, {}) }
            val play = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
            assertEquals("/v5/sessions/home/play", play?.path)
            job.cancelAndJoin()
            assertEquals("DELETE", server.takeRequest(3, TimeUnit.SECONDS)?.method)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun gamepadRegistrationSurvivesWithoutCloudTitleActivation() {
        var now = 0L
        val handshake = ChannelHandshake(clockMs = { now }, activateCloudTitle = false)
        val startup = listOf("control", "input", "message").flatMap(handshake::onOpen) + handshake.onHandshakeAck()
        startup.forEach(handshake.gamepadSync::sent)
        now += 600
        val delayed = handshake.onGamepadSyncDue()
        assertTrue(handshake.ready)
        assertTrue(delayed.any { it.channel == "control" && String(it.bytes).contains("gamepadChanged") })
        assertFalse((startup + delayed).any { !it.binary && String(it.bytes).contains("titleconstrain", ignoreCase = true) })
    }
}
