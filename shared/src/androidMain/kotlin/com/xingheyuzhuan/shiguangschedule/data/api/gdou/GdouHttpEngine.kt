package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress

internal actual fun gdouHttpEngine(): HttpClientEngine = OkHttp.create {
    config {
        dns(GdouIpv4FirstDns)
    }
}

/**
 * gdou 域名 IPv4 优先解析：
 * wfw.gdou.edu.cn 的 AAAA 记录是 CERNET2（校外不可达），A 记录是公网 IPv4。
 * Android 侧默认 IPv6 优先会先撞不可达地址，且 CIO 引擎不回退导致整体失败。
 * 这里对 *.gdou.edu.cn 把 IPv4 排在 IPv6 之前（不清空 v6，纯 IPv6 网络仍可兜底），
 * OkHttp 引擎会按序尝试地址列表，IPv4 失败后仍可回退 IPv6。
 */
internal object GdouIpv4FirstDns : Dns {
    private const val GDOU_DOMAIN_SUFFIX = ".gdou.edu.cn"

    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)
        if (!hostname.endsWith(GDOU_DOMAIN_SUFFIX, ignoreCase = true)) return resolved
        val (ipv4, ipv6) = resolved.partition { it !is Inet6Address }
        return ipv4 + ipv6
    }
}
