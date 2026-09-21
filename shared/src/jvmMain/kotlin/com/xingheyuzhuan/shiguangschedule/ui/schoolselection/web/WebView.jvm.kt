package com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

actual val isDesktopPlatform: Boolean = true

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}

private class JvmWebViewController : WebViewController {
    override val currentUrl: String = ""
    override fun reload() {}
    override fun goBack(): Boolean = false
    override fun canGoBack(): Boolean = false
    override fun setDevToolsEnabled(enabled: Boolean) {}
    override fun executeScript(jsCode: String) {}
    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        callback?.invoke(null)
    }
}

@Composable
actual fun rememberWebViewController(): WebViewController = remember { JvmWebViewController() }

@Composable
actual fun PlatformWebView(
    modifier: Modifier,
    url: String,
    isDesktopMode: Boolean,
    isDevToolsEnabled: Boolean,
    controller: WebViewController,
    bridgeHandler: WebBridgeHandler,
    onProgressChange: (Float) -> Unit,
    onTitleChange: (String) -> Unit,
    onNavigateToSchedule: () -> Unit
) {
}
