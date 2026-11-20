package com.yatesv.contacthub.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.yatesv.contacthub.BuildConfig
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.Settings
import com.yatesv.contacthub.engine.Backup
import com.yatesv.contacthub.engine.Diagnostics
import com.yatesv.contacthub.engine.HubSms
import com.yatesv.contacthub.engine.Notifier
import com.yatesv.contacthub.engine.Sender
import com.yatesv.contacthub.engine.Updater
import kotlinx.coroutines.launch

private fun upd(block: (Settings) -> Settings) = Hub.settings.update(block)

// ---------------- Motion & feel ----------------

@Composable
fun MotionPage() {
    val s by Hub.settings.flow.collectAsState()
    val view = LocalView.current
    var replay by remember { mutableIntStateOf(0) }
    SectionTitle("Animation style")
    ChoiceRow("Feel", Motion.labels.toList(), s.motion) { v ->
        upd { st ->
            val p = Motion.presets[v]
            if (v == "custom") st.copy(motion = v) else st.copy(motion = v, springDamping = p?.damping ?: st.springDamping, springStiffness = p?.stiffness ?: st.springStiffness)
        }
        replay++; Haptics.tap(view)
    }
    if (s.motion == "curve") CurveStudio(s)
    // Graph + live preview for the spring styles
    if (s.motion != "curve") HubCard {
        Text("How it moves", style = MaterialTheme.typography.labelLarge)
        run {
            SpringGraph(Motion.params(s), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.outlineVariant,
                Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp).semantics { contentDescription = "Spring curve graph" })
            Text("Time →   (the line is where it ends up; bumps above it are bounce). Pick \"My curve\" to draw your own.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        MotionPreview(s, replay)
        TextButton({ replay++; Haptics.tap(view) }) { Text("Replay") }
    }
    SectionTitle("Fine-tune (Spring)")
    SliderRow("Bounciness", 1.2f - s.springDamping, 0.05f..0.85f, "%.0f%%".format((1.2f - s.springDamping) / 0.85f * 100)) { v ->
        upd { it.copy(motion = "custom", springDamping = 1.2f - v) }; replay++
    }
    SliderRow("Speed", s.springStiffness, 80f..1500f, s.springStiffness.toInt().toString()) { v ->
        upd { it.copy(motion = "custom", springStiffness = v) }; replay++
    }
    SectionTitle("Screens & menus")
    ChoiceRow("Screen transition", listOf("slide" to "iOS slide", "fade" to "Fade", "zoom" to "Zoom"), s.transition) { v -> upd { it.copy(transition = v) } }
    ChoiceRow("Long-press menu", listOf("ios" to "iOS (lift + blur)", "sheet" to "Bottom sheet"), s.menuStyle) { v -> upd { it.copy(menuStyle = v) } }
    SectionTitle("Extras")
    SwitchRow("Vibration", "Light ticks on send, react, swipe, long-press", s.haptics) { v -> upd { it.copy(haptics = v) }; if (v) Haptics.confirm(view) }
    SwitchRow("Animated reactions", "Reactions pop and wiggle when they appear", s.animatedReactions) { v -> upd { it.copy(animatedReactions = v) } }
    SwitchRow("Send sound", "A little whoosh when a message sends", s.sendSound) { v -> upd { it.copy(sendSound = v) }; if (v) SendSound.play() }
    SwitchRow("Screen effects", "Confetti for \"happy birthday\", fireworks for \"congrats\", hearts for \"love you\". Only on your screen, never sent.", s.screenEffects) { v ->
        upd { it.copy(screenEffects = v) }; if (v) Effects.current.value = Effect.CONFETTI
    }
}

@Composable
private fun MotionPreview(s: Settings, replay: Int) {
    val st = LocalChatStyle.current
    val x = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(replay, s.motion, s.springDamping, s.springStiffness) {
        x.snapTo(0f); pop.snapTo(0f)
        launch { x.animateTo(1f, Motion.spec(s)) }
        launch { pop.animateTo(1f, Motion.popSpec(s)) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(70.dp)) {
        val travel = maxWidth - 150.dp
        Text("Sliding in", color = st.sentText, modifier = Modifier.offset(x = travel * x.value).align(Alignment.TopStart)
            .clip(st.bubble(true, true, true)).background(st.sent).padding(horizontal = 14.dp, vertical = 8.dp))
        Text("❤️", fontSize = 26.sp, modifier = Modifier.align(Alignment.BottomEnd).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
    }
}

/**
 * Curve editor: time goes left to right, progress bottom to top. Start and end dots are fixed;
 * the dots in between are yours. [onLive] fires on every move so previews follow your finger;
 * [onChange] fires when you let go (that's when it's saved).
 */
@Composable
fun CurveEditor(points: String, snap: Boolean, modifier: Modifier, onLive: (String) -> Unit, onChange: (String) -> Unit) {
    val view = LocalView.current
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val dotFill = MaterialTheme.colorScheme.background
    val label = MaterialTheme.colorScheme.onSurface
    // One state object for the editor's whole life, so the gesture code always edits the current dots
    val pts = remember { mutableStateOf(CurveEasing.parse(points).pts.drop(1).dropLast(1)) }
    val dragging = remember { mutableIntStateOf(-1) }
    val snapNow = androidx.compose.runtime.rememberUpdatedState(snap)
    LaunchedEffect(points) { if (dragging.intValue < 0) pts.value = CurveEasing.parse(points).pts.drop(1).dropLast(1) }
    val yMin = -0.3f; val yMax = 1.35f
    fun ser() = CurveEasing(pts.value).serialize()
    fun snapped(v: Float) = if (snapNow.value) (Math.round(v / 0.05f) * 0.05f) else v

    Box(modifier) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .semantics { contentDescription = "Animation curve editor" }
            .pointerInput(Unit) {
                val pad = 24.dp.toPx()
                fun toVal(o: androidx.compose.ui.geometry.Offset) =
                    snapped(((o.x - pad) / (size.width - 2 * pad))).coerceIn(0.02f, 0.98f) to
                        snapped(yMax - (o.y - pad) / (size.height - 2 * pad) * (yMax - yMin)).coerceIn(yMin, yMax)
                fun toPx(p: Pair<Float, Float>) = androidx.compose.ui.geometry.Offset(pad + p.first * (size.width - 2 * pad), pad + (yMax - p.second) / (yMax - yMin) * (size.height - 2 * pad))
                fun near(o: androidx.compose.ui.geometry.Offset, r: Float) = pts.value.indices.minByOrNull { (toPx(pts.value[it]) - o).getDistance() }
                    ?.takeIf { (toPx(pts.value[it]) - o).getDistance() < r } ?: -1
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val hit = near(down.position, 32.dp.toPx())
                    if (hit >= 0) {
                        // Drag a dot
                        dragging.intValue = hit; Haptics.tap(view)
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            if ((ch.position - down.position).getDistance() > 4f) moved = true
                            ch.consume()
                            val v = toVal(ch.position)
                            val i = dragging.intValue
                            val lo = pts.value.getOrNull(i - 1)?.first?.plus(0.02f) ?: 0.02f
                            val hi = pts.value.getOrNull(i + 1)?.first?.minus(0.02f) ?: 0.98f
                            pts.value = pts.value.toMutableList().also { it[i] = v.first.coerceIn(lo, hi) to v.second }
                            onLive(ser())
                        }
                        dragging.intValue = -1
                        if (moved) onChange(ser())
                        else {
                            // A quick second tap on the same dot removes it
                            val second = withTimeoutOrNull(300) { awaitFirstDown() }
                            if (second != null && near(second.position, 32.dp.toPx()) == hit) {
                                pts.value = pts.value.filterIndexed { k, _ -> k != hit }; Haptics.reject(view); onLive(ser()); onChange(ser())
                            }
                        }
                    } else {
                        // Tap empty space: add a dot there
                        val up = waitForUpOrCancellation()
                        if (up != null && pts.value.size < 8) {
                            pts.value = (pts.value + toVal(up.position)).sortedBy { it.first }
                            Haptics.tap(view); onLive(ser()); onChange(ser())
                        }
                    }
                }
            }) {
            val pad = 24.dp.toPx()
            val w = size.width - 2 * pad; val h = size.height - 2 * pad
            fun px(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(pad + x * w, pad + (yMax - y) / (yMax - yMin) * h)
            val steps = if (snapNow.value) 20 else 4
            for (i in 0..steps) { val x = pad + w * i / steps; drawLine(grid.copy(alpha = if (snapNow.value) 0.5f else 1f), androidx.compose.ui.geometry.Offset(x, pad), androidx.compose.ui.geometry.Offset(x, pad + h), 1f) }
            drawLine(grid, px(0f, 0f), px(1f, 0f), 2f)
            drawLine(grid, px(0f, 1f), px(1f, 1f), 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            val easing = CurveEasing(pts.value)
            val path = androidx.compose.ui.graphics.Path()
            for (i in 0..160) { val x = i / 160f; val p = px(x, easing.transform(x)); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            listOf(0f to 0f, 1f to 1f).forEach { (x, y) -> drawCircle(grid, 9f, px(x, y)) }
            pts.value.forEachIndexed { i, (x, y) ->
                val c = px(x, y)
                drawCircle(accent.copy(alpha = 0.18f), if (i == dragging.intValue) 38f else 26f, c)
                drawCircle(dotFill, 14f, c)
                drawCircle(accent, 14f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
            }
        }
        // Numbers for the dot you're dragging
        val d = dragging.intValue
        if (d >= 0) pts.value.getOrNull(d)?.let { (x, y) ->
            Text("time ${"%.2f".format(x)}  ·  position ${"%.2f".format(y)}", style = MaterialTheme.typography.labelMedium, color = label,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

/** Curve studio: pick a part of the app, start from a preset or a saved curve, bend it, see it live. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun CurveStudio(s: Settings) {
    val view = LocalView.current
    var cat by remember { mutableStateOf(Motion.Cat.ALL) }
    var menu by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    val (savedPts, savedDur) = Motion.curveFor(cat, s)
    var live by remember(cat, savedPts) { mutableStateOf(savedPts) }
    val overrides = remember(s.curveOverrides) { runCatching { org.json.JSONObject(s.curveOverrides) }.getOrDefault(org.json.JSONObject()) }
    val customized = cat != Motion.Cat.ALL && overrides.has(cat.key)
    val saved = remember(s.savedCurves) { runCatching { org.json.JSONArray(s.savedCurves) }.getOrDefault(org.json.JSONArray()) }

    fun write(points: String = live, duration: Int = savedDur) {
        if (cat == Motion.Cat.ALL) upd { it.copy(curvePoints = points, curveDuration = duration) }
        else {
            val o = org.json.JSONObject(s.curveOverrides).put(cat.key, org.json.JSONObject().put("p", points).put("d", duration))
            upd { it.copy(curveOverrides = o.toString()) }
        }
    }

    HubCard {
        // Category picker
        Box {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { menu = true }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Editing: ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(cat.label, style = MaterialTheme.typography.labelLarge)
                if (customized) Text("  • custom", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("  ▾", style = MaterialTheme.typography.labelLarge)
            }
            androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                Motion.Cat.entries.forEach { c ->
                    androidx.compose.material3.DropdownMenuItem({
                        Column {
                            Text(c.label)
                            Text(if (c == Motion.Cat.ALL) "Used by everything you haven't customized" else if (overrides.has(c.key)) "Custom curve" else "Uses \"Everything\"",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, { cat = c; menu = false; Haptics.tap(view) })
                }
            }
        }
        CurveEditor(savedPts, s.curveSnap, Modifier.fillMaxWidth().height(230.dp).padding(vertical = 10.dp),
            onLive = { live = it }, onChange = { live = it; write(it) })
        Text("Tap to add a dot · drag to bend · double-tap a dot to remove. Dots above the dashed line overshoot.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        CategoryPreview(cat, live, savedDur)
    }
    SliderRow("Duration (${cat.label.lowercase()})", savedDur.toFloat(), 150f..1200f, "$savedDur ms") { v -> write(duration = v.toInt()) }
    SwitchRow("Snap dots to grid", null, s.curveSnap) { v -> upd { it.copy(curveSnap = v) } }
    SectionTitle("Start from")
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Motion.curvePresets.forEach { (name, pts) -> Pill(name, live == pts) { live = pts; write(pts); Haptics.tap(view) } }
        for (i in 0 until saved.length()) {
            val o = saved.getJSONObject(i)
            Pill("★ " + o.optString("name"), live == o.optString("p")) { live = o.optString("p"); write(o.optString("p"), o.optInt("d", savedDur)); Haptics.tap(view) }
        }
    }
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton({ saveDialog = true }) { Text("Save this curve") }
        if (customized) TextButton({
            val o = org.json.JSONObject(s.curveOverrides).apply { remove(cat.key) }
            upd { it.copy(curveOverrides = o.toString()) }
        }) { Text("Use \"Everything\" curve") }
        if (cat == Motion.Cat.ALL) TextButton({ upd { it.copy(curvePoints = Settings().curvePoints, curveDuration = Settings().curveDuration) } }) { Text("Reset") }
    }
    if (saved.length() > 0) TextButton({ upd { it.copy(savedCurves = "[]") } }, Modifier.padding(horizontal = 8.dp)) { Text("Delete saved curves") }

    if (saveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save curve") },
            text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val a = org.json.JSONArray(s.savedCurves).put(org.json.JSONObject().put("name", name.ifBlank { "Curve ${saved.length() + 1}" }).put("p", live).put("d", savedDur))
                    upd { it.copy(savedCurves = a.toString()) }; saveDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ saveDialog = false }) { Text("Cancel") } },
        )
    }
}

/** Looping preview that matches what the category animates. Follows your finger while you drag. */
@Composable
private fun CategoryPreview(cat: Motion.Cat, points: String, duration: Int) {
    val st = LocalChatStyle.current
    val p = remember { Animatable(0f) }
    LaunchedEffect(points, duration, cat) {
        val e = Motion.easing(points)
        while (true) {
            p.snapTo(0f)
            p.animateTo(1f, androidx.compose.animation.core.tween(duration, easing = e))
            kotlinx.coroutines.delay(450)
        }
    }
    val v = p.value
    BoxWithConstraints(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f))) {
        val w = maxWidth
        when (cat) {
            Motion.Cat.ALL, Motion.Cat.PAGES -> {
                // A page sliding in over another, like opening a chat
                Box(Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    Text("Messages", Modifier.padding(10.dp), style = MaterialTheme.typography.titleSmall)
                }
                Box(Modifier.fillMaxSize().padding(10.dp).graphicsLayer { translationX = (1f - v) * w.toPx() }
                    .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.background)) {
                    Text("Chat", Modifier.padding(10.dp), style = MaterialTheme.typography.titleSmall)
                    Text("Hey!", color = st.sentText, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        .clip(st.bubble(true, true, true)).background(st.sent).padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Motion.Cat.BUBBLES -> Text("On my way 🚗", color = st.sentText, modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp)
                .graphicsLayer { translationY = (1f - v) * 60.dp.toPx(); val sc = 0.85f + 0.15f * v; scaleX = sc; scaleY = sc; alpha = v.coerceIn(0f, 1f)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f) }
                .clip(st.bubble(true, true, true)).background(st.sent).padding(horizontal = 14.dp, vertical = 8.dp))
            Motion.Cat.REACTIONS -> Box(Modifier.align(Alignment.Center)) {
                Text("Nice!", color = st.recvText, modifier = Modifier.clip(st.bubble(false, true, true)).background(st.recv).padding(horizontal = 16.dp, vertical = 10.dp))
                Text("❤️", fontSize = 22.sp, modifier = Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-12).dp).graphicsLayer { scaleX = v; scaleY = v })
            }
            Motion.Cat.MENUS -> Column(Modifier.align(Alignment.TopEnd).padding(10.dp).width(150.dp)
                .graphicsLayer { scaleX = 0.6f + 0.4f * v; scaleY = 0.6f + 0.4f * v; alpha = v.coerceIn(0f, 1f); transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f) }
                .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp)) {
                Text("Copy", style = MaterialTheme.typography.bodyMedium); Text("Star", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------- Messaging mode ----------------

@Composable
fun ModeSwitch() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    @Suppress("UNUSED_EXPRESSION") tick
    val hub = HubSms.isDefault(ctx)
    SectionTitle("Texting mode")
    ChoiceRow("Who handles your texts", listOf("google" to "Google Messages mode (RCS)", "hub" to "Orbit mode (SMS/MMS)"), if (hub) "hub" else "google") { v ->
        if (v == "hub" && !hub) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_SMS)) role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else if (v == "google" && hub) {
            // Android doesn't let apps give the role away; open the screen where you pick Google Messages
            runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    Text(if (hub) "Orbit sends and receives everything itself. No RCS (typing dots, read receipts, RCS reactions). To go back, pick Google Messages as your SMS app on the next screen."
        else "Google Messages stays your texting app and handles RCS in the background. Orbit sends through it so nothing looks different to anyone.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------- Updates ----------------

@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    val s by Hub.settings.flow.collectAsState()
    SettingRow("Version", "Build ${BuildConfig.VERSION_CODE}" + when {
        st.available -> " · build ${st.latest} is ready"
        st.latest > 0 -> " · up to date"
        else -> ""
    })
    if (st.progress in 0f..0.999f) LinearProgressIndicator({ st.progress }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    if (st.error.isNotBlank()) Text(st.error, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ scope.launch { Updater.check(force = true) } }) { Text("Check now") }
        if (st.available) Button({
            if (!Updater.canInstall(ctx)) ctx.startActivity(Updater.allowInstallsIntent(ctx))
            else Hub.scope.launch { Updater.install(ctx) }
        }) { Text("Update to build ${st.latest}") }
    }
    SwitchRow("Check for updates automatically", null, s.autoUpdate) { v -> upd { it.copy(autoUpdate = v) } }
}

/** Banner at the top of the inbox when a new build is out. */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    if (!st.available || hidden) return
    Banner(if (st.progress in 0f..0.999f) "Downloading update… ${(st.progress * 100).toInt()}%" else "Update ready: build ${st.latest}", "Update", {
        if (!Updater.canInstall(ctx)) ctx.startActivity(Updater.allowInstallsIntent(ctx)) else Hub.scope.launch { Updater.install(ctx) }
    }) { hidden = true }
}

// ---------------- Developer ----------------

@Composable
fun DeveloperPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf<List<Diagnostics.Check>>(emptyList()) }
    val extra = remember { mutableListOf<String>() }
    var report by remember { mutableStateOf("") }
    var selfTest by remember { mutableStateOf(false) }
    var hideTest by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) } }
    }
    fun rebuild() { report = Diagnostics.report(checks, extra) }

    val mock by com.yatesv.contacthub.engine.MockStore.state.collectAsState()
    SectionTitle("Location")
    SettingRow("Mock location", if (mock.active) "On: ${mock.item?.name.orEmpty()}" else "Fake GPS: single points, routes on real roads, favorites, schedules",
        androidx.compose.material.icons.Icons.Default.LocationOn, onClick = { Nav.go(Screen.MockLocation) })

    SectionTitle("Self-test")
    Text("Checks every feature it safely can. Nothing is sent to anyone. The report never includes what your messages say.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button({
        running = true; checks = emptyList()
        scope.launch {
            checks = Diagnostics.run(ctx) { progress = it }
            rebuild(); running = false; Haptics.confirm(view)
        }
    }, Modifier.fillMaxWidth().padding(16.dp), enabled = !running) { Text(if (running) "Testing…" else "Run full self-test") }
    if (running) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp))
        Text(progress, style = MaterialTheme.typography.bodySmall)
    }
    if (checks.isNotEmpty()) {
        val p = checks.count { it.status == Diagnostics.Status.PASS }
        val w = checks.count { it.status == Diagnostics.Status.WARN }
        val f = checks.count { it.status == Diagnostics.Status.FAIL }
        HubCard {
            Text("✅ $p passed   ⚠️ $w warnings   ❌ $f failed", style = MaterialTheme.typography.titleMedium)
            checks.filter { it.status == Diagnostics.Status.FAIL || it.status == Diagnostics.Status.WARN }.forEach { c ->
                Text("${if (c.status == Diagnostics.Status.FAIL) "❌" else "⚠️"} ${c.group} · ${c.name}: ${c.detail}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (report.isNotBlank()) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ save.launch("contacthub-selftest.txt") }) { Text("Download .txt") }
        OutlinedButton({ shareText(ctx, report) }) { Text("Share") }
    }

    SectionTitle("Try things by hand")
    SettingRow("Test RCS sender", "Sends \"Orbit test\" to YOUR OWN number through Google Messages") { TextButton({ selfTest = true }) { Text("Run") } }
    SettingRow("Test hiding methods", "Sends 3 texts to YOUR OWN number, one per method (pop-up, live mirror, snapshot), and picks the best for Auto") {
        TextButton({ hideTest = true }) { Text("Run") }
    }
    SettingRow("Test notification", "Shows a sample message notification") {
        TextButton({ scope.launch {
            Notifier.showMessage(ctx, "selftest", "Orbit", false, listOf("Orbit" to "This is a test notification"))
            extra += "Notification: shown"; rebuild()
        } }) { Text("Run") }
    }
    SettingRow("Test vibration") { TextButton({ val old = Hub.settings.value.haptics; upd { it.copy(haptics = true) }; Haptics.confirm(view); upd { it.copy(haptics = old) }; extra += "Vibration: fired"; rebuild() }) { Text("Run") } }
    SettingRow("Test send sound") { TextButton({ val old = Hub.settings.value.sendSound; upd { it.copy(sendSound = true) }; SendSound.play(); upd { it.copy(sendSound = old) }; extra += "Send sound: played"; rebuild() }) { Text("Run") } }
    SettingRow("Test screen effects") { TextButton({ Effects.current.value = Effect.FIREWORKS; extra += "Effects: fireworks shown"; rebuild() }) { Text("Run") } }
    SettingRow("Re-run setup wizard") { TextButton({ Nav.go(Screen.Setup) }) { Text("Open") } }

    SectionTitle("Activity log")
    SettingRow("Download activity log", "${HubLog.snapshot().size} lines: what the RCS sender saw and did, errors") {
        TextButton({ report = Diagnostics.report(checks, extra); save.launch("contacthub-log.txt") }) { Text("Save") }
    }
    SettingRow("Clear activity log") { TextButton({ HubLog.clear() }) { Text("Clear") } }

    if (selfTest) SelfSendDialog(onDone = { line -> selfTest = false; if (line != null) { extra += line; rebuild() } })
    if (hideTest) SelfSendDialog(title = "Test hiding methods", note = "Sends 3 real texts to the number below, one per hiding method. Watch the screen during each.",
        run = { ctx2, number ->
            val key = com.yatesv.contacthub.data.Phone.convKey(listOf(number))
            val results = mutableListOf<String>()
            var popupOk = false
            for (m in listOf("popup", "mirror", "snapshot")) {
                val r = Sender.send(ctx2, key, "Orbit hide test ($m)", listOf(number), forcedHide = m, skipReplyButton = true)
                val how = com.yatesv.contacthub.engine.MessagesAccessibility.lastHideResult()
                if (m == "popup" && r.ok && how.contains("small window OK")) popupOk = true
                results += "Hide test [$m]: sent=${r.ok} ${r.route} · $how ${r.error}"
                kotlinx.coroutines.delay(1500)
            }
            val winner = if (popupOk) "popup" else "snapshot"
            Hub.settings.update { it.copy(hideWinner = winner) }
            results += "Auto will use: $winner"
            results.joinToString("\n")
        },
        onDone = { line -> hideTest = false; if (line != null) { extra += line; rebuild() } })
}

@Composable
private fun SelfSendDialog(
    title: String = "Test the RCS sender",
    note: String = "This sends one real text, \"Orbit test\", to the number below. Use your own number.",
    run: (suspend (Context, String) -> String)? = null,
    onDone: (String?) -> Unit,
) {
    val ctx = LocalContext.current
    val mine = remember { HubSms.myNumbers(ctx).firstOrNull().orEmpty() }
    var number by remember { mutableStateOf(mine) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone(null) },
        title = { Text(title) },
        text = {
            Column {
                Text(note)
                androidx.compose.material3.OutlinedTextField(number, { number = it }, label = { Text("Your number") }, singleLine = true)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton({
                busy = true
                Hub.scope.launch {
                    val line = if (run != null) run(ctx, number) else {
                        val key = com.yatesv.contacthub.data.Phone.convKey(listOf(number))
                        val r = Sender.send(ctx, key, "Orbit test", listOf(number), skipReplyButton = true)
                        "RCS sender test: ok=${r.ok} route=${r.route} hide=${com.yatesv.contacthub.engine.MessagesAccessibility.lastHideResult()} ${r.error}"
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(line) }
                }
            }, enabled = !busy && number.count { it.isDigit() } >= 7) { Text(if (run != null) "Run test" else "Send test") }
        },
        dismissButton = { TextButton({ onDone(null) }, enabled = !busy) { Text("Cancel") } },
    )
}

fun shareText(ctx: Context, text: String) {
    val f = Diagnostics.writeFile(ctx, text)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------- Privacy & data, licenses ----------------

@Composable
fun PrivacyDataPage() {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    HubCard {
        Text("What Orbit reads", style = MaterialTheme.typography.titleMedium)
        Text("Your texts, contacts, call log and Google/Samsung Messages notifications, so it can show and send your messages. It only reads; in Google Messages mode it never changes your texts.",
            style = MaterialTheme.typography.bodyMedium)
    }
    HubCard {
        Text("What stays on your phone", style = MaterialTheme.typography.titleMedium)
        Text("Everything Orbit saves: socials, notes, birthdays, settings, folders, reactions, the activity log and backups. There are no Orbit servers, accounts, ads, analytics or trackers.",
            style = MaterialTheme.typography.bodyMedium)
    }
    HubCard {
        Text("What leaves your phone", style = MaterialTheme.typography.titleMedium)
        Text("• Life360 (only if you sign in): your Life360 login token, to fetch your circle's locations.\n" +
            "• OpenStreetMap: map tiles for the location card (it sees which area is being shown).\n" +
            "• GitHub: checking for and downloading updates.\n" +
            "• Google Drive: your backup file, only if you connect it.\n" +
            "• Social search: what you search goes to Google or the social site.",
            style = MaterialTheme.typography.bodyMedium)
    }
    HubCard {
        Text("Good to know", style = MaterialTheme.typography.titleMedium)
        Text("Life360 doesn't offer an official way for other apps to connect. Orbit uses an unofficial one, which may go against Life360's terms. Life360 could block it. You can sign out anytime in Settings → Life360.",
            style = MaterialTheme.typography.bodyMedium)
    }
    SectionTitle("Delete")
    SettingRow("Delete all Orbit data", "Removes socials, notes, settings, logs, Life360 sign-in and backups. Your texts, contacts and calls stay.",
        onClick = { confirm = true })
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete all Orbit data?") },
        text = { Text("This can't be undone. Orbit will close and start fresh. Your texts, contacts and call history are not touched.") },
        confirmButton = { TextButton({ wipeAll(ctx) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

private fun wipeAll(ctx: Context) {
    runCatching { Hub.db.close() }
    ctx.deleteDatabase("hub.db")
    listOf("settings", "life360", "rcs", "listener").forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    runCatching { Backup.dir(ctx).deleteRecursively() }
    HubLog.clear()
    ctx.contentResolver.persistedUriPermissions.forEach { runCatching { ctx.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
    android.os.Process.killProcess(android.os.Process.myPid())
}

@Composable
fun LicensesPage() {
    val libs = listOf(
        "AndroidX (Compose, Room, WorkManager, Biometric, WebKit, Core)" to "Apache License 2.0 · Google",
        "Kotlin & kotlinx.coroutines" to "Apache License 2.0 · JetBrains",
        "Coil (image loading)" to "Apache License 2.0 · Coil contributors",
        "osmdroid (maps)" to "Apache License 2.0 · osmdroid contributors",
        "Map data" to "© OpenStreetMap contributors, available under the Open Database License (ODbL)",
        "Material Icons" to "Apache License 2.0 · Google",
        "Social app icons" to "Shown from the apps installed on your phone; they belong to their owners. Not copied into Orbit.",
    )
    libs.forEach { (n, l) -> SettingRow(n, l) }
    Text("Full license texts: apache.org/licenses/LICENSE-2.0 and opendatacommons.org/licenses/odbl",
        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

@Suppress("unused") private val keepShapes = listOf(CircleShape, RoundedCornerShape(1.dp))
@Suppress("unused") private val keepColor = Color.Unspecified
@Suppress("unused") private fun Modifier.keepClick() = clickable { }
@Suppress("unused") private val keepBox: @Composable () -> Unit = { Box(Modifier) }
