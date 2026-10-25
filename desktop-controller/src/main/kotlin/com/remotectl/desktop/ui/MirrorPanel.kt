package com.remotectl.desktop.ui

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel

/**
 * Shows the phone's screen and turns mouse gestures on it into real touch input.
 *
 * The panel keeps the phone's own resolution ([sourceWidth] x [sourceHeight], as
 * reported in `evt.mirror_state`) and maps a click back into that space, so what
 * the user aims at is what the phone receives no matter how the window is
 * resized. A short press is a tap; pressing and dragging is a swipe.
 */
class MirrorPanel(
    private val onTap: (x: Float, y: Float) -> Unit,
    private val onSwipe: (x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) -> Unit,
) : JPanel() {

    private var picture: BufferedImage? = null

    /** Device pixels of the encoded frame, not of the window. */
    private var sourceWidth = 0
    private var sourceHeight = 0

    private var pressAt: Point? = null
    private var pressAtMs = 0L

    fun setFrame(image: BufferedImage, width: Int, height: Int) {
        picture = image
        sourceWidth = width
        sourceHeight = height
        repaint()
    }

    fun clear() {
        picture = null
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val image = picture ?: return

        val draw = g.create() as Graphics2D
        try {
            draw.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            val fit = fit()
            draw.drawImage(
                image,
                fit.offsetX.toInt(),
                fit.offsetY.toInt(),
                fit.width.toInt(),
                fit.height.toInt(),
                null,
            )
        } finally {
            draw.dispose()
        }
    }

    // ------------------------------------------------------------- interaction

    private val gestures = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            if (picture == null) return
            pressAt = e.point
            pressAtMs = System.currentTimeMillis()
        }

        override fun mouseReleased(e: MouseEvent) {
            if (picture == null) return
            val start = pressAt ?: return
            pressAt = null
            val from = toDevice(start) ?: return
            val to = toDevice(e.point) ?: return

            val travel = Math.hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
            if (travel < TAP_SLOP_PX) {
                onTap(to.x.toFloat(), to.y.toFloat())
            } else {
                val held = (System.currentTimeMillis() - pressAtMs).toInt()
                onSwipe(
                    from.x.toFloat(), from.y.toFloat(),
                    to.x.toFloat(), to.y.toFloat(),
                    held.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS),
                )
            }
        }
    }

    init {
        background = Color(0x18, 0x18, 0x18)
        preferredSize = Dimension(360, 640)
        addMouseListener(gestures)
    }

    /** Window point -> device pixel of the phone screen, or null outside the picture. */
    private fun toDevice(point: Point): Point? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val fit = fit()
        if (fit.width <= 0f || fit.height <= 0f) return null
        val deviceX = (point.x - fit.offsetX) / fit.width * sourceWidth
        val deviceY = (point.y - fit.offsetY) / fit.height * sourceHeight
        return Point(
            deviceX.toInt().coerceIn(0, sourceWidth - 1),
            deviceY.toInt().coerceIn(0, sourceHeight - 1),
        )
    }

    /** Where the picture lands inside the panel, aspect ratio preserved. */
    private fun fit(): Fit {
        val image = picture ?: return Fit(0f, 0f, 0f, 0f)
        if (width <= 0 || height <= 0) return Fit(0f, 0f, 0f, 0f)
        val scale = minOf(
            width.toFloat() / image.width,
            height.toFloat() / image.height,
        )
        val w = image.width * scale
        val h = image.height * scale
        return Fit((width - w) / 2f, (height - h) / 2f, w, h)
    }

    private data class Fit(val offsetX: Float, val offsetY: Float, val width: Float, val height: Float)

    private companion object {
        /** Below this the press is a tap, above it a swipe. */
        const val TAP_SLOP_PX = 12.0
        const val MIN_SWIPE_MS = 40
        const val MAX_SWIPE_MS = 1500
    }
}
