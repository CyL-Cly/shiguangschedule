package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.engine.HttpClientEngine

/**
 * GDOU 专用 HTTP 引擎注入。
 *
 * Android 用 OkHttp（wfw.gdou.edu.cn 为双栈域名，AAAA 是仅校内可达的 CERNET2 地址，
 * CIO 引擎不做多地址回退，Android 优先 IPv6 时校外必失败；OkHttp 引擎可注入
 * IPv4 优先的 Dns 且自带逐地址回退）。JVM/iOS 维持原引擎。
 */
internal expect fun gdouHttpEngine(): HttpClientEngine
