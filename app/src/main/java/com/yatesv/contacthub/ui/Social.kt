package com.yatesv.contacthub.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.ContactExtra
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Social
import com.yatesv.contacthub.data.Socials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Package names the app might be installed under (TikTok has two). */
fun Social.packages(): List<String> = when (this) {
    Social.TIKTOK -> listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    else -> listOf(pkg)
}

fun Social.installedPackage(ctx: Context): String? = packages().firstOrNull { p ->
    runCatching { ctx.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false)
}

private val iconCache = HashMap<String, Bitmap?>()

/** The app's own icon, taken from the copy installed on this phone. Generic globe if it isn't installed. */
@Composable
fun SocialIcon(s: Social, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp = remember(s) {
        iconCache.getOrPut(s.name) {
            s.installedPackage(ctx)?.let { p -> runCatching { ctx.packageManager.getApplicationIcon(p).toBitmap(144, 144) }.getOrNull() }
        }
    }
    if (bmp != null) Image(bmp.asImageBitmap(), s.label, Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) { Icon(Icons.Default.Public, s.label, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

object SocialImport {
    data class Found(val phoneKey: String, val name: String, val social: Social, val value: String)

    /**
     * Looks through your contacts for social profiles that are already saved
     * (website links, IM fields labeled Instagram/Snapchat/TikTok, or links in notes).
     */
    suspend fun scan(ctx: Context): List<Found> = withContext(Dispatchers.IO) {
        val contacts = Hub.contacts.all()
        val byId = contacts.groupBy { it.id }
        val out = ArrayList<Found>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1,
                    ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL, ContactsContract.Data.DATA3),
                "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?)",
                arrayOf(ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val mime = c.getString(1).orEmpty()
                    val data = c.getString(2).orEmpty()
                    val label = (c.getString(3) ?: c.getString(4)).orEmpty().lowercase()
                    val person = byId[id]?.firstOrNull() ?: continue
                    val found = mutableListOf<Pair<Social, String>>()
                    if (mime == ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE) {
                        Social.entries.firstOrNull { label.contains(it.label.lowercase()) || (it == Social.SNAPCHAT && label.contains("snap")) }
                            ?.let { found += it to Socials.clean(it, data) }
                    }
                    Regex("https?://\\S+").findAll(data).forEach { m ->
                        Socials.detect(m.value)?.let { found += it to Socials.clean(it, m.value) }
                    }
                    found.filter { it.second.isNotBlank() }.forEach { (s, v) -> out += Found(Phone.norm(person.number), person.name, s, v) }
                }
            }
        }
        out.distinctBy { it.phoneKey + it.social }
    }

    /** Fills in only the empty ones, never overwrites what you set. */
    suspend fun apply(list: List<Found>): Int {
        val dao = Hub.db.dao()
        var n = 0
        list.groupBy { it.phoneKey }.forEach { (k, items) ->
            var e = dao.extra(k) ?: ContactExtra(k)
            items.forEach { f ->
                e = when (f.social) {
                    Social.INSTAGRAM -> if (e.instagram.isBlank()) { n++; e.copy(instagram = f.value) } else e
                    Social.SNAPCHAT -> if (e.snapchat.isBlank()) { n++; e.copy(snapchat = f.value) } else e
                    Social.TIKTOK -> if (e.tiktok.isBlank()) { n++; e.copy(tiktok = f.value) } else e
                }
            }
            dao.putExtra(e)
        }
        Hub.changed()
        return n
    }
}

/** Search URL that works without being signed in. */
fun socialSearchUrl(s: Social, name: String, onSite: Boolean): String {
    val q = Uri.encode(name)
    return if (onSite) when (s) {
        Social.INSTAGRAM -> "https://www.instagram.com/explore/search/keyword/?q=$q"
        Social.SNAPCHAT -> "https://www.snapchat.com/explore/$q"
        Social.TIKTOK -> "https://www.tiktok.com/search/user?q=$q"
    } else {
        val site = when (s) { Social.INSTAGRAM -> "instagram.com"; Social.SNAPCHAT -> "snapchat.com/add"; Social.TIKTOK -> "tiktok.com/@" }
        "https://www.google.com/search?q=$q+site%3A${Uri.encode(site)}"
    }
}

/** If [url] is someone's profile page, returns their username. */
fun profileFromUrl(s: Social, url: String): String? {
    val real = if (url.contains("google.") && url.contains("/url?")) Uri.parse(url).getQueryParameter("q") ?: Uri.parse(url).getQueryParameter("url") ?: url else url
    if (Socials.detect(real) != s) return null
    val v = Socials.clean(s, real)
    if (v.startsWith("http") || v.isBlank()) return null
    if (v in setOf("explore", "accounts", "search", "about", "legal", "add", "discover", "tag", "music")) return null
    return v
}

/**
 * Find someone's Instagram / Snapchat / TikTok inside Contact Hub: search for their name,
 * tap their profile, and it's linked to the contact.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialSearchScreen(number: String, start: Social) {
    val scope = rememberCoroutineScope()
    val name = Hub.contacts.cached(number)?.name ?: Phone.pretty(number)
    var social by remember { mutableStateOf(start) }
    var onSite by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var web by remember { mutableStateOf<WebView?>(null) }

    fun load() { found = null; web?.loadUrl(socialSearchUrl(social, name, onSite)) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Find $name") }, navigationIcon = {
            IconButton({ if (web?.canGoBack() == true) web?.goBack() else Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Social.entries.forEach { s ->
                    FilterChip(social == s, { social = s; load() }, { Text(s.label) }, leadingIcon = { SocialIcon(s, 18.dp) },
                        modifier = Modifier.padding(end = 6.dp))
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (onSite) "Searching on ${social.label}" else "Searching Google", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton({ onSite = !onSite; load() }) { Text(if (onSite) "Use Google instead" else "Search on ${social.label}") }
            }
            // Friends you've synced that look like this person
            val suggestions = remember(social) { syncedSuggestions(Hub.app, social, name) }
            if (suggestions.isNotEmpty()) Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("From your ${social.label} friends", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                suggestions.forEach { f ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { found = f.username }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        SocialIcon(social, 28.dp); Spacer(Modifier.width(10.dp))
                        Column { Text("@${f.username}", style = MaterialTheme.typography.titleSmall); if (f.fullName.isNotBlank()) Text(f.fullName, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } else if (social != Social.SNAPCHAT) TextButton({ Nav.go(Screen.FriendSync(social)) }, Modifier.padding(horizontal = 4.dp)) {
                Text("Sign in to ${social.label} to sync all your friends at once")
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f)) {
                AndroidView(factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                profileFromUrl(social, url)?.let { found = it }
                                // Keep normal web links inside; don't jump out to the app
                                return !url.startsWith("http")
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                loading = true
                                profileFromUrl(social, url)?.let { found = it }
                            }
                            override fun onPageFinished(view: WebView, url: String) { loading = false }
                        }
                        web = this
                        loadUrl(socialSearchUrl(social, name, onSite))
                    }
                }, modifier = Modifier.fillMaxSize())

                androidx.compose.animation.AnimatedVisibility(found != null, Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    Surface(tonalElevation = 6.dp, shadowElevation = 12.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            SocialIcon(social, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("@${found.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                                Text("Link to $name?", style = MaterialTheme.typography.bodySmall)
                            }
                            Button({
                                val v = found ?: return@Button
                                scope.launch {
                                    val k = Phone.norm(number)
                                    val e = Hub.db.dao().extra(k) ?: ContactExtra(k)
                                    Hub.db.dao().putExtra(when (social) {
                                        Social.INSTAGRAM -> e.copy(instagram = v)
                                        Social.SNAPCHAT -> e.copy(snapchat = v)
                                        Social.TIKTOK -> e.copy(tiktok = v)
                                    })
                                    Hub.changed()
                                    Nav.back()
                                }
                            }) { Text("Link") }
                        }
                    }
                }
            }
        }
    }
}
