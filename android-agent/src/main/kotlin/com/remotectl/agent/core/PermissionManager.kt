package com.remotectl.agent.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Professional Permission Manager.
 * Handles runtime permissions with rationale, state tracking, and deep-linking to Settings.
 */
object PermissionManager {

    // Track which permissions are currently requested to avoid duplicate dialogs
    private var isRequesting = false

    // State flow to observe permission status if needed (optional advanced feature)
    val permissionStatus: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap())

    /**
     * List of all dangerous permissions this app might need.
     */
    private val requiredPermissions = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS, // Android 13+
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_FINE_LOCATION, // If you use GPS
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            filterNot { it == Manifest.permission.POST_NOTIFICATIONS }
        }
    }

    /**
     * Checks if all required permissions are granted.
     */
    fun isAllGranted(context: Context): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks if a specific permission is granted.
     */
    fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests permissions from an Activity.
     *
     * @param activity The host activity (must be [ComponentActivity] to support
     *                 [ActivityResultContracts.RequestMultiplePermissions]).
     * @param callback Called when the user responds (granted/denied).
     */
    fun requestPermissions(
        activity: ComponentActivity,
        callback: (Boolean) -> Unit
    ) {
        if (isRequesting) return // Prevent double requests
        isRequesting = true

        val permissionsToRequest = requiredPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            callback(true)
            isRequesting = false
            return
        }

        // Register for result launch
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissionsMap: Map<String, Boolean> ->
            val allGranted = permissionsMap.values.all { it }

            // Update internal state
            permissionStatus.value = requiredPermissions.associateWith { perm ->
                ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
            }

            callback(allGranted)
            isRequesting = false
        }

        launcher.launch(permissionsToRequest.toTypedArray())
    }

    /**
     * Checks if the user denied a permission and checked "Don't Ask Again".
     */
    fun shouldShowRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    /**
     * Opens the App Settings screen so the user can manually enable permissions.
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
