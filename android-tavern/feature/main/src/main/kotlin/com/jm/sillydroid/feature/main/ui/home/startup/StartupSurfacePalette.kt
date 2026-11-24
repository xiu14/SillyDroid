package com.jm.sillydroid.feature.main.ui.home.startup

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.jm.sillydroid.feature.main.R

/**
 * 启动画面跟着酒馆主题走：页面套上主题后，tavern-native-bridge 上报 --SmartThemeBlurTintColor（加载画面和 body 的底色），
 * 这里存下来；下次启动时，启动遮罩、浏览器占位底色和系统栏都用它，文字按底色深浅取日间或夜间那套颜色。
 * 还没上报过时退回资源里按系统深浅色定义的默认值。
 */
internal object StartupThemeColorStore {
    private const val PREFERENCES_NAME = "sillydroid_startup_surface"
    private const val KEY_BACKGROUND = "tavern_theme_background"

    @ColorInt
    fun read(context: Context): Int? {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (!preferences.contains(KEY_BACKGROUND)) {
            return null
        }
        return preferences.getInt(KEY_BACKGROUND, 0) or OPAQUE_ALPHA
    }

    /** 颜色没变就不写，返回是否有变化。 */
    fun write(context: Context, @ColorInt color: Int): Boolean {
        val opaque = color or OPAQUE_ALPHA
        if (read(context) == opaque) {
            return false
        }
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BACKGROUND, opaque)
            .apply()
        return true
    }
}

internal data class StartupSurfacePalette(
    @ColorInt val background: Int,
    @ColorInt val title: Int,
    @ColorInt val body: Int,
    @ColorInt val secondaryText: Int,
    @ColorInt val iconTint: Int,
    @ColorInt val iconButtonBackground: Int,
    @ColorInt val iconButtonStroke: Int
)

internal object StartupSurfaceColors {

    /** 浏览器占位底色：有上报过的主题底色就用它。 */
    @ColorInt
    fun placeholderBackground(context: Context): Int {
        return StartupThemeColorStore.read(context)
            ?: ContextCompat.getColor(context, R.color.tavern_webview_background)
    }

    /** 启动遮罩配色；还没上报过主题底色时返回 null，保持布局里的默认配色。 */
    fun overlayPalette(context: Context): StartupSurfacePalette? {
        val background = StartupThemeColorStore.read(context) ?: return null
        val light = isLightSurface(background)
        val textContext = contextForNightMode(context, night = !light)
        val title = ContextCompat.getColor(textContext, R.color.bootstrap_overlay_title)
        return StartupSurfacePalette(
            background = background,
            title = title,
            body = ContextCompat.getColor(textContext, R.color.bootstrap_overlay_body),
            secondaryText = ContextCompat.getColor(textContext, R.color.bootstrap_overlay_secondary_text),
            iconTint = ContextCompat.getColor(textContext, R.color.bootstrap_overlay_icon_tint),
            iconButtonBackground = blendColors(background, WHITE, if (light) 0.35f else 0.08f),
            iconButtonStroke = blendColors(background, title, 0.15f)
        )
    }

    fun applyToOverlay(root: View, palette: StartupSurfacePalette) {
        root.findViewById<View>(R.id.bootstrapOverlay)?.setBackgroundColor(palette.background)
        root.findViewById<TextView>(R.id.bootstrapTitle)?.setTextColor(palette.title)
        root.findViewById<TextView>(R.id.bootstrapStatus)?.setTextColor(palette.body)
        root.findViewById<TextView>(R.id.bootstrapProgressLabel)?.setTextColor(palette.secondaryText)
        root.findViewById<TextView>(R.id.bootstrapUpdateHint)?.let { hint ->
            hint.setTextColor(palette.body)
            hint.background = iconButtonBackground(root.context, palette)
        }
        listOf(R.id.bootstrapUpdateButton, R.id.bootstrapSettingsButton).forEach { id ->
            root.findViewById<ImageButton>(id)?.let { button ->
                button.background = iconButtonBackground(root.context, palette)
                button.imageTintList = ColorStateList.valueOf(palette.iconTint)
            }
        }
    }

    private fun iconButtonBackground(context: Context, palette: StartupSurfacePalette): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(palette.iconButtonBackground)
            setStroke(maxOf(1, (1f * density).toInt()), palette.iconButtonStroke)
        }
    }

    private fun contextForNightMode(context: Context, night: Boolean): Context {
        val configuration = Configuration(context.resources.configuration)
        val nightFlag = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightFlag
        return context.createConfigurationContext(configuration)
    }
}

private const val OPAQUE_ALPHA = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private val opaqueHexColorPattern = Regex("^#[0-9a-fA-F]{6}$")

/** 解析页面上报的 #RRGGBB；格式不对返回 null。 */
@ColorInt
internal fun parseOpaqueHexColor(value: String?): Int? {
    val trimmed = value?.trim() ?: return null
    if (!opaqueHexColorPattern.matches(trimmed)) {
        return null
    }
    return trimmed.substring(1).toInt(16) or OPAQUE_ALPHA
}

/** 按 WCAG 相对亮度判断浅色底：浅色底配深色字。 */
internal fun isLightSurface(@ColorInt color: Int): Boolean {
    fun channel(shift: Int): Double {
        val value = ((color shr shift) and 0xFF) / 255.0
        return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    }
    val luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    return luminance > 0.4
}

@ColorInt
internal fun blendColors(@ColorInt base: Int, @ColorInt overlay: Int, ratio: Float): Int {
    val clamped = ratio.coerceIn(0f, 1f)
    fun mix(shift: Int): Int {
        val from = (base shr shift) and 0xFF
        val to = (overlay shr shift) and 0xFF
        return (from + (to - from) * clamped).toInt().coerceIn(0, 255)
    }
    return OPAQUE_ALPHA or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}
