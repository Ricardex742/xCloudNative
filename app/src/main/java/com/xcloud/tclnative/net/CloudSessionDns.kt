package com.xcloud.tclnative.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Resolves only the Xbox signaling hostname. The system handles all other hostnames. */
internal class CloudSessionDns(
    private val host: String,
    private val system: Dns,
    private val candidates: List<Candidate> = publicResolvers,
    private val networkKey: (() -> String?)? = null,
    private val clockNs: () -> Long = System::nanoTime,
    private val cache: ResolverCache = sharedCache
) : Dns {
    internal data class Candidate(val name: String, val resolver: Dns)
    private data class Sample(val candidate: Candidate, val addresses: List<InetAddress>, val worstMs: Double)
    private data class Choice(val network: String?, val resolver: Candidate)
    private val systemCandidate = Candidate("SYSTEM", system)
    private var chosen: Choice? = null
    @Volatile private var lastLookup = "DNS/SYSTEM; mode=PENDING"
    @Volatile private var lastSelection: String? = null
    internal val activeResolver get() = lastLookup.substringAfter("DNS/").substringBefore(';')
    fun diagnostics(): String {
        val lookup = lastLookup
        val selection = lastSelection
        return if (selection != null && selection != lookup) "$lookup\nDNS_SELECTION/${selection.substringAfter('/')}" else lookup
    }

    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname.equals(host, ignoreCase = true)) lookupSignaling(hostname) else system.lookup(hostname)

    @Synchronized private fun lookupSignaling(hostname: String): List<InetAddress> {
        val start = clockNs()
        val network = networkKey?.invoke()
        if (networkKey != null && network == null) {
            chosen = null
            return resolveSystem(hostname, start, "SYSTEM_POLICY")
        }
        val key = network?.let { ResolverCache.Key(it, host.lowercase(Locale.ROOT)) }
        val existing = chosen?.takeIf { it.network == network }?.resolver
        val resolver = existing ?: key?.let { cache.get(it, start) }?.let { name ->
            if (name == systemCandidate.name) systemCandidate else candidates.firstOrNull { it.name == name }
        }
        if (resolver == null) {
            val measured = benchmark(hostname)
            if (networkKey != null && networkKey.invoke() != network) {
                chosen = null
                return resolveSystem(hostname, start, "NETWORK_CHANGED")
            }
            if (measured == null) {
                chosen = Choice(network, systemCandidate)
                return resolveSystem(hostname, start, "NO_PROBE_RESULT")
            }
            chosen = Choice(network, measured.candidate)
            if (key != null) cache.put(key, measured.candidate.name, clockNs())
            record(measured.candidate.name, start, "PROBE")
            return measured.addresses
        }
        chosen = Choice(network, resolver)
        val addresses = try {
            resolver.resolver.lookup(hostname).ifEmpty { throw UnknownHostException(hostname) }
        } catch (error: UnknownHostException) {
            if (key != null) cache.remove(key, resolver.name)
            record(resolver.name, start, "FAILED")
            if (resolver === systemCandidate) throw error
            chosen = Choice(network, systemCandidate)
            return resolveSystem(hostname, start, "FALLBACK")
        }
        if (networkKey != null && networkKey.invoke() != network) {
            chosen = null
            return resolveSystem(hostname, start, "NETWORK_CHANGED")
        }
        record(resolver.name, start, if (existing != null) "SESSION" else "CACHE")
        return addresses
    }

    private fun resolveSystem(hostname: String, start: Long, mode: String) =
        try { system.lookup(hostname) }
        finally { record(systemCandidate.name, start, mode) }

    private fun record(resolver: String, start: Long, mode: String) {
        val result = "DNS/$resolver; mode=$mode; lookup_ms=${(clockNs() - start).coerceAtLeast(0) / 1_000_000}"
        if (mode != "SESSION") lastSelection = result
        lastLookup = result
    }

    private fun benchmark(hostname: String): Sample? {
        val checks = (listOf(systemCandidate) + candidates).map { candidate -> Callable {
            try {
                var worst = 0.0
                var addresses = emptyList<InetAddress>()
                repeat(2) {
                    val start = System.nanoTime()
                    addresses = candidate.resolver.lookup(hostname)
                    if (addresses.isEmpty()) throw UnknownHostException(hostname)
                    worst = maxOf(worst, (System.nanoTime() - start) / 1_000_000.0)
                }
                Sample(candidate, addresses, worst)
            } catch (_: IOException) { null }
        } }
        val samples = try {
            workers.invokeAll(checks, 1250, TimeUnit.MILLISECONDS)
                .mapNotNull { future -> if (future.isCancelled) null else runCatching { future.get() }.getOrNull() }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UnknownHostException(hostname)
        }
        val fastest = samples.minByOrNull { it.worstMs } ?: return null
        // Give the network's own resolver priority when the sample differs by only a few ms.
        return samples.firstOrNull { it.candidate === systemCandidate && it.worstMs <= fastest.worstMs + 5 }
            ?: fastest
    }

    /** Cache only the resolver choice. The next connection always resolves the name again. */
    internal class ResolverCache {
        data class Key(val network: String, val host: String)
        private data class Entry(val name: String, val chosenAt: Long)
        private val values = LinkedHashMap<Key, Entry>(16, .75f, true)

        @Synchronized fun get(key: Key, now: Long): String? {
            val entry = values[key] ?: return null
            if (now - entry.chosenAt in 0 until CHOICE_TTL_NS) return entry.name
            values.remove(key)
            return null
        }
        @Synchronized fun put(key: Key, resolver: String, now: Long) {
            values[key] = Entry(resolver, now)
            if (values.size > 16) values.remove(values.keys.first())
        }
        @Synchronized fun remove(key: Key, resolver: String) {
            if (values[key]?.name == resolver) values.remove(key)
        }
    }

    private companion object {
        const val CHOICE_TTL_NS = 300_000_000_000L
        val sharedCache = ResolverCache()
        val https = OkHttpClient.Builder().callTimeout(600, TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        val publicResolvers by lazy { listOf(
            Candidate("Cloudflare", encrypted("https://cloudflare-dns.com/dns-query", "1.1.1.1", "2606:4700:4700::1111")),
            Candidate("Google", encrypted("https://dns.google/dns-query", "8.8.8.8", "2001:4860:4860::8888")),
            Candidate("Quad9", encrypted("https://dns.quad9.net/dns-query", "9.9.9.9", "2620:fe::fe")),
            Candidate("AdGuard", encrypted("https://unfiltered.adguard-dns.com/dns-query", "94.140.14.140", "2a10:50c0::1:ff")),
            Candidate("OpenDNS", encrypted("https://doh.opendns.com/dns-query", "146.112.41.2"))
        ) }
        val workers = Executors.newFixedThreadPool(6) { task -> Thread(task, "xCloud-DNS").apply { isDaemon = true } }

        fun encrypted(endpoint: String, vararg bootstrap: String): Dns = DnsOverHttps.Builder()
            .client(https).url(endpoint.toHttpUrl())
            .bootstrapDnsHosts(bootstrap.map(InetAddress::getByName)).includeIPv6(true).build()
    }
}
