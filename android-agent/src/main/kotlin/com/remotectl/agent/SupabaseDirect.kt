package com.remotectl.agent

import android.content.Context
import android.util.Log
import com.remotectl.protocol.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * Writes device state directly to Supabase from the Android app, bypassing the
 * Python hub for database persistence.
 *
 * The hub still handles WebSocket relay, pairing, and control traffic. This
 * class only upserts device rows into the `devices` table so devices survive
 * server restarts and appear in the controller's device list even when offline.
 *
 * Security note: the anon key is embedded in the APK. Anyone who reverse-engineers
 * the APK can read it. The anon key is subject to Row-Level Security on Supabase,
 * so it can only perform the operations the RLS policies allow.
 */
class SupabaseDirect(private val context: Context) {

    private val url: String by lazy {
        try { context.getString(R.string.supabase_url) } catch (e: Exception) { "" }
    }

    private val anonKey: String by lazy {
        try { context.getString(R.string.supabase_anon_key) } catch (e: Exception) { "" }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Scope for fire-and-forget Supabase writes. */
    private val scope = CoroutineScope(Dispatchers.Default)
    private val job = Job()

    /** Upsert one device row into Supabase `devices` table. Fire and forget. */
    fun upsertDevice(info: DeviceInfo, pairingCode: String?, autoApprove: Boolean, lastIp: String?) {
        if (url.isBlank() || anonKey.isBlank()) return
        scope.launch {
            try {
                val body = buildJsonObject {
                    put("device_id", info.deviceId)
                    put("name", info.name)
                    put("model", info.model)
                    put("manufacturer", info.manufacturer)
                    put("android_version", info.androidVersion)
                    put("sdk_int", info.sdkInt)
                    put("screen_width", info.screenWidth)
                    put("screen_height", info.screenHeight)
                    put("battery_percent", info.batteryPercent)
                    put("can_capture", info.canCapture)
                    put("can_inject_input", info.canInjectInput)
                    put("installed_app_count", info.installedAppCount)
                    put("pairing_code", pairingCode)
                    put("auto_approve", autoApprove)
                    put("last_ip", lastIp)
                    put("last_seen", System.currentTimeMillis().toString())
                }
                val requestBody = RequestBody.Companion.create(
                    json.encodeToJsonElement(body).toString(),
                    "application/json; charset=utf-8".toMediaType()
                )
                val request = Request.Builder()
                    .url("$url/rest/v1/devices")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $anonKey")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Prefer", "resolution=merge-duplicates")
                    .method("POST", requestBody)
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w("SupabaseDirect", "upsert failed: ${response.code} ${response.body?.string()}")
                    } else {
                        Log.i("SupabaseDirect", "device upserted: ${info.deviceId}")
                    }
                }
            } catch (e: Exception) {
                Log.w("SupabaseDirect", "upsert exception", e)
            }
        }
    }

    /** Delete a device row from Supabase. Fire and forget. */
    fun deleteDevice(deviceId: String) {
        if (url.isBlank() || anonKey.isBlank()) return
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("$url/rest/v1/devices?device_id=eq.$deviceId")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $anonKey")
                    .delete()
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w("SupabaseDirect", "delete failed: ${response.code} ${response.body?.string()}")
                    } else {
                        Log.i("SupabaseDirect", "device deleted: $deviceId")
                    }
                }
            } catch (e: Exception) {
                Log.w("SupabaseDirect", "delete exception", e)
            }
        }
    }

    fun close() {
        job.cancel()
        scope.cancel()
        http.connectionPool().evictAll()
    }

    companion object {
        @Volatile
        private var instance: SupabaseDirect? = null

        fun init(context: Context): SupabaseDirect {
            return instance ?: SupabaseDirect(context.applicationContext).also { instance = it }
        }

        fun get(): SupabaseDirect? = instance
    }
}
