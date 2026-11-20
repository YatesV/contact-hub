package com.yatesv.contacthub.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.yatesv.contacthub.BuildConfig
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One-tap updates from the newest GitHub build. */
object Updater {
    private const val API = "https://api.github.com/repos/YatesV/contact-hub/releases/latest"
    private const val APK = "https://github.com/YatesV/contact-hub/releases/latest/download/ContactHub.apk"
    /** Tiny file published with every build. Normal download link, so no GitHub API limits. */
    private const val VERSION = "https://github.com/YatesV/contact-hub/releases/latest/download/version.json"

    data class State(
        val latest: Int = 0,
        val available: Boolean = false,
        val progress: Float = -1f,
        val error: String = "",
        val checking: Boolean = false,
        val checkedAt: Long = 0,
    )
    val state = MutableStateFlow(State())

    /** Build number in the release title, e.g. "Orbit build #12" -> 12. */
    fun buildNumber(title: String): Int = Regex("#(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private class HttpError(val code: Int) : Exception("HTTP $code")

    private fun get(url: String): String {
        var u = URL(url)
        repeat(6) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 15000; useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "Orbit-Android/${BuildConfig.VERSION_CODE}")
                setRequestProperty("Accept", "application/vnd.github+json, application/json, */*")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(u, c.getHeaderField("Location") ?: throw HttpError(code)); return@repeat }
                if (code != 200) throw HttpError(code)
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }
        throw HttpError(310)
    }

    /** Newest build number: version.json first, then the GitHub API (older releases didn't have the file). */
    private fun fetchLatest(): Int {
        var last: Throwable? = null
        repeat(2) { attempt ->
            runCatching { return JSONObject(get(VERSION + "?t=" + System.currentTimeMillis())).getInt("build") }.onFailure { last = it }
            runCatching { return buildNumber(JSONObject(get(API)).optString("name")).also { if (it == 0) error("no build number") } }.onFailure { if (last !is java.net.UnknownHostException) last = it }
            if (attempt == 0) Thread.sleep(2500)
        }
        throw last ?: IllegalStateException("unknown")
    }

    private fun explain(e: Throwable): String = when {
        e is java.net.UnknownHostException || e is java.net.ConnectException -> "No internet connection. Try again when you're online."
        e is java.net.SocketTimeoutException -> "GitHub took too long to answer. Try again."
        e is HttpError && e.code == 404 -> "A new build is being published right now. Try again in a minute."
        e is HttpError && (e.code == 403 || e.code == 429) -> "GitHub is limiting checks from this network. Try again in a few minutes."
        else -> "Couldn't check for updates (${e.message ?: e.javaClass.simpleName})"
    }

    @Volatile private var running = false

    suspend fun check(force: Boolean = false): State = withContext(Dispatchers.IO) {
        if (running) return@withContext state.value
        // Without force, check at most every 30 minutes — but always once per app launch
        if (!force && state.value.checkedAt > 0 && System.currentTimeMillis() - state.value.checkedAt < 30 * 60_000L) return@withContext state.value
        running = true
        state.value = state.value.copy(checking = true, error = "")
        val result = try {
            val latest = fetchLatest()
            Hub.settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            state.value.copy(latest = latest, available = latest > BuildConfig.VERSION_CODE, checking = false, error = "", checkedAt = System.currentTimeMillis())
        } catch (e: Throwable) {
            HubLog.w("Update", "check failed", e)
            // Keep what we already knew (e.g. an update found earlier) and just show why this check failed
            state.value.copy(checking = false, error = if (force) explain(e) else "")
        } finally { running = false }
        state.value = result
        result
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Downloads the newest APK and hands it to Android's installer (you confirm with one tap). */
    suspend fun install(ctx: Context) = withContext(Dispatchers.IO) {
        var sessionId = -1
        runCatching {
            state.value = state.value.copy(progress = 0f, error = "")
            var url = URL(APK)
            var conn: HttpURLConnection
            // Follow GitHub's redirects to the file
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 30000; useCaches = false
                    setRequestProperty("User-Agent", "Orbit-Android/${BuildConfig.VERSION_CODE}") }
                if (conn.responseCode in 300..399) { url = URL(url, conn.getHeaderField("Location")); continue }
                break
            }
            if (conn.responseCode == 404) error("the new build is still being published. Try again in a minute.")
            if (conn.responseCode != 200) error("download returned ${conn.responseCode}")
            val total = conn.contentLengthLong
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            sessionId = id
            installer.openSession(id).use { session ->
                session.openWrite("ContactHub.apk", 0, total).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) state.value = state.value.copy(progress = done.toFloat() / total)
                        }
                    }
                    session.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(ctx, 4321, Intent(ctx, InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pi.intentSender)
            }
            sessionId = -1
            state.value = state.value.copy(progress = 1f)
        }.onFailure {
            if (sessionId >= 0) runCatching { ctx.packageManager.packageInstaller.abandonSession(sessionId) }
            HubLog.w("Update", "install failed", it)
            state.value = state.value.copy(progress = -1f, error = "Download failed: ${it.message}")
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { com.yatesv.contacthub.data.safely("Updater") {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                // If you left the app during the download, Android may block opening this; a notification always works
                runCatching { ctx.startActivity(confirm) }
                val pi = PendingIntent.getActivity(ctx, 4322, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = androidx.core.app.NotificationCompat.Builder(ctx, Notifier.CH_STATUS).setSmallIcon(com.yatesv.contacthub.R.drawable.ic_tile)
                    .setContentTitle("Orbit update ready").setContentText("Tap to install").setContentIntent(pi).setAutoCancel(true).build()
                runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(7600, n) }
            }
            PackageInstaller.STATUS_SUCCESS -> HubLog.d("Update", "installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                HubLog.w("Update", "install status: $msg")
                Updater.state.value = Updater.state.value.copy(progress = -1f, error = "Install didn't finish: $msg")
            }
        }
    }
    }
}
