package com.remotectl.agent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.remotectl.agent.input.RemoteInputService

/**
 * The phone owner's console.
 *
 * Nothing happens until the owner taps "Start agent" here, and the agent never
 * hides: while it is running there is a permanent notification with a Stop
 * button. Remote input additionally needs the accessibility service to be
 * switched on in system Settings, and every desktop session needs an explicit
 * Accept tap on this phone.
 */
class MainActivity : Activity() {

    private lateinit var config: AgentConfig

    private lateinit var statusView: TextView
    private lateinit var accessibilityView: TextView
    private lateinit var urlField: EditText
    private lateinit var nameField: EditText
    private lateinit var autoApproveBox: CheckBox
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var mirrorView: TextView

    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val ticker = Handler(Looper.getMainLooper())
    private var consentDialog: AlertDialog? = null
    private var lastAccessibilityState = false

    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            ticker.postDelayed(this, 1_000L)
        }
    }

    private val onConsentNeeded: (String) -> Unit = { controllerId ->
        runOnUiThread { showConsentDialog(controllerId) }
    }

    // -------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AgentConfig(this)
        setContentView(buildUi())
        askForNotificationPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * The service asks for screen capture by bringing us forward with
     * [AgentService.ACTION_REQUEST_CAPTURE]; that means the desktop wants the
     * mirror and only the owner can release it.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == AgentService.ACTION_REQUEST_CAPTURE) {
            Toast.makeText(this, R.string.capture_prompt_toast, Toast.LENGTH_LONG).show()
            askForCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        Consent.dialogListener = onConsentNeeded
        // While we are on screen the service can raise the system capture dialog
        // directly instead of having to restart this Activity.
        AgentService.capturePrompt = { askForCapture() }

        // Coming back from the accessibility screen: tell the hub the phone's
        // capabilities changed so the desktop's device card updates.
        val ready = RemoteInputService.isReady
        if (ready != lastAccessibilityState) {
            lastAccessibilityState = ready
            if (AgentStatus.connected) sendToService(AgentService.ACTION_REFRESH)
        }

        renderStatus()
        ticker.removeCallbacks(refresh)
        ticker.post(refresh)
    }

    override fun onPause() {
        Consent.dialogListener = null
        AgentService.capturePrompt = null
        ticker.removeCallbacks(refresh)
        consentDialog?.dismiss()
        consentDialog = null
        super.onPause()
    }

    // -------------------------------------------------------------------- ui

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 22f
            },
        )
        root.addView(
            TextView(this).apply {
                text = "Consent-based remote support. The agent runs only while you keep " +
                    "it started and always shows a notification you can see. Every desktop " +
                    "session has to be accepted on this phone."
                textSize = 13f
                setPadding(0, dp(6), 0, dp(4))
            },
        )

        root.addView(sectionLabel("Hub address (your computer)"))
        urlField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = AgentConfig.DEFAULT_HUB_URL
            setText(config.hubUrl)
        }
        root.addView(urlField)

        root.addView(sectionLabel("Device name shown on the desktop"))
        nameField = EditText(this).apply { setText(config.deviceName) }
        root.addView(nameField)

        autoApproveBox = CheckBox(this).apply {
            text = "Accept control requests without asking me (not recommended)"
            isChecked = config.autoApprove
        }
        root.addView(autoApproveBox)

        root.addView(
            Button(this).apply {
                text = "Save settings"
                setOnClickListener { saveSettings() }
            },
        )

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply {
            text = "Start agent"
            setOnClickListener {
                persist()
                sendToService(AgentService.ACTION_START)
            }
        }
        stopButton = Button(this).apply {
            text = "Stop agent"
            setOnClickListener { sendToService(AgentService.ACTION_STOP) }
        }
        buttonRow.addView(startButton, weight())
        buttonRow.addView(stopButton, weight())
        root.addView(buttonRow)

        statusView = TextView(this).apply { textSize = 13f }
        root.addView(statusView)

        root.addView(sectionLabel("Remote input (tap, swipe, type)"))
        accessibilityView = TextView(this).apply { textSize = 13f }
        root.addView(accessibilityView)
        root.addView(
            Button(this).apply {
                text = "Open accessibility settings"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            },
        )

        root.addView(sectionLabel("Screen mirroring"))
        mirrorView = TextView(this).apply { textSize = 13f }
        root.addView(mirrorView)
        root.addView(
            Button(this).apply {
                text = "Allow screen capture"
                setOnClickListener { askForCapture() }
            },
        )

        root.addView(sectionLabel("How to test"))
        root.addView(
            TextView(this).apply {
                text = "1. Start the hub on your computer.\n" +
                    "2. Put the computer's LAN IP / tunnel URL in the hub address above and save.\n" +
                    "3. Start the agent — this phone is now controllable.\n" +
                    "4. In the desktop app, connect and click this phone to take control.\n" +
                    "5. Switch on accessibility above to allow taps, swipes and typing."
                textSize = 13f
            },
        )

        return ScrollView(this).apply { addView(root) }
    }

    private fun renderStatus() {
        val running = AgentStatus.running
        startButton.isEnabled = !running
        stopButton.isEnabled = running

        val state = when {
            !running -> "stopped"
            AgentStatus.paired -> "paired — a desktop is in control"
            AgentStatus.connected -> "online, waiting for a desktop"
            else -> "connecting…"
        }

        statusView.text = buildString {
            append("Agent: ").append(state).append('\n')
            append("Hub: ").append(AgentStatus.hub.ifEmpty { config.normalizedHubUrl() }).append('\n')
            append("Device id: ").append(config.deviceId).append('\n')
            append("Last event: ").append(AgentStatus.lastMessage)
        }

        val ready = RemoteInputService.isReady
        accessibilityView.text = if (ready) {
            "Enabled — an accepted desktop can tap, swipe and type."
        } else {
            "Off — taps, swipes and typing are refused until you switch it on."
        }
        accessibilityView.setTextColor(if (ready) COLOR_OK else COLOR_BAD)

        mirrorView.text = when {
            AgentStatus.mirroring -> "ON — a paired desktop is seeing this screen right now."
            AgentStatus.captureGranted ->
                "Allowed, not yet streaming. The mirror starts when a desktop asks."
            else ->
                "Off — no desktop can see this screen. Tap below (or let a desktop ask) " +
                    "to approve capture; Android will confirm with you first."
        }
        mirrorView.setTextColor(if (AgentStatus.mirroring) COLOR_OK else COLOR_BAD)
    }

    private fun showConsentDialog(controllerId: String) {
        if (isFinishing || isDestroyed) return
        consentDialog?.dismiss()
        consentDialog = AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(getString(R.string.consent_message, controllerId))
            .setCancelable(false)
            .setPositiveButton(R.string.consent_accept) { _, _ -> answerConsent(true) }
            .setNegativeButton(R.string.consent_decline) { _, _ -> answerConsent(false) }
            .show()
    }

    private fun answerConsent(accepted: Boolean) {
        consentDialog?.dismiss()
        consentDialog = null
        sendToService(if (accepted) Consent.ACTION_ACCEPT else Consent.ACTION_DECLINE)
    }

    /**
     * Ask Android for permission to read the screen.
     *
     * There is deliberately no way around this dialog: it is the system's, not
     * ours, so nothing can capture the display without the owner tapping "Start
     * now". The answer comes back in [onActivityResult] and is handed straight
     * to the service, which is the only thing that owns the session.
     */
    private fun askForCapture() {
        if (isFinishing || isDestroyed) return
        runCatching { startActivityForResult(projectionManager.createScreenCaptureIntent(), RC_CAPTURE) }
            .onFailure {
                Toast.makeText(this, R.string.capture_dialog_unavailable, Toast.LENGTH_LONG).show()
            }
    }

    @Deprecated("startActivityForResult is the only API available without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_CAPTURE) return
        // Nothing to hand the answer to if the agent is not up: a stopped agent
        // must not be revived by a capture reply.
        if (!AgentStatus.running) return
        val intent = Intent(this, AgentService::class.java).apply {
            action = if (resultCode == RESULT_OK && data != null) {
                AgentService.ACTION_GRANT_CAPTURE
            } else {
                AgentService.ACTION_DENY_CAPTURE
            }
            putExtra(AgentService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AgentService.EXTRA_RESULT_DATA, data)
        }
        startService(intent)
    }

    // --------------------------------------------------------------- plumbing

    private fun persist() {
        config.hubUrl = urlField.text.toString()
        config.deviceName = nameField.text.toString()
        config.autoApprove = autoApproveBox.isChecked
    }

    private fun saveSettings() {
        persist()
        Toast.makeText(
            this,
            "Saved. The hub will be contacted at ${config.normalizedHubUrl()}",
            Toast.LENGTH_LONG,
        ).show()
        renderStatus()
    }

    private fun sendToService(action: String) {
        val intent = Intent(this, AgentService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFICATIONS)
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun weight() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val RC_NOTIFICATIONS = 100
        const val RC_CAPTURE = 101
        val COLOR_OK = 0xFF1B5E20.toInt()
        val COLOR_BAD = 0xFFB71C1C.toInt()
    }
}
