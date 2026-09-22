package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 用本机回环的 connection refused 实证 OkHttp 引擎（Android 侧 GdouHttpClient 现用引擎，
 * 见 GdouHttpEngine androidMain actual）在连接被拒时的异常形态，
 * 并固化「refused 异常命中 isConnectivityError」这一语义——
 * requestFailureSink 的连接类失败判定（中文映射 connectivityText）依赖它。
 *
 * 只连本机回环的临时关闭端口，不做任何外部网络探针。
 */
class GdouOkhttpRefusedShapeTest {
    @Test
    fun okhttpRefusedExceptionMatchesConnectivityHints() {
        // 先 bind 一个空闲端口再释放，保证目标端口处于无监听（refused）状态
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        socket.close()

        val client = HttpClient(OkHttp)
        val thrown: Throwable? = kotlinx.coroutines.runBlocking {
            try {
                client.get("http://127.0.0.1:$port/")
                null
            } catch (e: Throwable) {
                e
            } finally {
                client.close()
            }
        }

        val error = thrown ?: error("预期本机回环 $port 连接被拒，实际请求意外成功")
        assertTrue(
            GdouHttpClient.isConnectivityError(error),
            "OkHttp refused 异常必须命中 isConnectivityError，" +
                "实际形态：${error::class.simpleName}: ${error.message}（cause=${error.cause}）"
        )
        val text = GdouHttpClient.connectivityText(error)
        assertTrue(
            text != null && !text.contains("refused", ignoreCase = true),
            "connectivityText 必须返回不含 refused 原文的中文描述，实际：$text"
        )
    }
}
