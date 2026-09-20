package com.example.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

enum class AppTheme(
    val id: String,
    val title: String,
    val subtitle: String,
    val accentColor: Int,
    val accentGlowColor: Int,
    val cardBgColor: Int,
    val surfaceColor: Int,
    val isOled: Boolean = false
) {
    VLC_ORANGE(
        id = "vlc_orange",
        title = "VLC Dynamic Orange",
        subtitle = "Iconic Cone Orange & Deep Charcoal",
        accentColor = Color.parseColor("#FF8800"),
        accentGlowColor = Color.parseColor("#FFA726"),
        cardBgColor = Color.parseColor("#14171E"),
        surfaceColor = Color.parseColor("#0C0E12")
    ),
    CYBER_CYAN(
        id = "cyber_cyan",
        title = "Cyber Cyan (MX Style)",
        subtitle = "Electric Neon Cyan & Midnight Blue",
        accentColor = Color.parseColor("#00E5FF"),
        accentGlowColor = Color.parseColor("#80D8FF"),
        cardBgColor = Color.parseColor("#0D1522"),
        surfaceColor = Color.parseColor("#060A13")
    ),
    SPOTIFY_GREEN(
        id = "spotify_green",
        title = "Spotify Emerald",
        subtitle = "Vibrant Studio Green & Forest Black",
        accentColor = Color.parseColor("#1DB954"),
        accentGlowColor = Color.parseColor("#1ED760"),
        cardBgColor = Color.parseColor("#0F1A12"),
        surfaceColor = Color.parseColor("#070E09")
    ),
    NEBULA_PURPLE(
        id = "nebula_purple",
        title = "Nebula Violet",
        subtitle = "Cosmic Purple & Deep Galaxy",
        accentColor = Color.parseColor("#A855F7"),
        accentGlowColor = Color.parseColor("#C084FC"),
        cardBgColor = Color.parseColor("#170F24"),
        surfaceColor = Color.parseColor("#0D0717")
    ),
    CRIMSON_CINE(
        id = "crimson_cine",
        title = "Crimson Cinema",
        subtitle = "Vivid Ruby Red & Cinematic Velvet",
        accentColor = Color.parseColor("#EF4444"),
        accentGlowColor = Color.parseColor("#F87171"),
        cardBgColor = Color.parseColor("#1D0E11"),
        surfaceColor = Color.parseColor("#120709")
    ),
    OLED_MINIMAL(
        id = "oled_minimal",
        title = "OLED Pure Minimal",
        subtitle = "Ultra Clean Ice White & True Pitch Black",
        accentColor = Color.parseColor("#FFFFFF"),
        accentGlowColor = Color.parseColor("#9CA3AF"),
        cardBgColor = Color.parseColor("#121212"),
        surfaceColor = Color.parseColor("#000000"),
        isOled = true
    );

    companion object {
        fun fromId(id: String?): AppTheme {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: VLC_ORANGE
        }
    }
}

object ThemeManager {
    private const val PREFS_NAME = "directusb_theme_prefs"
    private const val KEY_SELECTED_THEME = "selected_theme_id"

    private var currentTheme: AppTheme = AppTheme.VLC_ORANGE
    private val listeners = mutableListOf<(AppTheme) -> Unit>()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_SELECTED_THEME, AppTheme.VLC_ORANGE.id)
        currentTheme = AppTheme.fromId(savedId)
    }

    fun getTheme(): AppTheme = currentTheme

    fun setTheme(context: Context, theme: AppTheme) {
        if (currentTheme == theme) return
        currentTheme = theme
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_THEME, theme.id).apply()
        listeners.forEach { it.invoke(theme) }
    }

    fun addListener(listener: (AppTheme) -> Unit) {
        listeners.add(listener)
        listener(currentTheme)
    }

    fun removeListener(listener: (AppTheme) -> Unit) {
        listeners.remove(listener)
    }

    fun applyToSeekBar(seekBar: SeekBar, theme: AppTheme = currentTheme) {
        seekBar.progressTintList = ColorStateList.valueOf(theme.accentColor)
        seekBar.thumbTintList = ColorStateList.valueOf(theme.accentColor)
    }

    fun applyToPlayPauseButton(button: Button, theme: AppTheme = currentTheme) {
        applyToPrimaryButton(button, theme)
    }

    fun applyToPrimaryButton(button: Button, theme: AppTheme = currentTheme) {
        val normalDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(theme.accentColor)
        }
        val focusedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(theme.accentGlowColor)
            setStroke(4, Color.WHITE)
        }

        val stateList = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
            addState(intArrayOf(android.R.attr.state_pressed), focusedDrawable)
            addState(intArrayOf(), normalDrawable)
        }
        button.background = stateList
        button.setTextColor(if (theme.isOled) Color.BLACK else Color.WHITE)
    }

    fun applyToBadge(badge: TextView, theme: AppTheme = currentTheme) {
        applyBadgeHighlight(badge, true, theme)
    }

    fun applyBadgeHighlight(badge: TextView, active: Boolean, theme: AppTheme = currentTheme) {
        if (active) {
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(theme.accentColor)
            }
            badge.background = gd
            badge.setTextColor(if (theme.isOled) Color.BLACK else Color.WHITE)
        } else {
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(Color.parseColor("#33FFFFFF"))
            }
            badge.background = gd
            badge.setTextColor(Color.parseColor("#CCCCCC"))
        }
    }
}
