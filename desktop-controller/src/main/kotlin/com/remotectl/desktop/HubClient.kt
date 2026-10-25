package com.remotectl.desktop

import com.remotectl.protocol.ConnectRequestCommand
import com.remotectl.protocol.DeviceInfo
import com.remotectl.protocol.DeviceView
import com.remotectl.protocol.Envelope
import com.remotectl.protocol.Msg
import com.remotectl.protocol.PROTOCOL_VERSION
import com.remotectl.protocol.WireJson
import com.remotectl.protocol.decodePayload
import com.remotectl.protocol.obj
import com.remotectl.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Callbacks the UI implements. Every one is invoked on the Swing EDT. */
interface HubEvents {
    fun onStatus(text: String)
    fun onWelcome(connId: String)
    fun onDevices(devices: List<DeviceView>)
    fun onPairingPending(deviceId: String)
    fun onPaired(deviceId: String, info: DeviceInfo?)
    fun onPeerGone(reason: String)
    fun onFailed(reason: String)
    fun onRelay(type: String, payload: JsonObject)

    /**
     * One raw screen frame straight off the socket: the 12-byte
     * [com.remotectl.protocol.VideoFrame] header followed by the encoded image.
     * Passed through as bytes because the hub relays it verbatim.
     */
    fun onFrame(frame: ByteArray)
}

/**
 * WebSocket link to the hub.
 *
 * OkHttp delivers frames on its own threads, so every callback is hopped onto
 * the EDT before it reaches the UI. Sending is thread-safe and never blocks.
 */
class HubClient(private val events: HubEvents) {

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    val isOpen: Boolean get() = socket != null

    fun connect(url: String) {
        disconnect()
        events.onStatus("Connecting to $url ...")
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    WireJson.encodeToString(
                        Envelope.serializer(),
                        Envelope(
                            type = Msg.HELLO,
                            payload = buildJsonObject {
                                put("role", "controller")
                                put("protocol_version", PROTOCOL_VERSION)
                            },
                        ),
                    ),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = bytes.toByteArray()
                // Decoding and painting must happen on the EDT like everything
                // else the UI touches; the bytes themselves are copied out of
                // okio's buffer first so they stay valid across the hop.
                post { events.onFrame(frame) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                post { events.onStatus("Disconnected from hub ($code)") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                post { events.onStatus("Connection failed: ${t.message}") }
            }
        })
    }

    fun disconnect() {
        socket?.close(1000, "client closing")
        socket = null
    }

    /** @return false when the socket is down, so the UI can say so instead of silently dropping input. */
    fun send(type: String, payload: JsonObject = JsonObject(emptyMap())): Boolean {
        val ws = socket ?: return false
        val frame = WireJson.encodeToString(Envelope.serializer(), Envelope(type, payload))
        return ws.send(frame)
    }

    fun requestConnection(deviceId: String, pairingCode: String): Boolean =
        send(Msg.CONNECT_REQUEST, WireJson.encodeToJsonElement(ConnectRequestCommand.serializer(), ConnectRequestCommand(deviceId, pairingCode)) as JsonObject)

    // ------------------------------------------------------------------ inbound

    private fun handle(text: String) {
        val envelope = runCatching { WireJson.decodeFromString(Envelope.serializer(), text) }.getOrNull()
        if (envelope == null) {
            post { events.onStatus("Ignored unreadable frame from hub") }
            return
        }
        val payload = envelope.payload

        when (envelope.type) {
            Msg.WELCOME -> post { events.onWelcome(payload.string("conn_id") ?: "?") }

            Msg.DEVICE_LIST -> {
                val devices = (payload["devices"] as? JsonArray)
                    ?.mapNotNull { runCatching { WireJson.decodeFromJsonElement(DeviceView.serializer(), it) }.getOrNull() }
                    .orEmpty()
                post { events.onDevices(devices) }
            }

            Msg.CONNECT_PENDING -> post { events.onPairingPending(payload.string("device_id") ?: "?") }

            Msg.PEER_CONNECTED -> {
                val info = payload.obj("info")?.let {
                    runCatching { WireJson.decodeFromJsonElement(DeviceInfo.serializer(), it) }.getOrNull()
                }
                post { events.onPaired(payload.string("device_id") ?: "?", info) }
            }

            Msg.PEER_DISCONNECTED -> post { events.onPeerGone(payload.string("reason") ?: "unknown") }

            Msg.ERROR -> post { events.onFailed(payload.string("reason") ?: "unknown") }

            Msg.PONG -> Unit

            else -> post { events.onRelay(envelope.type, payload) }
        }
    }

    private fun post(action: () -> Unit) = SwingUtilities.invokeLater(action)
}
