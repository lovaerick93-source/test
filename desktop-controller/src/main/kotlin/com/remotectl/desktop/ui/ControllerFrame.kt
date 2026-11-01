package com.remotectl.desktop.ui

import com.remotectl.desktop.HubClient
import com.remotectl.desktop.HubEvents
import com.remotectl.protocol.AppEntry
import com.remotectl.protocol.DeviceInfo
import com.remotectl.protocol.DeviceView
import com.remotectl.protocol.KeyCommand
import com.remotectl.protocol.LaunchAppCommand
import com.remotectl.protocol.Msg
import com.remotectl.protocol.NavKey
import com.remotectl.protocol.SwipeCommand
import com.remotectl.protocol.TapCommand
import com.remotectl.protocol.VideoFrame
import com.remotectl.protocol.VideoFrameHeader
import com.remotectl.protocol.WireJson
import com.remotectl.protocol.payloadFrom
import com.remotectl.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * The desktop controller — simplified for everyday use.
 *
 * 1. Enter your hub address (default ws://127.0.0.1:8000/ws) and click Connect.
 * 2. Pick a phone from the list, enter its 6-digit pairing code, click Pair.
 * 3. Accept on the phone — then click the screen to tap/drag to swipe.
 */
class ControllerFrame : JFrame("Remote Control — Desktop"), HubEvents {

    private val hub = HubClient(this)

    private val hubUrlField = JTextField("ws://127.0.0.1:8000/ws", 26)
    private val connectButton = JButton("Connect")
    private val statusLabel = JLabel("Not connected")

    private val deviceModel = DefaultListModel<DeviceView>()
    private val deviceList = JList(deviceModel)
    private val pairCodeField = JTextField(10)
    private val pairButton = JButton("Pair")

    private val infoArea = JTextArea(6, 30)
    private val appsModel = DefaultListModel<String>()
    private val appsList = JList(appsModel)
    private val logArea = JTextArea(6, 80)

    private val mirrorButton = JButton("Start mirroring")
    private val mirrorState = JLabel("Mirror off")
    private val mirrorPanel = MirrorPanel(
        onTap = { x, y -> sendTap(x, y) },
        onSwipe = { x1, y1, x2, y2, durationMs -> sendSwipe(x1, y1, x2, y2, durationMs) },
    )

    private var mirrorRunning = false
    private var framesSinceReport = 0
    private var lastFpsReportAt = System.currentTimeMillis()
    private var parsedApps: List<AppEntry> = emptyList()

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        layout = BorderLayout(10, 10)
        rootPane.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        add(buildTopBar(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildLog(), BorderLayout.SOUTH)

        wireActions()
        minimumSize = Dimension(1100, 700)
        pack()
        setLocationRelativeTo(null)

        // Auto-connect to the local hub on startup — no manual step needed.
        java.awt.EventQueue.invokeLater {
            javax.swing.Timer(800, null).apply {
                addActionListener { _ ->
                    stop()
                    hub.connect("ws://127.0.0.1:8000/ws")
                    hubUrlField.text = "ws://127.0.0.1:8000/ws"
                    connectButton.isEnabled = false
                    statusLabel.text = "Connecting to local hub…"
                }
                setRepeats(false)
                start()
            }
        }
    }

    // -------------------------------------------------------------- top bar

    private fun buildTopBar(): JPanel = JPanel(BorderLayout(8, 6)).apply {
        val row = JPanel(BorderLayout(6, 0))
        row.add(JLabel("Hub:"), BorderLayout.WEST)
        row.add(hubUrlField, BorderLayout.CENTER)
        row.add(connectButton, BorderLayout.EAST)
        add(row, BorderLayout.NORTH)
        add(statusLabel, BorderLayout.SOUTH)
    }

    // -------------------------------------------------------------- center

    private fun buildCenter(): javax.swing.JSplitPane {
        deviceList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        val left = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createTitledBorder("Phones")
            preferredSize = Dimension(320, 0)
            add(JScrollPane(deviceList), BorderLayout.CENTER)

            val pairing = JPanel(BorderLayout(8, 6)).apply {
                border = BorderFactory.createTitledBorder("Pair with the code shown on the phone")
                add(pairCodeField, BorderLayout.CENTER)
                add(pairButton, BorderLayout.EAST)
            }
            add(pairing, BorderLayout.SOUTH)
        }

        val center = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createTitledBorder("Screen — click to tap, drag to swipe")
            preferredSize = Dimension(520, 0)
            val mirrorBar = JPanel(BorderLayout(8, 0)).apply {
                add(mirrorButton, BorderLayout.WEST)
                add(mirrorState, BorderLayout.CENTER)
            }
            add(mirrorBar, BorderLayout.NORTH)
            add(mirrorPanel, BorderLayout.CENTER)
        }

        val right = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createTitledBorder("Phone")
            preferredSize = Dimension(300, 0)
            infoArea.isEditable = false
            infoArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            add(JScrollPane(infoArea), BorderLayout.NORTH)
            add(JScrollPane(appsList), BorderLayout.CENTER)

            val nav = JPanel(GridLayout(1, 3, 8, 0)).apply {
                border = BorderFactory.createTitledBorder("Navigation")
                add(JButton("Back").apply { addActionListener { sendKey(NavKey.BACK) } })
                add(JButton("Home").apply { addActionListener { sendKey(NavKey.HOME) } })
                add(JButton("Recents").apply { addActionListener { sendKey(NavKey.RECENTS) } })
            }
            add(nav, BorderLayout.SOUTH)
        }

        val centerRight = javax.swing.JSplitPane(
            javax.swing.JSplitPane.HORIZONTAL_SPLIT, center, right
        ).apply { resizeWeight = 0.6 }

        return javax.swing.JSplitPane(
            javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, centerRight
        ).apply { resizeWeight = 0.25 }
    }

    // -------------------------------------------------------------- log

    private fun buildLog(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder("Activity")
        logArea.isEditable = false
        logArea.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        add(JScrollPane(logArea), BorderLayout.CENTER)
    }

    // -------------------------------------------------------------- actions

    private fun wireActions() {
        connectButton.addActionListener {
            if (hub.isOpen) {
                hub.disconnect()
            } else {
                hub.connect(hubUrlField.text.trim())
            }
        }

        deviceList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            onDeviceSelected(deviceList.selectedValue)
        }

        pairButton.addActionListener { requestPairing() }

        mirrorButton.addActionListener { toggleMirror() }

        appsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val index = appsList.selectedIndex
                val entry = parsedApps.getOrNull(index) ?: return
                hub.send(Msg.CTL_LAUNCH_APP, payloadFrom(LaunchAppCommand(entry.packageName)))
                log("opening ${entry.label}…")
            }
        })
    }

    // -------------------------------------------------------------- calls

    private fun onDeviceSelected(view: DeviceView?) {
        if (view == null) {
            infoArea.text = ""
            pairButton.isEnabled = false
            return
        }
        pairButton.isEnabled = true
        infoArea.text = describe(view)
    }

    private fun describe(view: DeviceView): String {
        val info: DeviceInfo? = view.info
        return buildString {
            append("id       : ").append(view.deviceId).append('\n')
            append("name     : ").append(info?.name ?: "?").append('\n')
            append("model    : ").append(info?.manufacturer ?: "?").append(' ')
            append(info?.model ?: "?").append('\n')
            append("android  : ").append(info?.androidVersion ?: "?").append('\n')
            append("screen   : ").append(info?.screenWidth ?: 0).append('x')
            append(info?.screenHeight ?: 0).append('\n')
            append("battery  : ").append(info?.batteryPercent ?: -1).append("%\n")
            append("paired   : ").append(view.paired).append('\n')
        }
    }

    private fun requestPairing() {
        val view = deviceList.selectedValue
        if (view == null) {
            log("pick a phone first")
            return
        }
        val code = pairCodeField.text.trim()
        if (code.length != 6 || code.any { !it.isDigit() }) {
            log("enter the 6-digit code shown on the phone")
            return
        }
        if (hub.requestConnection(view.deviceId, code)) {
            log("waiting for ${view.displayName} to accept on the phone…")
        } else {
            log("cannot pair: hub link is down")
        }
    }

    private fun toggleMirror() {
        if (deviceList.selectedValue == null) {
            log("pick a phone first")
            return
        }
        if (mirrorRunning) {
            hub.send(Msg.CTL_STOP_MIRROR)
            log("stopping mirroring…")
        } else {
            hub.send(Msg.CTL_START_MIRROR)
            log("asking phone to share its screen (approve on the phone)…")
            mirrorState.text = "Waiting for phone to allow capture…"
        }
    }

    private fun sendTap(x: Float, y: Float) {
        if (!mirrorRunning) return
        if (!hub.send(Msg.CTL_TAP, payloadFrom(TapCommand(x, y)))) log("not paired: tap dropped")
    }

    private fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        if (!mirrorRunning) return
        val cmd = SwipeCommand(x1, y1, x2, y2, durationMs.toLong())
        if (!hub.send(Msg.CTL_SWIPE, payloadFrom(cmd))) log("not paired: swipe dropped")
    }

    private fun sendKey(key: String) {
        if (!hub.send(Msg.CTL_KEY, payloadFrom(KeyCommand(key)))) {
            log("cannot send $key: not paired")
        }
    }

    // -------------------------------------------------------------- mirror

    override fun onFrame(frame: ByteArray) {
        val header = VideoFrameHeader.parse(frame) ?: return
        if (header.codec != VideoFrame.CODEC_JPEG) {
            log("unsupported frame codec ${header.codec}; only JPEG")
            return
        }
        val picture = runCatching {
            ImageIO.read(ByteArrayInputStream(VideoFrame.payloadOf(frame)))
        }.getOrNull()
        if (picture == null) return

        SwingUtilities.invokeLater {
            mirrorPanel.setFrame(picture, header.width, header.height)
            framesSinceReport++
            val now = System.currentTimeMillis()
            val elapsed = now - lastFpsReportAt
            if (elapsed >= 2000L) {
                val fps = framesSinceReport * 1000 / elapsed
                mirrorState.text = "Live — ${header.width}x${header.height}, $fps fps"
                framesSinceReport = 0
                lastFpsReportAt = now
            }
        }
    }

    // -------------------------------------------------------------- HubEvents

    override fun onConnectionStateChanged(connected: Boolean) {
        SwingUtilities.invokeLater {
            connectButton.text = if (connected) "Disconnect" else "Connect"
            connectButton.isEnabled = !connected
        }
    }

    override fun onStatus(text: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = text
            log(text)
        }
    }

    override fun onWelcome(connId: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = "Connected to hub ($connId)"
            connectButton.text = "Disconnect"
        }
    }

    override fun onDevices(devices: List<DeviceView>) {
        SwingUtilities.invokeLater {
            val previous = deviceList.selectedValue?.deviceId
            deviceModel.clear()
            devices.forEach { deviceModel.addElement(it) }
            val restored = (0 until deviceModel.size).firstOrNull { deviceModel[it].deviceId == previous }
            if (restored != null) deviceList.selectedIndex = restored
            statusLabel.text = "${devices.size} phone(s) online"
        }
    }

    override fun onPairingPending(deviceId: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = "Waiting for $deviceId to accept on the phone…"
            log("pair request sent; accept it on the phone")
        }
    }

    override fun onPaired(deviceId: String, info: DeviceInfo?) {
        SwingUtilities.invokeLater {
            statusLabel.text = "Paired with ${info?.name ?: deviceId}"
            log("paired with $deviceId")
        }
    }

    override fun onPeerGone(reason: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = "Session ended: $reason"
            log("session ended: $reason")
            onMirrorStopped("session ended")
        }
    }

    override fun onFailed(reason: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = "Hub refused: $reason"
            log("hub refused: $reason")
        }
    }

    override fun onRelay(type: String, payload: JsonObject) {
        SwingUtilities.invokeLater {
            when (type) {
                Msg.EVT_APPS -> {
                    val apps = (payload["apps"] as? JsonArray)
                        ?.mapNotNull {
                            runCatching {
                                WireJson.decodeFromJsonElement(AppEntry.serializer(), it)
                            }.getOrNull()
                        }
                        .orEmpty()
                    showApps(apps)
                }
                Msg.EVT_ACK -> {
                    val cmd = payload.string("command") ?: "?"
                    val ok = payload["ok"]
                    log("phone $cmd: $ok")
                }
                Msg.EVT_SESSION -> log("session: $payload")
                Msg.EVT_MIRROR_STATE -> onMirrorState(payload)
                Msg.EVT_FILE_PROGRESS -> log("file: $payload")
                else -> log("$type $payload")
            }
        }
    }

    private fun onMirrorState(payload: JsonObject) {
        val running = payload["running"]?.toString() == "true"
        if (running) {
            mirrorRunning = true
            mirrorButton.text = "Stop mirroring"
            mirrorState.text = "Live — waiting for first frame…"
            log("mirror: phone started streaming")
            return
        }
        val error = payload.string("error")
        val reason = payload.string("reason")
        log("mirror: off${error?.let { " ($it)" } ?: reason?.let { " ($it)" } ?: ""}")
        when (error) {
            "capture_permission_required" ->
                mirrorState.text = "Approve screen capture on the phone"
            else -> onMirrorStopped(error ?: reason ?: "stopped")
        }
    }

    private fun onMirrorStopped(why: String) {
        mirrorRunning = false
        mirrorButton.text = "Start mirroring"
        mirrorState.text = "Mirror off ($why)"
        mirrorPanel.clear()
    }

    private fun showApps(apps: List<AppEntry>) {
        parsedApps = apps
        appsModel.clear()
        apps.forEach { appsModel.addElement("${it.label} — ${it.packageName}") }
        log("${apps.size} apps received; double-click one to open it")
    }

    private fun log(line: String) {
        SwingUtilities.invokeLater {
            logArea.append(line + "\n")
            logArea.caretPosition = logArea.document.length
        }
    }

    private class DeviceRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val view = value as? DeviceView
            val text = when {
                view == null -> ""
                view.paired -> "${view.displayName}  (in session)"
                else -> "${view.displayName}  (${view.deviceId})"
            }
            val comp = super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            comp.background = if (isSelected) Color(0x3a3a55) else Color(0x1a1a1a)
            comp.foreground = Color(0xe8eaee)
            return comp
        }
    }

    companion object {
        private const val FPS_REPORT_MS = 2_000L
    }
}
