package com.local.attendance.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import java.security.MessageDigest

private val Context.settingsDataStore by preferencesDataStore("settings")
data class AppSettings(
    val enabled: Boolean = false,
    val packageName: String = "com.ss.android.lark",
    val path: List<String> = listOf("工作台", "考勤"),
    val verifiedVersion: Long = -1,
    val verifiedFingerprint: String = "",
    val vivoConfigured: Boolean = false
) {
    val fingerprint get() = MessageDigest.getInstance("SHA-256")
        .digest((packageName + "\n" + path.joinToString("\n")).toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun verified(version: Long) = version >= 0 && verifiedVersion == version && verifiedFingerprint == fingerprint
}
class SettingsStore(context: Context) {
    private val store = context.settingsDataStore
    private val enabled = booleanPreferencesKey("enabled")
    private val pkg = stringPreferencesKey("package")
    private val path = stringPreferencesKey("path")
    private val version = longPreferencesKey("verified_version")
    private val fingerprint = stringPreferencesKey("verified_fingerprint")
    private val vivo = booleanPreferencesKey("vivo_configured")
    val flow: Flow<AppSettings> = store.data.map { p ->
        AppSettings(p[enabled] ?: false, p[pkg] ?: "com.ss.android.lark",
            (p[path] ?: "工作台\n考勤").split('\n').map(String::trim).filter(String::isNotEmpty),
            p[version] ?: -1, p[fingerprint] ?: "", p[vivo] ?: false)
    }
    suspend fun get() = flow.first()
    suspend fun enable(value: Boolean) { store.edit { it[enabled] = value } }
    suspend fun configure(packageName: String, steps: List<String>, configured: Boolean) {
        require(steps.all(com.local.attendance.core.NavigationPolicy::safeLabel)) { "导航入口不能包含打卡、提交或确认等操作文字" }
        store.edit {
            it[pkg] = packageName; it[path] = steps.joinToString("\n"); it[vivo] = configured
            it[enabled] = false; it[version] = -1; it[fingerprint] = ""
        }
    }
    suspend fun verify(settings: AppSettings, installedVersion: Long) {
        store.edit { it[version] = installedVersion; it[fingerprint] = settings.fingerprint }
    }
}
