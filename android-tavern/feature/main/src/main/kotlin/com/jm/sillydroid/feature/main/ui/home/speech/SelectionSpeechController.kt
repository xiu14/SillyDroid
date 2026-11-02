package com.jm.sillydroid.feature.main.ui.home.speech

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jm.sillydroid.feature.main.R
import com.google.android.material.R as MaterialR

/**
 * 长按选字菜单里「朗读」的宿主侧逻辑：读写 Breeze 配置、首次使用时弹配置、朗读期间显示可点停止的浮条。
 *
 * API Key 只存在 App 私有的 SharedPreferences 里，不进 Tavern 数据目录，备份和远程编辑都碰不到。
 */
class SelectionSpeechController(private val activity: AppCompatActivity) {
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var statusChip: TextView? = null
    private val player = BreezeSpeechPlayer(
        context = activity.applicationContext,
        scope = activity.lifecycleScope,
        onSpeakingChanged = ::showStatusChip,
        onError = { message ->
            Toast.makeText(activity, activity.getString(R.string.selection_speech_failed, message), Toast.LENGTH_LONG).show()
        }
    )

    private val config: BreezeVoiceConfig?
        get() {
            val apiKey = prefs.getString(KEY_API_KEY, "").orEmpty()
            val voiceId = prefs.getString(KEY_VOICE_ID, "").orEmpty()
            return if (apiKey.isBlank() || voiceId.isBlank()) null else BreezeVoiceConfig(apiKey, voiceId)
        }

    fun speak(rawText: String) {
        val text = speakableText(rawText)
        if (text.isBlank()) {
            Toast.makeText(activity, R.string.selection_speech_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val currentConfig = config ?: run {
            showSettingsDialog(onSaved = { speak(rawText) })
            return
        }
        if (text.length > MAX_SPEECH_CHARS) {
            Toast.makeText(activity, activity.getString(R.string.selection_speech_truncated, MAX_SPEECH_CHARS), Toast.LENGTH_SHORT).show()
        }
        player.speak(currentConfig, splitForSpeech(text.take(MAX_SPEECH_CHARS)))
    }

    fun stop() {
        player.stop()
    }

    fun release() {
        player.stop()
        statusChip?.let { chip -> (chip.parent as? ViewGroup)?.removeView(chip) }
        statusChip = null
    }

    fun showSettingsDialog(onSaved: (() -> Unit)? = null) {
        val padding = dp(20)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(8), padding, 0)
        }
        root.addView(TextView(activity).apply {
            setText(R.string.selection_speech_settings_summary)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
        })
        val hasKey = prefs.getString(KEY_API_KEY, "").orEmpty().isNotBlank()
        val keyInput = addInput(
            root,
            hint = activity.getString(R.string.selection_speech_api_key),
            helper = activity.getString(if (hasKey) R.string.selection_speech_api_key_saved else R.string.selection_speech_api_key_helper),
            initial = "",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        )
        val voiceInput = addInput(
            root,
            hint = activity.getString(R.string.selection_speech_voice_id),
            helper = activity.getString(R.string.selection_speech_voice_id_helper),
            initial = prefs.getString(KEY_VOICE_ID, "").orEmpty().ifBlank { DEFAULT_VOICE_ID },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.selection_speech_settings_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.selection_speech_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val apiKey = keyInput.text?.toString().orEmpty().trim()
                val voiceId = voiceInput.text?.toString().orEmpty().trim()
                if (apiKey.isBlank() && !hasKey) {
                    keyInput.error = activity.getString(R.string.selection_speech_required)
                    return@setOnClickListener
                }
                if (voiceId.isBlank()) {
                    voiceInput.error = activity.getString(R.string.selection_speech_required)
                    return@setOnClickListener
                }
                prefs.edit().apply {
                    if (apiKey.isNotBlank()) putString(KEY_API_KEY, apiKey)
                    putString(KEY_VOICE_ID, voiceId)
                }.apply()
                dialog.dismiss()
                onSaved?.invoke()
            }
        }
        dialog.show()
    }

    private fun addInput(
        root: LinearLayout,
        hint: String,
        helper: String,
        initial: String,
        inputType: Int,
        endIconMode: Int = TextInputLayout.END_ICON_NONE
    ): TextInputEditText {
        val layout = TextInputLayout(activity, null, MaterialR.attr.textInputStyle).apply {
            this.hint = hint
            helperText = helper
            this.endIconMode = endIconMode
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val editText = TextInputEditText(layout.context, null, android.R.attr.editTextStyle).apply {
            this.inputType = inputType
            setSingleLine(true)
            setText(initial)
        }
        layout.addView(editText)
        root.addView(layout)
        return editText
    }

    /** 朗读期间在底部显示浮条：点一下停止，长按改配置。挂在 decorView 上，不影响页面布局。 */
    private fun showStatusChip(speaking: Boolean) {
        if (!speaking) {
            statusChip?.visibility = android.view.View.GONE
            return
        }
        val chip = statusChip ?: TextView(activity).apply {
            setText(R.string.selection_speech_playing)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xE6303644.toInt())
            }
            elevation = dp(6).toFloat()
            setOnClickListener { player.stop() }
            setOnLongClickListener {
                showSettingsDialog()
                true
            }
            (activity.window.decorView as ViewGroup).addView(
                this,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dp(140) }
            )
            statusChip = this
        }
        chip.visibility = android.view.View.VISIBLE
        chip.bringToFront()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
    }

    private companion object {
        const val PREFS_NAME = "selection_speech"
        const val KEY_API_KEY = "breeze_api_key"
        const val KEY_VOICE_ID = "breeze_voice_id"
        // clawd 用的克隆声音（BreezeBlue 账号里的 vocals-no-bgm）。
        const val DEFAULT_VOICE_ID = "voc_2sw4r2rafxfv"
    }
}
