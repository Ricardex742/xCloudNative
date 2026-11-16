package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.net.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class Ownership { OWNED, NOT_OWNED, UNKNOWN }
enum class OwnershipFilter(private val source: String) {
    ALL("Todos os jogos"), OWNED("Adquiridos"), NOT_OWNED("Não adquiridos"), UNKNOWN("Propriedade não confirmada");
    val label get()=UiText.text(source)
    fun accepts(value: Ownership)=when(this) {
        ALL -> true
        OWNED -> value==Ownership.OWNED
        NOT_OWNED -> value==Ownership.NOT_OWNED
        UNKNOWN -> value==Ownership.UNKNOWN
    }
}

/** Exact product identity: neither Game Pass access nor public galleries prove ownership. */
private val ownershipId=Regex("[A-Z0-9]{12}")
internal fun ownershipProductKey(id: String?): String?=id?.let(GamePassCatalog::productKey)?.takeIf(ownershipId::matches)
fun CatalogCard.ownershipProductId(): String?=ownershipProductKey(product?.productId ?: game?.productId)

/** Read-only, lazy account lookup. Callers own the account-scoped result; no credentials or purchases are logged. */
class GameOwnershipService(
    private val authorization: suspend () -> String,
    private val awaitCatalog: suspend () -> Unit = {},
    private val client: OkHttpClient=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: String="https://emerald.xboxservices.com/xboxcomfd/entitlements/bulk",
    private val now: () -> Long=System::currentTimeMillis
) {
    suspend fun fetch(productIds: Collection<String>,onBatch: suspend (Map<String,Ownership>) -> Unit): Unit=withContext(Dispatchers.IO) {
        val ids=productIds.mapNotNull(::ownershipProductKey).distinct()
        if(ids.isEmpty()) return@withContext
        awaitCatalog()
        val auth=try { authorization() } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { onBatch(ids.associateWith { Ownership.UNKNOWN });return@withContext }
        val slots=Semaphore(2)
        coroutineScope { ids.chunked(50).map { batch -> async { slots.withPermit {
            awaitCatalog()
            val result=try {
                val body=JSONObject().put("ProductIds",JSONArray(batch)).toString()
                val request=Request.Builder().url("$endpoint?locale=en-US&getRecurrenceInfo=false&pageSize=50")
                    .header("Authorization",auth).header("Accept","application/json")
                    .header("X-MS-Api-Version","1.0")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                client.newCall(request).awaitResponse().use { response ->
                    check(response.isSuccessful) { "OWNERSHIP_HTTP_${response.code}" }
                    val source=response.body?.source() ?: error("OWNERSHIP_EMPTY")
                    check(!source.request(1_048_577)) { "OWNERSHIP_TOO_LARGE" }
                    parse(JSONObject(source.readUtf8()),batch,now())
                }
            } catch(cancelled: CancellationException) { throw cancelled }
              catch(_: Exception) { batch.associateWith { Ownership.UNKNOWN } }
            onBatch(result)
        } } }.awaitAll() }
    }
    companion object {
        /** Missing, partial, malformed or ambiguous results stay unknown, never inferred as unowned. */
        internal fun parse(data: JSONObject,requested: List<String>,now: Long): Map<String,Ownership> {
            val records=data.optJSONObject("entitlements") ?: return requested.associateWith { Ownership.UNKNOWN }
            val partial=!data.optString("continuationToken").isBlank()
            return requested.associateWith { id ->
                val record=records.optJSONObject(id) ?: records.optJSONObject(id.lowercase(Locale.ROOT))
                if(record==null || !record.optString("productId").equals(id,true) || record.opt("isOwned") !is Boolean ||
                    record.opt("isTrial") !is Boolean) Ownership.UNKNOWN
                else {
                    val status=record.optString("status");val owned=record.getBoolean("isOwned")
                    val trial=record.getBoolean("isTrial")
                    val end=record.optString("endDate")
                    val expires=parseExpiry(end)
                    when {
                        partial -> Ownership.UNKNOWN
                        status.isNotBlank() && status !in setOf("Active","Expired","Revoked","Banned","Suspended") -> Ownership.UNKNOWN
                        owned && !trial && (status.isBlank() || status=="Active") &&
                            (end.isBlank() || expires?.let { it>now }==true) -> Ownership.OWNED
                        !owned || trial || status in setOf("Expired","Revoked","Banned","Suspended") || expires?.let { it<=now }==true -> Ownership.NOT_OWNED
                        else -> Ownership.UNKNOWN
                    }
                }
            }
        }
        private fun parseExpiry(value: String): Long? {
            val match=Regex("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2})").matchEntire(value) ?: return null
            val fraction=match.groupValues[2].padEnd(3,'0').take(3)
            val zone=match.groupValues[3].let { if(it=="Z") "+0000" else it.replace(":","") }
            val normalized="${match.groupValues[1]}.$fraction$zone"
            val parser=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ",Locale.US).apply { isLenient=false }
            val position=ParsePosition(0)
            return parser.parse(normalized,position)?.time?.takeIf { position.index==normalized.length }
        }
    }
}
