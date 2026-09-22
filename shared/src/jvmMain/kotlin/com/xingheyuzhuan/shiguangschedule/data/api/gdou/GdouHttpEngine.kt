package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

/** JVM 维持 CIO（桌面 JVM 默认 IPv4 优先，无校外双栈问题）。 */
internal actual fun gdouHttpEngine(): HttpClientEngine = CIO.create()
