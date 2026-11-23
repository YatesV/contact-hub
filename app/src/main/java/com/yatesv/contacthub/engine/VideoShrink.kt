package com.yatesv.contacthub.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.yatesv.contacthub.data.HubLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Re-encodes a video small enough for a picture message (carriers cap MMS at about 1 MB).
 * Lowers the resolution and bitrate to fit; very long videos can't fit and are refused.
 */
@UnstableApi
object VideoShrink {
    sealed class Result {
        data class Ok(val file: File) : Result()
        data class TooLong(val seconds: Int) : Result()
        data class Failed(val why: String) : Result()
    }

    /** Total size to aim for, leaving room for the message's other parts. */
    private const val TARGET_BYTES = 900_000L
    private const val AUDIO_BPS = 48_000
    private const val MIN_VIDEO_BPS = 90_000

    fun durationMs(ctx: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { r -> r.setDataSource(ctx, uri); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
    }.getOrDefault(0L)

    /** Bitrate that fits [durationMs] in the target size, or null if it can't look acceptable. */
    fun videoBitrateFor(durationMs: Long): Int? {
        if (durationMs <= 0) return 300_000
        val totalBps = TARGET_BYTES * 8 * 1000 / durationMs
        val video = (totalBps - AUDIO_BPS).toInt().coerceAtMost(600_000)
        return video.takeIf { it >= MIN_VIDEO_BPS }
    }

    suspend fun shrink(ctx: Context, uri: Uri): Result {
        val dur = withContext(Dispatchers.IO) { durationMs(ctx, uri) }
        val bitrate = videoBitrateFor(dur) ?: return Result.TooLong((dur / 1000).toInt())
        val height = when { bitrate >= 350_000 -> 480; bitrate >= 180_000 -> 360; else -> 240 }
        val out = File(ctx.cacheDir, "mms-video-${System.currentTimeMillis()}.mp4")
        // Transformer must run on a thread with a Looper
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Result.Ok(out))
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            HubLog.w("VideoShrink", "couldn't shrink video", exportException)
                            out.delete()
                            if (cont.isActive) cont.resume(Result.Failed(exportException.errorCodeName))
                        }
                    })
                    .build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(height))))
                    .build()
                runCatching { transformer.start(item, out.absolutePath) }
                    .onFailure { if (cont.isActive) cont.resume(Result.Failed(it.message ?: "error")) }
                cont.invokeOnCancellation {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { transformer.cancel() }; out.delete() }
                }
            }
        }
    }
}
