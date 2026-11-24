package com.xcloud.tclnative

import com.xcloud.tclnative.net.CloudSessionDns
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

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
}
