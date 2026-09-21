package com.xingheyuzhuan.shiguangschedule.tool.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberRequestUnknownSources(onReturned: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onReturned() }
    return {
        launcher.launch(ApkInstaller.intentToUnknownAppSources(context))
    }
}
