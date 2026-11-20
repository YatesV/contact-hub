package com.yatesv.contacthub.phone

import android.os.Bundle
import android.telecom.Call
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.ui.Avatar
import com.yatesv.contacthub.ui.HubTheme
import kotlinx.coroutines.delay

/** Themed call screen: their photo / wallpaper in the background. */
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

@Composable
private fun CallScreen(c: CallUi) {
    val contact = Hub.contacts.cached(c.number)
    val name = contact?.name ?: Phone.pretty(c.number).ifBlank { "Unknown" }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var keypad by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val wallpaper = Hub.settings.value.wallpaper

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val bg = contact?.photo ?: wallpaper.ifBlank { null }
        if (bg != null) AsyncImage(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.45f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Avatar(name, contact?.photo, 112.dp)
            Spacer(Modifier.height(16.dp))
            Text(name, color = Color.White, fontSize = 30.sp, style = MaterialTheme.typography.headlineMedium)
            if (contact != null) Text(Phone.pretty(c.number), color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(8.dp))
            val status = when (c.state) {
                Call.STATE_RINGING -> "Incoming call"
                Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"
                Call.STATE_HOLDING -> "On hold"
                Call.STATE_ACTIVE -> {
                    val s = ((now - c.connectedAt) / 1000).coerceAtLeast(0)
                    "%d:%02d".format(s / 60, s % 60)
                }
                Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
                else -> ""
            }
            Text(status, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp)
            Spacer(Modifier.weight(1f))

            if (c.state == Call.STATE_RINGING) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(Icons.Default.CallEnd, "Decline", Color(0xFFE53935)) { CallManager.hangup() }
                    RoundButton(Icons.Default.Call, "Answer", Color(0xFF43A047)) { CallManager.answer() }
                }
            } else {
                if (keypad) {
                    Keypad { CallManager.dtmf(it) }
                    Spacer(Modifier.height(16.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToggleButton(Icons.Default.MicOff, "Mute", c.muted) { CallManager.setMuted(!c.muted) }
                    ToggleButton(Icons.Default.Dialpad, "Keypad", keypad) { keypad = !keypad }
                    ToggleButton(Icons.Default.VolumeUp, "Speaker", c.speaker) { CallManager.setSpeaker(!c.speaker) }
                    ToggleButton(Icons.Default.Pause, "Hold", c.onHold) { CallManager.toggleHold() }
                }
                Spacer(Modifier.height(32.dp))
                RoundButton(Icons.Default.CallEnd, "End", Color(0xFFE53935)) { CallManager.hangup() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick, Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = color)) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Text(label, color = Color.White, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.18f))
            .then(Modifier), contentAlignment = Alignment.Center) {
            androidx.compose.material3.IconButton(onClick) { Icon(icon, label, tint = if (on) Color.Black else Color.White) }
        }
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
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
                            .clickable(src, androidx.compose.material3.ripple()) { com.yatesv.contacthub.ui.Haptics.tap(view); onKey(ch) },
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
