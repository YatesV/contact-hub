package com.yatesv.contacthub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Every customizable option. Saved as JSON in SharedPreferences. */
data class Settings(
    // Look
    val preset: String = "google",          // google, samsung, imessage, custom
    val darkMode: String = "system",        // system, light, dark, amoled
    val dynamicColor: Boolean = true,
    val accent: Int = 0xFF3D7BFF.toInt(),
    val sentColor: Int = 0,                 // 0 = from preset
    val recvColor: Int = 0,
    val sentText: Int = 0,
    val recvText: Int = 0,
    val bubbleShape: String = "",           // "" = from preset, round, square, tail
    val bubbleRadius: Int = 20,
    val font: String = "default",           // default, serif, mono, condensed
    val fontScale: Float = 1f,
    val listDensity: String = "comfy",      // comfy, compact
    val avatarShape: String = "circle",     // circle, squircle, square
    val showAvatars: Boolean = true,
    val showTimestamps: String = "tap",     // tap, always, groups
    val swipeLeft: String = "archive",      // archive, pin, read, mute, none
    val swipeRight: String = "pin",
    val wallpaper: String = "",
    val wallpaperDim: Float = 0.3f,
    val emojiSize: Float = 1.6f,
    val sendOnEnter: Boolean = false,
    val showCategories: Boolean = true,
    val navLabels: Boolean = true,
    val startTab: String = "messages",      // messages, contacts, phone

    // Messaging
    val engine: String = "auto",            // auto (Google/Samsung Messages first, SMS fallback), sms
    val handleNotifications: Boolean = true,
    val convertReactions: Boolean = true,
    val olderThan6Months: Boolean = false,
    val autoReplyOn: Boolean = false,
    val autoReplyText: String = "Can't text right now, I'll get back to you soon.",
    val myName: String = "",

    // Privacy
    val appLock: Boolean = false,
    val useBiometric: Boolean = true,
    val pinHash: String = "",
    val lockAfterSec: Int = 30,
    val hidePreviews: Boolean = false,

    // Life360
    val l360Token: String = "",
    val l360RefreshMin: Int = 0,            // 0 = only when you open a contact
    val l360Enabled: Boolean = true,

    // Backup
    val backupDaily: Boolean = true,
    val lastBackup: Long = 0,

    // Motion & feel
    val motion: String = "ios",             // ios, bouncy, smooth, snappy, custom, off
    val springDamping: Float = 0.86f,       // used when motion = custom
    val springStiffness: Float = 380f,
    val transition: String = "slide",       // slide, fade, zoom
    val menuStyle: String = "ios",          // ios, sheet
    val haptics: Boolean = true,
    val screenEffects: Boolean = false,
    val sendSound: Boolean = false,
    val animatedReactions: Boolean = false,

    // Accounts & updates
    val driveUri: String = "",
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,

    // Setup
    val onboarded: Boolean = false,
    val setupVersion: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("preset", preset); put("darkMode", darkMode); put("dynamicColor", dynamicColor); put("accent", accent)
        put("sentColor", sentColor); put("recvColor", recvColor); put("sentText", sentText); put("recvText", recvText)
        put("bubbleShape", bubbleShape); put("bubbleRadius", bubbleRadius); put("font", font); put("fontScale", fontScale.toDouble())
        put("listDensity", listDensity); put("avatarShape", avatarShape); put("showAvatars", showAvatars)
        put("showTimestamps", showTimestamps); put("swipeLeft", swipeLeft); put("swipeRight", swipeRight)
        put("wallpaper", wallpaper); put("wallpaperDim", wallpaperDim.toDouble()); put("emojiSize", emojiSize.toDouble())
        put("sendOnEnter", sendOnEnter); put("showCategories", showCategories); put("navLabels", navLabels); put("startTab", startTab)
        put("engine", engine); put("handleNotifications", handleNotifications); put("convertReactions", convertReactions)
        put("olderThan6Months", olderThan6Months); put("autoReplyOn", autoReplyOn); put("autoReplyText", autoReplyText); put("myName", myName)
        put("appLock", appLock); put("useBiometric", useBiometric); put("pinHash", pinHash); put("lockAfterSec", lockAfterSec)
        put("hidePreviews", hidePreviews); put("l360Token", l360Token); put("l360RefreshMin", l360RefreshMin)
        put("l360Enabled", l360Enabled); put("backupDaily", backupDaily); put("lastBackup", lastBackup); put("onboarded", onboarded)
        put("motion", motion); put("springDamping", springDamping.toDouble()); put("springStiffness", springStiffness.toDouble())
        put("transition", transition); put("menuStyle", menuStyle); put("haptics", haptics); put("screenEffects", screenEffects)
        put("sendSound", sendSound); put("animatedReactions", animatedReactions); put("driveUri", driveUri)
        put("autoUpdate", autoUpdate); put("lastUpdateCheck", lastUpdateCheck); put("setupVersion", setupVersion)
    }

    companion object {
        fun fromJson(j: JSONObject): Settings {
            val d = Settings()
            return Settings(
                preset = j.optString("preset", d.preset), darkMode = j.optString("darkMode", d.darkMode),
                dynamicColor = j.optBoolean("dynamicColor", d.dynamicColor), accent = j.optInt("accent", d.accent),
                sentColor = j.optInt("sentColor", 0), recvColor = j.optInt("recvColor", 0),
                sentText = j.optInt("sentText", 0), recvText = j.optInt("recvText", 0),
                bubbleShape = j.optString("bubbleShape", d.bubbleShape), bubbleRadius = j.optInt("bubbleRadius", d.bubbleRadius),
                font = j.optString("font", d.font), fontScale = j.optDouble("fontScale", 1.0).toFloat(),
                listDensity = j.optString("listDensity", d.listDensity), avatarShape = j.optString("avatarShape", d.avatarShape),
                showAvatars = j.optBoolean("showAvatars", true), showTimestamps = j.optString("showTimestamps", d.showTimestamps),
                swipeLeft = j.optString("swipeLeft", d.swipeLeft), swipeRight = j.optString("swipeRight", d.swipeRight),
                wallpaper = j.optString("wallpaper", ""), wallpaperDim = j.optDouble("wallpaperDim", 0.3).toFloat(),
                emojiSize = j.optDouble("emojiSize", 1.6).toFloat(), sendOnEnter = j.optBoolean("sendOnEnter", false),
                showCategories = j.optBoolean("showCategories", true), navLabels = j.optBoolean("navLabels", true),
                startTab = j.optString("startTab", d.startTab),
                engine = j.optString("engine", d.engine), handleNotifications = j.optBoolean("handleNotifications", true),
                convertReactions = j.optBoolean("convertReactions", true), olderThan6Months = j.optBoolean("olderThan6Months", false),
                autoReplyOn = j.optBoolean("autoReplyOn", false), autoReplyText = j.optString("autoReplyText", d.autoReplyText), myName = j.optString("myName", ""),
                appLock = j.optBoolean("appLock", false), useBiometric = j.optBoolean("useBiometric", true),
                pinHash = j.optString("pinHash", ""), lockAfterSec = j.optInt("lockAfterSec", 30),
                hidePreviews = j.optBoolean("hidePreviews", false), l360Token = j.optString("l360Token", ""),
                l360RefreshMin = j.optInt("l360RefreshMin", 0), l360Enabled = j.optBoolean("l360Enabled", true),
                backupDaily = j.optBoolean("backupDaily", true), lastBackup = j.optLong("lastBackup", 0),
                onboarded = j.optBoolean("onboarded", false),
                motion = j.optString("motion", d.motion), springDamping = j.optDouble("springDamping", 0.86).toFloat(),
                springStiffness = j.optDouble("springStiffness", 380.0).toFloat(), transition = j.optString("transition", d.transition),
                menuStyle = j.optString("menuStyle", d.menuStyle), haptics = j.optBoolean("haptics", true),
                screenEffects = j.optBoolean("screenEffects", false), sendSound = j.optBoolean("sendSound", false),
                animatedReactions = j.optBoolean("animatedReactions", false), driveUri = j.optString("driveUri", ""),
                autoUpdate = j.optBoolean("autoUpdate", true), lastUpdateCheck = j.optLong("lastUpdateCheck", 0),
                setupVersion = j.optInt("setupVersion", 0),
            )
        }
    }
}

class SettingsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow
    val value: Settings get() = _flow.value

    private fun load(): Settings =
        prefs.getString("json", null)?.let { runCatching { Settings.fromJson(JSONObject(it)) }.getOrNull() } ?: Settings()

    @Synchronized
    fun update(block: (Settings) -> Settings) {
        val next = block(_flow.value)
        _flow.value = next
        prefs.edit().putString("json", next.toJson().toString()).apply()
    }

    /** Settings for backups, without secrets. */
    fun exportJson(): JSONObject = value.copy(l360Token = "", pinHash = "", driveUri = "").toJson()
}
