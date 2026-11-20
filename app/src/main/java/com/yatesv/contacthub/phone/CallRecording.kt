package com.yatesv.contacthub.phone

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.R
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.engine.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records calls with the microphone. Android doesn't let regular apps tap the call line itself,
 * so this hears your side clearly and the other person through the earpiece/speaker
 * (best with speaker on). While Orbit's accessibility service is on, Android lets it keep the mic during calls.
 */
object CallRecorder {
    /** When the current recording started (0 = not recording) */
    val startedAt = MutableStateFlow(0L)
    private var rec: MediaRecorder? = null
    private var file: File? = null

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "CallRecordings").apply { mkdirs() }

    fun canRecord(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Starts the foreground service that owns the recorder (keeps recording if you leave the call screen). */
    fun start(ctx: Context, number: String): Boolean {
        if (startedAt.value > 0) return true
        if (!canRecord(ctx)) return false
        return runCatching {
            ctx.startForegroundService(Intent(ctx, CallRecordService::class.java).putExtra("number", number))
            true
        }.getOrElse { HubLog.w("CallRec", "start failed", it); false }
    }

    /** Stops, saves, and ends the recording service (stopService works even from the background). */
    fun stop(ctx: Context) {
        if (startedAt.value == 0L && rec == null) return
        val f = finish()
        if (f != null) Hub.banner.value = "Call recording saved"
        runCatching { ctx.stopService(Intent(ctx, CallRecordService::class.java)) }
    }

    internal fun begin(ctx: Context, number: String): Boolean {
        if (rec != null) return true
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val f = File(dir(ctx), "${Phone.norm(number).ifBlank { "unknown" }}_$stamp.m4a")
        val accessibilityOn = runCatching { com.yatesv.contacthub.engine.MessagesAccessibility.enabledInSettings(ctx) }.getOrDefault(false)
        // Try the sources Android is most likely to allow during a call
        val sources = buildList {
            if (accessibilityOn) add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
            add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }
        for (src in sources) {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            val ok = runCatching {
                r.setAudioSource(src)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(96_000)
                r.setAudioChannels(1)
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                true
            }.getOrElse { HubLog.w("CallRec", "source $src failed", it); false }
            if (ok) { rec = r; file = f; startedAt.value = System.currentTimeMillis(); return true }
            runCatching { r.release() }
            f.delete()
        }
        return false
    }

    /** Stops and keeps the file if it's long enough to be useful. */
    internal fun finish(): File? {
        val r = rec ?: run { startedAt.value = 0; return null }
        val f = file
        val long = System.currentTimeMillis() - startedAt.value >= 1500
        val ok = runCatching { r.stop(); true }.getOrDefault(false)
        runCatching { r.release() }
        rec = null; file = null; startedAt.value = 0
        if (!ok || !long) { f?.delete(); return null }
        return f
    }

    data class Recording(val file: File, val number: String, val date: Long, val seconds: Int)

    fun list(ctx: Context, number: String? = null): List<Recording> {
        val want = number?.let { Phone.norm(it) }
        return dir(ctx).listFiles { f -> f.extension == "m4a" }.orEmpty()
            .mapNotNull { f ->
                val num = f.nameWithoutExtension.substringBefore('_')
                if (want != null && num != want && (num.length < 10 || num.takeLast(10) != want.takeLast(10))) return@mapNotNull null
                Recording(f, num, f.lastModified(), 0)
            }
            .sortedByDescending { it.date }
    }
}

/** Foreground service so the mic keeps recording when the call screen isn't showing. */
class CallRecordService : Service() {
    companion object { private const val NOTIF = 7700 }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra("number").orEmpty()
        val n = NotificationCompat.Builder(this, Notifier.CH_STATUS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Recording call")
            .setContentText(Hub.contacts.cached(number)?.name ?: Phone.pretty(number))
            .setOngoing(true)
            .setUsesChronometer(true)
            .build()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF, n)
            true
        }.getOrElse { HubLog.w("CallRec", "foreground failed", it); false }
        if (!started || !CallRecorder.begin(this, number)) {
            Hub.banner.value = "Couldn't start recording. Android blocked the microphone during this call."
            if (started) stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { CallRecorder.finish(); super.onDestroy() }
}

/** Recordings for one person, shown on their contact page. */
@Composable
fun CallRecordingsList(number: String) {
    val ctx = LocalContext.current
    val saved by CallRecorder.startedAt.collectAsState()
    var version by remember { mutableStateOf(0) }
    val items = remember(number, saved, version) { CallRecorder.list(ctx, number) }
    if (items.isEmpty()) return
    var playing by remember { mutableStateOf<File?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { player.value?.release() } } }
    fun stopPlaying() { runCatching { player.value?.release() }; player.value = null; playing = null }
    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("Call recordings", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
        items.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable {
                if (playing == r.file) stopPlaying()
                else {
                    stopPlaying()
                    runCatching {
                        player.value = MediaPlayer().apply { setDataSource(r.file.absolutePath); setOnCompletionListener { stopPlaying() }; prepare(); start() }
                        playing = r.file
                    }.onFailure { Hub.banner.value = "Couldn't play that recording" }
                }
            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (playing == r.file) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(fmt.format(Date(r.date)))
                    Text("%.1f MB".format(r.file.length() / 1_048_576.0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", r.file)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { Hub.banner.value = "Couldn't share it" }
                }) { Icon(Icons.Default.Share, "Share") }
                IconButton({ if (playing == r.file) stopPlaying(); r.file.delete(); version++ }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}
