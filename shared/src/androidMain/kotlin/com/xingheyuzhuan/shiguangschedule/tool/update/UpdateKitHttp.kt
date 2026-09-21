package com.xingheyuzhuan.shiguangschedule.tool.update

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Kit 内部 HTTP client 工厂：短超时 + 伪装浏览器 UA（清单请求与 APK 下载共用）。 */
object UpdateKitHttp {

    /**
     * 按 [UpdateKitConfig] 构建 client：
     * - 伪装浏览器 UA，绕过服务端按 UA 白名单拦截的 WAF（403）；
     * - retryOnConnectionFailure，弱网下连接失败自动换路重连。
     */
    fun createClient(config: UpdateKitConfig): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSec, TimeUnit.SECONDS)
        .writeTimeout(config.writeTimeoutSec, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", config.userAgent)
                    .build()
            )
        })
        .build()
}
