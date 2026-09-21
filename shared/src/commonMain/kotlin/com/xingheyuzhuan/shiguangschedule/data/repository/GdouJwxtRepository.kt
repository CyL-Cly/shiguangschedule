package com.xingheyuzhuan.shiguangschedule.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouAccountSnapshot
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouCookie
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouCredentials
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouHttpClient
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouMfaChannel
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouSyncLogEntry
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouTerm
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport
import com.xingheyuzhuan.shiguangschedule.tool.SecureCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import kotlin.random.Random

@Single
class GdouJwxtRepository(
    @Named("ApiConfig") private val dataStore: DataStore<Preferences>,
    private val secureCrypto: SecureCrypto,
    private val http: GdouHttpClient
) {
    private companion object {
        const val LEGACY_MIRROR_HOST = "it-eng.cloudbility.com.cn"
    }

    object Keys {
        private const val PREFIX = "gdou_"
        val USERNAME = stringPreferencesKey("${PREFIX}username")
        val ENCRYPTED_PASSWORD = stringPreferencesKey("${PREFIX}encrypted_pwd")
        val CRYPTO_IV = stringPreferencesKey("${PREFIX}crypto_iv")
        val COOKIES = stringPreferencesKey("${PREFIX}cookies")
        val FINGERPRINT = stringPreferencesKey("${PREFIX}fingerprint")
        val AUTO_SYNC = booleanPreferencesKey("${PREFIX}auto_sync")
        val TABLE_ID = stringPreferencesKey("${PREFIX}table_id")
        val XNM = stringPreferencesKey("${PREFIX}xnm")
        val XQM = stringPreferencesKey("${PREFIX}xqm")
        val MFA_CHANNEL = stringPreferencesKey("${PREFIX}mfa_channel")
        val LAST_SYNC_AT = longPreferencesKey("${PREFIX}last_sync_at")
        val LAST_SYNC_MSG = stringPreferencesKey("${PREFIX}last_sync_msg")
        val LAST_SYNC_OK = booleanPreferencesKey("${PREFIX}last_sync_ok")
        val SYNC_LOG = stringPreferencesKey("${PREFIX}sync_log")
    }

    val snapshotFlow: Flow<GdouAccountSnapshot> = dataStore.data.map { prefs ->
        GdouAccountSnapshot(
            username = prefs[Keys.USERNAME].orEmpty(),
            hasPassword = !prefs[Keys.ENCRYPTED_PASSWORD].isNullOrBlank(),
            autoSyncEnabled = prefs[Keys.AUTO_SYNC] ?: false,
            tableId = prefs[Keys.TABLE_ID].orEmpty(),
            xnm = prefs[Keys.XNM].orEmpty(),
            xqm = prefs[Keys.XQM].orEmpty(),
            mfaChannel = prefs[Keys.MFA_CHANNEL] ?: GdouMfaChannel.WECOM.reAuthType,
            lastSyncAtMillis = prefs[Keys.LAST_SYNC_AT] ?: 0L,
            lastSyncMessage = prefs[Keys.LAST_SYNC_MSG].orEmpty(),
            lastSyncSuccess = prefs[Keys.LAST_SYNC_OK] ?: false,
            hasSession = !prefs[Keys.COOKIES].isNullOrBlank()
        )
    }

    val syncLogFlow: Flow<List<GdouSyncLogEntry>> = dataStore.data.map { prefs ->
        runCatching {
            CourseImportExport.json.decodeFromString<List<GdouSyncLogEntry>>(prefs[Keys.SYNC_LOG].orEmpty())
        }.getOrDefault(emptyList())
            .sortedByDescending { it.atMillis }
    }

    suspend fun snapshot(): GdouAccountSnapshot = snapshotFlow.first()

    suspend fun saveCredentials(username: String, password: String) {
        val crypto = secureCrypto.encrypt(password) ?: return
        dataStore.edit { prefs ->
            prefs[Keys.USERNAME] = username.trim()
            prefs[Keys.ENCRYPTED_PASSWORD] = crypto.encryptedData
            prefs[Keys.CRYPTO_IV] = crypto.iv
        }
    }

    suspend fun loadCredentials(): GdouCredentials? {
        val prefs = dataStore.data.first()
        val username = prefs[Keys.USERNAME]?.trim().orEmpty()
        val encrypted = prefs[Keys.ENCRYPTED_PASSWORD]
        val iv = prefs[Keys.CRYPTO_IV]
        if (username.isBlank() || encrypted.isNullOrBlank() || iv.isNullOrBlank()) return null
        val password = secureCrypto.decrypt(encrypted, iv) ?: return null
        return GdouCredentials(username, password)
    }

    suspend fun fingerprint(): String {
        val existing = dataStore.data.first()[Keys.FINGERPRINT]
        if (!existing.isNullOrBlank()) return existing
        val generated = randomFingerprint()
        dataStore.edit { it[Keys.FINGERPRINT] = generated }
        return generated
    }

    suspend fun persistCookies() {
        val cookies = http.cookieStore.snapshot()
        val encoded = CourseImportExport.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(GdouCookie.serializer()),
            cookies
        )
        dataStore.edit { it[Keys.COOKIES] = encoded }
    }

    suspend fun restoreCookies() {
        val raw = dataStore.data.first()[Keys.COOKIES] ?: return
        val decoded = runCatching {
            CourseImportExport.json.decodeFromString<List<GdouCookie>>(raw)
        }.getOrNull() ?: return
        val cookies = decoded.filter { it.domain.trimStart('.').lowercase() != LEGACY_MIRROR_HOST }
        http.cookieStore.restore(cookies)
    }

    suspend fun restoreCookiesIfEmpty() {
        if (http.cookieStore.isEmpty()) restoreCookies()
    }

    suspend fun clearCookies() {
        http.cookieStore.clear()
        dataStore.edit { it.remove(Keys.COOKIES) }
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_SYNC] = enabled }
    }

    suspend fun setTableId(tableId: String) {
        dataStore.edit { it[Keys.TABLE_ID] = tableId }
    }

    suspend fun setTerm(term: GdouTerm?) {
        dataStore.edit { prefs ->
            if (term == null) {
                prefs.remove(Keys.XNM)
                prefs.remove(Keys.XQM)
            } else {
                prefs[Keys.XNM] = term.xnm
                prefs[Keys.XQM] = term.xqm
            }
        }
    }

    suspend fun setMfaChannel(channel: GdouMfaChannel) {
        dataStore.edit { it[Keys.MFA_CHANNEL] = channel.reAuthType }
    }

    suspend fun recordSync(
        success: Boolean,
        message: String,
        atMillis: Long,
        source: String = "manual",
        detail: String? = null
    ) {
        val current = runCatching {
            val raw = dataStore.data.first()[Keys.SYNC_LOG]
            if (raw == null) emptyList() else CourseImportExport.json.decodeFromString<List<GdouSyncLogEntry>>(raw)
        }.getOrDefault(emptyList())
        val updated = listOf(GdouSyncLogEntry(atMillis, success, message, source, detail)) + current
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_OK] = success
            prefs[Keys.LAST_SYNC_MSG] = message
            prefs[Keys.LAST_SYNC_AT] = atMillis
            prefs[Keys.SYNC_LOG] = CourseImportExport.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(GdouSyncLogEntry.serializer()),
                updated.take(50)
            )
        }
    }

    suspend fun clearSyncLog() {
        dataStore.edit { it.remove(Keys.SYNC_LOG) }
    }

    suspend fun logout() {
        clearCookies()
        dataStore.edit { prefs ->
            prefs.remove(Keys.USERNAME)
            prefs.remove(Keys.ENCRYPTED_PASSWORD)
            prefs.remove(Keys.CRYPTO_IV)
            prefs[Keys.AUTO_SYNC] = false
            prefs.remove(Keys.LAST_SYNC_MSG)
            prefs[Keys.LAST_SYNC_OK] = false
            prefs[Keys.LAST_SYNC_AT] = 0L
            prefs.remove(Keys.SYNC_LOG)
        }
    }

    private fun randomFingerprint(): String {
        val hex = "0123456789ABCDEF"
        return buildString(32) {
            repeat(32) { append(hex[Random.nextInt(16)]) }
        }
    }
}
