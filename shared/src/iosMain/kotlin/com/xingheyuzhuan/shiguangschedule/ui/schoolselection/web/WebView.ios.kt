package com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

actual val isDesktopPlatform: Boolean = false

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}

private class IosWebViewController : WebViewController {
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
actual fun rememberWebViewController(): WebViewController = remember { IosWebViewController() }

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
    Text(
        text = "Web-based course import is not supported on iOS.",
        modifier = modifier
    )
}
