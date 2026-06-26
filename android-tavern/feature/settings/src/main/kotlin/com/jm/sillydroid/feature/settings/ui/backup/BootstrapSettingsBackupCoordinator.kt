package com.jm.sillydroid.feature.settings.ui.backup

import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.domain.backup.RemoteBackupConfig
import com.jm.sillydroid.domain.backup.RemoteBackupConfigDraft
import com.jm.sillydroid.domain.backup.RemoteBackupHealth
import com.jm.sillydroid.domain.backup.RemoteBackupItem
import com.jm.sillydroid.domain.backup.RemoteBackupList
import com.jm.sillydroid.domain.backup.RemoteBackupRepository
import com.jm.sillydroid.domain.settings.TavernShellSettingsRepository
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.ui.applySettingsEndIconStyle
import com.jm.sillydroid.feature.settings.ui.createSettingsEditText
import com.jm.sillydroid.feature.settings.ui.createSettingsTextInputLayout
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootstrapSettingsBackupCoordinator(
    private val activity: AppCompatActivity,
    private val dispatchers: DispatcherProvider,
    private val container: LinearLayout,
    private val backupRepository: RemoteBackupRepository,
    private val settingsRepository: TavernShellSettingsRepository,
    private val setBusy: (Boolean) -> Unit,
    private val showError: (String) -> Unit,
    private val showMessage: (String) -> Unit,
    private val onBootstrapRestartRequired: () -> Unit,
) {
    private lateinit var autoBackupSwitch: MaterialSwitch
    private lateinit var statusView: TextView
    private lateinit var backupNowButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var r2SettingsButton: MaterialButton
    private lateinit var clearLogsButton: MaterialButton
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyListView: TextView
    private lateinit var logsView: TextView

    private var busy = false
    private var currentHealth: RemoteBackupHealth? = null
    private var currentConfig: RemoteBackupConfig? = null
    private var currentBackups: List<RemoteBackupItem> = emptyList()
    private var currentWarning: String = ""
    private var currentLogs: List<String> = emptyList()
    private var lastRefreshError: Throwable? = null

    fun initialize() {
        buildContent()
        renderAll()
    }

    fun refresh() {
        refresh(showFailure = false)
    }

    private fun buildContent() {
        while (container.childCount > 1) {
            container.removeViewAt(1)
        }

        addSectionTitle(R.string.bootstrap_settings_remote_backup_section_auto)
        autoBackupSwitch = addSwitchRow(
            titleRes = R.string.bootstrap_settings_remote_backup_auto_title,
            summaryRes = R.string.bootstrap_settings_remote_backup_auto_summary
        )
        autoBackupSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.remoteBackupAutoEnabled = isChecked
            renderStatus()
        }

        statusView = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm)
            )
        }
        createNestedCard().addContentView(statusView)

        addSectionTitle(R.string.bootstrap_settings_remote_backup_section_actions)
        addActionRows()

        addSectionTitle(R.string.bootstrap_settings_remote_backup_section_list)
        emptyListView = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setText(R.string.bootstrap_settings_remote_backup_empty)
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm)
            )
        }
        container.addView(emptyListView)
        listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(listContainer)

        addSectionTitle(R.string.bootstrap_settings_remote_backup_section_logs)
        logsView = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding)
            )
        }
        createNestedCard().addContentView(logsView)

        backupNowButton.setOnClickListener { createBackup() }
        refreshButton.setOnClickListener { refresh(showFailure = true) }
        r2SettingsButton.setOnClickListener { showR2ConfigDialog() }
        clearLogsButton.setOnClickListener { clearBackupLogs() }
    }

    private fun addActionRows() {
        val rowOne = createButtonRow()
        backupNowButton = addActionButton(rowOne, R.string.bootstrap_settings_remote_backup_now, weight = 1f)
        refreshButton = addActionButton(rowOne, R.string.bootstrap_settings_remote_backup_refresh, weight = 1f, addStartMargin = true)

        val rowTwo = createButtonRow().apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dimen(R.dimen.sillydroid_space_xs)
            }
        }
        r2SettingsButton = addActionButton(rowTwo, R.string.bootstrap_settings_remote_backup_r2_settings, weight = 1f)
        clearLogsButton = addActionButton(rowTwo, R.string.bootstrap_settings_remote_backup_clear_logs, weight = 1f, addStartMargin = true)
    }

    private fun refresh(showFailure: Boolean) {
        if (busy) {
            return
        }

        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    BackupState(
                        health = backupRepository.health(),
                        config = backupRepository.config(),
                        list = backupRepository.listBackups(),
                        logs = backupRepository.logs()
                    )
                }
            }
            setBusyState(false)

            result.onSuccess { state ->
                currentHealth = state.health
                currentConfig = state.config
                currentBackups = state.list.items
                currentWarning = state.list.warning
                currentLogs = state.logs
                lastRefreshError = null
                renderAll()
            }.onFailure { error ->
                currentHealth = null
                currentConfig = null
                currentBackups = emptyList()
                currentWarning = ""
                currentLogs = emptyList()
                lastRefreshError = error
                renderAll()
                if (showFailure) {
                    showError(activity.getString(R.string.bootstrap_settings_remote_backup_failed, formatFailure(error)))
                }
            }
        }
    }

    private fun createBackup() {
        if (busy) {
            return
        }

        showMessage(activity.getString(R.string.bootstrap_settings_remote_backup_started))
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching { backupRepository.createBackup() }
            }
            setBusyState(false)

            result.onSuccess { backup ->
                val fileName = backup.fileName.ifBlank {
                    activity.getString(R.string.bootstrap_settings_remote_backup_success_no_file)
                }
                if (backup.warning.isNotBlank()) {
                    showMessage(
                        activity.getString(
                            R.string.bootstrap_settings_remote_backup_warning,
                            backup.fileName.ifBlank { "-" },
                            backup.warning.compactForMessage()
                        )
                    )
                } else if (backup.fileName.isBlank()) {
                    showMessage(fileName)
                } else {
                    showMessage(activity.getString(R.string.bootstrap_settings_remote_backup_success, backup.fileName))
                }
                refresh(showFailure = false)
            }.onFailure { error ->
                showError(activity.getString(R.string.bootstrap_settings_remote_backup_failed, formatFailure(error)))
            }
        }
    }

    private fun confirmRestore(item: RemoteBackupItem) {
        if (busy) {
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_remote_backup_restore_confirm_title)
            .setMessage(
                "${item.name}\n\n${activity.getString(R.string.bootstrap_settings_remote_backup_restore_confirm_message)}"
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_remote_backup_restore) { _, _ ->
                restoreBackup(item.name)
            }
            .show()
    }

    private fun restoreBackup(name: String) {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching { backupRepository.restoreBackup(name) }
            }
            setBusyState(false)

            result.onSuccess {
                showMessage(activity.getString(R.string.bootstrap_settings_remote_backup_restore_success))
                onBootstrapRestartRequired()
            }.onFailure { error ->
                showError(activity.getString(R.string.bootstrap_settings_remote_backup_restore_failed, formatFailure(error)))
            }
        }
    }

    private fun confirmDelete(item: RemoteBackupItem) {
        if (busy) {
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_remote_backup_delete_confirm_title)
            .setMessage(
                "${item.name}\n\n${activity.getString(R.string.bootstrap_settings_remote_backup_delete_confirm_message)}"
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_remote_backup_delete) { _, _ ->
                deleteBackup(item.name)
            }
            .show()
    }

    private fun deleteBackup(name: String) {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching { backupRepository.deleteBackup(name) }
            }
            setBusyState(false)

            result.onSuccess {
                showMessage(activity.getString(R.string.bootstrap_settings_remote_backup_delete_success))
                refresh(showFailure = false)
            }.onFailure { error ->
                showError(activity.getString(R.string.bootstrap_settings_remote_backup_delete_failed, formatFailure(error)))
            }
        }
    }

    private fun clearBackupLogs() {
        if (busy) {
            return
        }

        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching { backupRepository.clearLogs() }
            }
            setBusyState(false)

            result.onSuccess {
                currentLogs = emptyList()
                renderLogs()
                showMessage(activity.getString(R.string.bootstrap_settings_remote_backup_logs_cleared))
            }.onFailure { error ->
                showError(activity.getString(R.string.bootstrap_settings_remote_backup_failed, formatFailure(error)))
            }
        }
    }

    private fun showR2ConfigDialog() {
        if (busy) {
            return
        }

        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching { backupRepository.config() }
            }
            setBusyState(false)

            result.onSuccess(::showR2ConfigDialog)
                .onFailure { error ->
                    showError(activity.getString(R.string.bootstrap_settings_remote_backup_failed, formatFailure(error)))
                }
        }
    }

    private fun showR2ConfigDialog(config: RemoteBackupConfig) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm),
                dimen(R.dimen.sillydroid_panel_padding),
                0
            )
        }
        val accountInput = addDialogInput(
            root = root,
            hintRes = R.string.bootstrap_settings_remote_backup_r2_account,
            initialValue = config.r2AccountId,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        val bucketInput = addDialogInput(
            root = root,
            hintRes = R.string.bootstrap_settings_remote_backup_r2_bucket,
            initialValue = config.r2Bucket,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        val accessKeyInput = addDialogInput(
            root = root,
            hintRes = R.string.bootstrap_settings_remote_backup_r2_access_key,
            initialValue = config.r2AccessKeyId,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        val secretInput = addDialogInput(
            root = root,
            hintRes = R.string.bootstrap_settings_remote_backup_r2_secret,
            initialValue = "",
            helperRes = R.string.bootstrap_settings_remote_backup_r2_secret_helper,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        )
        val prefixInput = addDialogInput(
            root = root,
            hintRes = R.string.bootstrap_settings_remote_backup_r2_prefix,
            initialValue = config.r2Prefix,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_remote_backup_r2_dialog_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.bootstrap_settings_remote_backup_r2_clear, null)
            .setPositiveButton(R.string.bootstrap_settings_remote_backup_r2_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                saveR2Config(
                    RemoteBackupConfigDraft(
                        r2AccountId = accountInput.text?.toString().orEmpty().trim(),
                        r2Bucket = bucketInput.text?.toString().orEmpty().trim(),
                        r2AccessKeyId = accessKeyInput.text?.toString().orEmpty().trim(),
                        r2SecretAccessKey = secretInput.text?.toString().orEmpty().trim().ifBlank { null },
                        r2Prefix = prefixInput.text?.toString().orEmpty().trim()
                    )
                )
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                confirmClearR2Config()
            }
        }
        dialog.show()
    }

    private fun saveR2Config(draft: RemoteBackupConfigDraft) {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching { backupRepository.saveConfig(draft) }
            }
            setBusyState(false)

            result.onSuccess {
                showMessage(activity.getString(R.string.bootstrap_settings_remote_backup_r2_saved))
                refresh(showFailure = false)
            }.onFailure { error ->
                showError(activity.getString(R.string.bootstrap_settings_remote_backup_failed, formatFailure(error)))
            }
        }
    }

    private fun confirmClearR2Config() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_remote_backup_r2_clear_confirm_title)
            .setMessage(R.string.bootstrap_settings_remote_backup_r2_clear_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_remote_backup_r2_clear) { _, _ ->
                clearR2Config()
            }
            .show()
    }

    private fun clearR2Config() {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    backupRepository.saveConfig(RemoteBackupConfigDraft(clearR2Config = true))
                }
            }
            setBusyState(false)

            result.onSuccess {
                showMessage(activity.getString(R.string.bootstrap_settings_remote_backup_r2_cleared))
                refresh(showFailure = false)
            }.onFailure { error ->
                showError(activity.getString(R.string.bootstrap_settings_remote_backup_failed, formatFailure(error)))
            }
        }
    }

    private fun renderAll() {
        if (this::autoBackupSwitch.isInitialized) {
            autoBackupSwitch.isChecked = settingsRepository.remoteBackupAutoEnabled
        }
        renderStatus()
        renderBackupItems()
        renderLogs()
        syncEnabledState()
    }

    private fun renderStatus() {
        if (!this::statusView.isInitialized) return
        val health = currentHealth
        val config = currentConfig
        val statusText = if (health == null) {
            val reason = lastRefreshError?.let(::formatFailure).orEmpty()
            buildString {
                append(activity.getString(R.string.bootstrap_settings_remote_backup_unavailable))
                if (reason.isNotBlank()) {
                    append('\n')
                    append(reason)
                }
            }
        } else {
            buildString {
                append(
                    activity.getString(
                        R.string.bootstrap_settings_remote_backup_status,
                        activity.getString(R.string.bootstrap_settings_remote_backup_connected),
                        health.dataDir.ifBlank { "-" },
                        health.backupDir.ifBlank { "-" },
                        if (config?.r2Configured == true || health.r2Configured) {
                            activity.getString(R.string.bootstrap_settings_remote_backup_r2_configured)
                        } else {
                            activity.getString(R.string.bootstrap_settings_remote_backup_r2_not_configured)
                        },
                        if (settingsRepository.remoteBackupAutoEnabled) {
                            activity.getString(R.string.bootstrap_settings_remote_backup_enabled)
                        } else {
                            activity.getString(R.string.bootstrap_settings_remote_backup_disabled)
                        },
                        formatLastAutoBackup()
                    )
                )
                if (currentWarning.isNotBlank()) {
                    append("\nR2：")
                    append(currentWarning.compactForMessage())
                }
            }
        }
        statusView.text = statusText
    }

    private fun renderBackupItems() {
        if (!this::listContainer.isInitialized) return
        listContainer.removeAllViews()
        emptyListView.isVisible = currentBackups.isEmpty()
        currentBackups.forEach { item ->
            listContainer.addView(createBackupItemCard(item))
        }
    }

    private fun createBackupItemCard(item: RemoteBackupItem): MaterialCardView {
        val card = createDetachedNestedCard()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm)
            )
        }
        content.addView(
            TextView(activity).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsCardTitle)
                text = item.name
                setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurface, 0))
            }
        )
        content.addView(
            TextView(activity).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
                text = activity.getString(
                    R.string.bootstrap_settings_remote_backup_item_meta,
                    formatSize(item.sizeBytes),
                    formatBackupTime(item.modifiedAt),
                    formatLocation(item)
                )
                setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dimen(R.dimen.sillydroid_space_xs_half)
                }
            }
        )

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dimen(R.dimen.sillydroid_space_sm)
            }
        }
        buttonRow.addView(
            createSmallButton(R.string.bootstrap_settings_remote_backup_restore).apply {
                isEnabled = !busy
                setOnClickListener { confirmRestore(item) }
            }
        )
        buttonRow.addView(
            createSmallButton(R.string.bootstrap_settings_remote_backup_delete).apply {
                isEnabled = !busy
                setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorError, 0))
                strokeColor = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(activity, MaterialR.attr.colorError, 0)
                )
                setOnClickListener { confirmDelete(item) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dimen(R.dimen.sillydroid_space_sm)
                }
            }
        )
        content.addView(buttonRow)
        card.addView(content)
        return card
    }

    private fun renderLogs() {
        if (!this::logsView.isInitialized) return
        logsView.text = currentLogs
            .joinToString(separator = "\n")
            .ifBlank { activity.getString(R.string.bootstrap_settings_remote_backup_logs_empty) }
    }

    private fun setBusyState(value: Boolean) {
        busy = value
        setBusy(value)
        syncEnabledState()
        renderBackupItems()
    }

    private fun syncEnabledState() {
        if (!this::autoBackupSwitch.isInitialized) return
        autoBackupSwitch.isEnabled = !busy
        backupNowButton.isEnabled = !busy
        refreshButton.isEnabled = !busy
        r2SettingsButton.isEnabled = !busy
        clearLogsButton.isEnabled = !busy
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
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding)
            )
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

    private fun createButtonRow(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            container.addView(this)
        }
    }

    private fun addActionButton(
        row: LinearLayout,
        textRes: Int,
        weight: Float,
        addStartMargin: Boolean = false,
    ): MaterialButton {
        return MaterialButton(activity, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
            setText(textRes)
            minHeight = dimen(R.dimen.sillydroid_control_min_height)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                if (addStartMargin) {
                    leftMargin = dimen(R.dimen.sillydroid_space_sm)
                }
            }
            row.addView(this)
        }
    }

    private fun createSmallButton(textRes: Int): MaterialButton {
        return MaterialButton(activity, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
            setText(textRes)
            minHeight = dimen(R.dimen.sillydroid_control_min_height)
            minWidth = 0
        }
    }

    private fun addDialogInput(
        root: LinearLayout,
        hintRes: Int,
        initialValue: String,
        inputType: Int,
        helperRes: Int? = null,
        endIconMode: Int = TextInputLayout.END_ICON_NONE,
    ): TextInputEditText {
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
            setSingleLine(true)
            setText(initialValue)
        }
        layout.addView(editText)
        if (endIconMode != TextInputLayout.END_ICON_NONE) {
            layout.applySettingsEndIconStyle()
        }
        root.addView(layout)
        return editText
    }

    private fun createNestedCard(): MaterialCardView {
        return createDetachedNestedCard().also(container::addView)
    }

    private fun createDetachedNestedCard(): MaterialCardView {
        return MaterialCardView(activity).apply {
            radius = activity.resources.getDimension(R.dimen.sillydroid_nested_card_radius)
            cardElevation = 0f
            strokeWidth = 1
            strokeColor = MaterialColors.getColor(activity, MaterialR.attr.colorOutlineVariant, 0)
            setCardBackgroundColor(MaterialColors.getColor(activity, MaterialR.attr.colorSurfaceContainerLow, 0))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dimen(R.dimen.sillydroid_space_md)
            }
        }
    }

    private fun MaterialCardView.addContentView(view: android.view.View) {
        addView(view)
    }

    private fun formatLastAutoBackup(): String {
        val lastAt = settingsRepository.remoteBackupLastAutoAt
        if (lastAt <= 0L) {
            return activity.getString(R.string.bootstrap_settings_remote_backup_last_never)
        }
        val time = DateFormat.format("MM-dd HH:mm", lastAt).toString()
        val file = settingsRepository.remoteBackupLastAutoFile
        return if (file.isBlank()) time else "$time · $file"
    }

    private fun formatBackupTime(value: String): String {
        if (value.isBlank()) return "-"
        return runCatching {
            DateFormat.format("MM-dd HH:mm", Instant.parse(value).toEpochMilli()).toString()
        }.getOrDefault(value.replace('T', ' ').take(16))
    }

    private fun formatLocation(item: RemoteBackupItem): String {
        return when {
            item.local && item.remote -> activity.getString(R.string.bootstrap_settings_remote_backup_location_both)
            item.remote -> activity.getString(R.string.bootstrap_settings_remote_backup_location_remote)
            else -> activity.getString(R.string.bootstrap_settings_remote_backup_location_local)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "-"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${bytes}B"
        } else {
            String.format(Locale.US, "%.1f%s", value, units[unitIndex])
        }
    }

    private fun formatFailure(error: Throwable): String {
        return when (error) {
            is ConnectException -> activity.getString(R.string.bootstrap_settings_remote_backup_unavailable)
            is SocketTimeoutException -> "请求超时"
            else -> error.message?.compactForMessage()?.ifBlank { null } ?: "未知错误"
        }
    }

    private fun String.compactForMessage(): String {
        return replace('\n', ' ').replace('\r', ' ').trim().take(240)
    }

    private fun dimen(resId: Int): Int {
        return activity.resources.getDimensionPixelSize(resId)
    }

    private data class BackupState(
        val health: RemoteBackupHealth,
        val config: RemoteBackupConfig,
        val list: RemoteBackupList,
        val logs: List<String>,
    )
}
