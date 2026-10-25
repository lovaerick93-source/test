package com.remotectl.agent.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.remotectl.protocol.NavKey

/**
 * Replays remote input on the phone.
 *
 * This is an ordinary AccessibilityService: the phone's owner switches it on by
 * hand in Settings, the system then keeps a permanent reminder on screen, and
 * switching it off stops remote input instantly. There is no way for the agent
 * to enable it by itself, and nothing is injected while it is off.
 *
 * Taps and swipes go through [dispatchGesture]; typing goes through
 * ACTION_SET_TEXT on whichever field currently has input focus.
 */
class RemoteInputService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        service = this
        Log.i(TAG, "input service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        service = null
        Log.i(TAG, "input service unbound; remote input is now off")
        return super.onUnbind(intent)
    }

    // --------------------------------------------------------------- gestures

    /** @return false when the gesture was refused, so the desktop can say so. */
    private fun play(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && focus.isEditable) return focus
        return firstEditable(root, 0)
    }

    private fun firstEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            firstEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pressEnter(): Boolean {
        val node = focusedInput() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) {
            return true
        }
        // Pre-Android 11 has no "press the IME action" verb, so append a newline
        // to the field's text instead. Works for most multi-line inputs.
        return setText(node, node.text?.toString().orEmpty() + "\n")
    }

    private fun pressBackspace(): Boolean {
        val node = focusedInput() ?: return false
        val current = node.text?.toString().orEmpty()
        if (current.isEmpty()) return false
        // Drop one whole code point so emoji and accented letters delete cleanly.
        val keep = current.offsetByCodePoints(current.length, -1)
        return setText(node, current.substring(0, keep))
    }

    companion object {
        private const val TAG = "RemoteInput"
        private const val MAX_TREE_DEPTH = 40

        @Volatile
        private var service: RemoteInputService? = null

        /** True once the owner has switched this service on. */
        val isReady: Boolean get() = service != null

        fun tap(x: Float, y: Float): Boolean {
            val s = service ?: return false
            return s.play(Path().apply { moveTo(x, y) }, TAP_MS)
        }

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val s = service ?: return false
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            return s.play(path, durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS))
        }

        fun typeText(value: String): Boolean {
            val s = service ?: return false
            val node = s.focusedInput() ?: return false
            return s.setText(node, value)
        }

        fun pressKey(key: String): Boolean {
            val s = service ?: return false
            return when (key) {
                NavKey.BACK -> s.performGlobalAction(GLOBAL_ACTION_BACK)
                NavKey.HOME -> s.performGlobalAction(GLOBAL_ACTION_HOME)
                NavKey.RECENTS -> s.performGlobalAction(GLOBAL_ACTION_RECENTS)
                NavKey.ENTER -> s.pressEnter()
                NavKey.BACKSPACE -> s.pressBackspace()
                else -> false
            }
        }

        private const val TAP_MS = 40L
        private const val MIN_SWIPE_MS = 20L
        private const val MAX_SWIPE_MS = 4_000L
    }
}
