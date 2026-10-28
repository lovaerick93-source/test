package com.remotectl.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire protocol shared by the phone agent and the desktop controller.
 *
 * Every frame on the socket is `{"type": "...", "payload": {...}}`.  Both ends
 * build payloads as [JsonObject]s so a command can be added on one side without
 * a schema change on the other; the small typed classes below exist only for
 * the messages that genuinely need both ends to agree on a shape.
 */
const val PROTOCOL_VERSION: Int = 1

/** Message type constants. Must stay in sync with `server/models.py`. */
object Msg {
    // client -> hub
    const val HELLO = "hello"
    const val PING = "ping"
    const val DEVICE_INFO = "device.info"
    const val CONNECT_REQUEST = "connect.request"
    const val CONNECT_RESPONSE = "connect.response"
    const val DISCONNECT = "disconnect"

    // hub -> client
    const val WELCOME = "welcome"
    const val PONG = "pong"
    const val ERROR = "error"
    const val DEVICE_LIST = "device.list"
    const val PEER_CONNECTED = "peer.connected"
    const val PEER_DISCONNECTED = "peer.disconnected"
    const val CONNECT_INCOMING = "connect.incoming"
    const val CONNECT_PENDING = "connect.pending"

    // controller -> agent, relayed verbatim by the hub
    const val CTL_TAP = "ctl.tap"
    const val CTL_SWIPE = "ctl.swipe"
    const val CTL_TEXT = "ctl.text"
    const val CTL_KEY = "ctl.key"
    const val CTL_LIST_APPS = "ctl.list_apps"
    const val CTL_LAUNCH_APP = "ctl.launch_app"
    const val CTL_START_MIRROR = "ctl.start_mirror"
    const val CTL_STOP_MIRROR = "ctl.stop_mirror"
    const val CTL_FILE_PUSH = "ctl.file_push"
    const val CTL_FILE_PULL = "ctl.file_pull"
    const val CTL_CLIPBOARD = "ctl.clipboard"

    // agent -> controller, relayed verbatim by the hub
    const val EVT_APPS = "evt.apps"
    const val EVT_SESSION = "evt.session"
    const val EVT_ACK = "evt.ack"
    const val EVT_MIRROR_STATE = "evt.mirror_state"
    const val EVT_FILE_PROGRESS = "evt.file_progress"
    const val EVT_FILE_DATA = "evt.file_data"
    const val EVT_CLIPBOARD = "evt.clipboard"
}

@Serializable
data class Envelope(
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DeviceInfo(
    @SerialName("device_id") val deviceId: String,
    val name: String = "unknown",
    val model: String = "unknown",
    val manufacturer: String = "unknown",
    @SerialName("android_version") val androidVersion: String = "unknown",
    @SerialName("sdk_int") val sdkInt: Int = 0,
    @SerialName("screen_width") val screenWidth: Int = 0,
    @SerialName("screen_height") val screenHeight: Int = 0,
    @SerialName("battery_percent") val batteryPercent: Int = -1,
    @SerialName("can_capture") val canCapture: Boolean = false,
    @SerialName("can_inject_input") val canInjectInput: Boolean = false,
    @SerialName("installed_app_count") val installedAppCount: Int = 0,
)

/** One installed application, as shown in the desktop's app drawer. */
@Serializable
data class AppEntry(
    @SerialName("package") val packageName: String,
    val label: String,
    val system: Boolean = false,
)

/** A device as it appears in the desktop's picker, straight from `device.list`. */
@Serializable
data class DeviceView(
    @SerialName("device_id") val deviceId: String,
    val info: DeviceInfo? = null,
    val online: Boolean = true,
    val paired: Boolean = false,
) {
    /** Falls back to the id so the picker always has something to show. */
    val displayName: String get() = info?.name?.takeIf { it.isNotBlank() } ?: deviceId
}

@Serializable
data class TapCommand(val x: Float, val y: Float)

/**
 * A straight-line drag. [durationMs] drives how fast the gesture is replayed on
 * the phone, which is what makes a flick-vs-slow-scroll feel correct.
 */
@Serializable
data class SwipeCommand(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    @SerialName("duration_ms") val durationMs: Long = 250,
)

/** [value] is inserted as literal text into whatever field has focus. */
@Serializable
data class TextCommand(val value: String)

/** Named navigation keys rather than raw Android keycodes, so the desktop stays portable. */
@Serializable
data class KeyCommand(val key: String)

object NavKey {
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val ENTER = "enter"
    const val BACKSPACE = "backspace"
}

@Serializable
data class LaunchAppCommand(@SerialName("package") val packageName: String)

/** Sent by the hub to an agent that must ask its user before accepting a session. */
@Serializable
data class ConnectRequestCommand(
    @SerialName("device_id") val deviceId: String,
    @SerialName("pairing_code") val pairingCode: String,
)

val WireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Build a payload from literal key/value pairs. */
fun payloadOf(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())

/** Encode a typed command into a payload object. */
inline fun <reified T> payloadFrom(value: T): JsonObject =
    WireJson.encodeToJsonElement(value) as JsonObject

/** Decode a payload object into a typed command. */
inline fun <reified T> JsonObject.decodePayload(): T = WireJson.decodeFromJsonElement(this)

fun envelope(type: String, payload: JsonObject = JsonObject(emptyMap())): Envelope =
    Envelope(type = type, payload = payload)

// Payload readers. Each returns null rather than throwing, so a malformed frame
// from the network can never crash the agent or the controller.

fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.float(key: String): Float? =
    (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()

fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/**
 * Screen frames travel as **binary** WebSocket messages, not JSON.
 *
 * Control traffic is rare and benefits from being readable, but a screen frame
 * arrives 10-20 times a second and is the one thing on this socket where base64
 * would burn a third of the bandwidth for no benefit. So frames get a fixed
 * 12-byte header and the raw encoded image behind it.
 *
 * Layout, little-endian throughout:
 *
 * | offset | size | field                                             |
 * |--------|------|---------------------------------------------------|
 * | 0      | 1    | kind, see `KIND_*`                                |
 * | 1      | 1    | flags, see `FLAG_*`                               |
 * | 2      | 1    | codec, see `CODEC_*`                              |
 * | 3      | 1    | reserved, always 0                                |
 * | 4      | 4    | ptsMs, capture timestamp in ms                    |
 * | 8      | 2    | width of the *encoded* image, not the screen      |
 * | 10     | 2    | height of the encoded image                       |
 * | 12     | ..   | the encoded bytes                                 |
 *
 * The hub relays these verbatim; `server/hub.py` documents the same layout.
 */
object VideoFrame {
    const val KIND_VIDEO: Int = 1

    /** Flags a frame the decoder can start from without any earlier frame. */
    const val FLAG_KEYFRAME: Int = 1

    /** Flags decoder setup data (H.264 SPS/PPS) rather than a picture. */
    const val FLAG_CODEC_CONFIG: Int = 2

    /** A complete still image: every frame decodes on its own. */
    const val CODEC_JPEG: Int = 0

    /** Annex-B H.264 access unit. Reserved for the low-latency encoder. */
    const val CODEC_H264: Int = 1

    const val HEADER_SIZE: Int = 12

    fun pack(
        codec: Int,
        flags: Int,
        ptsMs: Long,
        width: Int,
        height: Int,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(KIND_VIDEO.toByte())
        buf.put(flags.toByte())
        buf.put(codec.toByte())
        buf.put(0)
        buf.putInt(ptsMs.toInt())
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        buf.put(payload)
        return out
    }

    /** The encoded bytes, with the header stripped. */
    fun payloadOf(frame: ByteArray): ByteArray =
        if (frame.size <= HEADER_SIZE) ByteArray(0)
        else frame.copyOfRange(HEADER_SIZE, frame.size)
}

/** Decoded [VideoFrame] header. */
class VideoFrameHeader(
    val kind: Int,
    val flags: Int,
    val codec: Int,
    val ptsMs: Long,
    val width: Int,
    val height: Int,
) {
    val isKeyframe: Boolean get() = flags and VideoFrame.FLAG_KEYFRAME != 0
    val isCodecConfig: Boolean get() = flags and VideoFrame.FLAG_CODEC_CONFIG != 0

    companion object {
        /** @return null for anything that is not a well-formed video frame. */
        fun parse(frame: ByteArray): VideoFrameHeader? {
            if (frame.size < VideoFrame.HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(frame, 0, VideoFrame.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val kind = buf.get().toInt() and 0xFF
            if (kind != VideoFrame.KIND_VIDEO) return null
            val flags = buf.get().toInt() and 0xFF
            val codec = buf.get().toInt() and 0xFF
            buf.get() // reserved
            val pts = buf.int.toLong() and 0xFFFF_FFFFL
            val width = buf.short.toInt() and 0xFFFF
            val height = buf.short.toInt() and 0xFFFF
            return VideoFrameHeader(kind, flags, codec, pts, width, height)
        }
    }
}
