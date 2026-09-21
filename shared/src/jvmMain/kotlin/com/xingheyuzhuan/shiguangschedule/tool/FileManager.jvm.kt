package com.xingheyuzhuan.shiguangschedule.tool

import androidx.compose.runtime.Composable

private class JvmFileManager : FileManager {
    override fun pickImage() {}
    override fun importFile(allowedExtensions: List<String>) {}
    override fun exportFile(defaultFileName: String, bytes: ByteArray) {}
}

@Composable
actual fun rememberFileManager(callbacks: FileManagerCallbacks): FileManager = JvmFileManager()
