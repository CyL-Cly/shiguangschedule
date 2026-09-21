package com.xingheyuzhuan.shiguangschedule.tool.update

import androidx.compose.runtime.Composable

/**
 * 返回一个用于跳转系统「安装未知应用」授权页的启动函数；
 * 授权页关闭后回调 [onReturned]（由调用方重新判定权限并继续安装）。
 * 非 Android 平台为空实现。
 */
@Composable
expect fun rememberRequestUnknownSources(onReturned: () -> Unit): () -> Unit
