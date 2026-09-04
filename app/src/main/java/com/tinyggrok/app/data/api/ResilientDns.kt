package com.tinyggrok.app.data.api

import android.util.Log
import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS resolver that does not give up on the first failure.
 *
 * "Can't reach api.x.ai (DNS)" on Android is usually one of:
 *  - the carrier / hotel / captive Wi-Fi resolver dropping or blocking the query
 *  - Private DNS (DoT) misconfigured on the device
 *  - a VPN that hijacks port 53
 *  - a brief resolver outage right after a network hand-over
 *
 * Order of attempts:
 *  1. system resolver ([Dns.SYSTEM])
 *  2. each DNS-over-HTTPS resolver in [fallbacks] (bootstrapped with literal IPs so
 *     they work even when port-53 DNS is dead)
 *  3. the last address set that worked for this host (stale cache), so a transient
 *     resolver blip during an active session never fails the request
 *
 * Results are ordered IPv4 before IPv6. Mobile networks quite often advertise an
 * IPv6 route that silently blackholes traffic; OkHttp tries addresses sequentially,
 * so putting IPv6 first would cost a full connect timeout before anything works.
 */
class ResilientDns(
    private val fallbacks: List<Dns>,
    private val system: Dns = Dns.SYSTEM
) : Dns {

    private val lastGood = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        var firstError: Exception? = null

        try {
            val addresses = system.lookup(hostname)
            if (addresses.isNotEmpty()) return remember(hostname, addresses)
        } catch (e: UnknownHostException) {
            firstError = e
            Log.w(TAG, "System DNS failed for $hostname: ${e.message}")
        }

        fallbacks.forEachIndexed { index, dns ->
            try {
                val addresses = dns.lookup(hostname)
                if (addresses.isNotEmpty()) {
                    Log.i(TAG, "Resolved $hostname via DoH fallback #${index + 1}")
                    return remember(hostname, addresses)
                }
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                Log.w(TAG, "DoH fallback #${index + 1} failed for $hostname: ${e.message}")
            }
        }

        lastGood[hostname]?.let { cached ->
            Log.w(TAG, "Using last known addresses for $hostname (resolvers unavailable)")
            return cached
        }

        throw UnknownHostException(
            "Unable to resolve host \"$hostname\": system DNS and DNS-over-HTTPS both failed"
        ).apply { firstError?.let { initCause(it) } }
    }

    private fun remember(hostname: String, addresses: List<InetAddress>): List<InetAddress> {
        val ordered = preferIpv4(addresses)
        lastGood[hostname] = ordered
        return ordered
    }

    companion object {
        private const val TAG = "ResilientDns"

        /** Stable partition: keep resolver order within each family, IPv4 first. */
        fun preferIpv4(addresses: List<InetAddress>): List<InetAddress> =
            addresses.sortedBy { it is Inet6Address }
    }
}
