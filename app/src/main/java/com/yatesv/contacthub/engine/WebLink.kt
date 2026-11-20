package com.yatesv.contacthub.engine

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.R
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * "Text from your laptop": a tiny web server on your phone, only on your Wi-Fi.
 * Open the address in a browser, type the 6-digit code, and read/send texts from your laptop.
 */
object WebLink {
    data class State(val on: Boolean = false, val url: String = "", val pin: String = "", val clients: Int = 0)
    val state = MutableStateFlow(State())
    const val PORT = 8765
    private val rnd = SecureRandom()
    @Volatile internal var token = ""
    @Volatile internal var lastUse = 0L
    @Volatile internal var badTries = 0
    @Volatile internal var lockedUntil = 0L

    fun start(ctx: Context) {
        token = ""; badTries = 0; lastUse = System.currentTimeMillis()
        state.value = State(true, "", (0 until 6).joinToString("") { rnd.nextInt(10).toString() })
        ContextCompat.startForegroundService(ctx, Intent(ctx, WebLinkService::class.java))
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, WebLinkService::class.java))
        token = ""; state.value = State()
    }

    fun ipAddress(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    internal fun newToken(): String = ByteArray(24).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

class WebLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var idle: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "weblink"
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(ch, "Laptop access", android.app.NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 7500, Intent(this, MainActivity::class.java).putExtra("open", "laptop").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 7501, Intent(this, WebLinkService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (intent?.action == "stop") { WebLink.stop(this); return START_NOT_STICKY }
        val ip = WebLink.ipAddress(this)
        val url = if (ip != null) "http://$ip:${WebLink.PORT}" else ""
        if (WebLink.state.value.pin.isBlank()) { stopSelf(); return START_NOT_STICKY } // restarted without a session
        WebLink.state.value = WebLink.state.value.copy(on = true, url = url)
        val n = NotificationCompat.Builder(this, ch).setSmallIcon(R.drawable.ic_tile).setOngoing(true)
            .setContentTitle("Laptop access is on").setContentText(if (url.isBlank()) "Connect to Wi-Fi" else "$url · code ${WebLink.state.value.pin}")
            .setContentIntent(open).addAction(R.drawable.ic_tile, "Turn off", stop).build()
        ServiceCompat.startForeground(this, 7502, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (server == null) scope.launch { serve() }
        if (idle?.isActive != true) idle = scope.launch {
            // Turn off after 30 minutes without use
            while (isActive) { delay(60_000); if (System.currentTimeMillis() - WebLink.lastUse > 30 * 60_000) { WebLink.stop(this@WebLinkService); break } }
        }
        return START_NOT_STICKY
    }

    private fun serve() {
        runCatching {
            ServerSocket(WebLink.PORT).use { ss ->
                server = ss
                while (scope.isActive) {
                    val sock = ss.accept()
                    scope.launch { runCatching { handle(sock) }.onFailure { HubLog.w("WebLink", "request failed", it) }; runCatching { sock.close() } }
                }
            }
        }.onFailure { if (scope.isActive) HubLog.w("WebLink", "server stopped", it) }
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 15000
        // Only phones/computers on your own network
        val remote = sock.inetAddress
        if (!(remote.isSiteLocalAddress || remote.isLoopbackAddress || remote.isLinkLocalAddress)) return respond(sock, 403, "text/plain", "Forbidden")
        val input = sock.getInputStream().buffered()
        val line = readLine(input) ?: return
        val (method, path) = line.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val headers = HashMap<String, String>()
        while (true) { val h = readLine(input) ?: break; if (h.isEmpty()) break; h.indexOf(':').takeIf { it > 0 }?.let { headers[h.substring(0, it).lowercase()] = h.substring(it + 1).trim() } }
        val len = headers["content-length"]?.toIntOrNull()?.coerceAtMost(64_000) ?: 0
        val body = if (len > 0) String(ByteArray(len).also { var r = 0; while (r < len) { val n = input.read(it, r, len - r); if (n < 0) break; r += n } }) else ""
        val authed = WebLink.token.isNotEmpty() && headers["authorization"] == "Bearer ${WebLink.token}"
        val route = path.substringBefore('?')
        val query = path.substringAfter('?', "").split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        when {
            method == "GET" && route == "/" -> respond(sock, 200, "text/html; charset=utf-8", PAGE)
            method == "POST" && route == "/api/login" -> {
                val now = System.currentTimeMillis()
                if (now < WebLink.lockedUntil) return respond(sock, 429, "application/json", """{"error":"Too many tries. Wait a minute."}""")
                val pin = runCatching { JSONObject(body).optString("pin") }.getOrDefault("")
                if (pin == WebLink.state.value.pin) {
                    WebLink.token = WebLink.newToken(); WebLink.badTries = 0; WebLink.lastUse = now
                    WebLink.state.value = WebLink.state.value.copy(clients = 1)
                    respond(sock, 200, "application/json", JSONObject().put("token", WebLink.token).toString())
                } else {
                    if (++WebLink.badTries >= 5) { WebLink.lockedUntil = now + 60_000; WebLink.badTries = 0 }
                    respond(sock, 401, "application/json", """{"error":"Wrong code"}""")
                }
            }
            !authed -> respond(sock, 401, "application/json", """{"error":"Sign in"}""")
            method == "GET" && route == "/api/convs" -> { WebLink.lastUse = System.currentTimeMillis(); respond(sock, 200, "application/json", convs()) }
            method == "GET" && route == "/api/msgs" -> { WebLink.lastUse = System.currentTimeMillis(); respond(sock, 200, "application/json", msgs(query["key"].orEmpty())) }
            method == "POST" && route == "/api/send" -> {
                WebLink.lastUse = System.currentTimeMillis()
                val j = runCatching { JSONObject(body) }.getOrNull()
                val key = j?.optString("key").orEmpty(); val text = j?.optString("text").orEmpty().trim()
                if (key.isBlank() || text.isBlank() || text.length > 2000) return respond(sock, 400, "application/json", """{"error":"Bad message"}""")
                val hidden = runBlocking { Hub.db.dao().meta(key)?.hidden == true }
                if (hidden) return respond(sock, 403, "application/json", """{"error":"Hidden chat"}""")
                SendQueue.add(this, key, text)
                respond(sock, 200, "application/json", """{"ok":true}""")
            }
            else -> respond(sock, 404, "text/plain", "Not found")
        }
    }

    private fun convs(): String = runBlocking {
        val hidden = Hub.db.dao().allMeta().filter { it.hidden }.map { it.key }.toSet()
        val list = Hub.sms.conversations(false).filter { it.key !in hidden }.take(60)
        JSONArray().apply {
            list.forEach { c -> put(JSONObject().put("key", c.key).put("name", Notifier.displayName(c.key)).put("snippet", c.snippet.take(80)).put("date", c.date)) }
        }.toString()
    }

    private fun msgs(key: String): String = runBlocking {
        if (key.isBlank() || Hub.db.dao().meta(key)?.hidden == true) return@runBlocking "[]"
        val thread = Hub.sms.threadIdFor(key)
        val all = Hub.sms.messages(thread, 80, Phone.isGroupKey(key)).map { Triple(it.date, it.outgoing, it.body.ifBlank { if (it.attachments.isNotEmpty()) "📷 Photo" else "" }) to it.sender } +
            Hub.db.dao().local(key).map { Triple(it.date, it.outgoing, it.body) to it.sender }
        JSONArray().apply {
            all.sortedBy { it.first.first }.takeLast(100).forEach { (m, sender) ->
                put(JSONObject().put("date", m.first).put("me", m.second).put("text", m.third)
                    .put("from", if (m.second) "You" else Hub.contacts.cached(sender)?.name ?: Phone.pretty(sender)))
            }
        }.toString()
    }

    private fun readLine(i: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    private fun respond(sock: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        sock.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    override fun onDestroy() {
        scope.cancel(); runCatching { server?.close() }; server = null
        WebLink.token = ""; WebLink.state.value = WebLink.State()
        super.onDestroy()
    }

    companion object {
        private val PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Orbit</title>
<style>
*{box-sizing:border-box}body{margin:0;font:15px system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#000;color:#eee;height:100vh;display:flex}
#login{margin:auto;text-align:center}#login input{font-size:28px;letter-spacing:8px;width:220px;text-align:center;padding:10px;border-radius:12px;border:1px solid #333;background:#111;color:#fff}
button{background:linear-gradient(90deg,#a78bfa,#22d3ee);border:0;color:#000;font-weight:600;padding:10px 18px;border-radius:12px;cursor:pointer}
#app{display:none;flex:1;height:100vh}#list{width:320px;border-right:1px solid #222;overflow:auto}#list div{padding:12px 16px;border-bottom:1px solid #151515;cursor:pointer}
#list div:hover,#list .on{background:#111}#list b{display:block}#list small{color:#888}#chat{flex:1;display:flex;flex-direction:column}
#msgs{flex:1;overflow:auto;padding:16px;display:flex;flex-direction:column;gap:6px}.m{max-width:65%;padding:8px 12px;border-radius:16px;background:#1c1c1e;white-space:pre-wrap;word-wrap:break-word}
.me{align-self:flex-end;background:#5b5bf0}.m small{display:block;color:#aaa;font-size:11px}#bar{display:flex;gap:8px;padding:10px;border-top:1px solid #222}
#bar textarea{flex:1;resize:none;height:44px;border-radius:14px;border:1px solid #333;background:#111;color:#fff;padding:10px;font:inherit}h1{font-weight:600}
</style></head><body>
<div id="login"><h1>🪐 Orbit</h1><p>Type the code shown on your phone</p><input id="pin" maxlength="6" inputmode="numeric" autofocus><br><br><button onclick="login()">Sign in</button><p id="err" style="color:#f66"></p></div>
<div id="app"><div id="list"></div><div id="chat"><div id="msgs"><p style="color:#666;margin:auto">Pick a chat</p></div>
<div id="bar"><textarea id="t" placeholder="Text message"></textarea><button onclick="send()">Send</button></div></div></div>
<script>
let tok=sessionStorage.getItem('t'),cur=null,last='';
const h=()=>({'Authorization':'Bearer '+tok,'Content-Type':'application/json'});
const esc=s=>s.replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
async function login(){const r=await fetch('/api/login',{method:'POST',body:JSON.stringify({pin:document.getElementById('pin').value})});const j=await r.json();
 if(j.token){tok=j.token;sessionStorage.setItem('t',tok);show()}else document.getElementById('err').textContent=j.error||'Error'}
document.getElementById('pin').addEventListener('keydown',e=>{if(e.key==='Enter')login()});
async function api(p){const r=await fetch(p,{headers:h()});if(r.status==401){sessionStorage.removeItem('t');location.reload()}return r.json()}
async function convs(){const l=await api('/api/convs');document.getElementById('list').innerHTML=l.map(c=>'<div data-k="'+esc(c.key)+'" class="'+(c.key===cur?'on':'')+'"><b>'+esc(c.name)+'</b><small>'+esc(c.snippet)+'</small></div>').join('');
 document.querySelectorAll('#list div').forEach(d=>d.onclick=()=>{cur=d.dataset.k;last='';msgs();convs()})}
async function msgs(){if(!cur)return;const l=await api('/api/msgs?key='+encodeURIComponent(cur));const s=JSON.stringify(l);if(s===last)return;last=s;
 const box=document.getElementById('msgs');box.innerHTML=l.map(m=>'<div class="m '+(m.me?'me':'')+'">'+esc(m.text)+'<small>'+(m.me?'':esc(m.from)+' · ')+new Date(m.date).toLocaleString()+'</small></div>').join('');box.scrollTop=box.scrollHeight}
async function send(){const t=document.getElementById('t');if(!cur||!t.value.trim())return;await fetch('/api/send',{method:'POST',headers:h(),body:JSON.stringify({key:cur,text:t.value})});t.value='';setTimeout(msgs,1500)}
document.getElementById('t').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}});
function show(){document.getElementById('login').style.display='none';document.getElementById('app').style.display='flex';convs();setInterval(msgs,3000);setInterval(convs,15000)}
if(tok)show();
</script></body></html>"""
    }
}
