package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.net.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class CloudPlaytimeStatus { CHECKING, UNAVAILABLE, LIMITED, UNLIMITED }
data class CloudPlaytime(val status: CloudPlaytimeStatus=CloudPlaytimeStatus.UNAVAILABLE,val seconds: Long=0) {
    val label: String get()=when(status) {
        CloudPlaytimeStatus.CHECKING -> UiText.text("Tempo restante: consultando…")
        CloudPlaytimeStatus.UNAVAILABLE -> UiText.text("Tempo restante: indisponível")
        CloudPlaytimeStatus.UNLIMITED -> UiText.text("Tempo restante: ilimitado")
        CloudPlaytimeStatus.LIMITED -> if(seconds in 1..59) UiText.text("Tempo restante: menos de 1 min")
            else UiText.text("Tempo restante: {0}h{1}min",UiText.nested(seconds.coerceAtLeast(0)/3600),
                UiText.nested(String.format(Locale.ROOT,"%02d",seconds.coerceAtLeast(0)/60%60)))
    }
}

/** Server quota snapshot, not a local session countdown or a guess from the subscription's name. */
class CloudPlaytimeService(
    private val authorization: suspend () -> String,
    private val beforeRequest: suspend () -> Unit = {},
    private val client: OkHttpClient=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: String="https://contentaccess.exp.xboxservices.com/all/v1",
    private val market: String=Locale.getDefault().country.takeIf { it.matches(Regex("[A-Za-z]{2}")) } ?: "US",
    private val appVersion: String="1.0"
) {
    suspend fun fetch(): CloudPlaytime=withContext(Dispatchers.IO) {
        try {
            beforeRequest()
            val token=authorization()
            beforeRequest()
            val url=endpoint.toHttpUrl().newBuilder().addQueryParameter("market",market.uppercase(Locale.ROOT))
                .addQueryParameter("offering","xgpuweb").build()
            val request=Request.Builder().url(url).header("Authorization",token)
                .header("Calling-App-Name","xCloudNative").header("Calling-App-Version",appVersion)
                .get().build()
            client.newCall(request).awaitResponse().use { response ->
                check(response.isSuccessful) { "PLAYTIME_HTTP_${response.code}" }
                val source=response.body?.source() ?: error("PLAYTIME_EMPTY")
                check(!source.request(ContentAccessPlaytime.MAX_BYTES+1L)) { "PLAYTIME_TOO_LARGE" }
                ContentAccessPlaytime.decode(source.readByteArray())
            }
        } catch(cancelled: CancellationException) { throw cancelled }
          catch(_: Exception) { CloudPlaytime() }
    }
}

/** Small bounded reader for ContentAccessAllV1Response's quota fields. Product data is skipped. */
internal object ContentAccessPlaytime {
    const val MAX_BYTES=4*1024*1024
    private data class Budget(val remaining: Int,val maximum: Int,val declared: Boolean)
    private data class Quota(val periodic: Budget?,val persistent: Budget?)
    fun decode(bytes: ByteArray): CloudPlaytime = try {
        require(bytes.isNotEmpty() && bytes.size<=MAX_BYTES)
        val root=Wire(bytes);val accounts=mutableSetOf<String>();val quotas=mutableMapOf<String,Quota>()
        while(root.more()) {
            val tag=root.tag()
            if(tag ushr 3==1 || tag ushr 3==3) {
                val entry=root.message(tag);var id: String?=null;var quota: Quota?=null;var valueSeen=false
                while(entry.more()) {
                    val field=entry.tag()
                    when(field ushr 3) {
                        1 -> { require(id==null);id=entry.key(field) }
                        2 -> {
                            require(!valueSeen);valueSeen=true
                            if(tag ushr 3==3) quota=readQuota(entry.message(field)) else entry.skip(field)
                        }
                        else -> entry.skip(field)
                    }
                }
                val key=id ?: error("PLAYTIME_ACCOUNT_MISSING")
                require(key.matches(Regex("[1-9][0-9]{0,19}")) && (key.length<20 || key<="18446744073709551615"))
                accounts+=key;require(accounts.size<=8)
                if(tag ushr 3==3) { require(key !in quotas);quotas[key]=quota ?: error("PLAYTIME_QUOTA_MISSING") }
            } else root.skip(tag)
        }
        // A cloud user token normally returns one PUID. Never combine or guess between accounts.
        require(accounts.size==1)
        val quota=quotas[accounts.single()] ?: error("PLAYTIME_UNAVAILABLE")
        val present=listOfNotNull(quota.periodic,quota.persistent)
        require(present.isNotEmpty() && present.any { it.declared })
        require(present.all { it.remaining>=-1 && it.maximum>=-1 })
        // The official quota classifier uses -1 in either ad-free remaining/max field for unlimited.
        if(present.any { it.remaining==-1 || it.maximum==-1 }) CloudPlaytime(CloudPlaytimeStatus.UNLIMITED)
        else CloudPlaytime(CloudPlaytimeStatus.LIMITED,present.sumOf { it.remaining.toLong() })
    } catch(_: Exception) { CloudPlaytime() }

    private fun readQuota(message: Wire): Quota {
        var periodic: Budget?=null;var persistent: Budget?=null
        while(message.more()) {
            val tag=message.tag()
            when(tag ushr 3) {
                2 -> { require(periodic==null);periodic=readBudget(message.message(tag)) }
                3 -> { require(persistent==null);persistent=readBudget(message.message(tag)) }
                else -> message.skip(tag) // Per-game counters and ad-supported time are different budgets.
            }
        }
        return Quota(periodic,persistent)
    }
    private fun readBudget(message: Wire): Budget {
        var remaining=0;var maximum=0;var leftSeen=false;var maxSeen=false
        while(message.more()) {
            val tag=message.tag()
            when(tag ushr 3) {
                2 -> { require(!leftSeen);leftSeen=true;remaining=message.int32(tag) }
                4 -> { require(!maxSeen);maxSeen=true;maximum=message.int32(tag) }
                else -> message.skip(tag)
            }
        }
        return Budget(remaining,maximum,leftSeen||maxSeen)
    }
    private class Wire(val bytes: ByteArray,var at: Int=0,val end: Int=bytes.size) {
        fun more()=at<end
        private fun varint(): Long {
            var value=0L
            for(shift in 0..63 step 7) {
                require(at<end);val b=bytes[at++].toInt() and 255
                if(shift==63) require(b and 254==0)
                value=value or ((b and 127).toLong() shl shift)
                if(b and 128==0)return value
            }
            error("PLAYTIME_VARINT")
        }
        fun tag(): Int=varint().also { require(it in 8..0xfffffffDL) }.toInt()
        fun number(tag: Int): Long { require(tag and 7==0);return varint() }
        fun int32(tag: Int): Int {
            val raw=number(tag);val value=raw.toInt()
            require(raw==value.toLong() || raw==(value.toLong() and 0xffffffffL))
            return value
        }
        private fun length(): Int=varint().also { require(it>=0 && it<=end-at) }.toInt()
        fun message(tag: Int): Wire {
            require(tag and 7==2);val size=length();val child=Wire(bytes,at,at+size);at+=size;return child
        }
        fun key(tag: Int): String {
            val child=message(tag);require(child.end-child.at in 1..20)
            return bytes.copyOfRange(child.at,child.end).toString(Charsets.UTF_8)
        }
        fun skip(tag: Int) {
            when(tag and 7) {
                0 -> varint()
                1 -> { require(end-at>=8);at+=8 }
                2 -> { val size=length();at+=size }
                5 -> { require(end-at>=4);at+=4 }
                else -> error("PLAYTIME_WIRE_TYPE")
            }
        }
    }
}
