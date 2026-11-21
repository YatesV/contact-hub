package com.yatesv.contacthub.engine

import android.content.Context
import com.yatesv.contacthub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Update history with patch notes.
 * The list ships inside the app (assets/changelog.json) and the newest copy is also published
 * next to the APK, so you can see updates newer than the one installed.
 */
object Changelog {
    data class Update(val build: Int, val date: String, val title: String, val notes: List<String>)

    private const val URL = "https://github.com/YatesV/contact-hub/releases/latest/download/changelog.json"

    /** Parses changelog.json. A "next" build (not yet numbered) becomes [nextBuild]. */
    fun parse(json: String, nextBuild: Int = BuildConfig.VERSION_CODE): List<Update> {
        val arr = JSONObject(json).getJSONArray("updates")
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val b = o.opt("build")
                val build = (b as? Number)?.toInt() ?: b?.toString()?.toIntOrNull() ?: nextBuild
                val n = o.getJSONArray("notes")
                Update(build, o.optString("date"), o.optString("title"), (0 until n.length()).map { n.getString(it) })
            }.getOrNull()
        }.sortedByDescending { it.build }
    }

    fun bundled(ctx: Context): List<Update> =
        runCatching { parse(ctx.assets.open("changelog.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("changelog", Context.MODE_PRIVATE)

    /** Last copy downloaded from GitHub (may include builds newer than this one). */
    fun cached(ctx: Context): List<Update> =
        prefs(ctx).getString("json", null)?.let { runCatching { parse(it, nextBuild = Int.MAX_VALUE) }.getOrNull() }.orEmpty()

    /** Bundled + downloaded lists merged by build number (downloaded wins), newest first. */
    fun merged(ctx: Context, online: List<Update> = cached(ctx)): List<Update> =
        (bundled(ctx) + online.filter { it.build != Int.MAX_VALUE }).associateBy { it.build }.values.sortedByDescending { it.build }

    /** Downloads the newest list. Returns null (and keeps the old one) if offline. */
    suspend fun refresh(ctx: Context): List<Update>? = withContext(Dispatchers.IO) {
        runCatching {
            val json = Updater.get("$URL?t=${System.currentTimeMillis()}")
            val list = parse(json, nextBuild = Int.MAX_VALUE)
            prefs(ctx).edit().putString("json", json).apply()
            list
        }.onFailure { com.yatesv.contacthub.data.HubLog.d("Changelog", "refresh failed: ${it.message}") }.getOrNull()
    }

    /** "2026-11-20T19:50" → "Nov 20, 7:50 PM" */
    fun prettyDate(iso: String): String = runCatching {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).parse(iso)!!
        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(d)
    }.getOrDefault(iso)
}
