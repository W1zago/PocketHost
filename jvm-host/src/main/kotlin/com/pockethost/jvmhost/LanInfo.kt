package com.pockethost.jvmhost

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LAN address helpers for the PC port.
 *
 * The bridge must listen on 0.0.0.0 so a phone on the same LAN can reach
 * it; these addresses are what we print (and hand to android-observer)
 * as `http://<lanIp>:<port>`.
 */
object LanInfo {

    /** All usable site-local IPv4 addresses (192.168.x, 10.x, 172.16-31.x first). */
    fun lanIPv4(): List<String> {
        val all = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (iface in ifaces) {
                try {
                    if (!iface.isUp || iface.isLoopback) continue
                } catch (_: Exception) {
                    continue
                }
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("169.254.")) continue // link-local, not routable
                    all.add(ip)
                }
            }
        } catch (_: Exception) {
        }
        return all.sortedWith(
            compareBy(
                { if (it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.")) 0 else 1 },
                { it }
            )
        )
    }

    /** Best single LAN address for the observer, or 127.0.0.1 when offline. */
    fun primaryLanIp(): String = lanIPv4().firstOrNull() ?: "127.0.0.1"
}
