package com.remotectl.agent.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.TextView

class PermissionRationaleDialog(
    private val context: Context,
    private val title: String,
    private val message: String,
    private val onPositiveClick: () -> Unit,
    private val onNegativeClick: (() -> Unit)? = null
) : AlertDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Custom Layout (Simplified for brevity - in real app, use a layout XML file)
        val dialogView = android.view.LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, null)
        setContentView(dialogView)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Note: In a real pro app, you'd inflate a custom XML layout with icons and better styling.
        // Here we just show the standard AlertDialog but styled cleanly.
    }

    companion object {
        fun show(
            context: Context,
            titleResId: Int,
            messageResId: Int,
            positiveText: String = "Grant",
            negativeText: String = "Deny",
            onPositiveClick: () -> Unit,
            onNegativeClick: (() -> Unit)? = null
        ) {
            val dialog = AlertDialog.Builder(context)
                .setTitle(titleResId)
                .setMessage(messageResId)
                .setPositiveButton(positiveText) { _, _ -> onPositiveClick() }
                .setNegativeButton(negativeText, null) // Or handle negative click
                .create()

            dialog.show()

            // Optional: Make the buttons look more professional by changing text color
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#00796B")) // Teal
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#757575")) // Grey
        }
    }
}
