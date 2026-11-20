package com.yatesv.contacthub.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.ContactExtra
import com.yatesv.contacthub.data.FriendMatch
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Social
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Friends you've synced, kept on your phone so contact pages can suggest them later. */
object FriendStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("friends", Context.MODE_PRIVATE)

    fun save(ctx: Context, s: Social, list: List<FriendMatch.Friend>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("u", it.username).put("n", it.fullName)) }
        prefs(ctx).edit().putString(s.name, a.toString()).putLong(s.name + "_at", System.currentTimeMillis()).apply()
    }

    fun load(ctx: Context, s: Social): List<FriendMatch.Friend> = runCatching {
        val a = JSONArray(prefs(ctx).getString(s.name, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { FriendMatch.Friend(it.optString("u"), it.optString("n")) }
    }.getOrDefault(emptyList())

    fun syncedAt(ctx: Context, s: Social) = prefs(ctx).getLong(s.name + "_at", 0)
}

private fun cookies(url: String) = CookieManager.getInstance().getCookie(url).orEmpty()
private fun cookie(url: String, name: String) = cookies(url).split(';').map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

/** Reads your Following list the same way instagram.com does, from inside the signed-in page. */
private fun instagramScript(uid: String) = """
(async function(){
  const post = o => CHFriends.postMessage(JSON.stringify(o));
  try {
    let out = [], max = '', n = 0;
    while (n++ < 40) {
      const r = await fetch('/api/v1/friendships/$uid/following/?count=100' + (max ? '&max_id=' + max : ''),
        { headers: { 'X-IG-App-ID': '936619743392459' }, credentials: 'include' });
      if (!r.ok) { post({ error: 'Instagram said ' + r.status, users: out }); return; }
      const j = await r.json();
      (j.users || []).forEach(u => out.push({ u: u.username, n: u.full_name || '' }));
      post({ progress: out.length });
      if (!j.next_max_id) break;
      max = j.next_max_id;
      await new Promise(z => setTimeout(z, 900));
    }
    post({ done: true, users: out });
  } catch (e) { post({ error: String(e) }); }
})();
"""

/** Opens your Following list on tiktok.com and reads it as it scrolls. */
private const val TIKTOK_SCRIPT = """
(async function(){
  const post = o => CHFriends.postMessage(JSON.stringify(o));
  const sleep = t => new Promise(z => setTimeout(z, t));
  try {
    await sleep(2500);
    let btn = document.querySelector('[data-e2e="following"]') || document.querySelector('[data-e2e="following-count"]');
    if (btn && btn.closest('div')) btn = btn.closest('div');
    if (!btn) { post({ error: 'Couldn\'t find your Following list on TikTok' }); return; }
    btn.click();
    await sleep(2500);
    const seen = new Map(); let stale = 0;
    for (let i = 0; i < 300 && stale < 6; i++) {
      const before = seen.size;
      const scope = document.querySelector('[role="dialog"]') || document;
      scope.querySelectorAll('a[href^="/@"]').forEach(a => {
        const u = a.getAttribute('href').slice(2).split(/[/?]/)[0];
        if (u && !seen.has(u)) {
          const t = (a.innerText || '').split('\n').map(s => s.trim()).filter(Boolean);
          seen.set(u, t.find(s => s.replace('@','') !== u) || '');
        }
      });
      const box = [...scope.querySelectorAll('div')].find(d => d.scrollHeight > d.clientHeight + 40 && getComputedStyle(d).overflowY !== 'visible');
      if (box) box.scrollTop = box.scrollHeight; else window.scrollBy(0, 2000);
      await sleep(900);
      stale = seen.size === before ? stale + 1 : 0;
      post({ progress: seen.size });
    }
    post({ done: true, users: [...seen].map(([u, n]) => ({ u, n })) });
  } catch (e) { post({ error: String(e) }); }
})();
"""

private enum class Phase { LOGIN, SYNCING, REVIEW, DONE }

/**
 * Sign in to Instagram or TikTok inside Contact Hub, read who you follow, match them to your
 * contacts by name, and link the ones you confirm. Nothing leaves your phone except the normal
 * requests to Instagram/TikTok themselves.
 */
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendSyncScreen(social: Social) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.LOGIN) }
    var progress by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(emptyList<FriendMatch.Match>()) }
    var checked by remember { mutableStateOf(emptySet<String>()) }
    var linked by remember { mutableStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val base = if (social == Social.INSTAGRAM) "https://www.instagram.com" else "https://www.tiktok.com"
    val loginUrl = if (social == Social.INSTAGRAM) "$base/accounts/login/" else "$base/login"

    fun signedIn() = if (social == Social.INSTAGRAM) cookie(base, "ds_user_id") != null && cookie(base, "sessionid") != null
        else cookie(base, "sessionid") != null || cookie(base, "sid_tt") != null

    fun start(w: WebView) {
        if (started) return
        started = true; phase = Phase.SYNCING; error = ""
        HubLog.d("FriendSync", "starting ${social.label}")
        if (social == Social.INSTAGRAM) w.evaluateJavascript(instagramScript(cookie(base, "ds_user_id").orEmpty()), null)
        else w.loadUrl("$base/profile")
    }

    fun onMessage(raw: String) {
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (j.has("progress")) progress = j.optInt("progress")
        val arr = j.optJSONArray("users")
        if (j.has("error")) {
            error = j.optString("error"); HubLog.w("FriendSync", "${social.label}: $error")
            if (arr == null || arr.length() == 0) { phase = Phase.LOGIN; started = false; return }
        }
        if (j.optBoolean("done") || arr != null) {
            val friends = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }.map { FriendMatch.Friend(it.optString("u"), it.optString("n")) }
            FriendStore.save(Hub.app, social, friends)
            scope.launch {
                val contacts = Hub.contacts.all().distinctBy { it.id }.map { it.name to Phone.norm(it.number) }
                val extras = Hub.db.dao().allExtras().associateBy { it.phoneKey }
                // Only suggest people who don't already have this social linked
                matches = FriendMatch.match(friends, contacts).filter { m ->
                    val e = extras[m.phoneKey]
                    when (social) { Social.INSTAGRAM -> e?.instagram.isNullOrBlank(); Social.TIKTOK -> e?.tiktok.isNullOrBlank(); else -> true }
                }
                checked = matches.filter { it.score >= 2 }.map { it.phoneKey }.toSet()
                phase = Phase.REVIEW
                Haptics.confirm(view)
                HubLog.d("FriendSync", "${social.label}: ${friends.size} friends, ${matches.size} matches")
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Sync ${social.label} friends") },
            navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            // The real site, signed in as you. Hidden behind the progress/review screens once you're in.
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    setUpLikeChrome()
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        WebViewCompat.addWebMessageListener(this, "CHFriends", setOf(base)) { _, msg, _, _, _ ->
                            msg.data?.let { d -> post { onMessage(d) } }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                            val u = request.url.toString()
                            if (isAppLink(u)) { HubLog.d("FriendSync", "skipped app link ${request.url.scheme}"); return true }
                            return false
                        }
                        override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                            if (request.isForMainFrame) { error = "page didn't load (${err.description})"; HubLog.w("FriendSync", "load error ${err.errorCode} ${err.description}") }
                        }
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loading = true }
                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            if (!signedIn()) return
                            when {
                                !started -> start(view)
                                social == Social.TIKTOK && url.contains("/@") -> view.evaluateJavascript(TIKTOK_SCRIPT, null)
                            }
                        }
                    }
                    web = this
                    loadUrl(if (signedIn()) base else loginUrl)
                }
            }, modifier = Modifier.fillMaxSize())
            if (loading && phase == Phase.LOGIN) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())

            AnimatedContent(phase, label = "sync", transitionSpec = {
                (fadeIn(Motion.spec()) + slideInVertically(Motion.spec()) { it / 8 }) togetherWith fadeOut(Motion.spec())
            }) { p ->
                when (p) {
                    Phase.LOGIN -> if (error.isNotBlank()) Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer) {
                        Text("Sync stopped: $error. Sign in again or try later.", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    } else Box(Modifier)
                    Phase.SYNCING -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                        SocialIcon(social, 64.dp)
                        Spacer(Modifier.size(20.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.size(16.dp))
                        Text("Reading who you follow…", style = MaterialTheme.typography.titleMedium)
                        Text(if (progress > 0) "$progress so far" else "This takes a few seconds", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Phase.REVIEW -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Text(if (matches.isEmpty()) "No new matches. Everyone's already linked, or their names don't match your contacts."
                            else "Found ${matches.size} of your contacts. Check the right ones:",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                        LazyColumn(Modifier.weight(1f)) {
                            items(matches, key = { it.phoneKey }) { m ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    checked = if (m.phoneKey in checked) checked - m.phoneKey else checked + m.phoneKey
                                }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(m.phoneKey in checked, null)
                                    Avatar(m.contactName, Hub.contacts.cached(m.phoneKey)?.photo, 40.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(m.contactName, style = MaterialTheme.typography.titleMedium)
                                        Text("@${m.friend.username}" + if (m.friend.fullName.isNotBlank()) " · ${m.friend.fullName}" else "",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (m.score == 1) Text("Maybe", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                            TextButton({ Nav.back() }) { Text("Cancel") }
                            Spacer(Modifier.weight(1f))
                            Button({
                                scope.launch {
                                    val dao = Hub.db.dao()
                                    matches.filter { it.phoneKey in checked }.forEach { m ->
                                        val e = dao.extra(m.phoneKey) ?: ContactExtra(m.phoneKey)
                                        dao.putExtra(if (social == Social.INSTAGRAM) e.copy(instagram = m.friend.username) else e.copy(tiktok = m.friend.username))
                                    }
                                    linked = checked.size; Hub.changed(); Haptics.confirm(view); phase = Phase.DONE
                                }
                            }, enabled = checked.isNotEmpty()) { Text("Link ${checked.size}") }
                        }
                    }
                    Phase.DONE -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                        Text("✓", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                        Text("Linked $linked contacts", style = MaterialTheme.typography.titleLarge)
                        Text("Their ${social.label} button now opens straight to their profile.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.size(20.dp))
                        Button({ Nav.back() }) { Text("Done") }
                    }
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") web
}

/** Suggestions from friends you've already synced, for one contact. */
fun syncedSuggestions(ctx: Context, s: Social, contactName: String): List<FriendMatch.Friend> {
    val friends = FriendStore.load(ctx, s)
    if (friends.isEmpty()) return emptyList()
    val c = FriendMatch.norm(contactName)
    return friends.map { f -> f to FriendMatch.norm(f.fullName).count { it in c } }.filter { it.second > 0 }
        .sortedByDescending { it.second }.take(5).map { it.first }
}

@Composable
fun FriendSyncCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    HubCard {
        Text("Sync friends", style = MaterialTheme.typography.titleMedium)
        Text("Sign in to read who you follow, then link them to your contacts in one go.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 10.dp)) {
            listOf(Social.INSTAGRAM, Social.TIKTOK).forEach { s ->
                Row(Modifier.padding(end = 10.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { Nav.go(Screen.FriendSync(s)) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SocialIcon(s, 22.dp); Spacer(Modifier.width(8.dp))
                    val at = FriendStore.syncedAt(ctx, s)
                    Text(if (at > 0) "${s.label} · resync" else s.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
