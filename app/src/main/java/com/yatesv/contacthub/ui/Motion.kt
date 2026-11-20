package com.yatesv.contacthub.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Spring feel for every animation in the app. iOS-style by default. */
object Motion {
    data class SpringParams(val damping: Float, val stiffness: Float)

    val presets = linkedMapOf(
        "ios" to SpringParams(0.86f, 380f),
        "bouncy" to SpringParams(0.55f, 320f),
        "smooth" to SpringParams(1.0f, 180f),
        "snappy" to SpringParams(0.92f, 1100f),
    )
    val labels = mapOf("ios" to "iOS", "bouncy" to "Bouncy", "smooth" to "Smooth", "snappy" to "Snappy", "custom" to "Custom", "off" to "Off")

    /** Android's "Remove animations" accessibility setting turns motion off everywhere. */
    private fun systemMotionOff() = runCatching {
        android.provider.Settings.Global.getFloat(Hub.app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    fun params(s: Settings = Hub.settings.value): SpringParams? = if (systemMotionOff()) null else when (s.motion) {
        "off" -> null
        "custom" -> SpringParams(s.springDamping, s.springStiffness)
        else -> presets[s.motion] ?: presets.getValue("ios")
    }

    fun <T> spec(s: Settings = Hub.settings.value): FiniteAnimationSpec<T> =
        params(s)?.let { spring(it.damping, it.stiffness) } ?: snap()

    /** Slightly bouncier version for small pops (reactions, bubbles). */
    fun <T> popSpec(s: Settings = Hub.settings.value): FiniteAnimationSpec<T> =
        params(s)?.let { spring((it.damping * 0.7f).coerceAtLeast(0.35f), it.stiffness) } ?: snap()

    /** Position over time (0..1 target) for the settings graph. Damped spring, starting at 0. */
    fun curve(p: SpringParams, seconds: Float = 1.2f, steps: Int = 120): List<Float> {
        val w0 = sqrt(p.stiffness)
        val z = p.damping
        return (0..steps).map { i ->
            val t = seconds * i / steps
            val y = when {
                z < 1f -> { val wd = w0 * sqrt(1 - z * z); 1 - exp(-z * w0 * t) * (cos(wd * t) + (z * w0 / wd) * sin(wd * t)) }
                z == 1f -> 1 - exp(-w0 * t) * (1 + w0 * t)
                else -> {
                    val r1 = -w0 * (z - sqrt(z * z - 1)); val r2 = -w0 * (z + sqrt(z * z - 1))
                    1 - (r2 * exp(r1 * t) - r1 * exp(r2 * t)) / (r2 - r1)
                }
            }
            y
        }
    }
}

/** Light vibration ticks, if haptics are on. */
object Haptics {
    fun tap(v: View) = fire(v, HapticFeedbackConstants.CLOCK_TICK)
    fun confirm(v: View) = fire(v, HapticFeedbackConstants.CONFIRM)
    fun long(v: View) = fire(v, HapticFeedbackConstants.LONG_PRESS)
    fun reject(v: View) = fire(v, HapticFeedbackConstants.REJECT)
    private fun fire(v: View, type: Int) { if (Hub.settings.value.haptics) v.performHapticFeedback(type) }
}

/** Little "whoosh" when a message sends (made in code, no sound files). */
object SendSound {
    private val pcm: ShortArray by lazy {
        val rate = 44100; val n = rate * 18 / 100
        ShortArray(n) { i ->
            val t = i.toFloat() / rate
            val f = 500f + 1400f * (i.toFloat() / n)            // rising pitch
            val env = sin(PI * i / n).toFloat().let { it * it }  // fade in/out
            (sin(2 * PI * f * t).toFloat() * env * 0.25f * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun play() {
        if (!Hub.settings.value.sendSound) return
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        }
    }
}

fun pressScaleModifier(source: MutableInteractionSource): Modifier = Modifier.pressScale(source, 0.92f)

/** Press-to-shrink feel for tappable things. */
fun Modifier.pressScale(source: MutableInteractionSource, scale: Float = 0.95f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, Motion.spec(), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

// ---------------- Screen effects (only on your screen) ----------------

enum class Effect { CONFETTI, FIREWORKS, HEARTS }

object Effects {
    val current = MutableStateFlow<Effect?>(null)
    fun detect(text: String): Effect? {
        val t = text.lowercase()
        return when {
            "happy birthday" in t || "hbd" in t -> Effect.CONFETTI
            "congrats" in t || "congratulations" in t || "happy new year" in t -> Effect.FIREWORKS
            "love you" in t || "luv u" in t || "ily" == t.trim() -> Effect.HEARTS
            else -> null
        }
    }
    fun trigger(text: String) { if (Hub.settings.value.screenEffects) detect(text)?.let { current.value = it } }
}

private data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Color, var rot: Float, val vr: Float, val size: Float, var life: Float)

@Composable
fun EffectOverlay(effect: Effect, onDone: () -> Unit) {
    val particles = remember(effect) { mutableListOf<Particle>() }
    var frame by remember { mutableLongStateOf(0L) }
    var size by remember { androidx.compose.runtime.mutableStateOf(Size.Zero) }
    LaunchedEffect(effect) {
        val start = withFrameMillis { it }
        var last = start
        val colors = listOf(Color(0xFFFF5252), Color(0xFFFFD740), Color(0xFF69F0AE), Color(0xFF40C4FF), Color(0xFFE040FB), Color(0xFFFF6E40))
        while (true) {
            val now = withFrameMillis { it }
            val dt = ((now - last) / 1000f).coerceAtMost(0.05f); last = now
            val w = size.width; val h = size.height
            if (w > 0 && now - start < 1800) {
                repeat(if (effect == Effect.FIREWORKS) 0 else 6) {
                    when (effect) {
                        Effect.CONFETTI -> particles += Particle(Random.nextFloat() * w, -20f, Random.nextFloat() * 200 - 100, Random.nextFloat() * 300 + 300,
                            colors.random(), Random.nextFloat() * 360, Random.nextFloat() * 600 - 300, Random.nextFloat() * 14 + 10, 3f)
                        Effect.HEARTS -> particles += Particle(Random.nextFloat() * w, h + 20, Random.nextFloat() * 60 - 30, -(Random.nextFloat() * 300 + 250),
                            Color(0xFFFF4D6D), 0f, 0f, Random.nextFloat() * 24 + 18, 3f)
                        else -> {}
                    }
                }
                if (effect == Effect.FIREWORKS && (now - start) % 450 < 20) {
                    val cx = Random.nextFloat() * w * 0.8f + w * 0.1f; val cy = Random.nextFloat() * h * 0.4f + h * 0.1f
                    val c = colors.random()
                    repeat(60) { i ->
                        val a = 2 * PI * i / 60; val sp = Random.nextFloat() * 250 + 150
                        particles += Particle(cx, cy, (cos(a) * sp).toFloat(), (sin(a) * sp).toFloat(), c, 0f, 0f, 6f, 1.4f)
                    }
                }
            }
            particles.forEach { p ->
                p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt; p.life -= dt
                if (effect == Effect.FIREWORKS || effect == Effect.CONFETTI) p.vy += 400 * dt
            }
            particles.removeAll { it.life <= 0 || it.y > h + 60 || it.y < -80 }
            frame = now
            if (now - start > 1800 && particles.isEmpty()) break
            if (now - start > 6000) break
        }
        onDone()
    }
    Canvas(Modifier.fillMaxSize()) {
        size = this.size
        @Suppress("UNUSED_EXPRESSION") frame
        particles.forEach { p ->
            val alpha = p.life.coerceIn(0f, 1f)
            when (effect) {
                Effect.CONFETTI -> rotate(p.rot, Offset(p.x, p.y)) {
                    drawRect(p.color.copy(alpha = alpha), Offset(p.x - p.size / 2, p.y - p.size / 4), Size(p.size, p.size / 2))
                }
                Effect.FIREWORKS -> drawCircle(p.color.copy(alpha = alpha), p.size * alpha.coerceAtLeast(0.3f), Offset(p.x, p.y))
                Effect.HEARTS -> {
                    val s = p.size; val path = Path().apply {
                        moveTo(p.x, p.y + s * 0.3f)
                        cubicTo(p.x - s, p.y - s * 0.4f, p.x - s * 0.4f, p.y - s, p.x, p.y - s * 0.45f)
                        cubicTo(p.x + s * 0.4f, p.y - s, p.x + s, p.y - s * 0.4f, p.x, p.y + s * 0.3f)
                    }
                    drawPath(path, p.color.copy(alpha = alpha))
                }
            }
        }
    }
}

/** Spring response graph for the motion settings. */
@Composable
fun SpringGraph(p: Motion.SpringParams?, color: Color, grid: Color, modifier: Modifier) {
    val pts = remember(p) { p?.let { Motion.curve(it) } }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val top = h * 0.15f; val base = h * 0.9f
        fun yOf(v: Float) = base - (base - top) * (v / 1.3f).coerceIn(-0.1f, 1.1f) * 1.3f / 1.3f
        // target line + axis
        drawLine(grid, Offset(0f, yOf(1f)), Offset(w, yOf(1f)), 2f)
        drawLine(grid, Offset(0f, base), Offset(w, base), 2f)
        if (pts == null) {
            drawLine(color, Offset(0f, base), Offset(2f, base), 6f)
            drawLine(color, Offset(2f, yOf(1f)), Offset(w, yOf(1f)), 6f)
            return@Canvas
        }
        val path = Path()
        pts.forEachIndexed { i, v ->
            val x = w * i / (pts.size - 1); val y = yOf(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 6f))
    }
}

/** Animates 0 -> 1 with the current spring whenever [key] changes; used for live previews. */
@Composable
fun rememberSpringProgress(key: Any, s: Settings): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(key, s.motion, s.springDamping, s.springStiffness) {
        a.snapTo(0f)
        a.animateTo(1f, Motion.spec(s))
    }
    return a.value
}

@Suppress("unused") private val keepSpring = Spring.DampingRatioNoBouncy
