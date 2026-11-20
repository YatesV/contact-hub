package com.yatesv.contacthub.engine

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.ContactExtra
import com.yatesv.contacthub.data.ConvMeta
import com.yatesv.contacthub.data.Folder
import com.yatesv.contacthub.data.GroupInfo
import com.yatesv.contacthub.data.LocalMessage
import com.yatesv.contacthub.data.Reaction
import com.yatesv.contacthub.data.Scheduled
import com.yatesv.contacthub.data.Settings
import com.yatesv.contacthub.data.Starred
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Sends scheduled messages when they're due. */
class ScheduleWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val dao = Hub.db.dao()
        if (dao.failStuckScheduled(System.currentTimeMillis() - 10 * 60_000) > 0)
            Notifier.status(applicationContext, 7300, "A scheduled message may not have sent", "Check the Scheduled list and the chat.")
        for (s in dao.dueScheduled(System.currentTimeMillis())) {
            if (dao.claimScheduled(s.id) == 0) continue
            val r = Sender.send(applicationContext, s.convKey, s.body, s.addresses.split(',').filter { it.isNotBlank() })
            dao.setScheduled(s.id, if (r.ok) "sent" else "failed")
            // Repeating texts: schedule the next one (even if this one failed, so a yearly text isn't lost)
            runCatching { RecurringTexts.afterSent(s) }
            if (!r.ok) Notifier.status(applicationContext, s.id.toInt(), "Scheduled message not sent", r.error)
        }
        Hub.changed()
        return Result.success()
    }

    companion object {
        fun enqueue(ctx: Context, delayMs: Long) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "scheduled-${System.currentTimeMillis() + delayMs}", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ScheduleWorker>().setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS).build(),
            )
        }
    }
}

/** Sends a reply typed into a notification. Runs as a job so it isn't cut off. */
class ReplyWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("conv") ?: return Result.failure()
        val text = inputData.getString("text") ?: return Result.failure()
        // From a notification you're not looking at the app, so don't pop Google Messages open
        val r = Sender.send(applicationContext, key, text, allowDriving = false)
        com.yatesv.contacthub.engine.ActionReceiver.markRead(key)
        if (!r.ok) Notifier.status(applicationContext, key.hashCode(), "Reply not sent", r.error)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, Notifier.CH_STATUS)
            .setSmallIcon(com.yatesv.contacthub.R.drawable.ic_tile).setContentTitle("Sending…").build()
        return androidx.work.ForegroundInfo(7200, n)
    }

    companion object {
        fun enqueue(ctx: Context, key: String, text: String) {
            WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<ReplyWorker>()
                    .setInputData(androidx.work.workDataOf("conv" to key, "text" to text))
                    .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }
    }
}

class Life360Worker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val token = Hub.settings.value.l360Token
        if (token.isBlank()) return Result.success()
        Hub.l360.refresh(token)
        return Result.success()
    }
}

/** Daily backup of everything Orbit stores, plus a copy of your texts. Keeps the last 7. */
class BackupWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = runCatching {
        Backup.writeDaily(applicationContext); Result.success()
    }.getOrElse { Result.retry() }
}

object Backup {
    fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "backups").apply { mkdirs() }

    suspend fun build(ctx: Context): JSONObject {
        val dao = Hub.db.dao()
        fun arr(list: List<JSONObject>) = JSONArray().apply { list.forEach { put(it) } }
        return JSONObject().apply {
            put("app", "contact-hub"); put("version", 1); put("created", System.currentTimeMillis())
            put("settings", Hub.settings.exportJson())
            put("meta", arr(dao.allMeta().map { m -> JSONObject().apply {
                put("key", m.key); put("pinned", m.pinned); put("archived", m.archived); put("muted", m.muted); put("hidden", m.hidden)
                put("folderId", m.folderId); put("customName", m.customName); put("photoUri", m.photoUri); put("wallpaperUri", m.wallpaperUri)
                put("bubbleColor", m.bubbleColor); put("soundUri", m.soundUri); put("lastRead", m.lastRead); put("engine", m.engine)
            } }))
            put("local", arr(dao.allLocal().map { m -> JSONObject().apply {
                put("convKey", m.convKey); put("body", m.body); put("date", m.date); put("outgoing", m.outgoing)
                put("sender", m.sender); put("source", m.source)
            } }))
            put("reactions", arr(dao.allReactions().map { r -> JSONObject().apply {
                put("convKey", r.convKey); put("msgKey", r.msgKey); put("msgBody", r.msgBody); put("emoji", r.emoji)
                put("fromMe", r.fromMe); put("pending", r.pending); put("date", r.date)
            } }))
            put("starred", arr(dao.allStarred().map { s -> JSONObject().apply {
                put("msgKey", s.msgKey); put("convKey", s.convKey); put("body", s.body); put("date", s.date)
            } }))
            put("scheduled", arr(dao.allScheduled().filter { it.status == "waiting" }.map { s -> JSONObject().apply {
                put("convKey", s.convKey); put("addresses", s.addresses); put("body", s.body); put("sendAt", s.sendAt)
            } }))
            put("folders", arr(dao.allFolders().map { f -> JSONObject().apply { put("id", f.id); put("name", f.name); put("sort", f.sort) } }))
            put("extras", arr(dao.allExtras().map { e -> JSONObject().apply {
                put("phoneKey", e.phoneKey); put("instagram", e.instagram); put("snapchat", e.snapchat); put("tiktok", e.tiktok)
                put("notes", e.notes); put("birthday", e.birthday); put("life360Id", e.life360Id); put("priority", e.priority)
                put("groupColor", e.groupColor)
            } }))
            put("groups", arr(dao.allGroups().map { g -> JSONObject().apply { put("convKey", g.convKey); put("name", g.name); put("photoUri", g.photoUri) } }))
            put("sms", arr(runCatching { Hub.sms.allSmsForBackup() }.getOrDefault(emptyList()).map { JSONObject(it) }))
            MockStore.load()
            put("mockFavorites", org.json.JSONArray(com.yatesv.contacthub.data.MockItem.listToJson(MockStore.favorites.value)))
            put("orbit", Hub.orbit.exportJson())
            put("mockRecent", org.json.JSONArray(com.yatesv.contacthub.data.MockItem.listToJson(MockStore.history.value)))
        }
    }

    suspend fun writeDaily(ctx: Context): File {
        val name = "contacthub-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
        val f = File(dir(ctx), name)
        val json = build(ctx).toString()
        f.writeText(json)
        // Also overwrite the Google Drive copy, if Drive is connected
        Hub.settings.value.driveUri.takeIf { it.isNotBlank() }?.let { u ->
            runCatching { ctx.contentResolver.openOutputStream(Uri.parse(u), "wt")?.use { it.write(json.toByteArray()) } }
                .onFailure { com.yatesv.contacthub.data.HubLog.w("Backup", "Drive write failed", it) }
        }
        dir(ctx).listFiles()?.filter { it.name.startsWith("contacthub-") }?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }
        Hub.settings.update { it.copy(lastBackup = System.currentTimeMillis()) }
        return f
    }

    suspend fun exportTo(ctx: Context, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(build(ctx).toString(2).toByteArray()) }
        Hub.settings.update { it.copy(lastBackup = System.currentTimeMillis()) }
    }

    /** Restores Orbit's own data. Texts themselves stay in the phone's SMS database. */
    suspend fun restore(ctx: Context, uri: Uri): String {
        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return "Couldn't open file"
        val j = JSONObject(text)
        if (j.optString("app") != "contact-hub") return "That isn't a Orbit backup"
        val dao = Hub.db.dao()
        fun JSONObject.objs(k: String) = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        j.optJSONObject("settings")?.let { sj ->
            val keep = Hub.settings.value
            Hub.settings.update { Settings.fromJson(sj).copy(l360Token = keep.l360Token, pinHash = keep.pinHash, mapboxToken = keep.mapboxToken, aiKey = keep.aiKey, onboarded = true) }
        }
        j.objs("meta").forEach { o -> dao.putMeta(ConvMeta(o.getString("key"), o.optBoolean("pinned"), o.optBoolean("archived"),
            o.optBoolean("muted"), o.optBoolean("hidden"), o.optLong("folderId"), o.optString("customName"), o.optString("photoUri"),
            o.optString("wallpaperUri"), o.optInt("bubbleColor"), o.optString("soundUri"), o.optLong("lastRead"), o.optString("engine", "auto"))) }
        val existing = dao.allLocal().map { it.convKey + it.date + it.body }.toSet()
        j.objs("local").forEach { o ->
            val m = LocalMessage(convKey = o.getString("convKey"), body = o.optString("body"), date = o.optLong("date"),
                outgoing = o.optBoolean("outgoing"), sender = o.optString("sender"), source = o.optString("source", "rcs"))
            if (m.convKey + m.date + m.body !in existing) dao.addLocal(m)
        }
        j.objs("reactions").forEach { o -> dao.addReaction(Reaction(convKey = o.getString("convKey"), msgKey = o.getString("msgKey"),
            msgBody = o.optString("msgBody"), emoji = o.getString("emoji"), fromMe = o.optBoolean("fromMe"), pending = o.optBoolean("pending"),
            date = o.optLong("date"))) }
        j.objs("starred").forEach { o -> dao.star(Starred(o.getString("msgKey"), o.optString("convKey"), o.optString("body"), o.optLong("date"))) }
        j.objs("scheduled").forEach { o -> if (o.optLong("sendAt") > System.currentTimeMillis())
            dao.schedule(Scheduled(convKey = o.getString("convKey"), addresses = o.optString("addresses"), body = o.optString("body"), sendAt = o.optLong("sendAt"))) }
        j.objs("folders").forEach { o -> dao.addFolder(Folder(o.optLong("id"), o.optString("name"), o.optInt("sort"))) }
        j.objs("extras").forEach { o -> dao.putExtra(ContactExtra(o.getString("phoneKey"), o.optString("instagram"), o.optString("snapchat"),
            o.optString("tiktok"), o.optString("notes"), o.optString("birthday"), o.optString("life360Id"), o.optBoolean("priority"), o.optInt("groupColor"))) }
        j.objs("groups").forEach { o -> dao.putGroup(GroupInfo(o.getString("convKey"), o.optString("name"), o.optString("photoUri"))) }
        j.optJSONObject("orbit")?.let { Hub.orbit.importJson(it) }
        MockStore.load()
        j.optJSONArray("mockFavorites")?.let { a ->
            val have = MockStore.favorites.value.map { it.id }.toSet()
            com.yatesv.contacthub.data.MockItem.listFromJson(a.toString()).asReversed().filter { it.id !in have }.forEach { MockStore.saveFavorite(it) }
        }
        j.optJSONArray("mockRecent")?.let { a ->
            if (MockStore.history.value.isEmpty()) com.yatesv.contacthub.data.MockItem.listFromJson(a.toString()).asReversed().forEach { MockStore.addHistory(it) }
        }
        ScheduleWorker.enqueue(ctx, 0)
        Hub.changed()
        return "Restored"
    }
}
