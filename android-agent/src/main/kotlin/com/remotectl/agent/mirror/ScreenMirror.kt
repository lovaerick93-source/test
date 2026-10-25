package com.remotectl.agent.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Streams the phone's screen as a sequence of JPEG images.
 *
 * A [VirtualDisplay] mirrors the real display onto an [ImageReader] surface. The
 * pixels arrive as RGBA, are copied straight into a reusable [Bitmap] and JPEG
 * compressed. JPEG rather than H.264 because every frame then stands alone: a
 * dropped or delayed frame costs one stale picture instead of corrupting the
 * stream until the next keyframe, which is what actually happens on a phone
 * that keeps wandering between Wi-Fi access points.
 *
 * Capture only ever runs against a [MediaProjection] the owner granted through
 * the system's own screen-sharing dialog. Nothing here can start on its own.
 *
 * All work happens on one private [HandlerThread]; the class is not thread safe
 * and does not need to be.
 */
class ScreenMirror(
    private val context: Context,
    private val sink: Sink,
) {

    interface Sink {
        /**
         * One complete image, ready to send.
         *
         * [width] and [height] are the encoded size, which is the screen size
         * scaled down by [MAX_WIDTH]; the controller maps its clicks back to
         * device pixels using `evt.mirror_state`.
         */
        fun onFrame(jpeg: ByteArray, width: Int, height: Int, ptsMs: Long)

        /** Capture ended: the owner revoked it, or the display went away. */
        fun onEnded(reason: String)
    }

    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var projection: MediaProjection? = null

    private var outWidth = 0
    private var outHeight = 0

    // Reused across frames. Reallocating a megabyte per frame is exactly the
    // kind of churn that shows up as stutter on a low-end phone.
    private var target: Bitmap? = null
    private var wide: Bitmap? = null
    private val jpegSink = ByteArrayOutputStream(256 * 1024)

    @Volatile private var lastFrameAt = 0L
    @Volatile private var active = false

    val isRunning: Boolean get() = active
    val frameWidth: Int get() = outWidth
    val frameHeight: Int get() = outHeight

    /**
     * Begins mirroring. [projection] stays owned by the caller: this class only
     * creates and releases the virtual display, so a session can be stopped and
     * started again without asking the owner for consent a second time.
     *
     * @return false when there is nothing to mirror, e.g. a zero-sized display.
     */
    fun start(projection: MediaProjection): Boolean {
        if (active) return true

        val screen = screenSize() ?: run {
            Log.w(TAG, "no measurable display to mirror")
            return false
        }
        // Scale by the *long* edge so a tall portrait phone lands at
        // 324x720-ish rather than 720x1600, which would be five times the
        // pixels we have to JPEG every frame.
        val longEdge = maxOf(screen.first, screen.second)
        val scale = if (longEdge > CAPTURE_LONG_EDGE) {
            CAPTURE_LONG_EDGE.toFloat() / longEdge
        } else {
            1f
        }
        outWidth = even((screen.first * scale).roundToInt())
        outHeight = even((screen.second * scale).roundToInt())
        if (outWidth <= 0 || outHeight <= 0) {
            Log.w(TAG, "computed an empty capture size from ${screen.first}x${screen.second}")
            return false
        }

        val worker = HandlerThread("screen-mirror").also { it.start() }
        val handler = Handler(worker.looper)
        thread = worker

        val surface = ImageReader.newInstance(outWidth, outHeight, PixelFormat.RGBA_8888, MAX_IMAGES)
        surface.setOnImageAvailableListener({ onImageAvailable(it) }, handler)
        reader = surface

        // Android 14+ rejects createVirtualDisplay on a projection with no
        // registered callback, so this has to come first.
        runCatching { projection.registerCallback(stoppedCallback, handler) }

        val density = (context.resources.displayMetrics.densityDpi * scale).roundToInt().coerceAtLeast(1)
        val created = runCatching {
            projection.createVirtualDisplay(
                DISPLAY_NAME,
                outWidth,
                outHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface.surface,
                null,
                handler,
            )
        }.onFailure { Log.w(TAG, "could not create the virtual display", it) }
            .getOrNull()

        if (created == null) {
            releaseResources()
            return false
        }

        display = created
        this.projection = projection
        lastFrameAt = 0L
        active = true
        Log.i(TAG, "mirroring ${screen.first}x${screen.second} at ${outWidth}x$outHeight")
        return true
    }

    /** Tears the session down but leaves the consent token alone. Safe to call twice. */
    fun stop() {
        if (!active && display == null && reader == null) return
        active = false
        releaseResources()
        Log.i(TAG, "mirroring stopped")
    }

    private fun releaseResources() {
        display?.release()
        display = null

        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null

        projection?.let { runCatching { it.unregisterCallback(stoppedCallback) } }
        projection = null

        thread?.quitSafely()
        thread = null
    }

    private val stoppedCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "the system ended screen capture")
            val wasActive = active
            active = false
            releaseResources()
            if (wasActive) sink.onEnded("screen_capture_revoked")
        }
    }

    // -------------------------------------------------------------- frame loop

    private fun onImageAvailable(source: ImageReader) {
        if (!active) {
            runCatching { source.acquireLatestImage()?.close() }
            return
        }

        val image = runCatching { source.acquireLatestImage() }.getOrNull()
        if (image == null) return

        try {
            val now = SystemClock.uptimeMillis()
            // acquireLatestImage already drops stale buffers; this caps how much
            // bandwidth we hand a Wi-Fi link that may be slower than the screen.
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            lastFrameAt = now

            val jpeg = encode(image) ?: return
            sink.onFrame(jpeg, outWidth, outHeight, now)
        } catch (t: Throwable) {
            Log.w(TAG, "dropping a frame", t)
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * RGBA plane -> JPEG. Some drivers pad each row, in which case the padded
     * copy has to be cropped before it looks like a screen rather than a screen
     * with sheared edges.
     */
    private fun encode(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * outWidth

        if (rowPadding == 0) {
            val frame = bitmap(outWidth, outHeight) ?: return null
            frame.copyPixelsFromBuffer(buffer)
            return compress(frame)
        }

        val paddedWidth = outWidth + rowPadding / pixelStride
        val padded = paddedBitmap(paddedWidth, outHeight) ?: return null
        padded.copyPixelsFromBuffer(buffer)

        val frame = bitmap(outWidth, outHeight) ?: return null
        Canvas(frame).drawBitmap(
            padded,
            Rect(0, 0, outWidth, outHeight),
            Rect(0, 0, outWidth, outHeight),
            null,
        )
        return compress(frame)
    }

    private fun compress(bitmap: Bitmap): ByteArray {
        jpegSink.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegSink)
        return jpegSink.toByteArray()
    }

    private fun bitmap(width: Int, height: Int): Bitmap? = reuse(bitmap = target, width, height) { target = it }

    private fun paddedBitmap(width: Int, height: Int): Bitmap? =
        reuse(bitmap = wide, width, height) { wide = it }

    private inline fun reuse(bitmap: Bitmap?, width: Int, height: Int, store: (Bitmap) -> Unit): Bitmap? {
        if (width <= 0 || height <= 0) return null
        if (bitmap != null && bitmap.width == width && bitmap.height == height && !bitmap.isRecycled) {
            return bitmap
        }
        bitmap?.recycle()
        val fresh = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
        if (fresh != null) store(fresh)
        return fresh
    }

    // ------------------------------------------------------------------ helpers

    private fun screenSize(): Pair<Int, Int>? {
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
            return null
        }
        @Suppress("DEPRECATION")
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay?.getRealSize(point)
        return if (point.x > 0 && point.y > 0) point.x to point.y else null
    }

    /** Video encoders are happiest with even dimensions. */
    private fun even(value: Int): Int = (value / 2) * 2

    companion object {
        private const val TAG = "ScreenMirror"
        private const val DISPLAY_NAME = "remote-control-screen"
        private const val MAX_IMAGES = 2

        /** Long edge of the capture. 720 is readable text at a survivable bitrate. */
        private const val CAPTURE_LONG_EDGE = 720
        private const val JPEG_QUALITY = 60

        /** ~15 fps. Above this the phone spends more power encoding than it is worth. */
        private const val FRAME_INTERVAL_MS = 66L
    }
}
