package com.yatesv.contacthub.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Photos, videos and files people send you.
 *  - RCS (seen through Google Messages' notification): the file in the notification is copied into Orbit right away,
 *    so the chat shows the real thing instead of the word "Attachment".
 *  - Any attachment can be opened full screen in another app, and saved to the phone (Pictures / Movies / Download).
 *  - If a file couldn't be copied, the bubble opens that chat in Google Messages, where it can be seen and downloaded.
 */
object IncomingMedia {
    /** Words Google/Samsung Messages put in a notification instead of the file. */
    private val placeholders = setOf("attachment", "photo", "picture", "image", "video", "gif", "sticker", "audio",
        "voice message", "audio message", "file", "contact", "location", "sent an attachment", "sent a photo",
        "sent a video", "sent a gif", "sent a sticker", "sent an image", "sent a file", "sent a voice message")

    fun isPlaceholder(text: String): Boolean = text.trim().trimEnd('.').lowercase() in placeholders ||
        Regex("""^(sent )?(\d+ )?(attachments?|photos?|pictures?|images?|videos?|files?)$""").matches(text.trim().lowercase())

    private fun dir(ctx: Context) = File(ctx.filesDir, "incoming").apply { mkdirs() }

    fun extFor(mime: String?): String = when {
        mime == null -> "bin"
        mime.contains("gif") -> "gif"
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("heic") || mime.contains("heif") -> "heic"
        mime.startsWith("image") -> "jpg"
        mime.contains("mp4") -> "mp4"
        mime.contains("3gp") -> "3gp"
        mime.startsWith("video") -> "mp4"
        mime.contains("amr") -> "amr"
        mime.contains("aac") || mime.contains("m4a") -> "m4a"
        mime.startsWith("audio") -> "ogg"
        mime.contains("vcard") -> "vcf"
        mime.contains("pdf") -> "pdf"
        else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
    }

    /** Copies a file from a notification into Orbit. Null if Android doesn't let Orbit read it. */
    fun copy(ctx: Context, uri: Uri, mime: String?): Pair<File, String>? = runCatching {
        val type = mime?.takeIf { it.isNotBlank() } ?: ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val f = File(dir(ctx), "rcs-${System.currentTimeMillis()}-${(uri.toString().hashCode() and 0xffff)}.${extFor(type)}")
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: error("can't open")
        if (f.length() == 0L) { f.delete(); error("empty") }
        f to type
    }.onFailure { HubLog.w("Media", "couldn't copy attachment from notification ($mime)", it) }.getOrNull()

    /** Saves a bitmap (e.g. a photo message's big-picture preview from the notification) as a JPEG. */
    fun saveBitmap(ctx: Context, bmp: Bitmap): Pair<File, String>? = runCatching {
        val f = File(dir(ctx), "rcs-${System.currentTimeMillis()}-${bmp.hashCode() and 0xffff}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (f.length() == 0L) { f.delete(); error("empty") }
        f to "image/jpeg"
    }.onFailure { HubLog.w("Media", "couldn't save the notification picture", it) }.getOrNull()

    /** Orbit's own shareable link for a saved file. */
    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    /** Attachments Orbit saved for a local (RCS) message. */
    fun forMessage(ctx: Context, msgKey: String): List<Attachment> =
        com.yatesv.contacthub.Hub.orbit.files(msgKey).mapNotNull { (path, mime) ->
            File(path).takeIf { it.exists() }?.let { Attachment(uri(ctx, it), mime) }
        }

    /**
     * Makes a copy other apps can open. Picture texts live in Android's message database, which viewer apps
     * usually can't read, so they get copied into Orbit's share folder first.
     */
    private fun shareable(ctx: Context, a: Attachment): Uri {
        if (a.uri.authority == "${ctx.packageName}.files") return a.uri
        val out = File(ctx.cacheDir, "out").apply { mkdirs() }.resolve("view-${a.uri.toString().hashCode() and 0x7fffffff}.${extFor(a.mime)}")
        if (!out.exists() || out.length() == 0L)
            ctx.contentResolver.openInputStream(a.uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } ?: error("can't read")
        return uri(ctx, out)
    }

    /** Share sheet for a photo/video/file. */
    fun share(ctx: Context, a: Attachment): Boolean = runCatching {
        val u = shareable(ctx, a)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(a.mime).putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { HubLog.w("Media", "share failed (${a.mime})", it) }.isSuccess

    /** Opens the attachment full screen in the phone's viewer (Gallery, Photos, a PDF app…). */
    fun open(ctx: Context, a: Attachment): Boolean = runCatching {
        val u = shareable(ctx, a)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(u, a.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { HubLog.w("Media", "open failed (${a.mime})", it) }.isSuccess

    /** Saves to the phone: pictures → Pictures/Orbit, videos → Movies/Orbit, everything else → Download/Orbit. */
    fun saveToPhone(ctx: Context, a: Attachment): String? = runCatching {
        val (collection, folder) = when {
            a.mime.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            a.mime.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            a.mime.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            else -> (if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")) to Environment.DIRECTORY_DOWNLOADS
        }
        val name = "Orbit_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.${extFor(a.mime)}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, a.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Orbit")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val dest = r.insert(collection, values) ?: error("couldn't create the file")
        try {
            r.openInputStream(a.uri)?.use { i -> r.openOutputStream(dest)?.use { o -> i.copyTo(o) } ?: error("can't write") } ?: error("can't read")
            r.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) { r.delete(dest, null, null); throw e }
        "$folder/Orbit/$name"
    }.onFailure { HubLog.w("Media", "save failed (${a.mime})", it) }.getOrNull()

    /** Opens this chat in Google Messages (to see or download a file Orbit couldn't copy). */
    fun openInMessages(ctx: Context, convKey: String): Boolean {
        val gm = "com.google.android.apps.messaging"
        val numbers = runCatching { com.yatesv.contacthub.engine.Sender.addressesOf(convKey) }.getOrDefault(emptyList())
        val intents = buildList {
            if (numbers.isNotEmpty()) add(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(","))).setPackage(gm))
            ctx.packageManager.getLaunchIntentForPackage(gm)?.let { add(it) }
        }
        return intents.any { i -> runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
    }
}
