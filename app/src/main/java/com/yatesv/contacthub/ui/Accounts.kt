package com.yatesv.contacthub.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.HubApp
import com.yatesv.contacthub.data.HubLog
import kotlinx.coroutines.launch

/**
 * Sign in to Life360 on Life360's own website, inside Orbit. When the site logs you in,
 * Orbit picks up the sign-in token the site uses, so there's nothing to copy or paste.
 * Your password goes only to Life360's page; Orbit never sees it.
 */
private const val HOOK = """
(function(){
  if (window.__chHook) return; window.__chHook = 1;
  function send(v){ try { if (v && /^Bearer\s+\S{20,}/i.test(String(v))) CHToken.postMessage('H|' + v); } catch(e){} }
  var oset = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function(k, v){ if (String(k).toLowerCase() === 'authorization') send(v); return oset.apply(this, arguments); };
  var of = window.fetch;
  if (of) window.fetch = function(i, o){
    try {
      var h = (o && o.headers) || (i && i.headers);
      if (h) { if (h.get) send(h.get('authorization')); else for (var k in h) if (k.toLowerCase() === 'authorization') send(h[k]); }
    } catch(e){}
    return of.apply(this, arguments);
  };
  setInterval(function(){
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i), v = localStorage.getItem(k);
        if (/token|auth/i.test(k) && v) { var m = v.match(/[A-Za-z0-9\-_\.]{30,}/); if (m) CHToken.postMessage('S|' + m[0]); }
      }
    } catch(e){}
  }, 1500);
})();
"""

@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Life360SignInScreen(onDone: () -> Unit = { if (Nav.top == Screen.Life360SignIn) Nav.back() }) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Sign in with your Life360 account") }
    var done by remember { mutableStateOf(false) }
    val tried = remember { HashSet<String>() }

    fun tryToken(raw: String) {
        if (done) return
        val token = if (raw.startsWith("Bearer", true)) raw else "Bearer $raw"
        if (!tried.add(token)) return
        status = "Connecting…"
        scope.launch {
            Hub.l360.refresh(token).onSuccess { members ->
                done = true
                Hub.settings.update { it.copy(l360Token = token) }
                HubApp.scheduleWork(Hub.app)
                HubLog.d("Life360", "connected via in-app sign-in, ${members.size} people")
                status = "Connected! ${members.size} people found."
                kotlinx.coroutines.delay(900)
                onDone()
            }.onFailure { status = "Still signing in…" }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Life360 sign-in") }, navigationIcon = { IconButton(onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                if (Nav.top == Screen.Life360SignIn) TextButton({ Nav.replace(Screen.SettingsPage("life360")) }) { Text("Other ways") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    setUpLikeChrome()
                    val origins = setOf("https://life360.com", "https://*.life360.com")
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        WebViewCompat.addWebMessageListener(this, "CHToken", origins) { _, message, _, _, _ ->
                            val data = message.data ?: return@addWebMessageListener
                            post { tryToken(data.substringAfter('|').trim()) }
                        }
                    }
                    val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    if (docStart) WebViewCompat.addDocumentStartJavaScript(this, HOOK, origins)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = isAppLink(request.url.toString())
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loading = true }
                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            val host = Uri.parse(url).host.orEmpty()
                            if (!docStart && (host == "life360.com" || host.endsWith(".life360.com"))) view.evaluateJavascript(HOOK, null)
                        }
                    }
                    loadUrl("https://life360.com/login")
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Sets up an in-app browser so sites load like they do in Chrome:
 * - normal Chrome user agent (Instagram/TikTok/Life360 show blank or block logins for embedded browsers)
 * - links that try to jump to their app (instagram://, intent://, snssdk://) are ignored instead of breaking the page
 * - cookies, storage and pop-up logins work
 */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.setUpLikeChrome() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    @Suppress("DEPRECATION") settings.databaseEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.mediaPlaybackRequiresUserGesture = true
    settings.userAgentString = settings.userAgentString
        .replace("; wv", "")
        .replace(Regex("Version/\\d+\\.\\d+ "), "")
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

/** True for links a web view can't open (app links); those are skipped so the page keeps working. */
fun isAppLink(url: String) = !(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:"))
