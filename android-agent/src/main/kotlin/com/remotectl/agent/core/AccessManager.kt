package com.remotectl.agent.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Singleton Manager to coordinate access between AgentService and FullAccessService.
 */
object AccessManager {
    private var fullAccessService: FullAccessService? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun bind(service: FullAccessService) {
        fullAccessService = service
    }

    fun unbind() {
        fullAccessService = null
    }

    // Helper to start screen mirroring from AgentService
    fun startMirror(context: Context) {
        scope.launch {
            fullAccessService?.startScreenCapture(context)
        }
    }

    // Helper to stop screen mirroring
    fun stopMirror() {
        scope.launch {
            fullAccessService?.stopScreenCapture()
        }
    }
    
    // Helper to record audio
    fun startAudioRecording(context: Context) {
        scope.launch {
            fullAccessService?.startAudioRecording(context)
        }
    }

    fun stopAudioRecording() {
        scope.launch {
            fullAccessService?.stopAudioRecording()
        }
    }
}