package com.remotectl.agent

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings

/**
 * Everything the agent remembers between launches: where the hub lives, who
 * this phone claims to be, and whether the owner has opted into skipping the
 * per-session prompt.
 *
 * Nothing stored here is secret. [deviceId] is derived from ANDROID_ID, which
 * is scoped per app-signing-key and per user, not a hardware serial number.
 */
class AgentConfig(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** What the owner typed. May be a bare host, a host:port, or a full URL. */
    var hubUrl: String
        get() = prefs.getString(KEY_HUB_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_HUB_URL
        set(value) = prefs.edit().putString(KEY_HUB_URL, value.trim()).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: (Build.MODEL ?: "android")
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

    /** When true the phone accepts sessions without asking. Off unless opted in. */
    var autoApprove: Boolean
        get() = prefs.getBoolean(KEY_AUTO_APPROVE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_APPROVE, value).apply()

    /** Stable across restarts and reinstalls, so the desktop keeps recognising this phone. */
    val deviceId: String
        get() {
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val raw = runCatching {
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
            val generated = "android-" + raw.orEmpty().takeLast(10).lowercase()
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    /** The code the hub handed back, kept only so the UI can redisplay it. */
    var lastPairingCode: String
        get() = prefs.getString(KEY_LAST_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_CODE, value).apply()

    /**
     * Turns whatever the owner typed into a WebSocket URL the client can use:
     * `192.168.1.5` -> `ws://192.168.1.5:8000/ws`. Ports and schemes are filled
     * in from the hub's defaults so a half-typed address still works.
     */
    fun normalizedHubUrl(): String {
        var raw = hubUrl.trim()
        if (raw.isEmpty()) raw = DEFAULT_HUB_URL
        if (!raw.contains("://")) raw = "http://$raw"
        raw = raw.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")

        val secure = raw.startsWith("wss://")
        val prefix = if (secure) "wss://" else "ws://"
        val authority = raw.removePrefix(prefix).substringBefore('/')
        val hostPort = if (authority.contains(':')) authority else "$authority:$DEFAULT_PORT"
        return "$prefix$hostPort/ws"
    }

    companion object {
        const val DEFAULT_PORT = "8000"
        const val DEFAULT_HUB_URL = "http://192.168.1.100:8000/ws"

        private const val PREFS_NAME = "agent"
        private const val KEY_HUB_URL = "hub_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_AUTO_APPROVE = "auto_approve"
        private const val KEY_LAST_CODE = "last_pairing_code"
    }
}
