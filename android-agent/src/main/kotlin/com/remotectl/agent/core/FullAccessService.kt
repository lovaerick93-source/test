package com.remotectl.agent.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.remotectl.agent.AgentService
import com.remotectl.agent.R
import com.remotectl.agent.mirror.ScreenMirror

/**
 * The foreground service that owns MediaProjection, MediaRecorder, etc.
 * Started by AgentService; all public methods are called through [AccessManager].
 */
class FullAccessService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecorder: MediaRecorder? = null
    private var mirror: ScreenMirror? = null

    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    override fun onCreate() {
        super.onCreate()
        AccessManager.bind(this)
        startForegroundService()
    }

    override fun onDestroy() {
        stopScreenCapture()
        stopAudioRecording()
        AccessManager.unbind()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        val channelId = "full_access_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Full Access Service",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.remotectl.agent.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Full Access Service")
            .setContentText("Running")
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        val notifId = 1002
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // FOREGROUND_SERVICE_TYPE_DATA_SYNC=1, MEDIA_PROJECTION=4, MICROPHONE=8
            startForeground(
                notifId, notification,
                1 or 4 or 8,
            )
        } else {
            startForeground(notifId, notification)
        }
    }

    fun startScreenCapture(context: Context) {
        if (mediaProjection != null) {
            Log.i("FullAccessService", "Screen capture already running")
            return
        }

        val data = CaptureDataHolder.data ?: return
        val resultCode = CaptureDataHolder.resultCode

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            ?.also { projection ->
                mirror = ScreenMirror(context, frameSink)
                if (!mirror!!.start(projection)) {
                    Log.e("FullAccessService", "ScreenMirror failed to start")
                    stopScreenCapture()
                } else {
                    AgentService.sendMirrorState(true)
                }
            } ?: run {
                Log.e("FullAccessService", "MediaProjection was null")
                null
            }
    }

    fun stopScreenCapture() {
        mirror?.stop()
        mirror = null
        mediaProjection?.stop()
        mediaProjection = null
        AgentService.sendMirrorState(false)
    }

    fun startAudioRecording(context: Context) {
        if (audioRecorder != null) {
            Log.i("FullAccessService", "Audio recording already running")
            return
        }

        audioRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(getAudioFile(context).absolutePath)
            try {
                prepare()
                start()
            } catch (e: Exception) {
                Log.e("FullAccessService", "AudioRecorder failed: ${e.message}")
                release()
                audioRecorder = null
            }
        }
        AgentService.notifyAudioState(true)
    }

    fun stopAudioRecording() {
        audioRecorder?.apply {
            try { stop() } catch (e: Exception) { /* ignore */ }
            release()
        }
        audioRecorder = null
        AgentService.notifyAudioState(false)
    }

    private fun getAudioFile(context: Context) =
        java.io.File(context.cacheDir, "audio_${System.currentTimeMillis()}.m4a")

    private val frameSink = object : ScreenMirror.Sink {
        override fun onFrame(jpeg: ByteArray, width: Int, height: Int, ptsMs: Long) {
            AgentService.sendFrame(jpeg, width, height, ptsMs)
        }

        override fun onEnded(reason: String) {
            AgentService.sendMirrorState(false, reason)
            stopScreenCapture()
        }
    }
}
