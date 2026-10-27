package com.remotectl.desktop

import com.remotectl.desktop.ui.ControllerFrame
import java.awt.EventQueue
import javax.swing.UIManager

fun main() {
    EventQueue.invokeLater {
        runCatching {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel")
            UIManager.put("control", java.awt.Color(0xF5, 0xF7, 0xFB))
            UIManager.put("info", java.awt.Color(0xFF, 0xFF, 0xE0))
            UIManager.put("nimbusBase", java.awt.Color(0x1E, 0x3A, 0x5F))
            UIManager.put("nimbusBlueGrey", java.awt.Color(0xD9, 0xE2, 0xF3))
            UIManager.put("nimbusLightBackground", java.awt.Color(0xFF, 0xFF, 0xFF))
            UIManager.put("text", java.awt.Color(0x1F, 0x29, 0x37))
        }
        ControllerFrame().isVisible = true
    }
}
