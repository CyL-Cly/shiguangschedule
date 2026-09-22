package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import java.net.Inet4Address
import java.net.Inet6Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用真实系统 DNS 实证 GdouIpv4FirstDns 的排序语义（依赖宿主机网络的回归测试）。
 * 注意：宿主机无 IPv6 连通性时 Windows getaddrinfo 会过滤 AAAA 记录，
 * 系统解析可能只返回 v4（nslookup 能查到 AAAA 但 getAllByName 不返回），
 * 因此 v6 相关断言以「系统解析实际返回的地址族」为准，而非硬编码双栈。
 */
class GdouIpv4FirstDnsProbeTest {
    @Test
    fun gdouDomain_ipv4First_andNothingDropped() {
        val system = okhttp3.Dns.SYSTEM.lookup("wfw.gdou.edu.cn")
        assertTrue(system.isNotEmpty(), "系统 DNS 应能解析 wfw.gdou.edu.cn，实际为空列表")
        val result = GdouIpv4FirstDns.lookup("wfw.gdou.edu.cn")

        // 只重排不清空：地址集合与系统解析完全一致（v6 兜底的前提是系统给了 v6）
        assertEquals(
            system.map { it.address.toList() }.toSet(),
            result.map { it.address.toList() }.toSet(),
            "重排后不得丢失或新增地址",
        )

        // 系统返回 v4 时，v4 必须全排在 v6 之前且居首
        val lastV4Index = result.indexOfLast { it is Inet4Address }
        val firstV6Index = result.indexOfFirst { it is Inet6Address }
        if (lastV4Index != -1) {
            assertTrue(result.first() is Inet4Address, "首个地址必须是 IPv4，实际=${result.first()}")
        }
        if (lastV4Index != -1 && firstV6Index != -1) {
            assertTrue(
                firstV6Index > lastV4Index,
                "IPv4 必须全部排在 IPv6 之前：firstV6=$firstV6Index lastV4=$lastV4Index 序列=${result.map { it.javaClass.simpleName }}",
            )
        }
    }

    @Test
    fun nonGdouDomain_notReordered() {
        val system = java.net.InetAddress.getAllByName("timor.tech").toList()
        assertTrue(system.isNotEmpty(), "本机应能解析 timor.tech")
        val result = GdouIpv4FirstDns.lookup("timor.tech")
        assertTrue(result.isNotEmpty(), "非 gdou 域 lookup 应返回非空结果")
        // 非重排路径：与系统解析的地址集合一致
        val sysSet = system.map { it.address.toList() }.toSet()
        val resSet = result.map { it.address.toList() }.toSet()
        assertTrue(
            sysSet == resSet,
            "非 gdou 域应原样透传系统解析：系统=$sysSet 结果=$resSet",
        )
    }
}
