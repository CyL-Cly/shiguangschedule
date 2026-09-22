package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

/** iOS 维持 Darwin（NSURLSession 自带多地址回退/happy eyeballs）。 */
internal actual fun gdouHttpEngine(): HttpClientEngine = Darwin.create()
