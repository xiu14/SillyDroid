package com.jm.sillydroid.feature.settings.ui.tavern

import android.content.Intent
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jm.sillydroid.domain.bootstrap.RuntimeConfigRepository
import com.jm.sillydroid.domain.settings.TavernShellBridgeStatus
import com.jm.sillydroid.domain.settings.TavernShellSettingsRepository
import com.jm.sillydroid.domain.settings.TavernShellSiteCredentials
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.ui.createSettingsEditText
import com.jm.sillydroid.feature.settings.ui.createSettingsTextInputLayout

class BootstrapSettingsTavernShellCoordinator(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val settingsRepository: TavernShellSettingsRepository,
    private val runtimeConfigRepository: RuntimeConfigRepository,
    private val showMessage: (String) -> Unit,
) {
    private lateinit var bridgeStatusView: TextView
    private lateinit var currentHostView: TextView
    private lateinit var foregroundPillSwitch: MaterialSwitch
    private lateinit var strongKeepAliveSwitch: MaterialSwitch
    private lateinit var keepAliveNotificationOngoingSwitch: MaterialSwitch
    private lateinit var pendingKeepAliveInput: TextInputEditText
    private lateinit var pendingKeepAliveInputLayout: TextInputLayout
    private lateinit var wakeLockSwitch: MaterialSwitch
    private lateinit var completionSoundSwitch: MaterialSwitch
    private lateinit var completionMediaSoundSwitch: MaterialSwitch
    private lateinit var contentStartVibrationSwitch: MaterialSwitch
    private lateinit var skipOnlineFontsSwitch: MaterialSwitch
    private lateinit var imageProxySwitch: MaterialSwitch
    private lateinit var imageProxyUrlInput: TextInputEditText
    private lateinit var imageProxyUrlInputLayout: TextInputLayout
    private lateinit var imageProxyExcludedHostsInput: TextInputEditText
    private lateinit var imageProxyExcludedHostsInputLayout: TextInputLayout
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText

    fun initialize() {
        buildContent()
        loadState()
    }

    fun refreshBridgeStatus() {
        if (!this::bridgeStatusView.isInitialized) return
        bridgeStatusView.text = formatBridgeStatus(settingsRepository.bridgeStatus())
    }

    private fun buildContent() {
        while (container.childCount > 1) {
            container.removeViewAt(1)
        }

        addSectionTitle(R.string.bootstrap_settings_tavern_shell_section_bridge)
        bridgeStatusView = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
            setPadding(dimen(R.dimen.sillydroid_panel_padding), dimen(R.dimen.sillydroid_space_sm), dimen(R.dimen.sillydroid_panel_padding), dimen(R.dimen.sillydroid_space_sm))
        }
        createNestedCard().addContentView(bridgeStatusView)

        addSectionTitle(R.string.bootstrap_settings_tavern_shell_section_live)
        foregroundPillSwitch = addSwitchRow(
            titleRes = R.string.bootstrap_settings_tavern_shell_foreground_pill_title,
            summaryRes = R.string.bootstrap_settings_tavern_shell_foreground_pill_summary
        )
        strongKeepAliveSwitch = addSwitchRow(
            titleRes = R.string.bootstrap_settings_tavern_shell_strong_keep_alive_title,
            summaryRes = R.string.bootstrap_settings_tavern_shell_strong_keep_alive_summary
        )
        keepAliveNotificationOngoingSwitch = addSwitchRow(
            titleRes = R.string.bootstrap_settings_tavern_shell_ongoing_title,
            summaryRes = R.string.bootstrap_settings_tavern_shell_ongoing_summary
        )
        val pendingBinding = addInput(
            hintRes = R.string.bootstrap_settings_tavern_shell_pending_minutes_hint,
            helperRes = R.string.bootstrap_settings_tavern_shell_pending_minutes_helper,
            inputType = InputType.TYPE_CLASS_NUMBER
        )
        pendingKeepAliveInputLayout = pendingBinding.layout
        pendingKeepAliveInput = pendingBinding.editText
        addButton(R.string.bootstrap_settings_tavern_shell_stop_keep_alive) {
            stopGenerationKeepAlive()
            showMessage(activity.getString(R.string.bootstrap_settings_tavern_shell_stop_keep_alive_done))
        }
        wakeLockSwitch = addSwitchRow(
            titleRes = R.string.bootstrap_settings_tavern_shell_wakelock_title,
            summaryRes = R.string.bootstrap_settings_tavern_shell_wakelock_summary
        )
        completionSoundSwitch = addSwitchRow(
            titleRes = R.string.bootstrap_settings_tavern_shell_sound_title,
            summaryRes = R.string.bootstrap_settings_tavern_shell_sound_summary
        )
        completionMediaSoundSwitch = addSwitchRow(
            titleRes = R.string.bootstrap_settings_tavern_shell_media_sound_title,
            summaryRes = R.string.bootstrap_settings_tavern_shell_media_sound_summary
        )
        contentStartVibrationSwitch = addSwitchRow(
            titleRes = R.string.bootstrap_settings_tavern_shell_vibration_title,
            summaryRes = R.string.bootstrap_settings_tavern_shell_vibration_summary
        )

        addSectionTitle(R.string.bootstrap_settings_tavern_shell_section_web)
        skipOnlineFontsSwitch = addSwitchRow(
            titleRes = R.string.bootstrap_settings_tavern_shell_skip_fonts_title,
            summaryRes = R.string.bootstrap_settings_tavern_shell_skip_fonts_summary
        )
        imageProxySwitch = addSwitchRow(
            titleRes = R.string.bootstrap_settings_tavern_shell_image_proxy_title,
            summaryRes = R.string.bootstrap_settings_tavern_shell_image_proxy_summary
        )
        val proxyUrlBinding = addInput(
            hintRes = R.string.bootstrap_settings_tavern_shell_image_proxy_url_hint,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        imageProxyUrlInputLayout = proxyUrlBinding.layout
        imageProxyUrlInput = proxyUrlBinding.editText
        val excludedHostsBinding = addInput(
            hintRes = R.string.bootstrap_settings_tavern_shell_image_proxy_excluded_hosts_hint,
            helperRes = R.string.bootstrap_settings_tavern_shell_image_proxy_excluded_hosts_helper,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            minLines = 2
        )
        imageProxyExcludedHostsInputLayout = excludedHostsBinding.layout
        imageProxyExcludedHostsInput = excludedHostsBinding.editText

        addSectionTitle(R.string.bootstrap_settings_tavern_shell_section_auth)
        currentHostView = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dimen(R.dimen.sillydroid_space_xs)
            }
        }
        container.addView(currentHostView)
        usernameInput = addInput(
            hintRes = R.string.bootstrap_settings_tavern_shell_username_hint,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ).editText
        passwordInput = addInput(
            hintRes = R.string.bootstrap_settings_tavern_shell_password_hint,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        ).editText
        addButton(R.string.bootstrap_settings_tavern_shell_clear_auth) {
            clearCurrentHostCredentials()
        }
        addButton(R.string.bootstrap_settings_tavern_shell_save) {
            saveState()
        }

        strongKeepAliveSwitch.setOnCheckedChangeListener { _, _ -> syncEnabledStates() }
        completionSoundSwitch.setOnCheckedChangeListener { _, _ -> syncEnabledStates() }
        imageProxySwitch.setOnCheckedChangeListener { _, _ -> syncEnabledStates() }
    }

    private fun loadState() {
        foregroundPillSwitch.isChecked = settingsRepository.foregroundPillEnabled
        strongKeepAliveSwitch.isChecked = settingsRepository.strongKeepAliveEnabled
        keepAliveNotificationOngoingSwitch.isChecked = settingsRepository.keepAliveNotificationOngoing
        pendingKeepAliveInput.setText(settingsRepository.pendingKeepAliveMinutes.toString())
        wakeLockSwitch.isChecked = settingsRepository.wakeLockEnabled
        completionSoundSwitch.isChecked = settingsRepository.completionSoundEnabled
        completionMediaSoundSwitch.isChecked = settingsRepository.completionMediaSoundEnabled
        contentStartVibrationSwitch.isChecked = settingsRepository.contentStartVibrationEnabled
        skipOnlineFontsSwitch.isChecked = settingsRepository.skipOnlineFontsEnabled
        imageProxySwitch.isChecked = settingsRepository.imageProxyEnabled
        imageProxyUrlInput.setText(settingsRepository.imageProxyUrl)
        imageProxyExcludedHostsInput.setText(settingsRepository.imageProxyExcludedHosts)

        val host = currentCredentialHost()
        currentHostView.text = activity.getString(
            R.string.bootstrap_settings_tavern_shell_current_host,
            host.ifBlank { activity.getString(R.string.bootstrap_settings_tavern_shell_unknown_value) }
        )
        val credentials = host.takeIf { it.isNotBlank() }
            ?.let { settingsRepository.siteCredentials(it) }
        usernameInput.setText(credentials?.username.orEmpty())
        passwordInput.setText(credentials?.password.orEmpty())
        refreshBridgeStatus()
        syncEnabledStates()
    }

    private fun saveState() {
        val pendingMinutes = pendingKeepAliveInput.text
            ?.toString()
            ?.toLongOrNull()
            ?.coerceIn(
                TavernShellSettingsRepository.MIN_PENDING_KEEP_ALIVE_MINUTES,
                TavernShellSettingsRepository.MAX_PENDING_KEEP_ALIVE_MINUTES
            )
            ?: TavernShellSettingsRepository.DEFAULT_PENDING_KEEP_ALIVE_MINUTES

        settingsRepository.foregroundPillEnabled = foregroundPillSwitch.isChecked
        settingsRepository.strongKeepAliveEnabled = strongKeepAliveSwitch.isChecked
        settingsRepository.keepAliveNotificationOngoing = keepAliveNotificationOngoingSwitch.isChecked
        settingsRepository.pendingKeepAliveMinutes = pendingMinutes
        settingsRepository.wakeLockEnabled = wakeLockSwitch.isChecked
        settingsRepository.completionSoundEnabled = completionSoundSwitch.isChecked
        settingsRepository.completionMediaSoundEnabled = completionSoundSwitch.isChecked && completionMediaSoundSwitch.isChecked
        settingsRepository.contentStartVibrationEnabled = contentStartVibrationSwitch.isChecked
        settingsRepository.skipOnlineFontsEnabled = skipOnlineFontsSwitch.isChecked
        settingsRepository.imageProxyEnabled = imageProxySwitch.isChecked
        settingsRepository.imageProxyUrl = imageProxyUrlInput.text?.toString().orEmpty()
        settingsRepository.imageProxyExcludedHosts = imageProxyExcludedHostsInput.text?.toString().orEmpty()

        val username = usernameInput.text?.toString().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()
        val host = currentCredentialHost()
        if (host.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            settingsRepository.putSiteCredentials(
                host = host,
                credentials = TavernShellSiteCredentials(username = username, password = password)
            )
        }

        loadState()
        showMessage(activity.getString(R.string.bootstrap_settings_tavern_shell_saved))
    }

    private fun clearCurrentHostCredentials() {
        val host = currentCredentialHost()
        if (host.isNotBlank()) {
            settingsRepository.clearSiteCredentials(host)
        }
        usernameInput.setText("")
        passwordInput.setText("")
        showMessage(activity.getString(R.string.bootstrap_settings_tavern_shell_clear_auth_done))
    }

    private fun syncEnabledStates() {
        keepAliveNotificationOngoingSwitch.isEnabled = strongKeepAliveSwitch.isChecked
        pendingKeepAliveInputLayout.isEnabled = strongKeepAliveSwitch.isChecked
        completionMediaSoundSwitch.isEnabled = completionSoundSwitch.isChecked
        imageProxyUrlInputLayout.isEnabled = imageProxySwitch.isChecked
        imageProxyExcludedHostsInputLayout.isEnabled = imageProxySwitch.isChecked
    }

    private fun addSectionTitle(titleRes: Int) {
        container.addView(
            TextView(activity).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsSectionTitle)
                setText(titleRes)
                setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurface, 0))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dimen(R.dimen.sillydroid_space_lg)
                }
            }
        )
    }

    private fun addSwitchRow(titleRes: Int, summaryRes: Int): MaterialSwitch {
        val switch = MaterialSwitch(activity, null, MaterialR.attr.materialSwitchStyle).apply {
            scaleX = 0.62f
            scaleY = 0.62f
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dimen(R.dimen.sillydroid_panel_padding), dimen(R.dimen.sillydroid_panel_padding), dimen(R.dimen.sillydroid_panel_padding), dimen(R.dimen.sillydroid_panel_padding))
        }
        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(
            TextView(activity).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsCardTitle)
                setText(titleRes)
                setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurface, 0))
            }
        )
        textColumn.addView(
            TextView(activity).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
                setText(summaryRes)
                setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dimen(R.dimen.sillydroid_space_xs_half)
                }
            }
        )
        row.addView(textColumn)
        row.addView(
            switch,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dimen(R.dimen.sillydroid_space_md)
            }
        )
        createNestedCard().addContentView(row)
        return switch
    }

    private fun addInput(
        hintRes: Int,
        helperRes: Int? = null,
        inputType: Int,
        minLines: Int = 1,
        endIconMode: Int = TextInputLayout.END_ICON_NONE,
    ): InputBinding {
        val layout = activity.createSettingsTextInputLayout(
            hintText = activity.getString(hintRes),
            helperTextValue = helperRes?.let(activity::getString),
            endIconMode = endIconMode
        ).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dimen(R.dimen.sillydroid_field_vertical_spacing)
            }
        }
        val editText = layout.createSettingsEditText().apply {
            this.inputType = inputType
            this.minLines = minLines
            setSingleLine(minLines == 1)
        }
        layout.addView(editText)
        container.addView(layout)
        return InputBinding(layout = layout, editText = editText)
    }

    private fun addButton(textRes: Int, onClick: () -> Unit) {
        container.addView(
            MaterialButton(activity, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                setText(textRes)
                minHeight = dimen(R.dimen.sillydroid_control_min_height)
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dimen(R.dimen.sillydroid_field_vertical_spacing)
                }
            }
        )
    }

    private fun createNestedCard(): MaterialCardView {
        return MaterialCardView(activity).apply {
            radius = activity.resources.getDimension(R.dimen.sillydroid_nested_card_radius)
            cardElevation = 0f
            strokeWidth = 1
            strokeColor = MaterialColors.getColor(activity, MaterialR.attr.colorOutlineVariant, 0)
            setCardBackgroundColor(MaterialColors.getColor(activity, MaterialR.attr.colorSurfaceContainerLow, 0))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dimen(R.dimen.sillydroid_space_md)
            }
            container.addView(this)
        }
    }

    private fun MaterialCardView.addContentView(view: android.view.View) {
        addView(view)
    }

    private fun formatBridgeStatus(status: TavernShellBridgeStatus): String {
        val modeText = when (status.mode) {
            TavernShellBridgeStatus.MODE_EXTENSION -> activity.getString(R.string.bootstrap_settings_tavern_shell_bridge_mode_extension)
            TavernShellBridgeStatus.MODE_FALLBACK -> activity.getString(R.string.bootstrap_settings_tavern_shell_bridge_mode_fallback)
            else -> activity.getString(R.string.bootstrap_settings_tavern_shell_bridge_mode_unknown)
        }
        val lastAt = if (status.lastEventAt > 0L) {
            android.text.format.DateFormat.format("MM-dd HH:mm:ss", status.lastEventAt).toString()
        } else {
            activity.getString(R.string.bootstrap_settings_tavern_shell_empty_value)
        }
        return activity.getString(
            R.string.bootstrap_settings_tavern_shell_bridge_status,
            modeText,
            status.version.ifBlank { activity.getString(R.string.bootstrap_settings_tavern_shell_unknown_value) },
            status.lastEvent.ifBlank { activity.getString(R.string.bootstrap_settings_tavern_shell_empty_value) },
            lastAt
        )
    }

    private fun currentCredentialHost(): String {
        return runCatching {
            settingsRepository.hostOf(runtimeConfigRepository.localServiceUrl())
        }.getOrDefault("")
    }

    private fun stopGenerationKeepAlive() {
        runCatching {
            activity.startService(
                Intent().setClassName(
                    activity.packageName,
                    "com.jm.sillydroid.feature.main.status.GenerationLiveUpdateService"
                ).setAction("com.jm.sillydroid.action.STOP_KEEP_ALIVE")
            )
        }
    }

    private fun dimen(resId: Int): Int {
        return activity.resources.getDimensionPixelSize(resId)
    }

    private data class InputBinding(
        val layout: TextInputLayout,
        val editText: TextInputEditText,
    )
}
