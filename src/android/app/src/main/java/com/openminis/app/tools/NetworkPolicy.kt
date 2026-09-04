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

    /** Private/loopback/link-local/metadata ranges to block (as Long). */
    private val BLOCKED_RANGES = listOf(
        0x7F000000L to 0x7FFFFFFFL,          // 127.0.0.0/8
        0x0A000000L to 0x0AFFFFFFL,          // 10.0.0.0/8
        0xAC100000L to 0xAC1FFFFFL,          // 172.16.0.0/12
        0xC0A80000L to 0xC0A8FFFFL,          // 192.168.0.0/16
        0xA9FE0000L to 0xA9FEFFFFL,          // 169.254.0.0/16 (incl. cloud metadata)
        0x00000000L to 0x00FFFFFFL,          // 0.0.0.0/8 (reserved)
        // [B2 fix] 100.64.0.0/10 (CGNAT) REMOVED from the blocked list.
        // On several mobile carriers (user-confirmed: DNS maps EVERY public
        // domain to 100.64.x.x CGNAT egress IPs), blocking this range made
        // web_extract unusable for the entire web. CGNAT-internal services
        // are not a meaningful SSRF target for a phone app, while the real
        // targets — loopback, private LAN, link-local/metadata — stay blocked.
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
            val int = ((raw[0].toLong() and 0xFFL) shl 24) or
                ((raw[1].toLong() and 0xFFL) shl 16) or
                ((raw[2].toLong() and 0xFFL) shl 8) or
                (raw[3].toLong() and 0xFFL)
            val blocked = BLOCKED_RANGES.any { int in it.first..it.second }
            if (blocked) {
                return "مقصد «$host» (${ip.hostAddress}) در محدوده داخلی است و برای امنیت بلاک شد (SSRF guard)."
            }
        }
        return null
    }
}
