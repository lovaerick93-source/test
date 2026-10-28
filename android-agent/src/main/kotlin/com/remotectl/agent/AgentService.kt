package com.remotectl.agent

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.remotectl.agent.input.RemoteInputService
import com.remotectl.agent.mirror.ScreenMirror
import com.remotectl.protocol.Envelope
import com.remotectl.protocol.KeyCommand
import com.remotectl.protocol.LaunchAppCommand
import com.remotectl.protocol.Msg
import com.remotectl.protocol.PROTOCOL_VERSION
import com.remotectl.protocol.SwipeCommand
import com.remotectl.protocol.TapCommand
import com.remotectl.protocol.TextCommand
import com.remotectl.protocol.VideoFrame
import com.remotectl.protocol.WireJson
import com.remotectl.protocol.decodePayload
import com.remotectl.protocol.payloadFrom
import com.remotectl.protocol.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * Live state the owner's screen reads. Simple shared state instead of a bound
 * service: the Activity and this service always run in the same process, and
 * polling a few fields is far less code than a binder.
 */
object AgentStatus {
    @Volatile var running = false
    @Volatile var connected = false
    @Volatile var paired = false
    @Volatile var mirroring = false
    @Volatile var captureGranted = false
    @Volatile var hub = ""
    @Volatile var pairingCode = ""
    @Volatile var lastMessage = "idle"
}

/**
 * The agent's one outbound link to the hub, plus the command handling that sits
 * behind it.
 *
 * It is a foreground service purely so Android keeps the socket alive; the
 * notification it shows is the owner's guarantee that they can always see the
 * agent is on and stop it with one tap. Nothing is captured or injected until
 * the owner has both started this service and answered a paired desktop's
 * control request.
 */
class AgentService : Service() {

    private lateinit var config: AgentConfig
    private lateinit var http: OkHttpClient
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var link: WebSocket? = null
    @Volatile private var reconnectQueued = false
    /** Flips to true once MediaProjection consent has been granted for this app. */
    @Volatile private var canCapture = false

    private var mirror: ScreenMirror? = null
    /** Clipboard manager for get/set sync with the desktop. Initialised on create. */
    private var clipboard: ClipboardManager? = null
    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    /**
     * A consent the owner has granted but that has not been spent yet. The token
     * is single-use on Android 14+, so it is cleared the moment it is turned
     * into a [MediaProjection] — stopped or not, it cannot be replayed.
     */
    @Volatile private var captureCode = Activity.RESULT_CANCELED
    @Volatile private var captureData: Intent? = null
    @Volatile private var projection: MediaProjection? = null
    /** Set when a desktop wants the screen and we are only waiting on the owner. */
    @Volatile private var mirrorWanted = false

    private var pairingCode = ""
    private var statusText = ""
    private var retryAttempt = 0

    private val consentTimeout = Runnable {
        Log.i(TAG, "control request timed out with no answer; refusing")
        Consent.dismiss(this)
        respondToRequest(false)
    }

    // -------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        config = AgentConfig(this)
        statusText = getString(R.string.notification_text_offline)
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        clipboard?.addPrimaryClipChangedListener {
            // The phone's own clipboard changed: push it to the desktop.
            val clip = clipboard?.primaryClip
            val text = clip?.getItemAt(0)?.text?.toString().orEmpty()
            if (text.isNotEmpty()) {
                send(
                    Msg.EVT_CLIPBOARD,
                    buildJsonObject { put("text", text) },
                )
            }
        }
        http = OkHttpClient.Builder()
            // The scheme is read per lookup, so editing the saved hub address
            // between http:// and https:// is honoured without a restart.
            .dns(LanGuard.dns { !config.normalizedHubUrl().startsWith("wss://") })
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called unconditionally so the startForegroundService contract is met
        // no matter which action brought us here.
        ensureForeground()

        when (intent?.action) {
            ACTION_STOP -> {
                stopAgent()
                return START_NOT_STICKY
            }
            Consent.ACTION_ACCEPT -> {
                Consent.dismiss(this)
                main.removeCallbacks(consentTimeout)
                respondToRequest(true)
            }
            Consent.ACTION_DECLINE -> {
                Consent.dismiss(this)
                main.removeCallbacks(consentTimeout)
                respondToRequest(false)
            }
            ACTION_REFRESH -> reportDeviceInfo()
            ACTION_GRANT_CAPTURE -> onCaptureGranted(intent)
            ACTION_DENY_CAPTURE -> onCaptureRefused()
            else -> startLink()
        }

        // A system restart arrives as a null intent, which takes the else branch
        // above and reconnects. That is what keeps the phone reachable after the
        // app is swiped away.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AgentStatus.running = false
        AgentStatus.mirroring = false
        main.removeCallbacksAndMessages(null)
        reconnectQueued = false
        mirror?.stop()
        mirror = null
        projection?.let { runCatching { it.stop() } }
        projection = null
        runCatching { link?.cancel() }
        link = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------- link

    private fun startLink() {
        if (link != null) return
        AgentStatus.running = true
        AgentStatus.hub = config.normalizedHubUrl()
        updateStatus(getString(R.string.notification_text_offline))
        openSocket()
    }

    private fun openSocket() {
        val request = Request.Builder().url(config.normalizedHubUrl()).build()
        link = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            link = webSocket
            retryAttempt = 0
            webSocket.send(
                encode(
                    Msg.HELLO,
                    buildJsonObject {
                        put("role", "agent")
                        put("protocol_version", PROTOCOL_VERSION)
                        put("device_id", config.deviceId)
                        put("auto_approve", config.autoApprove)
                        // Re-claim the code the owner already noted down; the hub
                        // honours it when it is free or still this device's own.
                        config.lastPairingCode
                            .takeIf { it.isNotEmpty() }
                            ?.let { put("pairing_code", it) }
                    },
                ),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // A single bad frame must never take the session down.
            runCatching { handleInbound(text) }
                .onFailure { Log.w(TAG, "frame handling failed", it) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onLinkDown("closed ($code) $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onLinkDown("failed: ${t.message}")
        }
    }

    private fun onLinkDown(reason: String) {
        link = null
        AgentStatus.connected = false
        AgentStatus.paired = false
        Log.w(TAG, "link down: $reason")
        // The owner installs this by hand and has no ADB attached, so the reason
        // has to reach the app's own status line, not just the log.
        AgentStatus.lastMessage = reason
        // Nobody is watching any more; leaving the capture session up would keep
        // the phone encoding frames into a dead socket.
        if (AgentStatus.mirroring) stopMirror("hub_link_down")
        if (!AgentStatus.running) return
        updateStatus(getString(R.string.notification_text_offline))
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectQueued) return
        reconnectQueued = true
        val delayMs = (1_000L shl retryAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
        retryAttempt++
        Log.i(TAG, "reconnecting in ${delayMs}ms")
        main.postDelayed({
            reconnectQueued = false
            if (AgentStatus.running && link == null) openSocket()
        }, delayMs)
    }

    private fun stopAgent() {
        stopMirror("agent_stopped")
        AgentStatus.running = false
        AgentStatus.connected = false
        AgentStatus.paired = false
        AgentStatus.captureGranted = false
        AgentStatus.pairingCode = ""
        AgentStatus.lastMessage = "stopped by the phone owner"

        send(Msg.DISCONNECT)
        main.removeCallbacks(consentTimeout)
        Consent.dismiss(this)
        main.removeCallbacksAndMessages(null)
        reconnectQueued = false
        runCatching { link?.close(1000, "agent stopping") }
        link = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- inbound

    private fun handleInbound(text: String) {
        val envelope = runCatching {
            WireJson.decodeFromString(Envelope.serializer(), text)
        }.getOrNull()
        if (envelope == null) {
            Log.w(TAG, "ignored unreadable frame: ${text.take(120)}")
            return
        }
        val payload = envelope.payload

        when (envelope.type) {
            Msg.WELCOME -> {
                AgentStatus.connected = true
                pairingCode = payload.string("pairing_code").orEmpty()
                if (pairingCode.isNotEmpty()) config.lastPairingCode = pairingCode
                AgentStatus.pairingCode = pairingCode
                AgentStatus.lastMessage = "online"
                updateStatus(onlineText())
                reportDeviceInfo()
            }

            Msg.CONNECT_INCOMING ->
                requestConsent(payload.string("controller") ?: "desktop")

            Msg.PEER_CONNECTED -> {
                AgentStatus.paired = true
                AgentStatus.lastMessage = "desktop paired"
                updateStatus(getString(R.string.notification_text_paired))
            }

            Msg.PEER_DISCONNECTED -> {
                AgentStatus.paired = false
                AgentStatus.lastMessage = "desktop left"
                updateStatus(onlineText())
            }

            Msg.ERROR -> {
                val reason = payload.string("reason") ?: "unknown"
                AgentStatus.lastMessage = "hub error: $reason"
                Log.w(TAG, "hub error: $reason")
            }

            else -> handleControl(envelope.type, payload)
        }
    }

    private fun handleControl(type: String, payload: JsonObject) {
        when (type) {
            Msg.CTL_TAP -> {
                val cmd = decode<TapCommand>(payload)
                ack(type, cmd != null && RemoteInputService.tap(cmd.x, cmd.y))
            }

            Msg.CTL_SWIPE -> {
                val cmd = decode<SwipeCommand>(payload)
                ack(
                    type,
                    cmd != null &&
                        RemoteInputService.swipe(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.durationMs),
                )
            }

            Msg.CTL_TEXT -> {
                val cmd = decode<TextCommand>(payload)
                ack(type, cmd != null && RemoteInputService.typeText(cmd.value))
            }

            Msg.CTL_KEY -> {
                val cmd = decode<KeyCommand>(payload)
                ack(type, cmd != null && RemoteInputService.pressKey(cmd.key))
            }

            Msg.CTL_LIST_APPS -> sendApps()

            Msg.CTL_LAUNCH_APP -> {
                val cmd = decode<LaunchAppCommand>(payload)
                ack(type, cmd != null && launchApp(cmd.packageName))
            }

            Msg.CTL_START_MIRROR -> startMirror()

            Msg.CTL_STOP_MIRROR -> stopMirror("desktop_request")

            Msg.CTL_FILE_PUSH, Msg.CTL_FILE_PULL ->
                send(
                    Msg.EVT_FILE_PROGRESS,
                    buildJsonObject { put("error", "file_transfer_not_implemented_yet") },
                )

            Msg.CTL_CLIPBOARD -> {
                val text = payload.string("text").orEmpty()
                val ok = if (text.isNotEmpty()) setClipboard(text) else getClipboard()
                ack(type, ok)
            }

            else -> Log.w(TAG, "unhandled control message: $type")
        }
    }

    // ----------------------------------------------------------------- mirror

    /**
     * Turn the phone's screen into a live video stream for the paired desktop.
     *
     * MediaProjection is the only sanctioned way to do this, and it cannot be
     * obtained silently: Android puts up its own "start capturing?" dialog that
     * only the person holding the phone can answer. If that has not happened
     * yet, this asks for it and answers the desktop honestly rather than
     * pretending a mirror exists.
     */
    private fun startMirror() {
        if (mirror?.isRunning == true) {
            reportMirrorState(true)
            return
        }

        val data = captureData
        if (data == null) {
            // Ask the owner; startMirror() runs again once they answer.
            mirrorWanted = true
            AgentStatus.lastMessage = "waiting for you to allow screen capture"
            send(
                Msg.EVT_MIRROR_STATE,
                buildJsonObject {
                    put("running", false)
                    put("error", "capture_permission_required")
                    put("detail", "Allow screen capture on the phone to begin mirroring")
                },
            )
            requestCapturePermission()
            return
        }

        // Android 14+ requires the mediaProjection foreground type to be live
        // *before* the projection is handed to a virtual display.
        ensureForeground(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val granted = runCatching { projectionManager.getMediaProjection(captureCode, data) }
            .onFailure { Log.w(TAG, "getMediaProjection was refused", it) }
            .getOrNull()
        if (granted == null) {
            failMirror("capture_session_unavailable")
            return
        }

        // Single-use token: never hand the same consent to a second projection.
        captureCode = Activity.RESULT_CANCELED
        captureData = null
        canCapture = false
        AgentStatus.captureGranted = false

        val engine = mirror ?: ScreenMirror(this, mirrorSink).also { mirror = it }
        if (!engine.start(granted)) {
            runCatching { granted.stop() }
            failMirror("capture_start_failed")
            return
        }

        projection = granted
        mirrorWanted = false
        AgentStatus.mirroring = true
        AgentStatus.lastMessage = "mirroring your screen"
        updateStatus(getString(R.string.notification_text_mirroring))
        reportMirrorState(true)
    }

    /** The owner answered Android's capture dialog. */
    private fun onCaptureGranted(intent: Intent?) {
        if (intent == null) {
            onCaptureRefused()
            return
        }
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            onCaptureRefused()
            return
        }
        captureCode = code
        captureData = data
        canCapture = true
        AgentStatus.captureGranted = true
        reportDeviceInfo()
        if (mirrorWanted) startMirror()
    }

    private fun onCaptureRefused() {
        captureCode = Activity.RESULT_CANCELED
        captureData = null
        canCapture = false
        AgentStatus.captureGranted = false
        reportDeviceInfo()
        if (mirrorWanted) failMirror("capture_declined_by_owner")
    }

    private fun failMirror(reason: String) {
        mirrorWanted = false
        AgentStatus.mirroring = false
        AgentStatus.lastMessage = "screen mirroring failed: $reason"
        updateStatus(onlineText())
        send(
            Msg.EVT_MIRROR_STATE,
            buildJsonObject {
                put("running", false)
                put("error", reason)
            },
        )
    }

    private fun stopMirror(reason: String) {
        if (!AgentStatus.mirroring) return
        AgentStatus.mirroring = false
        mirrorWanted = false
        val engine = mirror
        val active = projection
        projection = null
        // Stop the engine first: it unregisters the projection callback, so the
        // asynchronous onStop() below cannot double-report this shutdown.
        engine?.stop()
        active?.let { runCatching { it.stop() } }
        canCapture = captureData != null
        AgentStatus.lastMessage = "screen mirroring stopped ($reason)"
        updateStatus(onlineText())
        reportDeviceInfo()
        send(
            Msg.EVT_MIRROR_STATE,
            buildJsonObject {
                put("running", false)
                put("reason", reason)
            },
        )
    }

    private fun reportMirrorState(running: Boolean) {
        send(
            Msg.EVT_MIRROR_STATE,
            buildJsonObject {
                put("running", running)
                if (running) {
                    val engine = mirror
                    put("width", engine?.frameWidth ?: 0)
                    put("height", engine?.frameHeight ?: 0)
                    // device_pixels: the desktop maps a click straight onto the
                    // frame it received, so taps land where the user aimed.
                    put("coordinate_space", "device_pixels")
                    put("format", "jpeg")
                }
            },
        )
    }

    private fun requestCapturePermission() {
        val prompt = capturePrompt
        if (prompt != null) {
            prompt()
            return
        }
        // The owner is not looking at the app: bring it forward so they can
        // answer Android's dialog. Without it there is nothing to show.
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_REQUEST_CAPTURE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "could not reach the owner for capture consent", it) }
    }

    /** Every captured frame goes to the desktop as one binary websocket message. */
    private val mirrorSink = object : ScreenMirror.Sink {
        override fun onFrame(jpeg: ByteArray, width: Int, height: Int, ptsMs: Long) {
            val ws = link ?: return
            val frame = VideoFrame.pack(
                VideoFrame.CODEC_JPEG,
                VideoFrame.FLAG_KEYFRAME,
                ptsMs,
                width,
                height,
                jpeg,
            )
            ws.send(frame.toByteString())
        }

        override fun onEnded(reason: String) {
            // Fired on the mirror thread when Android revokes capture — the
            // screen was locked, or the owner pulled the system capture chip.
            main.post { stopMirror(reason) }
        }
    }

    // --------------------------------------------------------------- outbound

    private fun requestConsent(controllerId: String) {
        AgentStatus.lastMessage = "control request from $controllerId"
        Consent.post(this, controllerId)
        main.removeCallbacks(consentTimeout)
        main.postDelayed(consentTimeout, CONSENT_TIMEOUT_MS)
    }

    private fun respondToRequest(accepted: Boolean) {
        AgentStatus.lastMessage = if (accepted) "request accepted" else "request declined"
        send(Msg.CONNECT_RESPONSE, buildJsonObject { put("accepted", accepted) })
    }

    private fun reportDeviceInfo() {
        val info = DeviceProbe.snapshot(
            context = this,
            config = config,
            canCapture = canCapture || AgentStatus.mirroring,
            canInjectInput = RemoteInputService.isReady,
        )
        send(Msg.DEVICE_INFO, payloadFrom(info))
    }

    private fun sendApps() {
        val apps = DeviceProbe.launchableApps(this)
        AgentStatus.lastMessage = "sent ${apps.size} installed apps"
        send(Msg.EVT_APPS, buildJsonObject { put("apps", WireJson.encodeToJsonElement(apps)) })
    }

    private fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent) }.isSuccess
    }

    private fun ack(command: String, ok: Boolean) {
        send(
            Msg.EVT_ACK,
            buildJsonObject {
                put("command", command)
                put("ok", ok)
                if (!ok) {
                    put(
                        "reason",
                        if (RemoteInputService.isReady) "refused_by_device"
                        else "accessibility_service_off",
                    )
                }
            },
        )
    }

    /** Write text to the phone's clipboard. Returns false when there is no clipboard service. */
    private fun setClipboard(text: String): Boolean {
        val c = clipboard ?: return false
        c.primaryClip = ClipData.newPlainText("remote-control", text)
        return true
    }

    /** Send the phone's current clipboard text to the desktop. Returns false when empty or unavailable. */
    private fun getClipboard(): Boolean {
        val c = clipboard ?: return false
        val clip = c.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString().orEmpty()
        if (text.isEmpty()) return false
        send(
            Msg.EVT_CLIPBOARD,
            buildJsonObject { put("text", text) },
        )
        return true
    }

    private fun send(type: String, payload: JsonObject = JsonObject(emptyMap())) {
        val ws = link
        if (ws == null) {
            Log.w(TAG, "dropped $type: link is down")
            return
        }
        ws.send(encode(type, payload))
    }

    private fun encode(type: String, payload: JsonObject): String =
        WireJson.encodeToString(Envelope.serializer(), Envelope(type, payload))

    private inline fun <reified T> decode(payload: JsonObject): T? =
        runCatching { payload.decodePayload<T>() }.getOrNull()

    // ----------------------------------------------------------- notification

    private fun onlineText(): String {
        val code = pairingCode.ifEmpty { config.lastPairingCode }.ifEmpty { "—" }
        return getString(R.string.notification_text_online, code)
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_SESSION) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSION,
                getString(R.string.notification_channel_name),
                // LOW keeps it silent but permanently visible: exactly the
                // "you can always see this is running" requirement.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun ensureForeground(
        types: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    ) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateStatus(text: String) {
        statusText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, RC_OPEN, Intent(this, MainActivity::class.java), pendingFlags(),
        )
        val stop = PendingIntent.getService(
            this, RC_STOP,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            pendingFlags(),
        )
        return Notification.Builder(this, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setStyle(Notification.BigTextStyle().bigText(statusText))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_agent),
                    getString(R.string.action_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val ACTION_START = "com.remotectl.agent.START"
        const val ACTION_STOP = "com.remotectl.agent.STOP"
        const val ACTION_REFRESH = "com.remotectl.agent.REFRESH"
        /** Carries the result of Android's own screen-capture dialog back here. */
        const val ACTION_GRANT_CAPTURE = "com.remotectl.agent.GRANT_CAPTURE"
        const val ACTION_DENY_CAPTURE = "com.remotectl.agent.DENY_CAPTURE"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        /** Sent to [MainActivity] when the owner needs to approve capture. */
        const val ACTION_REQUEST_CAPTURE = "com.remotectl.agent.REQUEST_CAPTURE"

        /**
         * Set by [MainActivity] while it is on screen. When the desktop asks for
         * the screen we can then raise Android's capture dialog immediately
         * instead of first having to bring the app to the foreground.
         */
        @Volatile var capturePrompt: (() -> Unit)? = null

        private const val TAG = "AgentService"
        private const val CHANNEL_SESSION = "session"
        private const val NOTIFICATION_ID = 1001
        private const val RC_OPEN = 11
        private const val RC_STOP = 12
        private const val CONSENT_TIMEOUT_MS = 60_000L
    }
}
