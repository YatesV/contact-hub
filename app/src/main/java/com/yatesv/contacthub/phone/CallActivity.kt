package com.yatesv.contacthub.phone

import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.ContactInfo
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.ui.Avatar
import com.yatesv.contacthub.ui.Haptics
import com.yatesv.contacthub.ui.HubTheme
import kotlinx.coroutines.delay

/** The in-call screen. */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            HubTheme {
                val ui by CallManager.ui.collectAsState()
                LaunchedEffect(ui == null) { if (ui == null) { delay(800); if (CallManager.ui.value == null) finish() } }
                ui?.let { CallScreen(it) } ?: Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }
    }
}

private val Red = Color(0xFFE5484D)
private val Green = Color(0xFF30A46C)
private val Glass = Color.White.copy(alpha = 0.12f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallScreen(c: CallUi) {
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val contact = Hub.contacts.cached(c.number)
    val conference = c.participants.size > 1
    val name = when {
        conference -> "Conference call"
        else -> contact?.name ?: Phone.pretty(c.number).ifBlank { "Unknown" }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }
    var keypad by remember { mutableStateOf(false) }
    var audioSheet by remember { mutableStateOf(false) }
    var addCall by remember { mutableStateOf(false) }
    var recordAsk by remember { mutableStateOf(false) }
    var textInstead by remember { mutableStateOf(false) }
    val routes by CallManager.routes.collectAsState()
    val recStart by CallRecorder.startedAt.collectAsState()
    val recording = recStart > 0
    val wallpaper = Hub.settings.value.wallpaper
    val ringing = c.state == Call.STATE_RINGING
    val dialing = c.state == Call.STATE_DIALING || c.state == Call.STATE_CONNECTING

    val spam = remember(c.number) {
        com.yatesv.contacthub.data.Spam.reason(c.number, contact != null, Hub.orbit.isReportedSpam(c.number), c.verificationFailed,
            runCatching { com.yatesv.contacthub.engine.HubSms.myNumbers(Hub.app).toList() }.getOrDefault(emptyList()))
    }
    var reported by remember { mutableStateOf(false) }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { if (!CallRecorder.start(ctx, c.number)) Hub.banner.value = "Couldn't start recording" }
        else Hub.banner.value = "Orbit needs the microphone to record calls"
    }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { CallManager.refreshRoutes() }
    fun startRecording() {
        if (!CallRecorder.canRecord(ctx)) micPerm.launch(android.Manifest.permission.RECORD_AUDIO)
        else if (!CallRecorder.start(ctx, c.number)) Hub.banner.value = "Couldn't start recording"
    }
    // Auto-record when the call connects (if turned on)
    LaunchedEffect(c.state == Call.STATE_ACTIVE) {
        if (c.state == Call.STATE_ACTIVE && Hub.orbit.autoRecordCalls() && CallRecorder.startedAt.value == 0L && CallRecorder.canRecord(ctx)) CallRecorder.start(ctx, c.number)
    }
    val extraRoutes = routes.any { it.kind == AudioRoute.Kind.BLUETOOTH || it.kind == AudioRoute.Kind.WIRED || it.kind == AudioRoute.Kind.STREAMING }
    fun openAudio() {
        if (Build.VERSION.SDK_INT in 31..33 && ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            btPerm.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        CallManager.refreshRoutes(); audioSheet = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Background: their photo, softly blurred, or a slow glow in your accent color
        val bg = contact?.photo ?: wallpaper.ifBlank { null }
        val accent = Color(Hub.settings.value.accent)
        if (bg != null) {
            AsyncImage(bg, null, Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier).graphicsLayer { alpha = 0.6f },
                contentScale = ContentScale.Crop)
        } else {
            val t = rememberInfiniteTransition(label = "glow")
            val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(
                listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f), Color.Black),
                center = androidx.compose.ui.geometry.Offset(300f + 500f * shift, 500f + 300f * shift), radius = 1400f)))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.4f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.92f))))

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // ---- Top: status chips
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    val blink = rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b")
                    Chip(Red.copy(alpha = 0.85f)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = blink.value)))
                        Spacer(Modifier.width(6.dp))
                        Text("REC " + clock(now - recStart), color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                val routeLabel = routes.firstOrNull { it.active }?.takeIf { it.kind != AudioRoute.Kind.PHONE }?.label
                if (routeLabel != null && !ringing) Chip(Glass) {
                    Icon(routeIcon(c.route), null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(routeLabel, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.weight(0.6f))

            // ---- Who
            Box(contentAlignment = Alignment.Center) {
                if (ringing || dialing) PulseRings(if (ringing) Green else accent)
                if (conference) Avatar("+${c.participants.size}", null, 128.dp, group = true)
                else Box(Modifier.size(132.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape)) {
                    if (contact?.photo != null) AsyncImage(contact.photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Avatar(name, null, 132.dp)
                }
            }
            Spacer(Modifier.height(20.dp))
            if (spam != null && !c.outgoing) {
                Chip(Red.copy(alpha = 0.85f)) { Text("⚠ Possible spam · $spam", color = Color.White, fontSize = 13.sp) }
                Spacer(Modifier.height(10.dp))
            }
            Text(name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (conference) {
                Spacer(Modifier.height(8.dp))
                c.participants.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Hub.contacts.cached(p)?.name ?: Phone.pretty(p), color = Color.White.copy(alpha = 0.8f))
                        androidx.compose.material3.IconButton({ CallManager.dropFromConference(p) }, Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Hang up on this person", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            } else if (contact != null) Text(Phone.pretty(c.number), color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            val status = when (c.state) {
                Call.STATE_RINGING -> if (c.other != null) "Call waiting" else "Incoming call"
                Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"
                Call.STATE_HOLDING -> "On hold"
                Call.STATE_ACTIVE -> clock(now - c.connectedAt)
                Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
                else -> ""
            }
            Text(status, color = Color.White.copy(alpha = 0.85f), fontSize = 17.sp,
                fontFamily = if (c.state == Call.STATE_ACTIVE) FontFamily.Monospace else FontFamily.Default)

            // ---- The other call (on hold or waiting)
            c.other?.let { o ->
                Spacer(Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(18.dp), color = Glass) {
                    Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Pause, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(if (o.participants > 1) "Conference (${o.participants})" else Hub.contacts.cached(o.number)?.name ?: Phone.pretty(o.number),
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (o.state == Call.STATE_HOLDING) "On hold" else if (o.state == Call.STATE_ACTIVE) "On the line" else "Waiting",
                                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        if (!ringing) TextButton({ Haptics.tap(view); CallManager.swap() }) { Text("Swap", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (ringing) {
                // ---- Incoming
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (contact == null && !reported) SmallAction(Icons.Default.Close, "Spam & block") {
                        reported = true; Hub.orbit.setSpam(c.number, true); Hub.contacts.setBlocked(c.number, true); CallManager.hangup()
                    }
                    if (c.number.isNotBlank()) SmallAction(Icons.AutoMirrored.Filled.Message, "Text instead") { textInstead = true }
                }
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BigButton(Icons.Default.CallEnd, "Decline", Red) { CallManager.hangup() }
                    BigButton(Icons.Default.Call, if (c.other != null) "Hold & answer" else "Answer", Green) { CallManager.answer() }
                }
            } else {
                AnimatedContent(keypad, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pad") { showPad ->
                    if (showPad) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        var typed by remember { mutableStateOf("") }
                        Text(typed.ifEmpty { " " }, color = Color.White, fontSize = 26.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        Spacer(Modifier.height(10.dp))
                        Keypad { ch -> typed = (typed + ch).takeLast(24); CallManager.dtmf(ch) }
                        TextButton({ keypad = false }) { Text("Hide keypad", color = Color.White) }
                    } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Control(Icons.Default.MicOff, "Mute", c.muted) { CallManager.setMuted(!c.muted) }
                            Control(Icons.Default.Dialpad, "Keypad", false) { keypad = true }
                            val activeRoute = routes.firstOrNull { it.active }
                            Control(routeIcon(c.route), if (extraRoutes) activeRoute?.label ?: "Audio" else "Speaker",
                                on = c.route != AudioRoute.Kind.PHONE, badge = extraRoutes) {
                                if (extraRoutes) openAudio() else CallManager.setSpeaker(c.route != AudioRoute.Kind.SPEAKER)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Control(Icons.Default.Pause, if (c.onHold) "Resume" else "Hold", c.onHold) { CallManager.toggleHold() }
                            when {
                                c.other != null && c.canMerge -> Control(Icons.AutoMirrored.Filled.CallMerge, "Merge", false) {
                                    Haptics.confirm(view); if (!CallManager.merge()) Hub.banner.value = "Your carrier won't merge these calls"
                                }
                                c.other != null -> Control(Icons.Default.SwapCalls, "Swap", false) { CallManager.swap() }
                                else -> Control(Icons.Default.AddIcCall, "Add call", false, enabled = c.canAddCall) { addCall = true }
                            }
                            Control(Icons.Default.FiberManualRecord, if (recording) "Stop" else "Record", recording, onColor = Red, enabled = c.state == Call.STATE_ACTIVE || recording) {
                                if (recording) CallRecorder.stop(ctx)
                                else if (!Hub.orbit.recordNoteSeen()) recordAsk = true
                                else startRecording()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                // ---- End
                val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(Modifier.width(200.dp).height(64.dp).then(com.yatesv.contacthub.ui.pressScaleModifier(src)).clip(RoundedCornerShape(32.dp)).background(Red)
                    .clickable(src, androidx.compose.material3.ripple()) { Haptics.confirm(view); CallManager.hangup() },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CallEnd, "End call", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    // ---- Audio picker: phone / speaker / each Bluetooth device / wired
    if (audioSheet) ModalBottomSheet({ audioSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Audio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            routes.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { Haptics.tap(view); CallManager.selectRoute(r); audioSheet = false }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(routeIcon(r.kind), null, tint = if (r.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.label, Modifier.weight(1f).padding(start = 16.dp), fontWeight = if (r.active) FontWeight.SemiBold else FontWeight.Normal)
                    if (r.active) Icon(Icons.Default.Check, "In use", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (routes.isEmpty()) Text("No audio devices found", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // ---- Add a second call
    if (addCall) ModalBottomSheet({ addCall = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AddCallSheet { number -> addCall = false; CallManager.place(ctx, number) }
    }

    // ---- Decline with a text
    if (textInstead) {
        val replies = listOf("Can't talk right now. I'll call you back.", "On my way.", "Call you in a few minutes.", "What's up?")
        AlertDialog(
            onDismissRequest = { textInstead = false },
            title = { Text("Text instead") },
            text = {
                Column {
                    replies.forEach { r ->
                        Text(r, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            textInstead = false
                            com.yatesv.contacthub.engine.SendQueue.add(ctx, com.yatesv.contacthub.ui.numberKey(c.number), r, drive = false)
                            CallManager.hangup()
                        }.padding(vertical = 12.dp, horizontal = 4.dp))
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton({ textInstead = false }) { Text("Cancel") } },
        )
    }

    // ---- First time recording: the honest version of how it works
    if (recordAsk) {
        var auto by remember { mutableStateOf(Hub.orbit.autoRecordCalls()) }
        AlertDialog(
            onDismissRequest = { recordAsk = false },
            title = { Text("Record calls") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Orbit records with the microphone. Your voice is always clear; the other person is clearest with speaker on.")
                    Text("Louisiana only needs one person on the call to agree (you), but some states need everyone's OK, so tell people if they might be in one of those.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.clickable { auto = !auto }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(auto, { auto = it })
                        Text("Record every call automatically")
                    }
                }
            },
            confirmButton = { TextButton({
                recordAsk = false; Hub.orbit.setRecordNoteSeen(); Hub.orbit.setAutoRecordCalls(auto); startRecording()
            }) { Text("Start recording") } },
            dismissButton = { TextButton({ recordAsk = false }) { Text("Cancel") } },
        )
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun routeIcon(k: AudioRoute.Kind): ImageVector = when (k) {
    AudioRoute.Kind.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    AudioRoute.Kind.BLUETOOTH -> Icons.Default.Bluetooth
    AudioRoute.Kind.WIRED -> Icons.Default.Headset
    AudioRoute.Kind.STREAMING -> Icons.Default.Cast
    AudioRoute.Kind.PHONE -> Icons.Default.PhoneInTalk
}

@Composable
private fun Chip(color: Color, content: @Composable () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** Soft rings spreading out from the photo while it rings / dials. */
@Composable
private fun PulseRings(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    repeat(3) { i ->
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, delayMillis = i * 800, easing = LinearEasing)), label = "r$i")
        Box(Modifier.size(132.dp).scale(1f + p * 0.9f).clip(CircleShape).background(color.copy(alpha = 0.35f * (1f - p))))
    }
}

@Composable
private fun Control(icon: ImageVector, label: String, on: Boolean, onColor: Color = Color.White, enabled: Boolean = true, badge: Boolean = false, onClick: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(Modifier.width(92.dp).graphicsLayer { alpha = if (enabled) 1f else 0.35f }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).then(com.yatesv.contacthub.ui.pressScaleModifier(src)).clip(RoundedCornerShape(24.dp))
            .background(if (on) onColor else Glass)
            .clickable(src, androidx.compose.material3.ripple(), enabled = enabled) { Haptics.tap(view); onClick() },
            contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (on && onColor == Color.White) Color.Black else Color.White, modifier = Modifier.size(28.dp))
            if (badge) Text("▾", color = if (on && onColor == Color.White) Color.Black else Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp))
        }
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun BigButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(78.dp).then(com.yatesv.contacthub.ui.pressScaleModifier(src)).clip(CircleShape).background(color)
            .clickable(src, androidx.compose.material3.ripple()) { Haptics.confirm(view); onClick() }, contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Text(label, color = Color.White, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = Color.White.copy(alpha = 0.85f))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Type a number or search a name, then call (the current call goes on hold). */
@Composable
private fun AddCallSheet(onCall: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }
    LaunchedEffect(Unit) { all = runCatching { Hub.contacts.all() }.getOrDefault(emptyList()) }
    val digits = q.filter { it.isDigit() || it == '+' }
    val matches = remember(q, all) {
        if (q.isBlank()) emptyList()
        else all.filter { it.name.contains(q, true) || (digits.length >= 3 && Phone.norm(it.number).contains(digits)) }.distinctBy { Phone.norm(it.number) }.take(5)
    }
    Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Add call", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Name or number") },
            trailingIcon = { if (q.isNotEmpty()) androidx.compose.material3.IconButton({ q = q.dropLast(1) }) { Icon(Icons.AutoMirrored.Filled.Backspace, "Delete") } })
        matches.forEach { m ->
            Row(Modifier.fillMaxWidth().clickable { onCall(m.number) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.name, m.photo, 36.dp)
                Column(Modifier.padding(start = 12.dp)) { Text(m.name); Text(Phone.pretty(m.number), style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (matches.isEmpty()) Keypad { ch -> q += ch }
        Spacer(Modifier.height(16.dp))
        val canCall = digits.length >= 3
        Box(Modifier.size(68.dp).clip(CircleShape).background(if (canCall) Green else Green.copy(alpha = 0.35f)).clickable(enabled = canCall) { onCall(digits) },
            contentAlignment = Alignment.Center) { Icon(Icons.Default.Call, "Call", tint = Color.White, modifier = Modifier.size(30.dp)) }
    }
}

/** Round dial pad keys with letters under the numbers. */
@Composable
fun Keypad(onKey: (Char) -> Unit) {
    val letters = mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+")
    val view = androidx.compose.ui.platform.LocalView.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("123", "456", "789", "*0#").forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                r.forEach { ch ->
                    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Column(
                        Modifier.size(76.dp).then(com.yatesv.contacthub.ui.pressScaleModifier(src)).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(src, androidx.compose.material3.ripple()) { Haptics.tap(view); onKey(ch) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text(ch.toString(), fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurface)
                        letters[ch]?.let { Text(it, fontSize = 10.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
