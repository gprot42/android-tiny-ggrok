package com.tinyggrok.app.data.api

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ResilientDnsTest {

    private val v4 = InetAddress.getByAddress("api.x.ai", byteArrayOf(104, 18, 0, 1))
    private val v6 = InetAddress.getByAddress(
        "api.x.ai",
        byteArrayOf(0x26, 0x06, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    )

    private class FixedDns(private val result: () -> List<InetAddress>) : Dns {
        var calls = 0
        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            return result()
        }
    }

    private fun failing() = FixedDns { throw UnknownHostException("nope") }

    @Test
    fun systemResolverWinsWhenItWorks() {
        val system = FixedDns { listOf(v6, v4) }
        val doh = failing()
        val dns = ResilientDns(fallbacks = listOf(doh), system = system)

        val result = dns.lookup("api.x.ai")

        assertEquals(listOf(v4, v6), result) // IPv4 first
        assertEquals(0, doh.calls)
    }

    @Test
    fun fallsBackToDohWhenSystemDnsFails() {
        val doh1 = failing()
        val doh2 = FixedDns { listOf(v4) }
        val dns = ResilientDns(fallbacks = listOf(doh1, doh2), system = failing())

        assertEquals(listOf(v4), dns.lookup("api.x.ai"))
        assertEquals(1, doh1.calls)
        assertEquals(1, doh2.calls)
    }

    @Test
    fun usesLastKnownGoodWhenEveryResolverFails() {
        var systemWorks = true
        val system = FixedDns { if (systemWorks) listOf(v4) else throw UnknownHostException("x") }
        val dns = ResilientDns(fallbacks = listOf(failing()), system = system)

        assertEquals(listOf(v4), dns.lookup("api.x.ai"))
        systemWorks = false
        assertEquals(listOf(v4), dns.lookup("api.x.ai"))
    }

    @Test
    fun throwsUnknownHostWhenNothingEverResolved() {
        val dns = ResilientDns(fallbacks = listOf(failing()), system = failing())
        try {
            dns.lookup("api.x.ai")
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message.orEmpty().contains("api.x.ai"))
            assertTrue(e.cause is UnknownHostException)
        }
    }

    @Test
    fun preferIpv4IsStable() {
        val v4b = InetAddress.getByAddress("api.x.ai", byteArrayOf(104, 18, 0, 2))
        assertEquals(listOf(v4, v4b, v6), ResilientDns.preferIpv4(listOf(v6, v4, v4b)))
    }
}
