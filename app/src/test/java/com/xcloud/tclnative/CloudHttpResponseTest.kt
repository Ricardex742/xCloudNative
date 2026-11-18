package com.xcloud.tclnative

import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.checkedBody
import com.xcloud.tclnative.net.readResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

class CloudHttpResponseTest {
    @Test fun cancellationAfterHeadersCancelsTheSocketAndFreesTheDispatcher() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(1024)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
            val bodyStarted = CompletableDeferred<Unit>()
            val idle = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { bodyStarted.complete(Unit) }
            }).build()
            client.dispatcher.idleCallback = Runnable { idle.complete(Unit) }
            val call = client.newCall(Request.Builder().url(server.url("/")).build())
            val request = async { call.readResponse { it.checkedBody("SESSION_STATE") } }
            withTimeout(5_000) { bodyStarted.await() }
            withTimeout(5_000) { request.cancelAndJoin() }
            assertTrue(call.isCanceled())
            withTimeout(5_000) { idle.await() }
        }
    }

    @Test fun limitAppliesToDecompressedAndChunkedBodies() = runBlocking {
        MockWebServer().use { server ->
            val compressed = Buffer()
            GzipSink(compressed).buffer().use { it.writeUtf8("x".repeat(2048)) }
            server.enqueue(MockResponse().setBody(compressed).setHeader("Content-Encoding", "gzip"))
            server.enqueue(MockResponse().setChunkedBody("x".repeat(1025), 64))
            server.enqueue(MockResponse().setChunkedBody("x".repeat(1024), 64))
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
            val request = Request.Builder().url(server.url("/")).build()
            repeat(2) {
                val error = runCatching {
                    client.newCall(request).readResponse { it.checkedBody("CATALOG", 1024) }
                }.exceptionOrNull()
                assertTrue(error is IOException)
                assertEquals("CATALOG: RESPONSE_TOO_LARGE", error?.message)
            }
            assertEquals(1024, client.newCall(request).readResponse { it.checkedBody("CATALOG", 1024) }.length)
        }
    }

    @Test fun largeErrorPreservesStatusAndSafeHeadersWithoutReturningItsPayload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("private".repeat(4096))
                .setHeader("x-ms-request-id", "request-id").setHeader("Retry-After", "3"))
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
            val error = runCatching {
                client.newCall(Request.Builder().url(server.url("/")).build()).readResponse { it.checkedBody("STATE", 16) }
            }.exceptionOrNull() as CloudHttpException
            assertEquals(429, error.status)
            assertEquals(3000L, error.retryAfterMs)
            assertTrue(error.message.orEmpty().contains("request-id"))
            assertTrue(!error.message.orEmpty().contains("private"))
        }
    }
}
