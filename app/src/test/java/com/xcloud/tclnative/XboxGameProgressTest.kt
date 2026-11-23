package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.XboxGameProgressService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class XboxGameProgressTest {
    @Test fun combinesAllPagesForTheSelectedTitleAndOrdersRecentUnlocks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"achievements":[
                    {"id":"a","name":"Primeira","progressState":"Achieved","titleAssociations":[{"id":42}],
                     "progression":{"timeUnlocked":"2025-01-01T12:00:00Z"},"rewards":[{"type":"Gamerscore","value":"20"}]},
                    {"id":"other-title","progressState":"Achieved","titleAssociations":[{"id":72}]}
                ],"pagingInfo":{"continuationToken":"page-2"}}
            """.trimIndent()))
            server.enqueue(MockResponse().setBody("""
                {"achievements":[
                    {"id":"b","name":"Mais recente","progressState":"Achieved","progression":{"timeUnlocked":"2025-02-01T12:00:00Z"},
                     "mediaAssets":[{"type":"Icon","url":"https://example.test/recent.png"}],"rewards":[{"type":"Gamerscore","value":"10"}]},
                    {"id":"c","name":"Bloqueada","progressState":"InProgress","rewards":[{"type":"Gamerscore","value":"30"}]}
                ],"pagingInfo":{"continuationToken":null}}
            """.trimIndent()))
            val service = XboxGameProgressService(endpoint = server.url("/").toString().trimEnd('/'))
            val result = service.load("XBL3.0 x=test;fixture", "123", "42", "pt-BR")!!
            assertEquals(30, result.gamerscore)
            assertEquals(60, result.totalGamerscore)
            assertEquals(2, result.achievements)
            assertEquals(3, result.totalAchievements)
            assertEquals(listOf("Mais recente", "Primeira"), result.recent.map { it.name })
            assertEquals("https://example.test/recent.png", result.recent.first().iconUrl)
            val first = server.takeRequest()
            assertEquals("2", first.getHeader("x-xbl-contract-version"))
            assertEquals("42", first.requestUrl!!.queryParameter("titleId"))
            assertEquals("page-2", server.takeRequest().requestUrl!!.queryParameter("continuationToken"))
        }
    }

    @Test fun legacyTitlesUseTheirDedicatedAchievementListAfterAnEmptyModernResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"achievements":[]}"""))
            server.enqueue(MockResponse().setBody("""
                {"achievements":[
                    {"id":1,"titleId":42,"name":"Primeira","unlocked":true,"gamerscore":30,
                     "timeUnlocked":"2024-10-01T13:00:00Z"},
                    {"id":2,"titleId":42,"name":"Bloqueada","unlocked":false,"gamerscore":70}
                ]}
            """.trimIndent()))
            val service = XboxGameProgressService(endpoint = server.url("/").toString().trimEnd('/'))
            val result = service.load("XBL3.0 x=test;fixture", "123", "0x2a", "pt-BR")!!
            assertEquals(30, result.gamerscore)
            assertEquals(100, result.totalGamerscore)
            assertEquals("Primeira", result.recent.single().name)
            assertNull(result.recent.single().iconUrl)
            server.takeRequest()
            val legacy = server.takeRequest()
            assertEquals("1", legacy.getHeader("x-xbl-contract-version"))
            assertTrue(legacy.requestUrl!!.encodedPath.endsWith("/titleachievements"))
        }
    }
}
