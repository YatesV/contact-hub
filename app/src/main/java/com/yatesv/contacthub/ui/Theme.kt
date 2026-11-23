package com.yatesv.contacthub.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Settings

/** Everything the chat screen needs to draw bubbles the way the user set them up. */
data class ChatStyle(
    val sent: Color,
    val sentText: Color,
    val recv: Color,
    val recvText: Color,
    val radius: Dp,
    val shape: String,
    val emojiScale: Float,
    val compact: Boolean,
    val avatarShape: String,
    val showAvatars: Boolean,
    val timestamps: String,
) {
    fun bubble(outgoing: Boolean, first: Boolean, last: Boolean): Shape {
        val r = radius
        val small = 4.dp
        return when (shape) {
            "square" -> RoundedCornerShape(6.dp)
            "tail" -> if (outgoing) RoundedCornerShape(r, r, if (last) small else r, r) else RoundedCornerShape(r, r, r, if (last) small else r)
            else -> if (outgoing) RoundedCornerShape(r, if (first) r else small, if (last) r else small, r)
            else RoundedCornerShape(if (first) r else small, r, r, if (last) r else small)
        }
    }
}

val LocalChatStyle = staticCompositionLocalOf<ChatStyle> { error("no chat style") }

private data class Preset(val accent: Long, val sentL: Long, val sentD: Long, val recvL: Long, val recvD: Long,
                          val sentTextL: Long, val sentTextD: Long, val shape: String, val radius: Int, val dynamic: Boolean)

private val presets = mapOf(
    "hub" to Preset(0xFF0A7CFF, 0xFF0A7CFF, 0xFF0A84FF, 0xFFE9E9EB, 0xFF262629, 0xFFFFFFFF, 0xFFFFFFFF, "tail", 20, false),
    "google" to Preset(0xFF0B57D0, 0, 0, 0, 0, 0, 0, "round", 20, true),
    "samsung" to Preset(0xFF3E91FF, 0xFFCFE4FF, 0xFF2D5BA8, 0xFFFFFFFF, 0xFF2B2B2E, 0xFF0B1A33, 0xFFFFFFFF, "round", 22, false),
    "imessage" to Preset(0xFF0A84FF, 0xFF0A84FF, 0xFF0A84FF, 0xFFE9E9EB, 0xFF26252A, 0xFFFFFFFF, 0xFFFFFFFF, "tail", 18, false),
    "custom" to Preset(0, 0, 0, 0, 0, 0, 0, "round", 20, false),
)

fun fontFamily(name: String): FontFamily = when (name) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "condensed" -> FontFamily.SansSerif
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.Default
}

private fun Typography.withFont(f: FontFamily): Typography {
    if (f == FontFamily.Default) return this
    return Typography(
        displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
        displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
        headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
        titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
        titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
        bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
        labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
        labelSmall = labelSmall.copy(fontFamily = f),
    )
}

/** Clear type scale: large titles, 17sp body, nothing below 12sp. */
val hubTypography = Typography().let { t ->
    fun st(size: Int, w: androidx.compose.ui.text.font.FontWeight, line: Int, track: Float = 0f) =
        androidx.compose.ui.text.TextStyle(fontSize = size.sp, fontWeight = w, lineHeight = line.sp, letterSpacing = track.sp)
    val B = androidx.compose.ui.text.font.FontWeight.Bold
    val SB = androidx.compose.ui.text.font.FontWeight.SemiBold
    val M = androidx.compose.ui.text.font.FontWeight.Medium
    val R = androidx.compose.ui.text.font.FontWeight.Normal
    t.copy(
        displaySmall = st(36, B, 42, -0.5f),
        headlineLarge = st(34, B, 41, -0.4f),     // large titles
        headlineMedium = st(30, B, 36, -0.4f),
        headlineSmall = st(24, B, 30, -0.2f),
        titleLarge = st(20, SB, 25, -0.2f),
        titleMedium = st(17, SB, 22, -0.2f),
        titleSmall = st(15, SB, 20),
        bodyLarge = st(17, R, 22, -0.2f),
        bodyMedium = st(15, R, 20, -0.1f),
        bodySmall = st(13, R, 18),
        labelLarge = st(15, M, 20),
        labelMedium = st(13, M, 18),
        labelSmall = st(12, R, 16),
    )
}

fun onColor(c: Color) = if (c.luminance() > 0.5f) Color(0xFF111111) else Color.White

@Composable
fun HubTheme(content: @Composable () -> Unit) {
    val s by Hub.settings.flow.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (s.darkMode) { "light" -> false; "dark", "amoled" -> true; else -> systemDark }
    val ctx = LocalContext.current
    val p = presets[s.preset] ?: presets.getValue("google")
    val accent = Color(if (s.preset == "custom" || p.accent == 0L) s.accent.toLong() and 0xFFFFFFFFL else p.accent)

    val useDynamic = s.dynamicColor && p.dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val neutral = s.preset == "hub" || s.preset == "imessage"
    var scheme: ColorScheme = when {
        // Calm, neutral greys with one accent: content first, color only where it means something
        neutral && dark -> darkColorScheme(
            primary = accent, onPrimary = Color.White, primaryContainer = accent.copy(alpha = 0.22f).compositeOverDark(), onPrimaryContainer = Color.White,
            secondary = accent, background = Color.Black, onBackground = Color(0xFFF2F2F7), surface = Color.Black, onSurface = Color(0xFFF2F2F7),
            surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFF98989F), surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF111113), surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E),
            surfaceContainerHighest = Color(0xFF3A3A3C), outline = Color(0xFF545458), outlineVariant = Color(0xFF38383A),
            tertiaryContainer = Color(0xFF2C2C2E), onTertiaryContainer = Color(0xFFF2F2F7), error = Color(0xFFFF453A))
        neutral -> lightColorScheme(
            primary = accent, onPrimary = Color.White, primaryContainer = accent.copy(alpha = 0.14f).compositeOverLight(), onPrimaryContainer = Color(0xFF0B1A33),
            secondary = accent, background = Color.White, onBackground = Color(0xFF111114), surface = Color.White, onSurface = Color(0xFF111114),
            surfaceVariant = Color(0xFFE9E9EB), onSurfaceVariant = Color(0xFF6C6C70), surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF7F7FA), surfaceContainer = Color(0xFFF2F2F7), surfaceContainerHigh = Color(0xFFE9E9EE),
            surfaceContainerHighest = Color(0xFFE0E0E6), outline = Color(0xFFC6C6C8), outlineVariant = Color(0xFFDCDCE0),
            tertiaryContainer = Color(0xFFF2F2F7), onTertiaryContainer = Color(0xFF111114), error = Color(0xFFFF3B30))
        useDynamic && dark -> dynamicDarkColorScheme(ctx)
        useDynamic -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = accent, secondary = accent, primaryContainer = accent.copy(alpha = 0.35f).compositeOverDark(),
            onPrimary = onColor(accent), surface = Color(0xFF121418), background = Color(0xFF0E1013),
            surfaceVariant = Color(0xFF23272E), surfaceContainer = Color(0xFF1A1D22))
        else -> lightColorScheme(primary = accent, secondary = accent, primaryContainer = accent.copy(alpha = 0.18f).compositeOverLight(),
            onPrimary = onColor(accent))
    }
    if (s.darkMode == "amoled") scheme = scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF0B0B0B))

    fun pick(user: Int, light: Long, darkV: Long, fallback: Color): Color = when {
        user != 0 -> Color(user.toLong() and 0xFFFFFFFFL)
        (if (dark) darkV else light) != 0L -> Color(if (dark) darkV else light)
        else -> fallback
    }
    val sent = pick(s.sentColor, p.sentL, p.sentD, if (s.preset == "custom") accent else scheme.primaryContainer)
    val recv = pick(s.recvColor, p.recvL, p.recvD, scheme.surfaceVariant)
    val style = ChatStyle(
        sent = sent,
        sentText = if (s.sentText != 0) Color(s.sentText.toLong() and 0xFFFFFFFFL) else onColor(sent),
        recv = recv,
        recvText = if (s.recvText != 0) Color(s.recvText.toLong() and 0xFFFFFFFFL) else onColor(recv),
        radius = (if (s.bubbleShape.isBlank()) p.radius else s.bubbleRadius).dp,
        shape = s.bubbleShape.ifBlank { p.shape },
        emojiScale = s.emojiSize,
        compact = s.listDensity == "compact",
        avatarShape = s.avatarShape,
        showAvatars = s.showAvatars,
        timestamps = s.showTimestamps,
    )
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalChatStyle provides style,
        LocalDensity provides Density(density.density, density.fontScale * s.fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = hubTypography.withFont(fontFamily(s.font)), content = content)
    }
}

private fun Color.compositeOverDark() = Color(
    red = red * alpha + 0.07f * (1 - alpha), green = green * alpha + 0.08f * (1 - alpha), blue = blue * alpha + 0.09f * (1 - alpha))
private fun Color.compositeOverLight() = Color(
    red = red * alpha + (1 - alpha), green = green * alpha + (1 - alpha), blue = blue * alpha + (1 - alpha))

val palette = listOf(
    0xFF3D7BFF, 0xFF0A84FF, 0xFF00B0FF, 0xFF00BFA5, 0xFF2E7D32, 0xFF7CB342, 0xFFFFC107, 0xFFFF9800,
    0xFFFF5722, 0xFFE53935, 0xFFD81B60, 0xFFEC407A, 0xFF8E24AA, 0xFF5E35B1, 0xFF6D4C41, 0xFF546E7A,
    0xFF212121, 0xFFFFFFFF, 0xFFE9E9EB, 0xFF26252A,
).map { it.toInt() }

/** Bubble color for each person in a group chat. */
fun memberColor(key: String, override: Int = 0): Color {
    if (override != 0) return Color(override.toLong() and 0xFFFFFFFFL)
    val colors = listOf(0xFF5C6BC0, 0xFF26A69A, 0xFFEF6C00, 0xFFAB47BC, 0xFF8D6E63, 0xFF29B6F6, 0xFFD4E157, 0xFFEC407A)
    return Color(colors[(key.hashCode() and 0x7fffffff) % colors.size])
}


