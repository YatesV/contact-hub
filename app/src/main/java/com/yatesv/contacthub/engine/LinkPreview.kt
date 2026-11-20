package com.yatesv.contacthub.engine

import android.content.Context
import com.yatesv.contacthub.data.HubLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Title + picture for a link, like Google Messages shows under a message. Cached on the phone. */
object LinkPreview {
    data class Info(val url: String, val title: String, val site: String, val description: String, val image: String) {
        val empty get() = title.isBlank() && image.isBlank()
    }

    private val mem = android.util.LruCache<String, Info>(200)
    private val locks = HashMap<String, Mutex>()

    private fun file(ctx: Context, url: String) = File(File(ctx.cacheDir, "previews").apply { mkdirs() }, url.hashCode().toUInt().toString(16) + ".json")

    fun cached(url: String): Info? = mem.get(url)

    suspend fun get(ctx: Context, url: String): Info? {
        mem.get(url)?.let { return it.takeUnless { i -> i.empty } }
        val lock = synchronized(locks) { locks.getOrPut(url) { Mutex() } }
        return lock.withLock {
            mem.get(url)?.let { return@withLock it.takeUnless { i -> i.empty } }
            withContext(Dispatchers.IO) {
                val f = file(ctx, url)
                val info = runCatching {
                    if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) {
                        JSONObject(f.readText()).let { j -> Info(url, j.optString("t"), j.optString("s"), j.optString("d"), j.optString("i")) }
                    } else fetch(url).also { i ->
                        f.writeText(JSONObject().put("t", i.title).put("s", i.site).put("d", i.description).put("i", i.image).toString())
                    }
                }.getOrElse { HubLog.w("Preview", "failed for a link", it); Info(url, "", "", "", "") }
                mem.put(url, info)
                info.takeUnless { it.empty }
            }
        }
    }

    private fun fetch(start: String): Info {
        var url = start
        var html = ""
        // Follow up to 5 redirects (including http → https)
        for (i in 0 until 5) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36 ContactHub-LinkPreview")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            val code = c.responseCode
            if (code in 300..399) {
                url = URL(URL(url), c.getHeaderField("Location") ?: break).toString(); c.disconnect(); continue
            }
            if (code !in 200..299) error("HTTP $code")
            val type = c.contentType.orEmpty()
            if (type.startsWith("image/")) return Info(start, "", URL(url).host.removePrefix("www."), "", url)
            if (!type.contains("html")) return Info(start, "", URL(url).host.removePrefix("www."), "", "")
            html = c.inputStream.use { s ->
                val buf = ByteArray(256 * 1024); var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                String(buf, 0, n, Charsets.UTF_8)
            }
            break
        }
        return parse(start, url, html)
    }

    /** Reads Open Graph / Twitter / <title> tags. */
    fun parse(original: String, finalUrl: String, html: String): Info {
        val head = html.substringBefore("</head>", html)
        fun meta(vararg keys: String): String {
            for (k in keys) {
                val a = Regex("<meta[^>]+(?:property|name)\\s*=\\s*[\"']${Regex.escape(k)}[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(head)
                    ?: Regex("<meta[^>]+content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*(?:property|name)\\s*=\\s*[\"']${Regex.escape(k)}[\"']", RegexOption.IGNORE_CASE).find(head)
                a?.groupValues?.get(1)?.let { v -> decode(v).trim().takeIf { it.isNotBlank() }?.let { return it } }
            }
            return ""
        }
        val title = meta("og:title", "twitter:title").ifBlank {
            Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.let { decode(it).trim() }.orEmpty()
        }
        val host = runCatching { URL(finalUrl).host.removePrefix("www.") }.getOrDefault("")
        val img = meta("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src").let { i ->
            if (i.isBlank()) "" else runCatching { URL(URL(finalUrl), i).toString() }.getOrDefault("")
        }
        return Info(original, title.take(200), meta("og:site_name").ifBlank { host }, meta("og:description", "description", "twitter:description").take(300), img)
    }

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
}
