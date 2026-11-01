package com.remotectl.agent.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.remotectl.protocol.NavKey
import java.util.concurrent.ConcurrentLinkedQueue

class RemoteInputService : AccessibilityService() {

    // Thread-safe queue for gestures to prevent overlap
    private val gestureQueue = ConcurrentLinkedQueue<Pair<GestureDescription, (Boolean) -> Unit>>()
    private var isProcessingGesture = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        service = this
        Log.i(TAG, "Input Service Connected & Ready for Full Control")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // No-op: we only inject input, we don't observe events.
    }

    override fun onInterrupt() {
        // Service was interrupted: mark not ready.
        service = null
    }

    /** Whether the accessibility service is connected and ready to inject input. */
    val isReady: Boolean get() = service != null

    /** Inject a single tap at (x, y). */
    fun tap(x: Float, y: Float): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = false
        enqueueTap(x, y) { ok -> result = ok; latch.countDown() }
        latch.await()
        return result
    }

    /** Inject a swipe from (x1,y1) to (x2,y2) over durationMs. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = false
        enqueueSwipe(x1, y1, x2, y2, durationMs) { ok -> result = ok; latch.countDown() }
        latch.await()
        return result
    }

    /** Type literal text into the currently focused editable field. */
    fun typeText(value: String): Boolean {
        val node = focusedInput() ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Press a navigation key by name. */
    fun pressKey(key: String): Boolean {
        return when (key) {
            NavKey.BACK -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            NavKey.HOME -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            NavKey.RECENTS -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            else -> false
        }
    }

    // --------------------------------------------------------------- Advanced Gestures

    /** 
     * Enqueue a gesture. Waits if the queue is busy.
     */
    fun enqueueTap(x: Float, y: Float, callback: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        enqueueGesture(gesture, callback)
    }

    fun enqueueSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, callback: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        enqueueGesture(gesture, callback)
    }

    private fun enqueueGesture(gesture: GestureDescription, callback: (Boolean) -> Unit) {
        gestureQueue.add(Pair(gesture, callback))
        if (!isProcessingGesture) processNextGesture()
    }

    private fun processNextGesture() {
        val pair = gestureQueue.poll() ?: run { isProcessingGesture = false; return }
        val (gesture, callback) = pair
        isProcessingGesture = true

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                super.onCompleted(gestureDescription)
                callback(true)
                processNextGesture() // Process next in queue
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                super.onCancelled(gestureDescription)
                callback(false)
                processNextGesture()
            }
        }, null)
    }

    // --------------------------------------------------------------- Smart Input

    private fun focusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        // Prefer focus, then search for any editable node
        var focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && focus.isEditable) return focus
        
        // Fallback: Find first editable node in tree
        return findFirstEditable(root)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findFirstEditable(child)
                if (result != null) return result
            }
        }
        return null
    }

    companion object {
        private const val TAG = "RemoteInput"
        @Volatile
        private var service: RemoteInputService? = null
        
        /** Whether the accessibility service is connected and ready. */
        val isReady: Boolean get() = service != null

        /** Inject a single tap at (x, y). */
        fun tap(x: Float, y: Float): Boolean = service?.tap(x, y) ?: false

        /** Inject a swipe from (x1,y1) to (x2,y2) over durationMs. */
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
            service?.swipe(x1, y1, x2, y2, durationMs) ?: false

        /** Type literal text into the currently focused editable field. */
        fun typeText(value: String): Boolean = service?.typeText(value) ?: false

        /** Press a navigation key by name. */
        fun pressKey(key: String): Boolean = service?.pressKey(key) ?: false

        // Expose for AgentService to use
        fun getService(): RemoteInputService? = service
        fun setService(s: RemoteInputService?) { service = s }

        private const val TAP_MS = 50L
        private const val MIN_SWIPE_MS = 20L
        private const val MAX_SWIPE_MS = 1_000L
    }
}