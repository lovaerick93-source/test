package com.remotectl.agent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.remotectl.agent.core.PermissionManager
import com.remotectl.agent.core.PermissionResultHolder

/**
 * Bridge activity that lets [AgentService] (a Service) request runtime permissions
 * through [PermissionManager], which requires an Activity context for
 * [androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions].
 *
 * AgentService launches this with FLAG_ACTIVITY_NEW_TASK. When the user responds,
 * the result is delivered via [PermissionResultHolder.callback].
 */
class PermissionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RESULT = "permission_result"
        const val REQUEST_CODE_PERMISSIONS = 1001

        fun startFrom(context: android.content.Context) {
            val intent = Intent(context, PermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PermissionManager.requestPermissions(this) { allGranted ->
            PermissionResultHolder.result = allGranted
            PermissionResultHolder.callback?.invoke(allGranted)
            PermissionResultHolder.callback = null
            finish()
        }
    }
}
