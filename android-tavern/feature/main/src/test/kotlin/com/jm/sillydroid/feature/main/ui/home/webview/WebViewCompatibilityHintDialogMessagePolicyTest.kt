package com.jm.sillydroid.feature.main.ui.home.webview

import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebViewCompatibilityHintDialogMessagePolicyTest {

    @Test
    fun `harden disables selection and long click for compatibility hint message`() {
        val messageView = TextView(ApplicationProvider.getApplicationContext()).apply {
            text = "Chromium 90"
            setTextIsSelectable(true)
            isLongClickable = true
            isHapticFeedbackEnabled = true
        }

        hardenCompatibilityHintDialogMessage(messageView)

        assertFalse(messageView.isTextSelectable)
        assertFalse(messageView.isLongClickable)
        assertFalse(messageView.isHapticFeedbackEnabled)
    }

    @Test
    fun `harden ignores missing dialog message view`() {
        hardenCompatibilityHintDialogMessage(messageView = null)
    }
}
