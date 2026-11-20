package com.yatesv.contacthub.life360

import android.content.Context
import com.yatesv.contacthub.data.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class L360Member(
    val id: String,
    val circleId: String,
    val firstName: String,
    val lastName: String,
    val phone: String,
    val avatar: String,
    val lat: Double?,
    val lng: Double?,
    val place: String,
    val address: String,
    val battery: Int?,
    val updated: Long,
    val driving: Boolean,
) {
    val name get() = "$firstName $lastName".trim()
}

/** Thrown with a message that can be shown to the user as-is. */
class L360Error(msg: String, val auth: Boolean = false) : Exception(msg)

/**
 * Life360 has no public API. This talks to the same unofficial API Home Assistant uses,
 * so it can stop working whenever Life360 changes their servers.
 */
class Life360(ctx: Context) {
    private val cache = ctx.getSharedPreferences("life360", Context.MODE_PRIVATE)
    private val _members = MutableStateFlow(loadCache())
    val members: StateFlow<List<L360Member>> = _members
    private val _status = MutableStateFlow(cache.getString("status", "") ?: "")
    /** Empty = working. Otherwise a short problem description for the banner. */
    val status: StateFlow<String> = _status
    val meId: String get() = cache.getString("me", "") ?: ""
    val lastSync: Long get() = cache.getLong("synced", 0)

    private val host = "https://api-cloudfront.life360.com"
    private val userAgent = "com.life360.android.safetymapd/KOKO/23.50.0 android/13"
    private val clientToken =
        "Y2F0aGFwYWNyQVBoZUtVc3RlOGV2ZXZldnVjSGFmZVRydVl1ZnJhYzpkOEM5ZVlVdkE2dUZ1YnJ1SmVnZXRyZVZ1dFJlQ1JVWQ=="

    /** Accepts "Bearer abc", or just "abc". */
    fun authHeader(token: String): String {
        val t = token.trim()
        return if (t.contains(' ')) t else "Bearer $t"
    }

    /** Email/phone + password login. Life360 sometimes blocks this; pasting a token always works. */
    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        val body = listOf("grant_type" to "password", "username" to username, "password" to password)
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = (URL("$host/v3/oauth2/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("Authorization", "Basic $clientToken")
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw L360Error(
            when (code) {
                400, 401, 403 -> "Life360 didn't accept that login. Try the token method instead."
                429 -> "Life360 is rate limiting. Wait a few minutes."
                else -> "Life360 login failed ($code)."
            }, auth = true,
        )
        val j = JSONObject(text)
        "${j.optString("token_type", "Bearer")} ${j.getString("access_token")}"
    }

    private fun get(path: String, token: String): JSONObject {
        val conn = (URL(host + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("Authorization", authHeader(token))
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        val code = conn.responseCode
        if (code == 401 || code == 403) throw L360Error("Life360 login expired. Sign in again in Settings.", auth = true)
        if (code == 429) throw L360Error("Life360 is rate limiting. It'll retry later.")
        if (code !in 200..299) throw L360Error("Life360 isn't responding ($code). It may have changed its servers.")
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    /** Ask a member's phone to send a fresh location (updates for about a minute). */
    suspend fun requestUpdate(token: String, m: L360Member) = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$host/v3/circles/${m.circleId}/members/${m.id}/request").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Authorization", authHeader(token))
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write("""{"type":"location"}""".toByteArray()) }
            conn.responseCode
        }
    }

    suspend fun refresh(token: String): Result<List<L360Member>> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result.failure(L360Error("Not signed in to Life360", auth = true))
        runCatching {
            val me = get("/v3/users/me", token).optString("id")
            val circles = get("/v4/circles", token).optJSONArray("circles") ?: JSONArray()
            val all = LinkedHashMap<String, L360Member>()
            for (i in 0 until circles.length()) {
                val cid = circles.getJSONObject(i).getString("id")
                val ms = get("/v3/circles/$cid/members", token).optJSONArray("members") ?: JSONArray()
                for (k in 0 until ms.length()) parse(ms.getJSONObject(k), cid)?.let { m ->
                    val old = all[m.id]
                    if (old == null || m.updated > old.updated) all[m.id] = m
                }
            }
            val list = all.values.toList()
            cache.edit().putString("members", toJson(list).toString()).putString("me", me)
                .putLong("synced", System.currentTimeMillis()).putString("status", "").apply()
            _members.value = list
            _status.value = ""
            list
        }.onFailure { e ->
            val msg = (e as? L360Error)?.message ?: "Couldn't reach Life360"
            _status.value = msg
            cache.edit().putString("status", msg).apply()
        }
    }

    fun signOut() {
        cache.edit().clear().apply()
        _members.value = emptyList()
        _status.value = ""
    }

    /** Life360 member for a phone number, or the manually linked one. */
    fun memberFor(phone: String, linkedId: String = ""): L360Member? {
        val list = _members.value
        if (linkedId.isNotBlank()) list.firstOrNull { it.id == linkedId }?.let { return it }
        val key = Phone.norm(phone)
        if (key.isEmpty()) return null
        return list.firstOrNull { it.id != meId && Phone.norm(it.phone) == key }
    }

    fun me(): L360Member? = _members.value.firstOrNull { it.id == meId }

    private fun parse(j: JSONObject, cid: String): L360Member? {
        val id = j.optString("id").ifBlank { return null }
        val loc = j.optJSONObject("location")
        fun d(k: String) = loc?.optString(k)?.toDoubleOrNull()
        val phone = j.optString("loginPhone").ifBlank {
            j.optJSONArray("communications")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("channel") == "Voice" }?.optString("value")
            }.orEmpty()
        }
        return L360Member(
            id = id, circleId = cid,
            firstName = j.optString("firstName"), lastName = j.optString("lastName"),
            phone = phone, avatar = j.optString("avatar"),
            lat = d("latitude"), lng = d("longitude"),
            place = loc?.optString("name").orEmpty().takeIf { it != "null" }.orEmpty(),
            address = loc?.optString("address1").orEmpty().takeIf { it != "null" }.orEmpty(),
            battery = loc?.optString("battery")?.toDoubleOrNull()?.toInt(),
            updated = (loc?.optString("timestamp")?.toLongOrNull() ?: 0L) * 1000,
            driving = loc?.optString("isDriving") == "1",
        )
    }

    private fun toJson(list: List<L360Member>) = JSONArray().apply {
        list.forEach { m ->
            put(JSONObject().apply {
                put("id", m.id); put("cid", m.circleId); put("fn", m.firstName); put("ln", m.lastName)
                put("ph", m.phone); put("av", m.avatar); put("lat", m.lat ?: JSONObject.NULL); put("lng", m.lng ?: JSONObject.NULL)
                put("pl", m.place); put("ad", m.address); put("bat", m.battery ?: JSONObject.NULL)
                put("up", m.updated); put("dr", m.driving)
            })
        }
    }

    private fun loadCache(): List<L360Member> = runCatching {
        val arr = JSONArray(cache.getString("members", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { j ->
            L360Member(
                j.getString("id"), j.optString("cid"), j.optString("fn"), j.optString("ln"), j.optString("ph"), j.optString("av"),
                if (j.isNull("lat")) null else j.optDouble("lat"), if (j.isNull("lng")) null else j.optDouble("lng"),
                j.optString("pl"), j.optString("ad"), if (j.isNull("bat")) null else j.optInt("bat"),
                j.optLong("up"), j.optBoolean("dr"),
            )
        }
    }.getOrDefault(emptyList())
}
