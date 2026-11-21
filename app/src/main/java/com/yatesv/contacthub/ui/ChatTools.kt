package com.yatesv.contacthub.ui

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.Msg
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Quote
import com.yatesv.contacthub.data.VCard
import com.yatesv.contacthub.engine.HubSms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Files Orbit makes to send (voice notes, edited photos, contact cards, GIFs). */
object OutFiles {
    fun new(ctx: Context, prefix: String, ext: String): File {
        val dir = File(ctx.cacheDir, "out").apply { mkdirs() }
        // Keep the folder small: drop files older than a day
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        return File(dir, "$prefix-${System.currentTimeMillis()}.$ext")
    }
    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
}

// ---------------- Delete ----------------

object MsgActions {
    /**
     * Orbit mode: deletes from the phone. Google Messages mode: Android only lets the texting app delete,
     * so the messages are removed from Orbit (they stay in Google Messages).
     */
    suspend fun delete(ctx: Context, convKey: String, msgs: List<Msg>): String = withContext(Dispatchers.IO) {
        val isDefault = HubSms.isDefault(ctx)
        var fromPhone = 0
        val hide = ArrayList<String>()
        for (m in msgs) {
            val id = m.key.substringAfter(':').toLongOrNull()
            when {
                m.key.startsWith("q:") -> id?.let { com.yatesv.contacthub.engine.SendQueue.cancel(it) }
                m.key.startsWith("l:") && id != null -> Hub.db.dao().deleteLocal(id)
                isDefault && id != null && m.key.startsWith("s:") ->
                    if (runCatching { ctx.contentResolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null) }.getOrDefault(0) > 0) fromPhone++ else hide += m.key
                isDefault && id != null && m.key.startsWith("m:") ->
                    if (runCatching { ctx.contentResolver.delete(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id), null, null) }.getOrDefault(0) > 0) fromPhone++ else hide += m.key
                else -> hide += m.key
            }
        }
        if (hide.isNotEmpty()) Hub.orbit.hideMsgs(convKey, hide)
        Hub.changed()
        val n = msgs.size
        when {
            hide.isEmpty() -> if (n == 1) "Message deleted" else "$n messages deleted"
            isDefault -> "Deleted"
            else -> (if (n == 1) "Removed from Orbit" else "$n removed from Orbit") + ". Google Messages still has them."
        }
    }
}

// ---------------- Forward ----------------

/** Waiting to be sent in the chat you forwarded to. */
object Forwarding {
    data class Pending(val text: String, val media: List<Uri>)
    val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()
}

@Composable
fun ForwardDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf(emptyList<com.yatesv.contacthub.data.ContactInfo>()) }
    LaunchedEffect(Unit) { contacts = runCatching { Hub.contacts.all().distinctBy { Phone.norm(it.number) } }.getOrDefault(emptyList()) }
    val recent = InboxData.cache.orEmpty().filter { !it.meta.hidden }.take(40)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward to") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search name or number") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) })
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    val digits = q.filter { it.isDigit() }
                    if (digits.length >= 7) Row(Modifier.fillMaxWidth().clickable { onPick(Phone.convKey(listOf(digits))) }.padding(vertical = 10.dp)) {
                        Text("Send to ${Phone.pretty(digits)}", color = MaterialTheme.colorScheme.primary)
                    }
                    val chats = recent.filter { q.isBlank() || it.name.contains(q, true) }
                    if (chats.isNotEmpty()) Text("Recent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    chats.forEach { r ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(r.key) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(r.name, r.photo, 36.dp, group = r.isGroup); Spacer(Modifier.width(12.dp))
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (q.isNotBlank()) {
                        val people = contacts.filter { it.name.contains(q, true) }.take(15)
                        if (people.isNotEmpty()) Text("Contacts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        people.forEach { c ->
                            Row(Modifier.fillMaxWidth().clickable { onPick(Phone.convKey(listOf(c.number))) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(c.name, c.photo, 36.dp); Spacer(Modifier.width(12.dp))
                                Column { Text(c.name); Text(Phone.pretty(c.number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Shown in a chat when you forwarded something to it: one tap to send. */
@Composable
fun ForwardBar(key: String, onSend: (Forwarding.Pending) -> Unit) {
    var p by remember(key) { mutableStateOf(Forwarding.pending[key]) }
    val f = p ?: return
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Forward", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(listOfNotNull(f.text.takeIf { it.isNotBlank() }, if (f.media.isNotEmpty()) "📎 ${f.media.size} attachment${if (f.media.size > 1) "s" else ""}" else null)
                    .joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton({ Forwarding.pending.remove(key); p = null }) { Text("Cancel") }
            Button({ Forwarding.pending.remove(key); p = null; onSend(f) }) { Text("Send") }
        }
    }
}

// ---------------- Reply ----------------

@Composable
fun ReplyBar(m: Msg, who: String, onClear: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Replying to $who", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(Quote.snippet(m.body).ifBlank { "📷 Photo" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClear) { Icon(Icons.Default.Close, "Cancel reply") }
        }
    }
}

/** The quote block at the top of a reply bubble. */
@Composable
fun QuoteBlock(quote: String, fg: Color) {
    Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp).clip(RoundedCornerShape(10.dp)).background(fg.copy(alpha = 0.12f)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(14.dp), tint = fg.copy(alpha = 0.7f))
        Text(quote, Modifier.padding(start = 6.dp), color = fg.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Voice messages ----------------

/** Records a voice message to an .m4a file. */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    var startedAt = 0L; private set

    fun start(): Boolean = runCatching {
        val f = OutFiles.new(ctx, "voice", "m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(64000); r.setAudioChannels(1)
        // MMS limit is ~1 MB on most carriers: cap at 2 minutes
        r.setMaxDuration(120_000)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        rec = r; file = f; startedAt = System.currentTimeMillis()
        true
    }.getOrElse { HubLog.w("Voice", "record start failed", it); release(); false }

    /** Stops; returns the file if it's long enough to send. */
    fun stop(): File? {
        val f = file
        val long = System.currentTimeMillis() - startedAt >= 800
        runCatching { rec?.stop() }.onFailure { release(); return null }
        release()
        file = null  // handed off; cancel() must not delete it
        return if (long) f else { f?.delete(); null }
    }

    fun cancel() { runCatching { rec?.stop() }; release(); file?.delete(); file = null }
    private fun release() { runCatching { rec?.release() }; rec = null }
}

/** Hold to record, let go to send. Slide away (drag up) to cancel. */
@Composable
fun VoiceButton(enabled: Boolean, onNeedPermission: () -> Unit, onRecorded: (File) -> Unit) {
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val recorder = remember { VoiceRecorder(ctx) }
    val sent = rememberUpdatedState(onRecorded)
    val needPerm = rememberUpdatedState(onNeedPermission)
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) { while (recording) { elapsed = System.currentTimeMillis() - recorder.startedAt; delay(200) } }
    Box(contentAlignment = Alignment.Center) {
        if (recording) Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFE53935), modifier = Modifier.padding(bottom = 64.dp)) {
            Text("● %d:%02d  · slide up to cancel".format(elapsed / 60000, (elapsed / 1000) % 60), color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (recording) Color(0xFFE53935) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(enabled) {
                // One gesture: press = start, release = send, slide up = cancel
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!enabled) return@awaitEachGesture
                    if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { needPerm.value(); return@awaitEachGesture }
                    recording = recorder.start(); if (recording) Haptics.long(view)
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                        if (recording && c.position.y < -120f) { recording = false; recorder.cancel(); Haptics.reject(view) }
                        if (!c.pressed) break
                    }
                    if (recording) {
                        recording = false
                        recorder.stop()?.let { sent.value(it) } ?: run { Hub.banner.value = "Hold the mic to record a voice message" }
                    }
                }
            }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Mic, "Hold to record a voice message", tint = if (recording) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- Location ----------------

@SuppressLint("MissingPermission")
suspend fun currentLocation(ctx: Context): Location? = withContext(Dispatchers.Main) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val provs = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
    }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (Build.VERSION.SDK_INT >= 30) {
        val fresh = kotlinx.coroutines.withTimeoutOrNull(10_000) {
            kotlinx.coroutines.suspendCancellableCoroutine<Location?> { c ->
                var done = false
                provs.forEach { p -> runCatching { lm.getCurrentLocation(p, null, ctx.mainExecutor) { l -> if (!done && l != null) { done = true; c.resumeWith(Result.success(l)) } } } }
                if (provs.isEmpty()) c.resumeWith(Result.success(null))
            }
        }
        if (fresh != null) return@withContext fresh
    }
    provs.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
}

fun locationText(l: Location) = "📍 Here's where I am: https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.US, l.latitude, l.longitude)

// ---------------- Contact card ----------------

/** Makes a .vcf for a contact you picked. Returns (file, name). */
suspend fun contactCard(ctx: Context, contactUri: Uri): Pair<File, String>? = withContext(Dispatchers.IO) {
    runCatching {
        val cr = ctx.contentResolver
        var id = 0L; var name = ""
        cr.query(contactUri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) { id = c.getLong(0); name = c.getString(1).orEmpty() }
        }
        if (id == 0L) return@runCatching null
        val phones = ArrayList<Pair<String, String>>()
        cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) phones += (when (c.getInt(1)) {
                ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME"; ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK"; else -> "CELL"
            }) to c.getString(0).orEmpty()
        }
        val emails = ArrayList<String>()
        cr.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(id.toString()), null)?.use { c -> while (c.moveToNext()) emails += c.getString(0).orEmpty() }
        val f = OutFiles.new(ctx, name.filter { it.isLetterOrDigit() }.ifBlank { "contact" }, "vcf")
        f.writeText(VCard.build(name, phones.distinctBy { Phone.norm(it.second) }, emails.distinct()))
        f to name
    }.getOrNull()
}

// ---------------- GIFs ----------------

/** GIF search (GIPHY). Needs a free GIPHY key; GIFs from your keyboard work without one. */
@Composable
fun GifPicker(onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Hub.orbit.giphyKey()) }
    var keyDraft by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // preview, full
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val loader = remember { coil.ImageLoader.Builder(ctx).components { if (Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory()) else add(coil.decode.GifDecoder.Factory()) }.build() }
    suspend fun load(query: String) {
        busy = true; err = ""
        results = withContext(Dispatchers.IO) {
            runCatching {
                val url = if (query.isBlank()) "https://api.giphy.com/v1/gifs/trending?api_key=$key&limit=30&rating=pg-13"
                else "https://api.giphy.com/v1/gifs/search?api_key=$key&q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=30&rating=pg-13"
                val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 15000 }
                if (c.responseCode == 401 || c.responseCode == 403) error("GIPHY didn't accept the key")
                val a = org.json.JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("data")
                (0 until a.length()).mapNotNull { i ->
                    val im = a.getJSONObject(i).getJSONObject("images")
                    val prev = im.optJSONObject("fixed_width_small")?.optString("url") ?: return@mapNotNull null
                    // Smaller full size so it fits in a picture message
                    val full = im.optJSONObject("downsized")?.optString("url") ?: im.optJSONObject("fixed_height")?.optString("url") ?: prev
                    prev to full
                }
            }.onFailure { err = it.message ?: "Couldn't load GIFs" }.getOrDefault(emptyList())
        }
        busy = false
    }
    LaunchedEffect(key) { if (key.isNotBlank()) load("") }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).heightIn(max = 600.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GIFs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                if (key.isBlank()) {
                    Text("Tip: your keyboard's GIF button (Samsung Keyboard or Gboard) works in Orbit with no setup.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("To search GIFs here too, make a free GIPHY developer key at developers.giphy.com (Create an App → API) and paste it:",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(keyDraft, { keyDraft = it.trim() }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("GIPHY API key") }, singleLine = true)
                    Button({ Hub.orbit.setGiphyKey(keyDraft); key = keyDraft }, enabled = keyDraft.length > 10) { Text("Save") }
                } else {
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search GIPHY") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { scope.launch { load(q) } }))
                    if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    if (busy) CircularProgressIndicator(Modifier.padding(20.dp).align(Alignment.CenterHorizontally))
                    LazyVerticalGrid(GridCells.Fixed(3), Modifier.padding(top = 8.dp).heightIn(max = 440.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(results) { (prev, full) ->
                            coil.compose.AsyncImage(prev, "GIF", loader, Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable {
                                scope.launch {
                                    busy = true
                                    val f = withContext(Dispatchers.IO) {
                                        runCatching { OutFiles.new(ctx, "gif", "gif").also { out -> java.net.URL(full).openStream().use { i -> out.outputStream().use { i.copyTo(it) } } } }.getOrNull()
                                    }
                                    busy = false
                                    if (f != null) onPick(f) else err = "Couldn't download that GIF"
                                }
                            }, contentScale = ContentScale.Crop)
                        }
                    }
                    Text("Powered by GIPHY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** Copies something your keyboard inserted (GIF, sticker, image) into Orbit's own file, before the keyboard's permission expires. */
fun copyIncoming(ctx: Context, uri: Uri, mime: String?): File? = runCatching {
    val ext = when { mime?.contains("gif") == true -> "gif"; mime?.contains("png") == true -> "png"; mime?.contains("webp") == true -> "webp"; else -> "jpg" }
    OutFiles.new(ctx, "kb", ext).also { f -> ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: error("can't read") }
}.getOrNull()

// ---------------- Photo editor ----------------

private val PEN_COLORS = listOf(Color.White, Color.Black, Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF007AFF), Color(0xFFAF52DE))

/** Draw on a photo, crop it, rotate it, then send. */
@Composable
fun PhotoEditor(source: Uri, onDismiss: () -> Unit, onDone: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source) { bmp = withContext(Dispatchers.IO) { loadScaled(ctx, source, 2048) } }
    data class Stroke(val color: Color, val width: Float, val points: List<Offset>)
    val strokes = remember { mutableStateListOf<Stroke>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(PEN_COLORS[2]) }
    var mode by remember { mutableStateOf("draw") }
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) } // fractions of the image
    var saving by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    /** Draws strokes into the bitmap. Strokes are stored in bitmap pixels. */
    fun bake(b: Bitmap): Bitmap {
        if (strokes.isEmpty()) return b
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(out)
        strokes.forEach { s ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = s.color.toArgb(); style = android.graphics.Paint.Style.STROKE; strokeWidth = s.width
                strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
            }
            val path = android.graphics.Path()
            s.points.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            if (s.points.size == 1) path.lineTo(s.points[0].x + 0.1f, s.points[0].y)
            c.drawPath(path, p)
        }
        strokes.clear()
        return out
    }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.Default.Close, "Cancel", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton({ if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (strokes.isNotEmpty()) Color.White else Color.Gray)
                }
                IconButton({ bmp?.let { b -> runCatching { val baked = bake(b); bmp = Bitmap.createBitmap(baked, 0, 0, baked.width, baked.height, Matrix().apply { postRotate(90f) }, true); crop = Rect(0f, 0f, 1f, 1f) }
                    .onFailure { Hub.banner.value = "Couldn't rotate this photo" } } }) {
                    Icon(Icons.Default.RotateRight, "Rotate", tint = Color.White)
                }
                IconButton({ mode = if (mode == "crop") "draw" else "crop" }) { Icon(Icons.Default.Crop, "Crop", tint = if (mode == "crop") MaterialTheme.colorScheme.primary else Color.White) }
                IconButton({ mode = "draw" }) { Icon(Icons.Default.Draw, "Draw", tint = if (mode == "draw") MaterialTheme.colorScheme.primary else Color.White) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b == null) CircularProgressIndicator()
                else {
                    // Where the photo sits on screen
                    val boxW = with(density) { maxWidth.toPx() }; val boxH = with(density) { maxHeight.toPx() }
                    val scale = minOf(boxW / b.width, boxH / b.height)
                    val w = b.width * scale; val h = b.height * scale
                    val left = (boxW - w) / 2; val top = (boxH - h) / 2
                    fun toImg(o: Offset) = Offset(((o.x - left) / scale).coerceIn(0f, b.width.toFloat()), ((o.y - top) / scale).coerceIn(0f, b.height.toFloat()))
                    val img = remember(b) { b.asImageBitmap() }
                    Canvas(Modifier.fillMaxSize().pointerInput(mode, b) {
                        if (mode == "draw") detectDragGestures(
                            onDragStart = { current = listOf(toImg(it)) },
                            onDragEnd = { if (current.isNotEmpty()) strokes += Stroke(color, 4f * density.density / scale, current); current = emptyList() },
                        ) { ch, _ -> ch.consume(); current = current + toImg(ch.position) }
                        else detectDragGestures { ch, _ ->
                            ch.consume()
                            // Drag the nearest corner of the crop box
                            val p = toImg(ch.position); val fx = p.x / b.width; val fy = p.y / b.height
                            val c = crop
                            val corners = listOf(Offset(c.left, c.top), Offset(c.right, c.top), Offset(c.left, c.bottom), Offset(c.right, c.bottom))
                            val i = corners.indices.minBy { (corners[it] - Offset(fx, fy)).getDistance() }
                            val min = 0.1f
                            crop = when (i) {
                                0 -> c.copy(left = fx.coerceAtMost(c.right - min), top = fy.coerceAtMost(c.bottom - min))
                                1 -> c.copy(right = fx.coerceAtLeast(c.left + min), top = fy.coerceAtMost(c.bottom - min))
                                2 -> c.copy(left = fx.coerceAtMost(c.right - min), bottom = fy.coerceAtLeast(c.top + min))
                                else -> c.copy(right = fx.coerceAtLeast(c.left + min), bottom = fy.coerceAtLeast(c.top + min))
                            }
                        }
                    }) {
                        drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()), dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()))
                        fun drawStroke(pts: List<Offset>, col: Color, width: Float) {
                            if (pts.isEmpty()) return
                            val path = Path().apply { pts.forEachIndexed { i, o -> val x = left + o.x * scale; val y = top + o.y * scale; if (i == 0) moveTo(x, y) else lineTo(x, y) } }
                            drawPath(path, col, style = Stroke(width * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        strokes.forEach { drawStroke(it.points, it.color, it.width) }
                        drawStroke(current, color, 4f * density.density / scale)
                        // Crop box: darken outside
                        if (mode == "crop" || crop != Rect(0f, 0f, 1f, 1f)) {
                            val r = Rect(left + crop.left * w, top + crop.top * h, left + crop.right * w, top + crop.bottom * h)
                            val dim = Color.Black.copy(alpha = 0.55f)
                            drawRect(dim, Offset(left, top), Size(w, r.top - top))
                            drawRect(dim, Offset(left, r.bottom), Size(w, top + h - r.bottom))
                            drawRect(dim, Offset(left, r.top), Size(r.left - left, r.height))
                            drawRect(dim, Offset(r.right, r.top), Size(left + w - r.right, r.height))
                            drawRect(Color.White, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
            if (mode == "draw") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PEN_COLORS.forEach { c ->
                    Box(Modifier.size(32.dp).clip(CircleShape).background(c).border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                        .clickable { color = c })
                }
            } else Text("Drag the corners to crop", color = Color.White, modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
            Button({
                val b = bmp ?: return@Button
                saving = true
                scope.launch {
                    val f = withContext(Dispatchers.Default) {
                        runCatching {
                            val baked = bake(b)
                            val cx = (crop.left * baked.width).toInt(); val cy = (crop.top * baked.height).toInt()
                            val cw = ((crop.right - crop.left) * baked.width).toInt().coerceIn(1, baked.width - cx)
                            val ch = ((crop.bottom - crop.top) * baked.height).toInt().coerceIn(1, baked.height - cy)
                            val out = Bitmap.createBitmap(baked, cx, cy, cw, ch)
                            OutFiles.new(ctx, "photo", "jpg").also { file -> file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
                        }.getOrNull()
                    }
                    saving = false
                    if (f != null) onDone(f) else Hub.banner.value = "Couldn't save the photo"
                }
            }, Modifier.fillMaxWidth().padding(12.dp).height(52.dp), enabled = bmp != null && !saving, shape = RoundedCornerShape(16.dp)) {
                Text(if (saving) "Saving…" else "Send")
            }
        }
    }
}

/** Loads a photo no bigger than [max] px on its long side, upright. */
fun loadScaled(ctx: Context, uri: Uri, max: Int): Bitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    while (maxOf(opts.outWidth, opts.outHeight) / sample > max) sample *= 2
    var b = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    // Camera photos store rotation separately
    val deg = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            when (androidx.exifinterface.media.ExifInterface(s).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)
    val s = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
    if (deg != 0f || s < 1f) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postScale(s, s); postRotate(deg) }, true)
    b
}.getOrNull()

// ---------------- Select mode ----------------

@Composable
fun SelectionBar(count: Int, onClose: () -> Unit, onCopy: () -> Unit, onForward: () -> Unit, onStar: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Default.Close, "Done selecting") }
            Text("$count selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onCopy) { Icon(androidx.compose.material.icons.Icons.Default.ContentCopy, "Copy") }
            IconButton(onForward) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.Forward, "Forward") }
            IconButton(onStar) { Icon(androidx.compose.material.icons.Icons.Default.Star, "Star") }
            IconButton(onDelete) { Icon(androidx.compose.material.icons.Icons.Default.Delete, "Delete") }
        }
    }
}

// ---------------- Keyboard GIFs / stickers ----------------

/** Lets the chat box's TextFieldState be read and set like a plain String (`var text by FieldText(state)`). */
class FieldText(private val state: androidx.compose.foundation.text.input.TextFieldState) {
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String = state.text.toString()
    operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
}

/**
 * Copies media the keyboard handed over into Orbit's own files right away, while the keyboard's
 * read permission is still valid, and returns Orbit's own URIs for sending. Text is left alone.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun keyboardMedia(ctx: Context, content: androidx.compose.foundation.content.TransferableContent): List<Uri> {
    val clip = content.clipEntry.clipData
    val out = ArrayList<Uri>()
    for (i in 0 until clip.itemCount) {
        val uri = clip.getItemAt(i).uri ?: continue
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
            ?: clip.description.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull { !it.startsWith("text") } }
        if (mime != null && !mime.startsWith("image") && !mime.startsWith("video")) continue
        val f = if (mime?.startsWith("video") == true) copyVideo(ctx, uri) else copyIncoming(ctx, uri, mime)
        if (f != null) out += OutFiles.uri(ctx, f)
        else com.yatesv.contacthub.data.HubLog.w("Chat", "Couldn't read keyboard media $mime")
    }
    return out
}

private fun copyVideo(ctx: Context, uri: Uri): File? = runCatching {
    OutFiles.new(ctx, "kb", "mp4").also { f -> ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: error("can't read") }
}.getOrNull()
