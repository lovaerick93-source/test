package com.remotectl.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Listen for Boot AND Package Restarted (if app is killed but not uninstalled)
        val action = intent.action
        
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
             action == "android.intent.action.PACKAGE_RESTARTED")) {
            
            val serviceIntent = Intent(context, AgentService::class.java)
            serviceIntent.action = AgentService.ACTION_START
            serviceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}