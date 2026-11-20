package com.yatesv.contacthub.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.R
import com.yatesv.contacthub.data.ArrivalRule
import com.yatesv.contacthub.data.Birthdays
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.Nudge
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.ui.MainActivity
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Notifications for Orbit's reminders. Each opens the right chat. */
object OrbitNotes {
    const val CH_REMINDERS = "reminders"

    fun channel(ctx: Context) {
        ctx.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(CH_REMINDERS, "Reminders (birthdays, snoozes, check-ins)", android.app.NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openChat(ctx: Context, key: String, draft: String? = null, req: Int = key.hashCode()): PendingIntent = PendingIntent.getActivity(ctx, req,
        Intent(ctx, MainActivity::class.java).putExtra("conv", key).apply { if (draft != null) putExtra("draft", draft) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun show(ctx: Context, id: Int, title: String, text: String, open: PendingIntent?, action: Pair<String, PendingIntent>? = null) {
        channel(ctx)
        val b = NotificationCompat.Builder(ctx, CH_REMINDERS).setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setColor(Hub.settings.value.accent)
        open?.let { b.setContentIntent(it) }
        action?.let { (label, pi) -> b.addAction(R.drawable.ic_tile, label, pi) }
        runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }
}

/** Runs every morning: birthday reminders and "haven't talked in a while" nudges. */
class DailyWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        runCatching { birthdays(applicationContext) }.onFailure { HubLog.w("Daily", "birthdays", it) }
        runCatching { nudges(applicationContext) }.onFailure { HubLog.w("Daily", "nudges", it) }
        return Result.success()
    }

    companion object {
        /** Schedules the next run around 9 AM. */
        fun schedule(ctx: Context) {
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); if (before(now)) add(Calendar.DAY_OF_YEAR, 1) }
            // KEEP: opening the app must not push today's pending run to tomorrow
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS).setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS).build())
        }

        /** Birthdays from Contacts plus the ones you typed on contact pages. */
        suspend fun birthdays(ctx: Context) {
            if (!Hub.settings.value.birthdayReminders) return
            val year = Calendar.getInstance().get(Calendar.YEAR)
            val contacts = Hub.contacts.all()
            val found = LinkedHashMap<String, Pair<String, String>>() // number → (name, birthday)
            // From Android Contacts
            runCatching {
                ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Event.START_DATE),
                    "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                    arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0); val date = c.getString(1).orEmpty()
                        contacts.firstOrNull { it.id == id }?.let { found[Phone.norm(it.number)] = it.name to date }
                    }
                }
            }
            // Typed on the contact page (wins)
            Hub.db.dao().allExtras().filter { it.birthday.isNotBlank() }.forEach { e ->
                val name = Hub.contacts.cached(e.phoneKey)?.name ?: Phone.pretty(e.phoneKey)
                found[e.phoneKey] = name to e.birthday
            }
            // One reminder per person, even if they have several numbers
            for ((number, nb) in found.entries.distinctBy { it.value.first }.associate { it.key to it.value }) {
                val (name, bday) = nb
                if (!Birthdays.isToday(bday)) continue
                val tag = "$number:$year"
                if (Hub.orbit.birthdayDone(tag)) continue
                Hub.orbit.markBirthday(tag)
                val key = Phone.convKey(listOf(number))
                val age = Birthdays.turning(bday)
                OrbitNotes.show(ctx, ("bday$number").hashCode(), "🎂 It's ${name.substringBefore(' ')}'s birthday",
                    if (age != null) "$name turns $age today." else "Send $name a happy birthday.",
                    OrbitNotes.openChat(ctx, key),
                    "Text happy birthday" to OrbitNotes.openChat(ctx, key, "Happy birthday ${name.substringBefore(' ')}! 🎉", ("bd" + number).hashCode()))
            }
        }

        suspend fun nudges(ctx: Context) {
            for ((phoneKey, days) in Hub.orbit.nudges()) {
                val key = Phone.convKey(listOf(phoneKey))
                val thread = Hub.sms.threadIdFor(key)
                val last = maxOf(Hub.sms.messages(thread, limit = 1).firstOrNull()?.date ?: 0L, Hub.db.dao().local(key).maxOfOrNull { it.date } ?: 0L)
                if (last == 0L) continue
                if (!Nudge.due(last, days, Hub.orbit.lastNudged(phoneKey))) continue
                Hub.orbit.markNudged(phoneKey)
                val name = Hub.contacts.cached(phoneKey)?.name ?: Phone.pretty(phoneKey)
                val ago = ((System.currentTimeMillis() - last) / 86_400_000L).toInt()
                OrbitNotes.show(ctx, ("nudge$phoneKey").hashCode(), "Check in with ${name.substringBefore(' ')}?",
                    "You haven't talked to $name in $ago days.", OrbitNotes.openChat(ctx, key))
            }
        }
    }
}

/** Brings a snoozed chat back and reminds you about it. */
class SnoozeWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("conv") ?: return Result.success()
        val s = Hub.orbit.snoozes()[key] ?: return Result.success()
        if (s.first > System.currentTimeMillis() + 60_000) return Result.success() // re-snoozed for later
        Hub.orbit.unsnooze(key)
        Hub.changed()
        // If the chat moved on since you snoozed it (they texted, or you did), no need to remind you
        val thread = Hub.sms.threadIdFor(key)
        val last = maxOf(Hub.sms.messages(thread, limit = 1).firstOrNull()?.date ?: 0L, Hub.db.dao().local(key).maxOfOrNull { it.date } ?: 0L)
        if (last > s.second) return Result.success()
        OrbitNotes.show(applicationContext, ("snz$key").hashCode(), "Reminder: ${Notifier.displayName(key)}", "You snoozed this chat. Tap to open it.",
            OrbitNotes.openChat(applicationContext, key))
        return Result.success()
    }

    companion object {
        fun snooze(ctx: Context, key: String, until: Long) {
            Hub.orbit.snooze(key, until)
            WorkManager.getInstance(ctx).enqueueUniqueWork("snooze-$key", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SnoozeWorker>().setInputData(workDataOf("conv" to key))
                    .setInitialDelay((until - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build())
            Hub.changed()
        }

        fun unsnooze(ctx: Context, key: String) {
            Hub.orbit.unsnooze(key); WorkManager.getInstance(ctx).cancelUniqueWork("snooze-$key"); Hub.changed()
        }
    }
}

/** "Text when I arrive": Android tells Orbit when you enter a place, and Orbit sends your text. */
object Arrivals {
    private const val ACTION = "com.yatesv.contacthub.ARRIVE"

    fun canWatch(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun canWatchInBackground(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pi(ctx: Context, id: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT) = PendingIntent.getBroadcast(ctx, id.hashCode(),
        Intent(ctx, ArrivalReceiver::class.java).setAction(ACTION).putExtra("id", id), flags or PendingIntent.FLAG_MUTABLE)

    /**
     * Hands one rule to Android. Android says "entering" right away if you're already inside, so the rule is
     * stamped as just armed and that first event is ignored (you have to leave and come back).
     * Only call this when a rule is created/turned on, or after boot/update; Android keeps alerts while the phone is on,
     * even when Orbit's process is closed.
     */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context, r: ArrivalRule) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        runCatching { lm.removeProximityAlert(pi(ctx, r.id)) }
        if (!r.on || !canWatch(ctx)) return
        runCatching {
            Hub.orbit.setArmed(r.id)
            lm.addProximityAlert(r.lat, r.lng, r.radiusM.toFloat(), -1, pi(ctx, r.id))
        }.onFailure { HubLog.w("Arrive", "add alert", it) }
    }

    @SuppressLint("MissingPermission")
    fun unregister(ctx: Context, id: String) {
        runCatching { ctx.getSystemService(LocationManager::class.java).removeProximityAlert(pi(ctx, id)) }
    }

    /** After a reboot, an app update, a restore, or location permission just granted. */
    fun syncAll(ctx: Context) = Hub.orbit.arrivals().forEach { register(ctx, it) }

    fun remove(ctx: Context, id: String) {
        unregister(ctx, id)
        Hub.orbit.deleteArrival(id)
    }
}

class ArrivalReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { com.yatesv.contacthub.data.safely("OrbitWork") {
        if (!intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) return
        val id = intent.getStringExtra("id") ?: return
        val r = Hub.orbit.arrivals().firstOrNull { it.id == id } ?: return
        if (!r.on) return
        // Right after a rule is set up (or the phone restarts), Android says "inside" if you're already there.
        // That isn't an arrival: you have to leave and come back.
        if (System.currentTimeMillis() - Hub.orbit.armedAt(id) < 90_000) { HubLog.d("Arrive", "already inside when armed, not sending"); return }
        // Only real arrivals: a running mock location doesn't count
        if (MockStore.state.value.active) { HubLog.d("Arrive", "ignored while mocking"); return }
        // Don't send twice for one visit (GPS can wobble across the edge)
        if (System.currentTimeMillis() - r.lastFired < 2 * 3_600_000L) return
        Hub.orbit.saveArrival(r.copy(lastFired = System.currentTimeMillis(), on = r.everyTime))
        if (!r.everyTime) Arrivals.unregister(ctx, r.id)
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<ArrivalWorker>()
            .setInputData(workDataOf("to" to r.to, "text" to r.text, "place" to r.place))
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }
    }
}

class ArrivalWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val to = inputData.getString("to") ?: return Result.success()
        val text = inputData.getString("text") ?: return Result.success()
        val key = Phone.convKey(listOf(to))
        val r = Sender.send(applicationContext, key, text, listOf(to), allowDriving = false)
        val name = Hub.contacts.cached(to)?.name ?: Phone.pretty(to)
        OrbitNotes.show(applicationContext, ("arr$key").hashCode(),
            if (r.ok) "📍 Told $name you arrived" else "📍 Arrival text not sent",
            if (r.ok) "\"$text\" · ${inputData.getString("place")}" else r.error, OrbitNotes.openChat(applicationContext, key))
        return Result.success()
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo =
        androidx.work.ForegroundInfo(7210, NotificationCompat.Builder(applicationContext, Notifier.CH_STATUS).setSmallIcon(R.drawable.ic_tile).setContentTitle("Sending arrival text…").build())
}

/** After a call: a notification to jot down a note. */
object CallNotes {
    @Volatile var pendingFor: String? = null

    fun afterCall(ctx: Context, number: String, durationSec: Long) {
        if (number.isBlank() || durationSec < 20) return
        val name = Hub.contacts.cached(number)?.name ?: Phone.pretty(number)
        val pi = PendingIntent.getActivity(ctx, ("note$number").hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra("callnote", number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        OrbitNotes.show(ctx, 4300, "Call with ${name.substringBefore(' ')} ended", "Tap to add a note about the call.", pi, "Add note" to pi)
    }
}

/** Keeps recurring scheduled texts going: after one sends, the next one is scheduled. */
object RecurringTexts {
    suspend fun afterSent(s: com.yatesv.contacthub.data.Scheduled) {
        val rule = Hub.orbit.repeatOf(s.id).ifBlank { return }
        val next = com.yatesv.contacthub.data.Recurring.next(s.sendAt, rule) ?: return
        val id = Hub.db.dao().schedule(com.yatesv.contacthub.data.Scheduled(convKey = s.convKey, addresses = s.addresses, body = s.body, sendAt = next))
        Hub.orbit.setRepeat(id, rule)
        Hub.orbit.setRepeat(s.id, "")
        ScheduleWorker.enqueue(Hub.app, next - System.currentTimeMillis())
    }
}
