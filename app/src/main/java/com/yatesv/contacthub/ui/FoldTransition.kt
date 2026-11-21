package com.yatesv.contacthub.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp

/**
 * The Fold transition: screens tilt in 3D with a depth-blur as you move between them, like the
 * iPhone Duo's open/close animation. Content stays anchored while the frame reorients around it.
 * All the geometry lives in [foldFrame] so it can be unit tested without a screen.
 */

/** How long the fold runs. Fade (in MainActivity) and the 3D layer use the same duration so they finish together. */
const val FOLD_DURATION_MS = 420

data class FoldFrame(
    val rotationY: Float,
    val scale: Float,
    val blur: Float,
    val originX: Float,
    val alpha: Float,
)

/**
 * @param p how present this screen is: 1 = settled and sharp in front, 0 = fully folded away.
 * @param forward true for a push (going deeper), false for back.
 * @param entering true for the screen coming in, false for the one leaving.
 * @param intensity 0 = subtle, 1 = dramatic.
 */
fun foldFrame(p: Float, forward: Boolean, entering: Boolean, intensity: Float): FoldFrame {
    val i = intensity.coerceIn(0f, 1f)
    val c = p.coerceIn(0f, 1f)
    val maxAngle = lerp(7f, 24f, i)
    val maxBlur = lerp(3f, 22f, i)
    val depth = lerp(0.05f, 0.16f, i)
    val d = if (forward) 1f else -1f
    // Entering tilts in from one side; leaving tilts away to the other, so they fold around a shared spine.
    val sign = if (entering) -d else d
    return FoldFrame(
        rotationY = (1f - c) * maxAngle * sign,
        scale = 1f - depth * (1f - c),
        blur = (1f - c) * maxBlur,
        originX = if (entering == forward) 0f else 1f,
        alpha = 0.35f + 0.65f * c,
    )
}

private fun Modifier.applyFold(frame: FoldFrame, withAlpha: Boolean) = graphicsLayer {
    cameraDistance = 14f * density
    rotationY = frame.rotationY
    scaleX = frame.scale
    scaleY = frame.scale
    transformOrigin = TransformOrigin(frame.originX, 0.5f)
    if (withAlpha) alpha = frame.alpha
    if (Build.VERSION.SDK_INT >= 31 && frame.blur > 0.3f) {
        renderEffect = AndroidRenderEffect.createBlurEffect(frame.blur, frame.blur, Shader.TileMode.CLAMP).asComposeRenderEffect()
    }
}

/**
 * Applies the fold to one screen inside an AnimatedContent. Alpha is left to the fade transition
 * (set in MainActivity) so the two don't double up; this layer does the 3D tilt, depth and blur.
 */
@Composable
fun Modifier.foldScreen(scope: AnimatedVisibilityScope, forward: Boolean, intensity: Float): Modifier {
    val entering = scope.transition.targetState == EnterExitState.Visible
    val p by scope.transition.animateFloat(
        transitionSpec = { tween(FOLD_DURATION_MS, easing = FastOutSlowInEasing) },
        label = "fold",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    return this.applyFold(foldFrame(p, forward, entering, intensity), withAlpha = false)
}

/** One-shot fold when Orbit itself opens: the whole app tilts in from folded to flat. */
@Composable
fun AppOpenFold(enabled: Boolean, intensity: Float, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(FOLD_DURATION_MS + 80, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val frame = foldFrame(anim.value, forward = true, entering = true, intensity = intensity)
        cameraDistance = 14f * density
        rotationY = frame.rotationY
        scaleX = frame.scale
        scaleY = frame.scale
        alpha = frame.alpha
        if (Build.VERSION.SDK_INT >= 31 && frame.blur > 0.3f) {
            renderEffect = AndroidRenderEffect.createBlurEffect(frame.blur, frame.blur, Shader.TileMode.CLAMP).asComposeRenderEffect()
        }
    }) { content() }
}
