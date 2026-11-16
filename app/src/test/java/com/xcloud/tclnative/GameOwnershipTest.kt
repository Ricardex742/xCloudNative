package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GameOwnershipTest {
    private val a="AAA000000001";private val b="BBB000000002";private val c="CCC000000003"
    private fun record(id: String,owned: Boolean,trial: Boolean=false,status: String="Active",end: String="")=
        JSONObject().put("productId",id).put("isOwned",owned).put("isTrial",trial).put("status",status).put("endDate",end)
    private fun data(vararg records: JSONObject)=JSONObject().put("entitlements",JSONObject().apply {
        records.forEach { put(it.getString("productId"),it) }
    })
    private fun parse(body: JSONObject,ids: List<String> = listOf(a,b,c))=GameOwnershipService.parse(body,ids,1_800_000_000_000L)
    @Test fun subscriptionAccessAndFreeToPlayDoNotProveOwnership() {
        val subscription=record(b,false).put("isSatisfyingEntitlement",true)
        val result=parse(data(record(a,true),subscription))
        assertEquals(Ownership.OWNED,result[a]);assertEquals(Ownership.NOT_OWNED,result[b]);assertEquals(Ownership.UNKNOWN,result[c])
        assertEquals(Ownership.NOT_OWNED,parse(data(record(a,false).put("freeToPlay",true)))[a])
    }
    @Test fun missingMalformedPartialAndOtherEditionResultsStayUnknown() {
        assertEquals(Ownership.UNKNOWN,parse(JSONObject())[a])
        assertEquals(Ownership.UNKNOWN,parse(data(record(a,true).put("isOwned","true")))[a])
        assertEquals(Ownership.UNKNOWN,parse(data(record(a,true)).put("continuationToken","more"))[a])
        assertEquals(Ownership.UNKNOWN,parse(data(record(b,true)))[a])
        assertEquals(Ownership.UNKNOWN,parse(data(record(a,true,status="FutureStatus")))[a])
        assertEquals(Ownership.UNKNOWN,parse(data(record(a,true,end="not-a-date")))[a])
    }
    @Test fun trialsAndExpiredOrRevokedRightsAreNotCurrentOwnership() {
        for(r in listOf(record(a,true,trial=true),record(a,true,status="Revoked"),record(a,true,end="2020-01-01T00:00:00Z")))
            assertEquals(Ownership.NOT_OWNED,parse(data(r))[a])
        for(end in listOf("9999-12-31T23:59:59Z","2099-01-01T00:00:00.1234567+03:00"))
            assertEquals(Ownership.OWNED,parse(data(record(a,true,end=end)))[a])
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
    @Test fun networkErrorsRemainUnknownAndAccountsNeverShareAResultCache()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(data(record(a,true)).toString()))
            server.enqueue(MockResponse().setBody(data(record(a,false)).toString()))
            server.enqueue(MockResponse().setResponseCode(503))
            suspend fun fetch(auth: String): Map<String,Ownership> {
                val result=mutableMapOf<String,Ownership>()
                GameOwnershipService({auth},endpoint=server.url("/licenses").toString()).fetch(listOf(a)) { result.putAll(it) }
                assertEquals(auth,server.takeRequest().getHeader("Authorization"))
                return result
            }
            assertEquals(Ownership.OWNED,fetch("account-A")[a])
            assertEquals(Ownership.NOT_OWNED,fetch("account-B")[a])
            assertEquals(Ownership.UNKNOWN,fetch("account-B")[a])
        }
    }
    @Test fun requestsAreBatchedBoundedAndWaitForTheCatalogGate()=runBlocking {
        MockWebServer().use { server ->
            val ids=(1..51).map { "T"+it.toString().padStart(11,'0') }
            server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val products=JSONObject(request.body.readUtf8()).getJSONArray("ProductIds")
                    assertTrue(products.length()<=50)
                    return MockResponse().setBody(data(*(0 until products.length()).map {record(products.getString(it),true)}.toTypedArray()).toString())
                }
            }
            val gate=CatalogWorkGate().apply { pause() };var tokenRequests=0
            val results=java.util.concurrent.ConcurrentHashMap<String,Ownership>()
            val work=launch { GameOwnershipService({tokenRequests++;"token"},gate::await,endpoint=server.url("/licenses").toString())
                .fetch(ids+ids) { results.putAll(it) } }
            delay(50);assertEquals(0,tokenRequests);assertEquals(0,server.requestCount)
            gate.resume();work.join();assertEquals(2,server.requestCount);assertEquals(51,results.size)
            assertTrue(results.values.all { it==Ownership.OWNED })
        }
    }
}
