package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GdouHttpClientTest {
    @Test
    fun isConnectivityError_matchesRefusedDnsTimeoutAcrossCauseChain() {
        assertTrue(
            GdouHttpClient.isConnectivityError(RuntimeException("Connection refused: no further information"))
        )
        assertTrue(
            GdouHttpClient.isConnectivityError(RuntimeException("""Unable to resolve host "jw.gdou.edu.cn""""))
        )
        assertTrue(GdouHttpClient.isConnectivityError(ConnectTimeoutException("Connect timeout has expired")))
        assertTrue(
            GdouHttpClient.isConnectivityError(
                RuntimeException(
                    "send request failed",
                    RuntimeException("java.net.ConnectException: Connection refused")
                )
            )
        )
        assertTrue(GdouHttpClient.isConnectivityError(RuntimeException("Network is unreachable")))
        assertFalse(GdouHttpClient.isConnectivityError(RuntimeException("malformed URL")))
        assertFalse(GdouHttpClient.isConnectivityError(RuntimeException("Unexpected JSON token")))
    }

    @Test
    fun isConnectivityError_matchesRefusedInsideEngineWrapper() {
        // 常见引擎包装形态：外层 message 只有 "Failed to connect to ..."，
        // refused 字样挂在 cause 链上（工单 G：真机 CIO 同源，需保证这种形态也命中）
        val wrapped = RuntimeException(
            "Failed to connect to 'jw.gdou.edu.cn/1.2.3.4:443'",
            RuntimeException("Connection refused")
        )
        assertTrue(GdouHttpClient.isConnectivityError(wrapped))
    }

    @Test
    fun isConnectivityError_stopsAtCauseChainDepth8() {
        // refused 藏在第 10 层 cause（超出 8 层窗口）→ 不视为连接类错误，避免误降级
        var deep: Throwable = RuntimeException("Connection refused")
        repeat(10) { deep = RuntimeException("wrapper $it", deep) }
        assertFalse(GdouHttpClient.isConnectivityError(deep))

        // 6 层包装（窗口内）→ 命中
        var near: Throwable = RuntimeException("Connection refused")
        repeat(6) { near = RuntimeException("wrapper $it", near) }
        assertTrue(GdouHttpClient.isConnectivityError(near))
    }

    @Test
    fun connectivityText_mapsRefusedTimeoutUnreachableToChinese() {
        assertEquals(
            "连接被拒",
            GdouHttpClient.connectivityText(RuntimeException("Connection refused: no further information"))
        )
        assertEquals(
            "连接被拒",
            GdouHttpClient.connectivityText(
                RuntimeException(
                    "send request failed",
                    RuntimeException("java.net.ConnectException: Connection refused")
                )
            )
        )
        assertEquals(
            "连接超时",
            GdouHttpClient.connectivityText(ConnectTimeoutException("Connect timeout has expired"))
        )
        assertEquals(
            "网络不可达",
            GdouHttpClient.connectivityText(RuntimeException("Network is unreachable"))
        )
        // 非连接类 → null，不参与中文映射
        assertEquals(null, GdouHttpClient.connectivityText(RuntimeException("malformed URL")))
        assertEquals(null, GdouHttpClient.connectivityText(RuntimeException("Unexpected JSON token")))
    }
}
