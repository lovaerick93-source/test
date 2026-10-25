package com.remotectl.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.BatteryManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.remotectl.protocol.AppEntry
import com.remotectl.protocol.DeviceInfo

/**
 * Reads facts about the phone itself.
 *
 * Everything here is either public device metadata or the launcher-visible app
 * list -- the same list any app drawer shows, and only ever sent to a desktop
 * the owner has approved. No other app's private data is read.
 */
object DeviceProbe {

    /** The app list changes rarely and is slow to build, so it is reused briefly. */
    private const val APP_CACHE_MS = 30_000L
    @Volatile
    private var cachedApps: Pair<Long, List<AppEntry>>? = null

    fun snapshot(
        context: Context,
        config: AgentConfig,
        canCapture: Boolean,
        canInjectInput: Boolean,
    ): DeviceInfo {
        val (width, height) = screenSize(context)
        return DeviceInfo(
            deviceId = config.deviceId,
            name = config.deviceName,
            model = Build.MODEL ?: "unknown",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            screenWidth = width,
            screenHeight = height,
            batteryPercent = batteryPercent(context),
            canCapture = canCapture,
            canInjectInput = canInjectInput,
            installedAppCount = launchableApps(context).size,
        )
    }

    /**
     * Apps that have a launcher entry -- i.e. the ones a remote user could
     * actually open. One query instead of one lookup per installed package.
     */
    fun launchableApps(context: Context): List<AppEntry> {
        val now = System.currentTimeMillis()
        cachedApps?.let { (at, list) -> if (now - at < APP_CACHE_MS) return list }
        val fresh = queryLaunchableApps(context)
        cachedApps = now to fresh
        return fresh
    }

    private fun queryLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }

        val self = context.packageName
        return resolved
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != self }
            .map { app ->
                AppEntry(
                    packageName = app.packageName,
                    label = pm.getApplicationLabel(app).toString(),
                    system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun batteryPercent(context: Context): Int {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return (level * 100) / scale
    }
}
