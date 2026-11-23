package com.xingheyuzhuan.shiguangschedule.tool

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSLog

private class IosFileManager(private val callbacks: FileManagerCallbacks) : FileManager {
    override fun pickImage() = unsupported("Image picking")
    override fun importFile(allowedExtensions: List<String>) = unsupported("File import")
    override fun exportFile(defaultFileName: String, bytes: ByteArray) = unsupported("File export")

    private fun unsupported(operation: String) {
        NSLog("ShiguangSchedule: %@ is not supported on iOS", operation)
        callbacks.onFileExported?.invoke(false)
        callbacks.onFileImported?.invoke(null, null)
        callbacks.onImagePicked?.invoke(null)
    }
}

@Composable
actual fun rememberFileManager(callbacks: FileManagerCallbacks): FileManager = remember(callbacks) {
    IosFileManager(callbacks)
}
