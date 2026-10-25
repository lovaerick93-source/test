package com.remotectl.agent

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * Keeps the agent's plaintext hub connection on the local network.
 *
 * The manifest permits cleartext app-wide, because the hub is an ordinary
 * personal server on the owner's own LAN: it is reached at an address the owner
 * types in, it holds no certificate, and `wss://` cannot be assumed. Left at
 * that, the permission would equally allow plaintext to anywhere on the
 * internet, so this guard denies the one case that matters — a `ws://` hub
 * resolving to a public address. A hub outside the LAN has to be reached over
 * `wss://`.
 *
 * The decision is made inside OkHttp's DNS step, so it judges the addresses a
 * hostname actually resolves to rather than the string that was typed.
 */
object LanGuard {

    /**
     * True when [address] cannot route off the local network: loopback, the
     * three private IPv4 ranges, link-local, the carrier-grade NAT range that
     * some ISPs and mesh VPNs hand out, and IPv6 unique-local.
     */
    fun isLocal(address: InetAddress): Boolean =
        address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress ||
            isCarrierGradeNat(address) ||
            isUniqueLocalV6(address)

    /** 100.64.0.0/10. */
    private fun isCarrierGradeNat(address: InetAddress): Boolean {
        val octets = address.address
        return octets.size == 4 &&
            (octets[0].toInt() and 0xFF) == 100 &&
            (octets[1].toInt() and 0xC0) == 64
    }

    /** fc00::/7. */
    private fun isUniqueLocalV6(address: InetAddress): Boolean {
        val octets = address.address
        return octets.size == 16 && (octets[0].toInt() and 0xFE) == 0xFC
    }

    /**
     * A [Dns] that resolves normally but refuses to let a cleartext hub
     * connection reach an address that is not local. [cleartext] is consulted
     * per lookup, so editing the saved hub address between `http://` and
     * `https://` takes effect without rebuilding the HTTP client.
     */
    fun dns(cleartext: () -> Boolean): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (cleartext() && addresses.isNotEmpty() && addresses.none(::isLocal)) {
                throw UnknownHostException(
                    "refusing plaintext to $hostname (${addresses.first().hostAddress}): " +
                        "a hub outside your local network needs an https:// address",
                )
            }
            return addresses
        }
    }
}
