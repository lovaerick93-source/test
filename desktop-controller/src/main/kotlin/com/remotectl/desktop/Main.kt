package com.remotectl.desktop

import com.remotectl.desktop.ui.ControllerFrame
import java.awt.EventQueue
import javax.swing.UIManager

fun main() {
    EventQueue.invokeLater {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        ControllerFrame().isVisible = true
    }
}
