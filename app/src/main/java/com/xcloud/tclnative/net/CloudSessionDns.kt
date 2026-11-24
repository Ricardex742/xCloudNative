package com.xcloud.tclnative.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Resolves only the Xbox signaling hostname. The system handles all other hostnames. */
internal class CloudSessionDns(
    private val host: String,
    private val system: Dns,
    private val candidates: List<Candidate> = publicResolvers
) : Dns {
    internal data class Candidate(val name: String, val resolver: Dns)
    private data class Sample(val candidate: Candidate, val addresses: List<InetAddress>, val worstMs: Double)
    private val systemCandidate = Candidate("SYSTEM", system)
    @Volatile private var chosen: Candidate? = null
    val activeResolver get() = chosen?.name ?: systemCandidate.name

    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(host, ignoreCase = true)) return system.lookup(hostname)
        val resolver = synchronized(this) {
            chosen ?: run {
                val result = benchmark(hostname)
                if (result != null) {
                    chosen = result.candidate
                    return result.addresses
                }
                systemCandidate.also { chosen = it }
            }
        }
        return try {
            resolver.resolver.lookup(hostname).ifEmpty { throw UnknownHostException(hostname) }
        } catch (error: UnknownHostException) {
            if (resolver === systemCandidate) throw error
            chosen = systemCandidate
            system.lookup(hostname)
        }
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

    private companion object {
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
