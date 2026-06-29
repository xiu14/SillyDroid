package com.jm.sillydroid.feature.settings.ui.storage

import android.graphics.Typeface
import android.text.format.DateFormat
import android.text.format.Formatter
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
import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.domain.backup.RemoteBackupRepository
import com.jm.sillydroid.domain.logs.HostLogRepository
import com.jm.sillydroid.domain.storage.StorageCleanupAction
import com.jm.sillydroid.domain.storage.StorageUsageItem
import com.jm.sillydroid.domain.storage.StorageUsageItemId
import com.jm.sillydroid.domain.storage.StorageUsageRepository
import com.jm.sillydroid.domain.storage.StorageUsageSnapshot
import com.jm.sillydroid.feature.settings.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootstrapSettingsStorageCoordinator(
    private val activity: AppCompatActivity,
    private val dispatchers: DispatcherProvider,
    private val container: LinearLayout,
    private val storageRepository: StorageUsageRepository,
    private val hostLogRepository: HostLogRepository,
    private val remoteBackupRepository: RemoteBackupRepository,
    private val setBusy: (Boolean) -> Unit,
    private val showError: (String) -> Unit,
    private val showMessage: (String) -> Unit,
    private val onClearBrowserDataRequested: () -> Unit,
) {
    private lateinit var refreshButton: MaterialButton
    private lateinit var scannedAtView: TextView
    private lateinit var emptyView: TextView
    private lateinit var listContainer: LinearLayout

    private var busy = false
    private var currentSnapshot: StorageUsageSnapshot? = null
    private val actionButtons = mutableListOf<MaterialButton>()

    fun initialize() {
        buildContent()
        render()
    }

    fun refresh() {
        if (busy) return
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching { storageRepository.snapshot() }
            }
            setBusyState(false)
            result.onSuccess { snapshot ->
                currentSnapshot = snapshot
                render()
            }.onFailure { error ->
                showError(activity.getString(R.string.bootstrap_settings_storage_clean_failed, formatFailure(error)))
            }
        }
    }

    private fun buildContent() {
        while (container.childCount > 1) {
            container.removeViewAt(1)
        }
        actionButtons.clear()

        addSectionTitle(R.string.bootstrap_settings_storage_section_overview)
        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimen(R.dimen.sillydroid_space_sm)
            }
        }
        container.addView(actionRow)
        refreshButton = MaterialButton(activity).apply {
            setText(R.string.bootstrap_settings_storage_refresh)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { refresh() }
        }
        actionRow.addView(refreshButton)

        scannedAtView = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm)
            )
        }
        createNestedCard().addView(scannedAtView)

        emptyView = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setText(R.string.bootstrap_settings_storage_empty)
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_space_sm)
            )
        }
        container.addView(emptyView)
        listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(listContainer)
    }

    private fun render() {
        val snapshot = currentSnapshot
        scannedAtView.text = snapshot?.let {
            activity.getString(
                R.string.bootstrap_settings_storage_scanned_at,
                DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(it.scannedAtEpochMillis)).toString()
            )
        }.orEmpty()
        emptyView.isVisible = snapshot == null || snapshot.items.isEmpty()
        listContainer.removeAllViews()
        actionButtons.clear()
        snapshot?.items.orEmpty().forEach { item ->
            listContainer.addView(createItemCard(item))
        }
        syncEnabledState()
    }

    private fun createItemCard(item: StorageUsageItem): MaterialCardView {
        val card = createDetachedCard()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding)
            )
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBlock.addView(TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsCardTitle)
            setText(item.titleRes())
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurface, 0))
            setTypeface(typeface, Typeface.BOLD)
        })
        titleBlock.addView(TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            text = Formatter.formatFileSize(activity, item.sizeBytes)
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorPrimary, 0))
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(titleBlock)

        val actionButton = createActionButton(item)
        if (actionButton != null) {
            header.addView(actionButton)
            actionButtons.add(actionButton)
        }
        content.addView(header)

        content.addView(TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setText(item.summaryRes())
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
            setPadding(0, dimen(R.dimen.sillydroid_space_xs_half), 0, 0)
        })

        if (item.path.isNotBlank()) {
            content.addView(TextView(activity).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
                text = item.path
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
                setPadding(0, dimen(R.dimen.sillydroid_space_xs), 0, 0)
            })
        }

        card.addView(content)
        return card
    }

    private fun createActionButton(item: StorageUsageItem): MaterialButton? {
        val labelRes = when (item.cleanupAction) {
            StorageCleanupAction.NONE -> return null
            StorageCleanupAction.CLEAR_BROWSER_DATA -> R.string.bootstrap_settings_storage_action_browser
            StorageCleanupAction.CLEAN_REMOTE_BACKUP_DUPLICATES -> R.string.bootstrap_settings_storage_action_backup
            else -> R.string.bootstrap_settings_storage_action_clean
        }
        return MaterialButton(activity).apply {
            setText(labelRes)
            minWidth = 0
            tag = item.sizeBytes > 0L
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dimen(R.dimen.sillydroid_space_sm)
            }
            isEnabled = item.sizeBytes > 0L
            setOnClickListener {
                handleCleanupAction(item)
            }
        }
    }

    private fun handleCleanupAction(item: StorageUsageItem) {
        if (busy) return
        if (item.cleanupAction == StorageCleanupAction.CLEAR_BROWSER_DATA) {
            onClearBrowserDataRequested()
            return
        }

        val message = activity.getString(
            R.string.bootstrap_settings_storage_clean_confirm_message,
            activity.getString(item.titleRes()),
            item.path.ifBlank { activity.getString(item.summaryRes()) }
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_storage_clean_confirm_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bootstrap_settings_storage_action_clean) { _, _ ->
                executeCleanup(item)
            }
            .show()
    }

    private fun executeCleanup(item: StorageUsageItem) {
        activity.lifecycleScope.launch {
            setBusyState(true)
            val result = withContext(dispatchers.io) {
                runCatching {
                    when (item.cleanupAction) {
                        StorageCleanupAction.CLEAR_LOGS -> {
                            val beforeBytes = item.sizeBytes
                            hostLogRepository.clearAllLogs()
                            beforeBytes
                        }

                        StorageCleanupAction.CLEAR_IMAGE_PROXY_CACHE -> storageRepository.clearImageProxyCache().freedBytes
                        StorageCleanupAction.CLEAR_TEMPORARY_FILES -> storageRepository.clearTemporaryFiles().freedBytes
                        StorageCleanupAction.CLEAN_REMOTE_BACKUP_DUPLICATES -> {
                            remoteBackupRepository.listBackups()
                            -1L
                        }

                        StorageCleanupAction.CLEAR_BROWSER_DATA,
                        StorageCleanupAction.NONE -> 0L
                    }
                }
            }
            setBusyState(false)
            result.onSuccess { freedBytes ->
                if (item.cleanupAction == StorageCleanupAction.CLEAN_REMOTE_BACKUP_DUPLICATES) {
                    showMessage(activity.getString(R.string.bootstrap_settings_storage_backup_cleanup_success))
                } else {
                    showMessage(
                        activity.getString(
                            R.string.bootstrap_settings_storage_clean_success,
                            Formatter.formatFileSize(activity, freedBytes.coerceAtLeast(0L))
                        )
                    )
                }
                refresh()
            }.onFailure { error ->
                val messageRes = if (item.cleanupAction == StorageCleanupAction.CLEAN_REMOTE_BACKUP_DUPLICATES && error.isConnectionFailure()) {
                    R.string.bootstrap_settings_storage_backup_cleanup_unavailable
                } else {
                    R.string.bootstrap_settings_storage_clean_failed
                }
                if (messageRes == R.string.bootstrap_settings_storage_backup_cleanup_unavailable) {
                    showError(activity.getString(messageRes))
                } else {
                    showError(activity.getString(messageRes, formatFailure(error)))
                }
            }
        }
    }

    private fun setBusyState(value: Boolean) {
        busy = value
        setBusy(value)
        syncEnabledState()
    }

    private fun syncEnabledState() {
        if (!this::refreshButton.isInitialized) return
        refreshButton.isEnabled = !busy
        actionButtons.forEach { button ->
            button.isEnabled = !busy && button.tag == true
        }
    }

    private fun addSectionTitle(titleRes: Int) {
        container.addView(
            TextView(activity).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsSectionTitle)
                setText(titleRes)
                setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurface, 0))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dimen(R.dimen.sillydroid_space_lg)
                }
            }
        )
    }

    private fun createNestedCard(): MaterialCardView {
        return createDetachedCard().also(container::addView)
    }

    private fun createDetachedCard(): MaterialCardView {
        val cardRadius = activity.resources.getDimension(R.dimen.sillydroid_card_radius)
        return MaterialCardView(activity).apply {
            radius = cardRadius
            cardElevation = 0f
            setCardBackgroundColor(MaterialColors.getColor(activity, MaterialR.attr.colorSurfaceContainer, 0))
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimen(R.dimen.sillydroid_space_sm)
            }
        }
    }

    private fun StorageUsageItem.titleRes(): Int = when (id) {
        StorageUsageItemId.APP_PRIVATE_DATA -> R.string.bootstrap_settings_storage_item_app_private_data_title
        StorageUsageItemId.TAVERN_USER_DATA -> R.string.bootstrap_settings_storage_item_tavern_user_data_title
        StorageUsageItemId.CHAT_HISTORY -> R.string.bootstrap_settings_storage_item_chat_history_title
        StorageUsageItemId.CHARACTERS -> R.string.bootstrap_settings_storage_item_characters_title
        StorageUsageItemId.AVATARS_AND_BACKGROUNDS -> R.string.bootstrap_settings_storage_item_avatars_backgrounds_title
        StorageUsageItemId.WORLDS -> R.string.bootstrap_settings_storage_item_worlds_title
        StorageUsageItemId.EXTENSIONS_AND_PLUGINS -> R.string.bootstrap_settings_storage_item_extensions_plugins_title
        StorageUsageItemId.SERVER_RUNTIME -> R.string.bootstrap_settings_storage_item_server_runtime_title
        StorageUsageItemId.HOST_RUNTIME -> R.string.bootstrap_settings_storage_item_host_runtime_title
        StorageUsageItemId.LOGS -> R.string.bootstrap_settings_storage_item_logs_title
        StorageUsageItemId.WEBVIEW_DATA -> R.string.bootstrap_settings_storage_item_webview_data_title
        StorageUsageItemId.IMAGE_PROXY_CACHE -> R.string.bootstrap_settings_storage_item_image_proxy_cache_title
        StorageUsageItemId.TEMPORARY_FILES -> R.string.bootstrap_settings_storage_item_temporary_files_title
        StorageUsageItemId.LOCAL_BACKUPS -> R.string.bootstrap_settings_storage_item_local_backups_title
    }

    private fun StorageUsageItem.summaryRes(): Int = when (id) {
        StorageUsageItemId.APP_PRIVATE_DATA -> R.string.bootstrap_settings_storage_item_app_private_data_summary
        StorageUsageItemId.TAVERN_USER_DATA -> R.string.bootstrap_settings_storage_item_tavern_user_data_summary
        StorageUsageItemId.CHAT_HISTORY -> R.string.bootstrap_settings_storage_item_chat_history_summary
        StorageUsageItemId.CHARACTERS -> R.string.bootstrap_settings_storage_item_characters_summary
        StorageUsageItemId.AVATARS_AND_BACKGROUNDS -> R.string.bootstrap_settings_storage_item_avatars_backgrounds_summary
        StorageUsageItemId.WORLDS -> R.string.bootstrap_settings_storage_item_worlds_summary
        StorageUsageItemId.EXTENSIONS_AND_PLUGINS -> R.string.bootstrap_settings_storage_item_extensions_plugins_summary
        StorageUsageItemId.SERVER_RUNTIME -> R.string.bootstrap_settings_storage_item_server_runtime_summary
        StorageUsageItemId.HOST_RUNTIME -> R.string.bootstrap_settings_storage_item_host_runtime_summary
        StorageUsageItemId.LOGS -> R.string.bootstrap_settings_storage_item_logs_summary
        StorageUsageItemId.WEBVIEW_DATA -> R.string.bootstrap_settings_storage_item_webview_data_summary
        StorageUsageItemId.IMAGE_PROXY_CACHE -> R.string.bootstrap_settings_storage_item_image_proxy_cache_summary
        StorageUsageItemId.TEMPORARY_FILES -> R.string.bootstrap_settings_storage_item_temporary_files_summary
        StorageUsageItemId.LOCAL_BACKUPS -> R.string.bootstrap_settings_storage_item_local_backups_summary
    }

    private fun Throwable.isConnectionFailure(): Boolean {
        return this is ConnectException || this is SocketTimeoutException
    }

    private fun formatFailure(error: Throwable): String {
        return error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    private fun dimen(resId: Int): Int = activity.resources.getDimensionPixelSize(resId)
}
