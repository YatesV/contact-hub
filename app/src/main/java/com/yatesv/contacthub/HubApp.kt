package com.yatesv.contacthub

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yatesv.contacthub.data.ContactsRepo
import com.yatesv.contacthub.data.HubDb
import com.yatesv.contacthub.data.SettingsStore
import com.yatesv.contacthub.data.SmsRepo
import com.yatesv.contacthub.engine.BackupWorker
import com.yatesv.contacthub.engine.Life360Worker
import com.yatesv.contacthub.engine.Notifier
import com.yatesv.contacthub.life360.Life360
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A reply button taken from a Google/Samsung Messages notification. Sending through it goes out over RCS when available. */
data class ReplyHandle(
    val reply: PendingIntent,
    val inputs: Array<RemoteInput>,
    val markRead: PendingIntent?,
    val pkg: String,
    val time: Long,
    val title: String,
)

/** App-wide singletons. */
object Hub {
    lateinit var app: Context
    lateinit var db: HubDb
    lateinit var settings: SettingsStore
    lateinit var sms: SmsRepo
    lateinit var contacts: ContactsRepo
    lateinit var l360: Life360
    lateinit var orbit: com.yatesv.contacthub.data.OrbitStore
    /** Background work. A failure in one job is logged instead of crashing the whole app. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        com.yatesv.contacthub.data.HubLog.w("Hub", "background job failed: " + e.stackTraceToString().take(1500), e)
    })

    /** Bumped whenever messages change, so open screens reload. */
    val version = MutableStateFlow(0L)
    private var widgetJob: kotlinx.coroutines.Job? = null
    fun changed() {
        version.value = System.nanoTime()
        // Person widgets show the latest message: redraw them shortly after changes settle
        if (::app.isInitialized) { widgetJob?.cancel(); widgetJob = scope.launch { kotlinx.coroutines.delay(2500); com.yatesv.contacthub.ui.PersonWidget.refreshAll(app) } }
    }

    /** Latest reply buttons from the Messages app, per conversation. */
    val replies = ConcurrentHashMap<String, ReplyHandle>()

    /** Short message for the in-app banner when something breaks (RCS, Life360...). Empty = fine. */
    val banner = MutableStateFlow("")

    /** Conversation currently open on screen (no notification for it). */
    @Volatile var openConv: String = ""

    private val rcsPrefs by lazy { app.getSharedPreferences("rcs", Context.MODE_PRIVATE) }
    /** Last seen chat type for a conversation in the Messages app: "rcs", "sms" or "" (unknown). */
    fun rcsState(key: String): String = rcsPrefs.getString(key, "") ?: ""
    fun setRcsState(key: String, state: String) = rcsPrefs.edit().putString(key, state).apply()
}

class HubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.app = this
        com.yatesv.contacthub.engine.CrashLog.install(this)
        com.yatesv.contacthub.data.HubLog.init(this)
        Hub.db = HubDb.create(this)
        Hub.settings = SettingsStore(this)
        Hub.sms = SmsRepo(this)
        Hub.contacts = ContactsRepo(this)
        Hub.l360 = Life360(this)
        Hub.orbit = com.yatesv.contacthub.data.OrbitStore(this)
        Configuration.getInstance().userAgentValue = packageName
        Notifier.createChannels(this)
        com.yatesv.contacthub.engine.MockService.createChannel(this)
        com.yatesv.contacthub.engine.OrbitNotes.channel(this)
        runCatching { com.yatesv.contacthub.engine.DailyWorker.schedule(this) }
        runCatching { scheduleWork(this) }.onFailure { com.yatesv.contacthub.data.HubLog.w("App", "scheduleWork failed", it) }
        Hub.scope.launch { runCatching { Hub.contacts.all() } }
    }

    companion object {
        fun scheduleWork(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val s = Hub.settings.value
            if (s.backupDaily) {
                wm.enqueueUniquePeriodicWork(
                    "backup", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                        .setConstraints(Constraints.Builder().setRequiresCharging(true).build()).build(),
                )
            } else wm.cancelUniqueWork("backup")
            if (s.l360Token.isNotBlank() && s.l360RefreshMin >= 15) {
                wm.enqueueUniquePeriodicWork(
                    "life360", ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<Life360Worker>(s.l360RefreshMin.toLong(), TimeUnit.MINUTES).build(),
                )
            } else wm.cancelUniqueWork("life360")
        }
    }
}

/** Reads the reply / mark-as-read buttons off a Messages notification. */
fun Notification.replyHandle(pkg: String, title: String): ReplyHandle? {
    val acts = actions ?: return null
    val reply = acts.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return null
    val read = acts.firstOrNull { it.title?.toString()?.contains("read", ignoreCase = true) == true }
    return ReplyHandle(reply.actionIntent, reply.remoteInputs, read?.actionIntent, pkg, System.currentTimeMillis(), title)
}
