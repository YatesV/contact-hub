package com.yatesv.contacthub.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Msg
import com.yatesv.contacthub.data.Otp
import com.yatesv.contacthub.data.Reactions
import kotlinx.coroutines.launch

/** New message: rises from the text box with a spring (old messages just appear). */
@Composable
fun EnterAnim(isNew: Boolean, outgoing: Boolean, onShown: () -> Unit, content: @Composable () -> Unit) {
    val p = remember { Animatable(if (isNew && Motion.params() != null) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (p.value < 1f) p.animateTo(1f, Motion.popSpec())
        onShown()
    }
    val density = LocalDensity.current
    Box(Modifier.graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1 - v) * with(density) { 48.dp.toPx() }
        scaleX = 0.85f + 0.15f * v; scaleY = 0.85f + 0.15f * v
        transformOrigin = TransformOrigin(if (outgoing) 1f else 0f, 1f)
    }) { content() }
}

/** Reaction pop: scales up with a little overshoot (and a wiggle when "Animated reactions" is on). */
@Composable
fun PopIn(key: String, content: @Composable () -> Unit) {
    val s = Hub.settings.value
    val scale = remember(key) { Animatable(if (Motion.params() == null) 1f else 0f) }
    val rot = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        launch { scale.animateTo(1f, Motion.popSpec()) }
        if (s.animatedReactions && Motion.params() != null) {
            scale.snapTo(0f)
            launch { scale.animateTo(1.35f, Motion.popSpec()); scale.animateTo(1f, Motion.spec()) }
            rot.animateTo(-18f, Motion.spec()); rot.animateTo(14f, Motion.spec()); rot.animateTo(0f, Motion.popSpec())
        }
    }
    Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value; rotationZ = rot.value }) { content() }
}

/**
 * iOS-style long-press: the chat blurs, the bubble lifts in place, reactions float above it
 * and actions show below it.
 */
@Composable
fun IosMessageMenu(
    m: Msg, rect: Rect?, isStarred: Boolean, bubble: Color, bubbleText: Color,
    onDismiss: () -> Unit, onReact: (String) -> Unit, onStar: (Boolean) -> Unit, onCopy: (String) -> Unit,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.popSpec()) }
    val style = LocalChatStyle.current
    val density = LocalDensity.current
    Popup(popupPositionProvider = object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                                       layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize) = IntOffset.Zero
    }, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        BoxWithConstraints(Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f * appear.value))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
            val screenH = with(density) { maxHeight.toPx() }
            val screenW = with(density) { maxWidth.toPx() }
            val barH = with(density) { 56.dp.toPx() }
            val gap = with(density) { 10.dp.toPx() }
            val menuH = with(density) { (if (Otp.find(m.body) != null) 160.dp else 116.dp).toPx() }
            val r = rect ?: Rect(screenW * 0.2f, screenH * 0.4f, screenW * 0.8f, screenH * 0.4f + 120f)
            val bubbleH = r.height.coerceAtMost(screenH * 0.35f)
            // Shift everything so the reactions and the menu both fit on screen
            var top = r.top
            val minTop = barH + gap * 4
            val maxTop = screenH - menuH - bubbleH - gap * 4
            top = top.coerceIn(minTop, maxOf(minTop, maxTop))
            val alignRight = m.outgoing
            val leftPx = r.left
            val dy = with(density) { (top).toDp() }

            // Reactions bar
            Surface(
                shape = RoundedCornerShape(28.dp), shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.offset { IntOffset(if (alignRight) (r.right - with(density) { 300.dp.toPx() }).toInt().coerceAtLeast(16) else leftPx.toInt(), (top - barH - gap).toInt()) }
                    .graphicsLayer { scaleX = appear.value; scaleY = appear.value; alpha = appear.value; transformOrigin = TransformOrigin(if (alignRight) 1f else 0f, 1f) },
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Reactions.picker.take(7).forEachIndexed { i, e ->
                        val pop = remember { Animatable(0f) }
                        LaunchedEffect(Unit) { kotlinx.coroutines.delay(i * 25L); pop.animateTo(1f, Motion.popSpec()) }
                        Text(e, fontSize = 28.sp, modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                            .clip(RoundedCornerShape(50)).clickable { onReact(e) }.padding(6.dp))
                    }
                }
            }

            // The lifted bubble
            Box(Modifier.offset { IntOffset(leftPx.toInt(), top.toInt()) }
                .graphicsLayer { val sc = 1f + 0.04f * appear.value; scaleX = sc; scaleY = sc; shadowElevation = 24f * appear.value; shape = style.bubble(m.outgoing, true, true); clip = true }) {
                Text(m.body.ifBlank { "📷 Photo" }, color = bubbleText, fontSize = 16.sp, maxLines = 12,
                    modifier = Modifier.width(with(density) { r.width.toDp() }).widthIn(max = 300.dp).background(bubble).padding(horizontal = 14.dp, vertical = 9.dp))
            }

            // Actions
            Surface(
                shape = RoundedCornerShape(16.dp), shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.width(230.dp)
                    .offset { IntOffset(if (alignRight) (r.right - with(density) { 230.dp.toPx() }).toInt().coerceAtLeast(16) else leftPx.toInt(), (top + bubbleH + gap).toInt()) }
                    .graphicsLayer { scaleX = appear.value; scaleY = appear.value; alpha = appear.value; transformOrigin = TransformOrigin(if (alignRight) 1f else 0f, 0f) },
            ) {
                Column {
                    MenuItem("Copy", Icons.Default.ContentCopy) { onCopy(m.body) }
                    Otp.find(m.body)?.let { code -> HorizontalDivider(); MenuItem("Copy code $code", Icons.Default.Key) { onCopy(code) } }
                    HorizontalDivider()
                    MenuItem(if (isStarred) "Unstar" else "Star", if (isStarred) Icons.Default.StarBorder else Icons.Default.Star) { onStar(!isStarred) }
                    HorizontalDivider()
                    Text(fullTime(m.date) + if (m.rcs) " · RCS" else "", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
            @Suppress("UNUSED_EXPRESSION") dy
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
