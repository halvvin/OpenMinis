package com.openminis.app.tools

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * [T-network-policy] SSRF / internal-address guard for model-chosen URLs.
 *
 * Mirrors the Vega-Agent NetworkPolicy: URLs the model picks (web_search,
 * web_extract, download_file) are a real SSRF surface — page content can
 * steer the model to hit localhost / LAN / cloud metadata endpoints. This
 * blocks those. The USER's own configured provider endpoint is deliberately
 * NOT subject to this (they chose it); this guard applies only to
 * model-chosen outbound URLs.
 */
object NetworkPolicy {

    /** Private/loopback/link-local/metadata ranges to block. */
    private val BLOCKED_RANGES = listOf(
        // Loopback
        0x7F000000 to 0x7FFFFFFF,          // 127.0.0.0/8
        // RFC1918 private
        0x0A000000 to 0x0AFFFFFF,          // 10.0.0.0/8
        0xAC100000 to 0xAC1FFFFF,          // 172.16.0.0/12
        0xC0A80000 to 0xC0A8FFFF,          // 192.168.0.0/16
        // Link-local
        0xA9FE0000 to 0xA9FEFFFF,          // 169.254.0.0/16
        // AWS metadata / ECS
        0xFE800000 to 0xFEBFFFFF.toInt(),  // 169.254.169.254 is inside link-local
        // CGNAT (100.64.0.0/10)
        0x64400000 to 0x647FFFFF,
        // Reserved
        0x00000000 to 0x00FFFFFF,
    )

    /**
     * Returns an error message if [url] targets a blocked (internal) address,
     * else null. DNS is resolved here; unresolvable hostnames are allowed
     * through (the connection itself will fail with a clear network error).
     */
    fun check(url: String): String? {
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        if (host.equals("localhost", true) || host == "127.0.0.1" || host == "::1") {
            return "آدرس‌های داخلی (localhost) برای امنیت بلاک شدند — این مقصد قابل دسترسی نیست."
        }
        val ip = runCatching { InetAddress.getAllByName(host).firstOrNull() }.getOrNull() ?: return null
        if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress) {
            return "مقصد «$host» یک آدرس داخلی/شبکه محلی است و برای امنیت بلاک شد (SSRF guard)."
        }
        if (ip is Inet4Address) {
            val raw = ip.address
            val int = ((raw[0].toInt() and 0xFF) shl 24) or
                ((raw[1].toInt() and 0xFF) shl 16) or
                ((raw[2].toInt() and 0xFF) shl 8) or
                (raw[3].toInt() and 0xFF)
            val blocked = BLOCKED_RANGES.any { int in it.first..it.second }
            if (blocked) {
                return "مقصد «$host» (${ip.hostAddress}) در محدوده داخلی است و برای امنیت بلاک شد (SSRF guard)."
            }
        }
        return null
    }
}
