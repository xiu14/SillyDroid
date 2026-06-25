package com.jm.sillydroid.feature.main.ui.home.webview

import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.widget.TextViewCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jm.sillydroid.domain.settings.TavernShellSettingsRepository
import com.jm.sillydroid.domain.settings.TavernShellSiteCredentials
import com.jm.sillydroid.feature.main.R
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue

data class HttpAuthCredentials(
    val username: String,
    val password: String
)

data class HttpAuthPromptRequest(
    val source: String,
    val host: String?,
    val realm: String?,
    val initialUsername: String? = null,
    val initialPassword: String? = null,
    val onConfirm: (HttpAuthCredentials) -> Unit,
    val onCancel: () -> Unit
)

/**
 * 统一承接 WebView / GeckoView 的 HTTP Basic Auth challenge。
 *
 * 这里只把浏览器内核的认证请求展示给用户输入，不读取或落盘 SillyTavern 配置里的密码，
 * 避免宿主自动绕过用户主动开启的基础认证。
 */
class HttpAuthPromptController(
    private val activity: AppCompatActivity,
    private val tavernShellSettingsRepository: TavernShellSettingsRepository? = null,
    private val diagnosticSink: HostDiagnosticSink = HostDiagnosticSink { _, _ -> }
) {
    private var activeDialog: AlertDialog? = null
    private var activeCancel: (() -> Unit)? = null

    fun show(request: HttpAuthPromptRequest) {
        runOnUiThread {
            if (!isActivityAlive()) {
                record(
                    "event=http_auth_prompt_skipped reason=activity_not_alive source=${request.source} " +
                        "host=${normalizeDiagnosticValue(request.host)} realm=${normalizeDiagnosticValue(request.realm)}"
                )
                request.onCancel()
                return@runOnUiThread
            }

            if (activeDialog?.isShowing == true) {
                record(
                    "event=http_auth_prompt_skipped reason=active_prompt source=${request.source} " +
                        "host=${normalizeDiagnosticValue(request.host)} realm=${normalizeDiagnosticValue(request.realm)}"
                )
                request.onCancel()
                return@runOnUiThread
            }

            showDialog(request)
        }
    }

    fun dismissActivePrompt(reason: String) {
        runOnUiThread {
            val dialog = activeDialog ?: return@runOnUiThread
            record("event=http_auth_prompt_dismissed reason=$reason")
            val cancel = activeCancel
            clearActivePrompt()
            dialog.setOnCancelListener(null)
            dialog.dismiss()
            cancel?.invoke()
        }
    }

    private fun showDialog(request: HttpAuthPromptRequest) {
        var completed = false
        fun complete(action: String, callback: () -> Unit) {
            if (completed) return
            completed = true
            clearActivePrompt()
            record(
                "event=http_auth_prompt_$action source=${request.source} " +
                    "host=${normalizeDiagnosticValue(request.host)} realm=${normalizeDiagnosticValue(request.realm)}"
            )
            callback()
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.sillydroid_auth_prompt_dialog_padding_horizontal),
                dimen(R.dimen.sillydroid_auth_prompt_dialog_padding_vertical),
                dimen(R.dimen.sillydroid_auth_prompt_dialog_padding_horizontal),
                0
            )
        }
        val messageView = TextView(activity).apply {
            text = buildPromptMessage(request)
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_AuthPromptBody)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val usernameInputLayout = createInputLayout(
            hint = activity.getString(R.string.http_auth_prompt_username_hint),
            topMargin = R.dimen.sillydroid_auth_prompt_first_field_margin_top
        )
        val usernameInput = createInputEditText(
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_NORMAL or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            initialValue = request.initialUsername
                ?: storedCredentialsFor(request)?.username
                ?: ""
        )
        usernameInputLayout.addView(usernameInput)

        val passwordInputLayout = createInputLayout(
            hint = activity.getString(R.string.http_auth_prompt_password_hint),
            topMargin = R.dimen.sillydroid_auth_prompt_field_spacing
        ).apply {
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val passwordInput = createInputEditText(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            initialValue = request.initialPassword
                ?: storedCredentialsFor(request)?.password
                ?: ""
        )
        passwordInputLayout.addView(passwordInput)

        container.addView(messageView)
        container.addView(usernameInputLayout)
        container.addView(passwordInputLayout)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.http_auth_prompt_title))
            .setView(container)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                complete("cancelled", request.onCancel)
            }
            .setPositiveButton(activity.getString(R.string.http_auth_prompt_login)) { _, _ ->
                complete("submitted") {
                    storeCredentials(
                        request = request,
                        username = usernameInput.text?.toString().orEmpty(),
                        password = passwordInput.text?.toString().orEmpty()
                    )
                    request.onConfirm(
                        HttpAuthCredentials(
                            username = usernameInput.text?.toString().orEmpty(),
                            password = passwordInput.text?.toString().orEmpty()
                        )
                    )
                }
            }
            .setOnCancelListener {
                complete("cancelled", request.onCancel)
            }
            .show()

        activeDialog = dialog
        activeCancel = { complete("cancelled", request.onCancel) }
        usernameInput.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        usernameInput.post {
            activity.getSystemService<InputMethodManager>()
                ?.showSoftInput(usernameInput, InputMethodManager.SHOW_IMPLICIT)
        }
        record(
            "event=http_auth_prompt_shown source=${request.source} " +
                "host=${normalizeDiagnosticValue(request.host)} realm=${normalizeDiagnosticValue(request.realm)}"
        )
    }

    private fun createInputLayout(hint: String, topMargin: Int): TextInputLayout {
        return TextInputLayout(activity, null, MaterialR.attr.textInputStyle).apply {
            this.hint = hint
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.topMargin = dimen(topMargin)
            }
        }
    }

    private fun createInputEditText(inputType: Int, initialValue: String): TextInputEditText {
        return TextInputEditText(activity, null, android.R.attr.editTextStyle).apply {
            this.inputType = inputType
            setSingleLine(true)
            setText(initialValue)
            setSelectAllOnFocus(true)
        }
    }

    private fun buildPromptMessage(request: HttpAuthPromptRequest): String {
        val host = request.host
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
            ?: activity.getString(R.string.http_auth_prompt_unknown_host)
        val realm = request.realm
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
        return if (realm == null) {
            activity.getString(R.string.http_auth_prompt_message, host)
        } else {
            activity.getString(R.string.http_auth_prompt_message_with_realm, host, realm)
        }
    }

    private fun clearActivePrompt() {
        activeDialog = null
        activeCancel = null
    }

    private fun storedCredentialsFor(request: HttpAuthPromptRequest): TavernShellSiteCredentials? {
        val host = credentialHostFor(request)
        if (host.isBlank()) return null
        return tavernShellSettingsRepository?.siteCredentials(host, request.realm)
    }

    private fun storeCredentials(request: HttpAuthPromptRequest, username: String, password: String) {
        val host = credentialHostFor(request)
        if (host.isBlank() || username.isBlank() || password.isBlank()) return
        tavernShellSettingsRepository?.putSiteCredentials(
            host = host,
            realm = request.realm,
            credentials = TavernShellSiteCredentials(username = username, password = password)
        )
    }

    private fun credentialHostFor(request: HttpAuthPromptRequest): String {
        val rawHost = request.host?.trim().orEmpty()
        if (rawHost.isBlank()) return ""
        return if (rawHost.contains("://")) {
            runCatching {
                tavernShellSettingsRepository?.hostOf(rawHost).orEmpty()
            }.getOrDefault("")
        } else {
            rawHost
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action()
        } else {
            activity.runOnUiThread(action)
        }
    }

    private fun isActivityAlive(): Boolean {
        return !activity.isFinishing && !activity.isDestroyed
    }

    private fun dimen(resId: Int): Int {
        return activity.resources.getDimensionPixelSize(resId)
    }

    private fun record(body: String) {
        runCatching { diagnosticSink.record("http_auth", body) }
    }
}
