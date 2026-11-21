package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GameOwnershipTest {
    private val a="AAA000000001";private val b="BBB000000002";private val c="CCC000000003"
    private fun mapEntry(field: Int, key: String, value: Buffer): Buffer = Buffer().apply {
        val entry = Buffer()
        entry.writeByte(10).writeByte(key.length).writeUtf8(key)
        entry.writeByte(18).writeByte(value.size.toInt()).writeAll(value)
        writeByte(field * 8 + 2).writeByte(entry.size.toInt()).writeAll(entry)
    }
    private fun product(id: String, direct: Int, combined: Int = direct, account: String = "12345"): Buffer =
        mapEntry(2, id, mapEntry(2, account, Buffer().writeByte(8).writeByte(direct)
            .writeByte(16).writeByte(combined)))
    private fun account(id: String = "12345") = mapEntry(3, id, Buffer())
    @Test fun directOwnedFlagMarksOnlyTheRequestedEditionAndSkipsAggregateAccess() {
        val response = account()
        response.writeAll(product(a, direct = 3))
        response.writeAll(product(b, direct = 2, combined = 3))
        val rights = ContentAccessOwnership.decode(response.readByteArray(), setOf(a, b, c))
        assertEquals(mapOf(a to Ownership.OWNED, b to Ownership.NOT_OWNED), rights)
    }

    private fun input(filter: OwnershipFilter,rights: Map<String,Ownership>,query: String="",tab: LibraryTab=LibraryTab.ALL): CatalogInput {
        val games=listOf(GameTitle("A","Alpha",a,gamePass=true),GameTitle("B","Beta",b),GameTitle("C","Gamma",c))
        return CatalogInput(games,emptyMap(),emptyMap(),null,emptySet(),tab,setOf("A","B","C"),null,listOf("A","B","C"),query,filter,rights)
    }
    @Test fun filtersComposeWithSearchAndCategoriesWithoutTreatingUnknownAsNotOwned() {
        val rights=mapOf(a to Ownership.OWNED,b to Ownership.NOT_OWNED)
        for(tab in listOf(LibraryTab.ALL,LibraryTab.RECENT,LibraryTab.FAVORITES)) {
            assertEquals(listOf("Alpha"),CatalogPresentation.build(input(OwnershipFilter.OWNED,rights,tab=tab)).cards.map { it.name })
            assertEquals(listOf("Beta"),CatalogPresentation.build(input(OwnershipFilter.NOT_OWNED,rights,tab=tab)).cards.map { it.name })
            assertEquals(listOf("Gamma"),CatalogPresentation.build(input(OwnershipFilter.UNKNOWN,rights,tab=tab)).cards.map { it.name })
        }
        assertTrue(CatalogPresentation.build(input(OwnershipFilter.NOT_OWNED,rights,"Gamma")).cards.isEmpty())
        assertEquals(3,CatalogPresentation.build(input(OwnershipFilter.ALL,rights)).cards.size)
    }
    @Test fun galleryEditionUsesItsOwnLicenseRatherThanTheBaseGamesLicense() {
        val product=GalleryProduct(b,"Special edition")
        val base=GameTitle("BASE","Base edition",a)
        val card=CatalogCard(base,product)
        assertEquals(b,card.ownershipProductId())
        assertNull(CatalogCard(base.copy(productId=null)).ownershipProductId())
    }
    @Test fun contentAccessLookupMatchesTheSignedInAccountAcrossAllRequestedIdsInOneCall() = runBlocking {
        MockWebServer().use { server ->
            val missingIds = (1..3860).map { "T"+it.toString().padStart(11,'0') }
            val response = account().apply { writeAll(product(a, direct = 1)); writeAll(product(b, direct = 2)) }
            server.enqueue(MockResponse().setBody(response))
            server.enqueue(MockResponse().setBody(account().apply { writeAll(product(a, direct = 2)) }))
            server.enqueue(MockResponse().setResponseCode(503))
            suspend fun fetch(auth: String): Map<String, Ownership> {
                val result = CloudPlaytimeService({ auth }, endpoint = server.url("/all/v1").toString(), market = "BR")
                    .fetchOwnership((missingIds + listOf(a, b)).toSet())
                val request = server.takeRequest()
                assertEquals(auth, request.getHeader("Authorization"))
                assertEquals("GET", request.method)
                assertEquals("/all/v1?market=BR&offering=xgpuweb", request.path)
                return result
            }
            val first = fetch("account-A")
            assertEquals(Ownership.OWNED, first[a])
            assertEquals(Ownership.NOT_OWNED, first[b])
            assertEquals(Ownership.UNKNOWN, first[missingIds.last()])
            assertEquals(Ownership.NOT_OWNED, fetch("account-B")[a])
            assertEquals(Ownership.UNKNOWN, fetch("account-B")[a])
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun mixedAccountsAndTruncatedPayloadLeaveOwnershipUnconfirmed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(account().apply {
                writeAll(product(a, direct = 1))
                writeAll(product(b, direct = 1, account = "67890"))
            }))
            val truncated = account().apply { writeAll(product(a, direct = 1)) }.readByteArray().dropLast(1).toByteArray()
            server.enqueue(MockResponse().setBody(Buffer().write(truncated)))
            val service = CloudPlaytimeService({ "account-A" }, endpoint = server.url("/all/v1").toString())
            repeat(2) {
                assertEquals(mapOf(a to Ownership.UNKNOWN, b to Ownership.UNKNOWN), service.fetchOwnership(setOf(a, b)))
            }
        }
    }

    @Test fun catalogGateDelaysAuthorizationAndLookup() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(account().apply { writeAll(product(a, direct = 1)) }))
            val gate = CatalogWorkGate().apply { pause() }
            var tokenRequests = 0
            val work = async {
                CloudPlaytimeService({ tokenRequests++; "token" }, gate::await, endpoint = server.url("/all/v1").toString())
                    .fetchOwnership(setOf(a))
            }
            delay(50)
            assertEquals(0, tokenRequests)
            assertEquals(0, server.requestCount)
            gate.resume()
            assertEquals(Ownership.OWNED, work.await()[a])
            assertEquals(1, server.requestCount)
        }
    }
}
