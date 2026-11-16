package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class CloudPlaytimeTest {
    private fun varint(value: Long): ByteArray {
        val out=ByteArrayOutputStream();var n=value
        do { val b=(n and 127).toInt();n=n ushr 7;out.write(b or if(n!=0L) 128 else 0) }while(n!=0L)
        return out.toByteArray()
    }
    private fun number(field: Int,value: Long)=varint((field*8).toLong())+varint(value)
    private fun message(field: Int,bytes: ByteArray)=varint((field*8+2).toLong())+varint(bytes.size.toLong())+bytes
    private fun budget(left: Int?=null,max: Int?=null)=
        (left?.let { number(2,it.toLong()) } ?: byteArrayOf())+(max?.let { number(4,it.toLong()) } ?: byteArrayOf())
    private fun account(id: String,quota: ByteArray)=message(3,message(1,id.toByteArray())+message(2,quota))
    private fun quota(periodic: ByteArray?=null,persistent: ByteArray?=null)=
        (periodic?.let {message(2,it)} ?: byteArrayOf())+(persistent?.let {message(3,it)} ?: byteArrayOf())
    private fun decode(bytes: ByteArray)=ContentAccessPlaytime.decode(bytes)
    @Test fun requestMetadataUsesANumericAppVersionAndAFreshValidCorrelationVector()=runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(account("12345",quota(budget(-1,-1)))))) }
            val service=CloudPlaytimeService({"test"},endpoint=server.url("/all/v1").toString(),appVersion="0.37.6-image-lab")
            repeat(2) { assertEquals(CloudPlaytimeStatus.UNLIMITED,service.fetch().status) }
            val one=server.takeRequest();val two=server.takeRequest()
            assertEquals("0.37.6",one.getHeader("Calling-App-Version"))
            val cv=one.getHeader("MS-CV")!!
            assertTrue(cv.matches(Regex("[A-Za-z0-9+/]{22}\\.0")));assertNotEquals(cv,two.getHeader("MS-CV"))
        }
    }
    @Test fun rejectedRequestReportsOnlyAnErrorCodeAndAKnownParameter()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"InvalidParameter","target":"Calling-App-Version","message":"private-account bearer private-token"}}"""))
            val value=CloudPlaytimeService({"secret"},endpoint=server.url("/all/v1").toString()).fetch()
            assertEquals("InvalidParameter",value.serviceCode);assertEquals("Calling-App-Version",value.rejectedField)
            val text=value.diagnostics()
            assertTrue(text.contains("CAS/InvalidParameter"));assertTrue(text.contains("CAS_FIELD/Calling-App-Version"))
            assertFalse(text.contains("private-account"));assertFalse(text.contains("private-token"));assertFalse(text.contains("secret"))
            val hidden=CloudPlaytimeContract.rejection("""{"code":"https://private.example/token","target":"private-account","message":"private-token"}""")
            assertNull(hidden.code);assertNull(hidden.field)
        }
    }
    @Test fun diagnosticExplainsUnknownWithoutInferringUnlimitedOrLeakingPrivateErrorText()=runBlocking {
        val empty=decode(message(1,message(1,"12345".toByteArray())+message(2,byteArrayOf())))
        assertEquals(PlaytimeFailure.NO_QUOTA,empty.failure)
        assertTrue(empty.diagnostics().contains("PLAYTIME/NO_QUOTA"))
        assertEquals(PlaytimeFailure.ACCOUNT_SELECTION,decode(account("12345",quota(budget(-1,-1)))+account("23456",quota(budget(-1,-1)))).failure)
        val denied=CloudPlaytimeService({throw com.xcloud.tclnative.net.CloudHttpException("STORE_XSTS",401,"private-value",null)}).fetch()
        assertEquals(PlaytimeFailure.AUTHORIZATION,denied.failure);assertEquals(401,denied.httpStatus)
        assertFalse(denied.diagnostics().contains("private-value"));assertTrue(denied.diagnostics().contains("HTTP 401"))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("private-response"))
            val response=CloudPlaytimeService({"secret"},endpoint=server.url("/all/v1").toString()).fetch()
            assertEquals(PlaytimeFailure.HTTP,response.failure);assertEquals(403,response.httpStatus)
            assertFalse(response.diagnostics().contains("secret"));assertFalse(response.diagnostics().contains("private-response"))
        }
    }
    @Test fun officialAdFreeBudgetsAddWithoutUsingAdSupportedOrPerGameCounters() {
        val q=quota(budget(3600,7200)+number(3,1800000000),budget(1800,1800))+
            message(4,budget(-1,-1))+message(1,message(1,"some-game".toByteArray())+message(2,budget(-1,-1)))
        assertEquals(CloudPlaytime(CloudPlaytimeStatus.LIMITED,5400),decode(account("12345",q)))
        assertEquals(CloudPlaytime(CloudPlaytimeStatus.LIMITED,0),decode(account("12345",quota(budget(max=7200)))))
        for(q2 in listOf(quota(budget(-1,0)),quota(budget(0,-1)),quota(persistent=budget(-1,-1))))
            assertEquals(CloudPlaytimeStatus.UNLIMITED,decode(account("12345",q2)).status)
    }
    @Test fun absentAmbiguousOrMalformedQuotasNeverClaimZeroOrUnlimited() {
        val valid=account("12345",quota(budget(3600,7200)))
        val invalid=listOf(byteArrayOf(),message(2,byteArrayOf(1,2)),account("12345",byteArrayOf()),
            account("12345",quota(byteArrayOf())),account("12345",message(4,budget(-1,-1))),
            account("12345",quota(budget(-2,7200))),valid+account("23456",quota(budget(-1,-1))),
            valid+valid,account("18446744073709551616",quota(budget(-1,-1))),
            account("not-a-puid",quota(budget(-1,-1))),valid.copyOf(valid.size-1),
            account("12345",quota(number(2,Long.MAX_VALUE))),byteArrayOf(0),
            account("12345",quota(budget(1,2)+number(2,3))),ByteArray(ContentAccessPlaytime.MAX_BYTES+1))
        invalid.forEachIndexed { i,b -> assertEquals("invalid $i",CloudPlaytimeStatus.UNAVAILABLE,decode(b).status) }
        // Even a pass map from another account must not be combined with this quota.
        assertEquals(CloudPlaytimeStatus.UNAVAILABLE,decode(valid+message(1,message(1,"23456".toByteArray())+message(2,byteArrayOf()))).status)
    }
    @Test fun unknownWireFieldsAndProductPayloadsAreSkippedWithoutChangingQuota() {
        val valid=account("12345",quota(budget(123,7200)))
        val products=message(2,ByteArray(2000){0xff.toByte()})
        val fixed64=varint((20*8+1).toLong())+ByteArray(8)
        val fixed32=varint((21*8+5).toLong())+ByteArray(4)
        assertEquals(CloudPlaytime(CloudPlaytimeStatus.LIMITED,123),decode(products+fixed64+valid+fixed32+number(22,42)))
    }
    @Test fun labelsDistinguishUnknownUnlimitedExhaustedAndLessThanAMinute() {
        assertTrue(CloudPlaytime().label.contains("indisponível"))
        assertTrue(CloudPlaytime(CloudPlaytimeStatus.UNLIMITED).label.contains("ilimitado"))
        assertTrue(CloudPlaytime(CloudPlaytimeStatus.LIMITED,0).label.contains("0h00min"))
        assertTrue(CloudPlaytime(CloudPlaytimeStatus.LIMITED,59).label.contains("menos de 1 min"))
        assertTrue(CloudPlaytime(CloudPlaytimeStatus.LIMITED,45000).label.contains("12h30min"))
    }
    @Test fun requestUsesOfficialQuotaRouteAuthenticatedAndWaitsForTheCatalogGate()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(account("12345",quota(budget(45000,72000))))))
            val gate=CatalogWorkGate().apply { pause() };var tokens=0
            val job=async { CloudPlaytimeService({tokens++;"XBL3.0 test-account"},gate::await,
                endpoint=server.url("/all/v1").toString(),market="br").fetch() }
            delay(50);assertEquals(0,tokens);assertEquals(0,server.requestCount)
            gate.resume();assertEquals(45000L,job.await().seconds)
            val request=server.takeRequest()
            assertEquals("/all/v1?market=BR&offering=xgpuweb",request.path)
            assertEquals("XBL3.0 test-account",request.getHeader("Authorization"))
            assertEquals("xCloudNative",request.getHeader("Calling-App-Name"))
        }
    }
    @Test fun errorsRedirectsAndCancellationDoNotInventABalanceOrLeakAuthorization()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/should-not-follow")))
            val service=CloudPlaytimeService({"secret"},endpoint=server.url("/all/v1").toString())
            repeat(2) { assertEquals(CloudPlaytimeStatus.UNAVAILABLE,service.fetch().status) }
            assertEquals(2,server.requestCount)
            val gate=CatalogWorkGate().apply { pause() }
            val job=launch { CloudPlaytimeService({error("must not authorize")},gate::await).fetch() }
            yield();job.cancelAndJoin();assertTrue(job.isCancelled)
        }
    }
}
