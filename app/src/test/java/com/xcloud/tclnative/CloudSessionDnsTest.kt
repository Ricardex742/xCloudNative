package com.xcloud.tclnative

import com.xcloud.tclnative.net.CloudSessionDns
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CloudSessionDnsTest {
    @Test fun requiresTwoSuccessfulLookupsAndFallsBackIfTheSelectedDnsFails() {
        val host = "xbox.example"
        val systemAddress = InetAddress.getByAddress(byteArrayOf(127,0,0,1))
        val alternate = InetAddress.getByAddress(byteArrayOf(127,0,0,2))
        fun dns(lookup: (String) -> List<InetAddress>): Dns = object : Dns {
            override fun lookup(hostname: String) = lookup.invoke(hostname)
        }
        val system = dns { name ->
            if (name == host) Thread.sleep(35)
            listOf(systemAddress)
        }
        val flakyAttempts = AtomicInteger()
        val fastAttempts = AtomicInteger()
        val policy = CloudSessionDns(host, system, listOf(
            CloudSessionDns.Candidate("flaky", dns {
                if (flakyAttempts.incrementAndGet() > 1) throw UnknownHostException(it)
                listOf(systemAddress)
            }),
            CloudSessionDns.Candidate("fast", dns {
                if (fastAttempts.incrementAndGet() > 2) throw UnknownHostException(it)
                listOf(alternate)
            })
        ))
        assertEquals(listOf(systemAddress), policy.lookup("catalog.example"))
        assertEquals(0, fastAttempts.get())
        assertEquals(listOf(alternate), policy.lookup(host))
        assertEquals("fast", policy.activeResolver)
        assertEquals(2, flakyAttempts.get())
        assertEquals(listOf(systemAddress), policy.lookup(host))
        assertEquals("SYSTEM", policy.activeResolver)
    }
    @Test fun sessionsReuseOnlyTheResolverAndRefreshTheChoiceAfterNetworkChangeOrExpiry() {
        val host = "xbox.example"
        val systemAddress = InetAddress.getByAddress(byteArrayOf(127,0,0,1))
        val firstAddress = InetAddress.getByAddress(byteArrayOf(127,0,0,2))
        val newAddress = InetAddress.getByAddress(byteArrayOf(127,0,0,3))
        val attempts = AtomicInteger()
        val time = AtomicLong()
        var network: String? = "wifi"
        val system = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                Thread.sleep(35)
                return listOf(systemAddress)
            }
        }
        val public = object : Dns {
            override fun lookup(hostname: String) = listOf(if (attempts.incrementAndGet() <= 2) firstAddress else newAddress)
        }
        val cache = CloudSessionDns.ResolverCache()
        fun session() = CloudSessionDns(host, system, listOf(CloudSessionDns.Candidate("fast", public)), { network }, time::get, cache)

        assertEquals(listOf(firstAddress), session().lookup(host))
        assertEquals(2, attempts.get())
        val next = session()
        assertEquals(listOf(newAddress), next.lookup(host))
        assertEquals(3, attempts.get())
        assertTrue(next.diagnostics().contains("mode=CACHE"))
        next.lookup(host)
        assertEquals(4, attempts.get())
        assertTrue(next.diagnostics().startsWith("DNS/fast; mode=SESSION"))
        assertTrue(next.diagnostics().contains("DNS_SELECTION/fast; mode=CACHE"))
        network = "ethernet"
        next.lookup(host)
        assertEquals(6, attempts.get())
        assertTrue(next.diagnostics().contains("mode=PROBE"))
        time.addAndGet(TimeUnit.MINUTES.toNanos(5))
        session().lookup(host)
        assertEquals(8, attempts.get())
        network = null
        assertEquals(listOf(systemAddress), next.lookup(host))
        assertEquals(8, attempts.get())
        assertTrue(next.diagnostics().contains("mode=SYSTEM_POLICY"))
    }

    @Test fun unrelatedHostsKeepUsingTheSystemWhileSignalingProbesAreInProgress() {
        val host = "xbox.example"
        val address = InetAddress.getByAddress(byteArrayOf(127,0,0,1))
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val unrelatedResolved = CountDownLatch(1)
        val system = object : Dns { override fun lookup(hostname: String) = listOf(address) }
        val blocked = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                started.countDown()
                finish.await(2, TimeUnit.SECONDS)
                return listOf(address)
            }
        }
        val policy = CloudSessionDns(host, system, listOf(CloudSessionDns.Candidate("blocked", blocked)))
        val signaling = Thread { policy.lookup(host) }
        val catalog = Thread {
            policy.lookup("catalog.example")
            unrelatedResolved.countDown()
        }
        signaling.start()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            catalog.start()
            assertTrue(unrelatedResolved.await(500, TimeUnit.MILLISECONDS))
        } finally {
            finish.countDown()
            signaling.join()
            catalog.join()
        }
    }
}
