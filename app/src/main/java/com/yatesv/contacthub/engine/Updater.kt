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

    data class State(val latest: Int = 0, val available: Boolean = false, val progress: Float = -1f, val error: String = "")
    val state = MutableStateFlow(State())

    /** Build number in the release title, e.g. "Orbit build #12" -> 12. */
    fun buildNumber(title: String): Int = Regex("#(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    suspend fun check(force: Boolean = false): State = withContext(Dispatchers.IO) {
        val s = Hub.settings.value
        if (!force && System.currentTimeMillis() - s.lastUpdateCheck < 6 * 3600_000L) return@withContext state.value
        runCatching {
            val c = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 10000; setRequestProperty("Accept", "application/vnd.github+json")
            }
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val latest = buildNumber(j.optString("name"))
            Hub.settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            State(latest, latest > BuildConfig.VERSION_CODE)
        }.getOrElse { State(error = "Couldn't check for updates") }.also { state.value = it }
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
                conn = (url.openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 30000 }
                if (conn.responseCode in 300..399) { url = URL(url, conn.getHeaderField("Location")); continue }
                break
            }
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
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                // If you left the app during the download, Android may block opening this; a notification always works
                runCatching { ctx.startActivity(confirm) }
                val pi = PendingIntent.getActivity(ctx, 4322, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = androidx.core.app.NotificationCompat.Builder(ctx, Notifier.CH_STATUS).setSmallIcon(com.yatesv.contacthub.R.drawable.ic_tile)
                    .setContentTitle("Orbit update ready").setContentText("Tap to install").setContentIntent(pi).setAutoCancel(true).build()
                runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(7400, n) }
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
