package com.yatesv.contacthub.ui

import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Attachment
import kotlinx.coroutines.launch

/**
 * Full-screen photos and videos inside Orbit: swipe between every photo/video in the chat,
 * pinch or double-tap to zoom, play videos, save or share. (They used to open in another app.)
 */
object MediaViewer {
    /** Photos/videos of the chat on screen, oldest first (set by the chat). */
    @Volatile var gallery: List<Attachment> = emptyList()
    var open by mutableStateOf<Pair<List<Attachment>, Int>?>(null)

    fun viewable(a: Attachment) = a.mime.startsWith("image") || a.mime.startsWith("video")

    /** Opens [a], with the rest of the chat's media to swipe through. */
    fun show(a: Attachment) {
        val list = gallery.takeIf { g -> g.any { it.uri == a.uri } } ?: listOf(a)
        open = list to list.indexOfFirst { it.uri == a.uri }.coerceAtLeast(0)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewerOverlay() {
    val (items, start) = MediaViewer.open ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start) { items.size }
    var chrome by remember { mutableStateOf(true) }
    BackHandler { MediaViewer.open = null }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val a = items[page]
            if (a.mime.startsWith("video")) VideoPage(a.uri, active = pager.currentPage == page)
            else ZoomImage(a.uri, onTap = { chrome = !chrome })
        }
        if (chrome) Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ MediaViewer.open = null }) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Text(if (items.size > 1) "${pager.currentPage + 1} of ${items.size}" else "", color = Color.White,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton({
                    val a = items[pager.currentPage]
                    scope.launch {
                        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.yatesv.contacthub.data.IncomingMedia.saveToPhone(ctx, a) }
                        Hub.banner.value = if (saved == null) "Couldn't save it" else "Saved to ${saved.substringBeforeLast('/')}"
                    }
                }) { Icon(Icons.Default.Download, "Save to phone", tint = Color.White) }
                IconButton({ com.yatesv.contacthub.data.IncomingMedia.share(ctx, items[pager.currentPage]) }) { Icon(Icons.Default.Share, "Share", tint = Color.White) }
            }
        }
    }
}


@Composable
private fun ZoomImage(uri: Uri, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize()
        .pointerInput(uri) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = { p ->
                if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else { scale = 2.5f; offset = (Offset(size.width / 2f, size.height / 2f) - p) * 1.5f }
            })
        }
        .pointerInput(uri) {
            // Pinch to zoom, drag to pan while zoomed (at 1x the pager gets the swipe)
            detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                val newScale = (scale * zoom).coerceIn(1f, 6f)
                if (newScale > 1.01f || scale > 1.01f) {
                    scale = newScale
                    val maxX = size.width * (scale - 1) / 2f; val maxY = size.height * (scale - 1) / 2f
                    offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                    if (scale <= 1.01f) offset = Offset.Zero
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(uri, "Photo", Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
        }, contentScale = ContentScale.Fit)
    }
}

@Composable
private fun VideoPage(uri: Uri, active: Boolean) {
    Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { c ->
            VideoView(c).apply {
                setVideoURI(uri)
                val mc = MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                setOnPreparedListener { mp: MediaPlayer -> mp.isLooping = false; if (tag == true) start() }
                setOnErrorListener { _, _, _ -> Hub.banner.value = "Can't play this video"; true }
            }
        }, update = { v -> v.tag = active; if (active) v.start() else if (v.isPlaying) v.pause() },
            onRelease = { it.stopPlayback() })
    }
}
