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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.remotectl.agent.input.RemoteInputService

/**
 * The phone owner's console — kept intentionally simple.
 *
 * 1. Type your computer's address (e.g. 192.168.1.5 or your tunnel URL).
 * 2. Tap "Start agent" — a 6-digit pairing code appears.
 * 3. In the desktop app, connect and enter that code. Accept on this phone.
 *
 * While running, a permanent notification shows the code and lets you stop.
 */
class MainActivity : Activity() {

    private lateinit var config: AgentConfig

    private lateinit var statusView: TextView
    private lateinit var codeView: TextView
    private lateinit var urlField: EditText
    private lateinit var accessibilityView: TextView
    private lateinit var mirrorView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

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

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == AgentService.ACTION_REQUEST_CAPTURE) {
            Toast.makeText(this, R.string.capture_prompt_toast, Toast.LENGTH_LONG).show()
            askForCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        Consent.dialogListener = onConsentNeeded
        AgentService.capturePrompt = { askForCapture() }

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

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(Typeface.DEFAULT_BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Control your phone from your desktop"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(sectionLabel("Computer address (hub)"))
        urlField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "192.168.1.5:8000"
            setText(config.hubUrl.takeIf { it.isNotEmpty() } ?: "http://192.168.1.100:8000")
        }
        root.addView(urlField)

        // Start / Stop row
        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply {
            text = "START AGENT"
            textSize = 16f
            setOnClickListener {
                config.hubUrl = urlField.text.toString()
                sendToService(AgentService.ACTION_START)
            }
        }
        stopButton = Button(this).apply {
            text = "STOP AGENT"
            textSize = 16f
            setOnClickListener { sendToService(AgentService.ACTION_STOP) }
        }
        buttonRow.addView(startButton, weight())
        buttonRow.addView(stopButton, weight())
        root.addView(buttonRow)

        // Pairing code (large, prominent)
        codeView = TextView(this).apply {
            textSize = 40f
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE)
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(codeView)

        statusView = TextView(this).apply { textSize = 13f }
        root.addView(statusView)

        // Remote input note
        root.addView(sectionLabel("Remote input (tap, swipe, type)"))
        accessibilityView = TextView(this).apply { textSize = 13f }
        root.addView(accessibilityView)
        root.addView(Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        // Screen mirroring note
        root.addView(sectionLabel("Screen mirroring"))
        mirrorView = TextView(this).apply { textSize = 13f }
        root.addView(mirrorView)
        root.addView(Button(this).apply {
            text = "Allow screen capture"
            setOnClickListener { askForCapture() }
        })

        // Simple instructions
        root.addView(TextView(this).apply {
            text = "How to use:\n" +
                "1. Start the hub on your computer (start_server.py).\n" +
                "2. Enter your computer's address above and tap START AGENT.\n" +
                "3. Note the 6-digit pairing code shown below.\n" +
                "4. Open the desktop app, connect, pick this phone, enter the code.\n" +
                "5. Tap Accept on this phone when asked — then you're in control."
            textSize = 12f
            setPadding(0, dp(12), 0, dp(4))
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun renderStatus() {
        val running = AgentStatus.running
        startButton.isEnabled = !running
        stopButton.isEnabled = running

        // Show pairing code prominently
        val code = AgentStatus.pairingCode
        codeView.text = if (running && code.isNotEmpty()) code else "—"

        val state = when {
            !running -> "Stopped"
            AgentStatus.paired -> "Paired — desktop is in control"
            AgentStatus.connected -> "Online, waiting for desktop"
            else -> "Connecting…"
        }

        statusView.text = buildString {
            append("Status: ").append(state).append('\n')
            append("Hub: ").append(AgentStatus.hub.ifEmpty { config.normalizedHubUrl() }).append('\n')
            append("Last: ").append(AgentStatus.lastMessage)
        }

        val ready = RemoteInputService.isReady
        accessibilityView.text = if (ready) {
            "Remote input: ON (taps, swipes, typing work)"
        } else {
            "Remote input: OFF (enable accessibility in settings above)"
        }
        accessibilityView.setTextColor(if (ready) COLOR_OK else COLOR_BAD)

        mirrorView.text = when {
            AgentStatus.mirroring -> "Screen mirroring: ON (desktop sees your screen)"
            AgentStatus.captureGranted -> "Screen mirroring: Allowed, waiting for desktop"
            else -> "Screen mirroring: OFF (desktop can ask to start it)"
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
