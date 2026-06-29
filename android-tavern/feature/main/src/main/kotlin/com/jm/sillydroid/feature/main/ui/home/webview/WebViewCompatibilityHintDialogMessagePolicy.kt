package com.jm.sillydroid.feature.main.ui.home.webview

import android.widget.TextView
import androidx.appcompat.app.AlertDialog

internal fun hardenCompatibilityHintDialogMessage(dialog: AlertDialog) {
    hardenCompatibilityHintDialogMessage(dialog.findViewById(android.R.id.message))
}

internal fun hardenCompatibilityHintDialogMessage(messageView: TextView?) {
    if (messageView == null) {
        return
    }

    // 某些旧 WebView 设备长按兼容性提示弹窗文案时，Framework Editor 会继续走拖拽阴影路径，
    // 并因为异常尺寸直接抛 IllegalStateException；这些提示文案不需要选择/拖拽能力。
    messageView.setTextIsSelectable(false)
    messageView.isLongClickable = false
    messageView.setOnLongClickListener { true }
    messageView.isHapticFeedbackEnabled = false
}
