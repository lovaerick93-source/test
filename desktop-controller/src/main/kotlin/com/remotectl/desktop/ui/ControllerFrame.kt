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
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.WindowConstants

/**
 * The desktop side.
 *
 * Connects to the hub, lists the phones the hub knows about, and pairs with one
 * using the code shown on that phone. All control traffic is relayed by the hub
 * and interpreted here.
 */
class ControllerFrame : JFrame("Remote Control — desktop"), HubEvents {

    private val hub = HubClient(this)

    private val hubUrlField = JTextField(DEFAULT_HUB, 22)
    private val connectButton = JButton("Connect")
    private val statusLabel = JLabel("Not connected")

    private val deviceModel = DefaultListModel<DeviceView>()
    private val deviceList = JList(deviceModel)
    private val pairButton = JButton("Connect")

    private val infoArea = JTextArea(8, 34)
    private val appsModel = DefaultListModel<String>()
    private val appsList = JList(appsModel)
    private val logArea = JTextArea(9, 80)

    private val mirrorButton = JButton("Start mirroring")
    private val mirrorState = JLabel("Mirror off")
    private val mirrorPanel = MirrorPanel(
        onTap = { x, y -> sendTap(x, y) },
        onSwipe = { x1, y1, x2, y2, durationMs -> sendSwipe(x1, y1, x2, y2, durationMs) },
    )

    private val clipboardField = JTextField(24)
    private val getClipboardButton = JButton("Get from phone")
    private val setClipboardButton = JButton("Set on phone")

    private var mirrorRunning = false
    private var framesSinceReport = 0
    private var lastFpsReportAt = System.currentTimeMillis()

    private var parsedApps: List<AppEntry> = emptyList()

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        layout = BorderLayout(8, 8)
        rootPane.border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        contentPane.background = java.awt.Color(0xF5, 0xF7, 0xFB)

        add(buildTopBar(), BorderLayout.NORTH)
        add(buildMiddle(), BorderLayout.CENTER)
        add(buildLog(), BorderLayout.SOUTH)

        wireActions()
        minimumSize = Dimension(1180, 760)
        pack()
        setLocationRelativeTo(null)
    }

    // -------------------------------------------------------------- assembling

    private fun buildTopBar(): JPanel = JPanel(BorderLayout(8, 6)).apply {
        background = java.awt.Color(0x1E, 0x3A, 0x5F)
        border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        val title = JLabel("Remote Control").apply {
            font = Font("Segoe UI", Font.BOLD, 20)
            foreground = java.awt.Color.WHITE
        }
        val subtitle = JLabel("Desktop controller").apply {
            font = Font("Segoe UI", Font.PLAIN, 12)
            foreground = java.awt.Color(0xB0, 0xC4, 0xDE)
        }
        val titleBox = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            add(subtitle, BorderLayout.SOUTH)
        }
        add(titleBox, BorderLayout.WEST)

        val row = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(JLabel("Hub: ").apply {
                foreground = java.awt.Color.WHITE
                font = Font("Segoe UI", Font.BOLD, 12)
            }, BorderLayout.WEST)
            add(hubUrlField.apply {
                font = Font("Segoe UI", Font.PLAIN, 12)
            }, BorderLayout.CENTER)
            add(connectButton.apply {
                font = Font("Segoe UI", Font.BOLD, 12)
                background = java.awt.Color(0x2E, 0x7D, 0x32)
                foreground = java.awt.Color.WHITE
                isOpaque = true
            }, BorderLayout.EAST)
        }
        add(row, BorderLayout.CENTER)

        statusLabel.apply {
            foreground = java.awt.Color(0xB0, 0xC4, 0xDE)
            font = Font("Segoe UI", Font.PLAIN, 11)
        }
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun buildMiddle(): JSplitPane {
        deviceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        deviceList.cellRenderer = DeviceRenderer()
        deviceList.font = Font("Segoe UI", Font.PLAIN, 13)

        val connectBtn = pairButton.apply {
            text = "Connect"
            font = Font("Segoe UI", Font.BOLD, 12)
            background = java.awt.Color(0x19, 0x76, 0xD2)
            foreground = java.awt.Color.WHITE
            isOpaque = true
        }
        val pairing = JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createTitledBorder("Selected phone")
            add(connectBtn, BorderLayout.CENTER)
        }

        val keys = JPanel(GridLayout(1, 3, 6, 0)).apply {
            border = BorderFactory.createTitledBorder("Navigation")
            add(JButton("Back").apply { addActionListener { sendKey(NavKey.BACK) } })
            add(JButton("Home").apply { addActionListener { sendKey(NavKey.HOME) } })
            add(JButton("Recents").apply { addActionListener { sendKey(NavKey.RECENTS) } })
        }

        val left = JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createTitledBorder("Phones  (double-click to connect)")
            preferredSize = Dimension(300, 0)
            add(JScrollPane(deviceList), BorderLayout.CENTER)
            add(JPanel(GridLayout(2, 1, 0, 6)).apply {
                add(pairing)
                add(keys)
            }, BorderLayout.SOUTH)
        }

        infoArea.isEditable = false
        infoArea.font = Font("Consolas", Font.PLAIN, 12)
        infoArea.background = java.awt.Color(0xF8, 0xFA, 0xFC)

        val appsButton = JButton("List installed apps").apply {
            addActionListener { requestApps() }
            font = Font("Segoe UI", Font.PLAIN, 12)
        }
        clipboardField.font = Font("Segoe UI", Font.PLAIN, 12)
        clipboardField.isEnabled = false
        getClipboardButton.apply {
            font = Font("Segoe UI", Font.PLAIN, 11)
            isEnabled = false
        }
        setClipboardButton.apply {
            font = Font("Segoe UI", Font.PLAIN, 11)
            isEnabled = false
        }
        val clipboardBar = JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createTitledBorder("Clipboard")
            add(clipboardField, BorderLayout.CENTER)
            add(JPanel(GridLayout(1, 2, 4, 0)).apply {
                add(getClipboardButton)
                add(setClipboardButton)
            }, BorderLayout.SOUTH)
        }
        val right = JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createTitledBorder("Device details")
            preferredSize = Dimension(330, 0)
            add(JScrollPane(infoArea), BorderLayout.NORTH)
            add(JScrollPane(appsList), BorderLayout.CENTER)
            add(appsButton, BorderLayout.SOUTH)
            add(clipboardBar, BorderLayout.PAGE_END)
        }

        mirrorButton.apply {
            font = Font("Segoe UI", Font.BOLD, 12)
            background = java.awt.Color(0x7B, 0x1F, 0xA2)
            foreground = java.awt.Color.WHITE
            isOpaque = true
        }
        val mirrorBar = JPanel(BorderLayout(8, 0)).apply {
            add(mirrorButton, BorderLayout.WEST)
            add(mirrorState, BorderLayout.CENTER)
        }
        mirrorState.font = Font("Segoe UI", Font.PLAIN, 12)
        val centre = JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createTitledBorder("Screen  (click to tap, drag to swipe)")
            add(mirrorBar, BorderLayout.NORTH)
            add(mirrorPanel, BorderLayout.CENTER)
        }

        val centreAndRight = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centre, right).apply {
            resizeWeight = 0.68
            dividerSize = 6
        }
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, centreAndRight).apply {
            resizeWeight = 0.22
            dividerSize = 6
        }
    }

    private fun buildLog(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder("Activity log")
        logArea.isEditable = false
        logArea.font = Font("Consolas", Font.PLAIN, 11)
        logArea.background = java.awt.Color(0x1F, 0x29, 0x37)
        logArea.foreground = java.awt.Color(0xE5, 0xE7, 0xEB)
        add(JScrollPane(logArea), BorderLayout.CENTER)
    }

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
        deviceList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) requestPairing()
            }
        })

        pairButton.addActionListener { requestPairing() }

        mirrorButton.addActionListener { toggleMirror() }

        appsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val index = appsList.selectedIndex
                val entry = parsedApps.getOrNull(index) ?: return
                hub.send(Msg.CTL_LAUNCH_APP, payloadFrom(LaunchAppCommand(entry.packageName)))
                log("asked the phone to open ${entry.label}")
            }
        })

        getClipboardButton.addActionListener { requestClipboard() }
        setClipboardButton.addActionListener { sendClipboardToPhone() }
    }

    // ------------------------------------------------------------------- calls

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
            append("id           : ").append(view.deviceId).append('\n')
            append("name         : ").append(info?.name ?: "?").append('\n')
            append("model        : ").append(info?.manufacturer ?: "?").append(' ')
            append(info?.model ?: "?").append('\n')
            append("android      : ").append(info?.androidVersion ?: "?")
            append(" (sdk ").append(info?.sdkInt ?: 0).append(")\n")
            append("screen       : ").append(info?.screenWidth ?: 0).append('x')
            append(info?.screenHeight ?: 0).append('\n')
            append("battery      : ").append(info?.batteryPercent ?: -1).append("%\n")
            append("apps         : ").append(info?.installedAppCount ?: 0).append('\n')
            append("can capture  : ").append(info?.canCapture ?: false).append('\n')
            append("can inject   : ").append(info?.canInjectInput ?: false).append('\n')
            append("paired       : ").append(view.paired).append('\n')
        }
    }

    private fun requestPairing() {
        val view = deviceList.selectedValue
        if (view == null) {
            log("pick a phone first")
            return
        }
        if (hub.requestConnection(view.deviceId, "")) {
            log("connecting to ${view.displayName}…")
        } else {
            log("cannot connect: the hub link is down")
        }
    }

    private fun requestApps() {
        if (deviceList.selectedValue == null) {
            log("pick a phone first")
            return
        }
        hub.send(Msg.CTL_LIST_APPS)
        log("asking for the installed app list…")
    }

    private fun sendKey(key: String) {
        if (!hub.send(Msg.CTL_KEY, payloadFrom(KeyCommand(key)))) {
            log("cannot send $key: not paired")
        }
    }

    /** Ask the phone to send its current clipboard text. */
    private fun requestClipboard() {
        if (!hub.send(Msg.CTL_CLIPBOARD, buildJsonObject { })) {
            log("cannot get clipboard: not paired")
        }
    }

    /** Push the text field's content to the phone's clipboard. */
    private fun sendClipboardToPhone() {
        val text = clipboardField.text.trim()
        if (text.isEmpty()) {
            log("nothing to send: clipboard field is empty")
            return
        }
        if (!hub.send(Msg.CTL_CLIPBOARD, buildJsonObject { put("text", text) })) {
            log("cannot set clipboard: not paired")
        }
    }

    // ------------------------------------------------------------------ mirror

    private fun toggleMirror() {
        if (deviceList.selectedValue == null) {
            log("pick a phone first")
            return
        }
        if (mirrorRunning) {
            hub.send(Msg.CTL_STOP_MIRROR)
            log("asking the phone to stop mirroring…")
        } else {
            hub.send(Msg.CTL_START_MIRROR)
            log("asking the phone for its screen. It has to allow capture on the phone.")
            mirrorState.text = "Waiting for the phone to allow screen capture…"
        }
    }

    /** A click on the picture becomes a real tap at the same point on the phone. */
    private fun sendTap(x: Float, y: Float) {
        if (!mirrorRunning) return
        if (!hub.send(Msg.CTL_TAP, payloadFrom(TapCommand(x, y)))) log("not paired: tap dropped")
    }

    private fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        if (!mirrorRunning) return
        val cmd = SwipeCommand(x1, y1, x2, y2, durationMs.toLong())
        if (!hub.send(Msg.CTL_SWIPE, payloadFrom(cmd))) log("not paired: swipe dropped")
    }

    /**
     * One frame off the wire. The 12-byte header says how big the picture is in
     * device pixels, which is the space taps have to be expressed in.
     */
    override fun onFrame(frame: ByteArray) {
        val header = VideoFrameHeader.parse(frame) ?: return
        if (header.codec != VideoFrame.CODEC_JPEG) {
            log("unsupported frame codec ${header.codec}; only JPEG is implemented")
            return
        }
        val picture = runCatching {
            ImageIO.read(ByteArrayInputStream(VideoFrame.payloadOf(frame)))
        }.getOrNull()
        if (picture == null) return

        mirrorPanel.setFrame(picture, header.width, header.height)
        framesSinceReport++

        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsReportAt
        if (elapsed >= FPS_REPORT_MS) {
            val fps = framesSinceReport * 1000 / elapsed
            mirrorState.text = "Live — ${header.width}x${header.height}, $fps fps"
            framesSinceReport = 0
            lastFpsReportAt = now
        }
    }

    // ---------------------------------------------------------------- HubEvents

    override fun onConnectionStateChanged(connected: Boolean) {
        connectButton.text = if (connected) "Disconnect" else "Connect"
    }

    override fun onStatus(text: String) {
        statusLabel.text = text
        log(text)
    }

    override fun onWelcome(connId: String) {
        statusLabel.text = "Connected to the hub ($connId)"
        connectButton.text = "Disconnect"
    }

    override fun onDevices(devices: List<DeviceView>) {
        val previous = deviceList.selectedValue?.deviceId
        deviceModel.clear()
        devices.forEach { deviceModel.addElement(it) }
        val restored = (0 until deviceModel.size).firstOrNull { deviceModel[it].deviceId == previous }
        if (restored != null) deviceList.selectedIndex = restored
        val online = devices.count { it.online }
        statusLabel.text = "${devices.size} phone(s), $online online"
    }

    override fun onPairingPending(deviceId: String) {
        statusLabel.text = "Waiting for $deviceId to accept on the phone…"
        log("request sent; accept it on the phone")
    }

    override fun onPaired(deviceId: String, info: DeviceInfo?) {
        statusLabel.text = "Paired with ${info?.name ?: deviceId}"
        log("paired with $deviceId")
        clipboardField.isEnabled = true
        getClipboardButton.isEnabled = true
        setClipboardButton.isEnabled = true
    }

    override fun onPeerGone(reason: String) {
        statusLabel.text = "Session ended: $reason"
        log("session ended: $reason")
        onMirrorStopped("the session ended")
        clipboardField.isEnabled = false
        getClipboardButton.isEnabled = false
        setClipboardButton.isEnabled = false
        clipboardField.text = ""
    }

    override fun onFailed(reason: String) {
        statusLabel.text = "Hub refused: $reason"
        log("hub refused: $reason")
    }

    override fun onRelay(type: String, payload: JsonObject) {
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

            Msg.EVT_ACK ->
                log("phone: ${payload.string("command")} ok=${payload["ok"]} ${payload.string("reason") ?: ""}")

            Msg.EVT_SESSION -> log("session: $payload")
            Msg.EVT_MIRROR_STATE -> onMirrorState(payload)
            Msg.EVT_FILE_PROGRESS -> log("file: $payload")
            Msg.EVT_CLIPBOARD -> {
                val text = payload.string("text").orEmpty()
                if (text.isNotEmpty()) {
                    clipboardField.text = text
                    log("clipboard from phone: $text")
                }
            }
            else -> log("$type $payload")
        }
    }

    /**
     * The phone's answer to "may I see your screen". `running:false` with an
     * `error` means it is not mirroring yet — most often because the owner has
     * not approved Android's capture dialog, which only they can do.
     */
    private fun onMirrorState(payload: JsonObject) {
        val running = payload["running"]?.toString() == "true"
        if (running) {
            mirrorRunning = true
            mirrorButton.text = "Stop mirroring"
            mirrorState.text = "Live — waiting for the first frame…"
            log("mirror: the phone started streaming its screen")
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
        apps.forEach { appsModel.addElement("${it.label}  —  ${it.packageName}") }
        log("phone sent ${apps.size} installed apps; double-click one to open it")
    }

    // ------------------------------------------------------------------ helpers

    private fun log(line: String) {
        logArea.append(line + "\n")
        logArea.caretPosition = logArea.document.length
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
            val dot = if (view?.online == true) "●" else "○"
            val text = when {
                view == null -> ""
                view.paired -> "$dot ${view.displayName}  (in session)"
                else -> "$dot ${view.displayName}  (${view.deviceId})"
            }
            val c = super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            if (!isSelected && view?.online == true) c.foreground = java.awt.Color(0, 150, 0)
            if (!isSelected && view?.online == false) c.foreground = java.awt.Color.GRAY
            return c
        }
    }

    companion object {
        private const val DEFAULT_HUB = "ws://127.0.0.1:8000/ws"
        /** How often the fps readout is refreshed; per-frame updates would flood the EDT. */
        private const val FPS_REPORT_MS = 2_000L
    }
}
