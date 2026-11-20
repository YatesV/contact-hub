package com.yatesv.contacthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yatesv.contacthub.Hub

fun avatarShape(name: String): Shape = when (name) {
    "square" -> RoundedCornerShape(6.dp)
    "squircle" -> RoundedCornerShape(30)
    else -> CircleShape
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun Avatar(name: String, photo: String?, size: Dp = 48.dp, group: Boolean = false, modifier: Modifier = Modifier, sharedKey: String? = null) {
    val shape = avatarShape(Hub.settings.value.avatarShape)
    val sharedScope = LocalSharedScope.current
    val screenScope = LocalScreenScope.current
    val sharedMod = if (sharedKey != null && sharedScope != null && screenScope != null && Motion.enabled()) with(sharedScope) {
        Modifier.sharedElement(rememberSharedContentState("avatar:$sharedKey"), screenScope,
            boundsTransform = { _, _ -> Motion.spec() })
    } else Modifier
    val initials = name.split(' ', ',').filter { it.isNotBlank() && it.first().isLetter() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier.then(sharedMod).size(size).clip(shape).background(memberColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (group) "👥" else initials.ifEmpty { "#" }, color = Color.White, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.SemiBold)
        if (!photo.isNullOrBlank()) AsyncImage(photo, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp))
}

@Composable
fun HubCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null,
               trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) =
    SettingRow(title, subtitle, icon, onClick = { onChange(!checked) }) { Switch(checked, onChange) }

@Composable
fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row { Text(title, Modifier.weight(1f)); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(value, onChange, valueRange = range, steps = steps)
    }
}

/** Row of choice chips for a setting with a few options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            options.forEach { (value, label) ->
                androidx.compose.material3.FilterChip(selected == value, { onSelect(value) }, { Text(label) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorRow(title: String, selected: Int, allowAuto: Boolean = true, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)) {
            if (allowAuto) {
                Box(Modifier.size(34.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onSelect(0) }, contentAlignment = Alignment.Center) {
                    Text(if (selected == 0) "✓" else "A", fontSize = 12.sp)
                }
            }
            palette.forEach { c ->
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c.toLong() and 0xFFFFFFFFL))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable { onSelect(c) },
                    contentAlignment = Alignment.Center) {
                    if (selected == c) Icon(Icons.Default.Check, null, tint = onColor(Color(c.toLong() and 0xFFFFFFFFL)), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        if (action != null) androidx.compose.material3.TextButton(onAction) { Text(action) }
        androidx.compose.material3.TextButton(onClose) { Text("✕") }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
    }
}

fun shortTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.apply { timeInMillis = now }.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = ms
    val day = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    val fmt = when {
        day == today -> "h:mm a"
        now - ms < 6L * 86_400_000 -> "EEE"
        day.second == today.second -> "MMM d"
        else -> "M/d/yy"
    }
    return java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(java.util.Date(ms))
}

fun fullTime(ms: Long): String = java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms))
