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
        // "Stuck" = marked sending, but no worker is sending it (the app was killed mid-send)
        val stuck = dao.sendingScheduled().filter { it.id !in inFlight && it.sendAt < System.currentTimeMillis() - 10 * 60_000 }
        stuck.forEach { dao.setScheduled(it.id, "failed") }
        if (stuck.isNotEmpty()) Notifier.status(applicationContext, 7300, "A scheduled message may not have sent", "Check the Scheduled list and the chat.")
        for (s in dao.dueScheduled(System.currentTimeMillis())) {
            if (dao.claimScheduled(s.id) == 0) continue
            inFlight += s.id
            try {
            // Background: never open Google Messages over whatever you're doing. It goes through the
            // send queue, which holds it for RCS (unlock / open Orbit) instead of falling back to SMS.
            SendQueue.add(applicationContext, s.convKey, s.body, drive = false, background = true,
                addresses = s.addresses.split(',').filter { it.isNotBlank() })
            dao.setScheduled(s.id, "sent")
            // Repeating texts: schedule the next one
            runCatching { RecurringTexts.afterSent(s) }
            } finally { inFlight -= s.id }
        }
        // Always arm the next one, so nothing waits for a reboot (e.g. after a restore)
        dao.allScheduled().filter { it.status == "waiting" && it.sendAt > System.currentTimeMillis() }.minByOrNull { it.sendAt }
            ?.let { enqueue(applicationContext, it.sendAt - System.currentTimeMillis()) }
        Hub.changed()
        return Result.success()
    }

    companion object {
        /** Scheduled texts being sent right now in this process. */
        val inFlight: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
        // From a notification you're not looking at the app, so don't pop Google Messages open.
        // The queue sends it right away through the reply button, or holds it for RCS.
        SendQueue.add(applicationContext, key, text, drive = false, background = true)
        com.yatesv.contacthub.engine.ActionReceiver.markRead(key)
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

/** Daily backup of Orbit's data plus a copy of your SMS texts (not photos). Keeps the last 7, also in Downloads/Orbit. */
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
                put("id", m.id); put("convKey", m.convKey); put("body", m.body); put("date", m.date); put("outgoing", m.outgoing)
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
                put("repeat", Hub.orbit.repeatOf(s.id))
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

    suspend fun writeDaily(ctx: Context): File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeDaily0(ctx) }

    private suspend fun writeDaily0(ctx: Context): File {
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
        // A copy in Downloads/Orbit too: Orbit's own folder is wiped if Orbit is uninstalled, this one isn't
        runCatching { copyToDownloads(ctx, name, json) }.onFailure { com.yatesv.contacthub.data.HubLog.w("Backup", "Downloads copy failed", it) }
        Hub.settings.update { it.copy(lastBackup = System.currentTimeMillis()) }
        return f
    }

    private const val DOWNLOADS_DIR = "Download/Orbit/"

    /** Today's backup in Downloads/Orbit (replaced if it already exists), keeping the newest 7. */
    private fun copyToDownloads(ctx: Context, name: String, json: String) {
        val cr = ctx.contentResolver
        val col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val mine = ArrayList<Pair<Long, String>>()
        cr.query(col, arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(DOWNLOADS_DIR), null)?.use { c ->
            while (c.moveToNext()) mine += c.getLong(0) to c.getString(1).orEmpty()
        }
        val today = mine.firstOrNull { it.second == name }?.first
        val uri = today?.let { android.content.ContentUris.withAppendedId(col, it) } ?: cr.insert(col, android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOADS_DIR)
        }) ?: return
        cr.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
        mine.filter { it.second.startsWith("contacthub-") && it.second != name }.sortedByDescending { it.second }.drop(6)
            .forEach { (id, _) -> runCatching { cr.delete(android.content.ContentUris.withAppendedId(col, id), null, null) } }
    }

    suspend fun exportTo(ctx: Context, uri: Uri) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(build(ctx).toString(2).toByteArray()) }
        Hub.settings.update { it.copy(lastBackup = System.currentTimeMillis()) }
    }

    /** Restores Orbit's own data. Texts themselves stay in the phone's SMS database. */
    suspend fun restore(ctx: Context, uri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { restore0(ctx, uri) }

    private suspend fun restore0(ctx: Context, uri: Uri): String {
        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return "Couldn't open file"
        val j = JSONObject(text)
        if (j.optString("app") != "contact-hub") return "That isn't a Orbit backup"
        val dao = Hub.db.dao()
        fun JSONObject.objs(k: String) = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        j.optJSONObject("settings")?.let { sj ->
            val keep = Hub.settings.value
            Hub.settings.update { Settings.fromJson(sj).copy(l360Token = keep.l360Token, pinHash = keep.pinHash, mapboxToken = keep.mapboxToken, aiKey = keep.aiKey, driveUri = keep.driveUri, onboarded = true) }
        }
        j.objs("meta").forEach { o -> dao.putMeta(ConvMeta(o.getString("key"), o.optBoolean("pinned"), o.optBoolean("archived"),
            o.optBoolean("muted"), o.optBoolean("hidden"), o.optLong("folderId"), o.optString("customName"), o.optString("photoUri"),
            o.optString("wallpaperUri"), o.optInt("bubbleColor"), o.optString("soundUri"), o.optLong("lastRead"), o.optString("engine", "auto"))) }
        // Message ids change on a restore: remember old "l:<id>" -> new so hidden messages still point at the right ones
        val idMap = HashMap<String, String>()
        val existing = dao.allLocal().associateBy { it.convKey + it.date + it.body }
        j.objs("local").forEach { o ->
            val m = LocalMessage(convKey = o.getString("convKey"), body = o.optString("body"), date = o.optLong("date"),
                outgoing = o.optBoolean("outgoing"), sender = o.optString("sender"), source = o.optString("source", "rcs"))
            val newId = existing[m.convKey + m.date + m.body]?.id ?: dao.addLocal(m)
            if (o.has("id")) idMap["l:${o.getLong("id")}"] = "l:$newId"
        }
        // Reactions are matched to messages by text, not by ids (phone message ids differ on a new phone).
        // Skipped if already there, so restoring twice doesn't double them.
        val haveReactions = dao.allReactions().map { "${it.convKey}|${it.msgBody}|${it.emoji}|${it.fromMe}" }.toHashSet()
        j.objs("reactions").forEach { o ->
            val body = o.optString("msgBody")
            val old = o.getString("msgKey")
            val r = Reaction(convKey = o.getString("convKey"), msgKey = if (old.startsWith("q:") || body.isBlank()) old else "q:$body",
                msgBody = body, emoji = o.getString("emoji"), fromMe = o.optBoolean("fromMe"), pending = o.optBoolean("pending"), date = o.optLong("date"))
            if (haveReactions.add("${r.convKey}|${r.msgBody}|${r.emoji}|${r.fromMe}")) dao.addReaction(r)
        }
        j.objs("starred").forEach { o -> dao.star(Starred(o.getString("msgKey"), o.optString("convKey"), o.optString("body"), o.optLong("date"))) }
        val haveScheduled = dao.allScheduled().filter { it.status == "waiting" }.map { "${it.convKey}|${it.sendAt}|${it.body}" }.toHashSet()
        j.objs("scheduled").forEach { o -> if (o.optLong("sendAt") > System.currentTimeMillis() &&
                haveScheduled.add("${o.getString("convKey")}|${o.optLong("sendAt")}|${o.optString("body")}")) {
            val id = dao.schedule(Scheduled(convKey = o.getString("convKey"), addresses = o.optString("addresses"), body = o.optString("body"), sendAt = o.optLong("sendAt")))
            // Repeat rules travel with the message (the old IDs mean nothing after a restore)
            o.optString("repeat").takeIf { it.isNotBlank() }?.let { Hub.orbit.setRepeat(id, it) }
            // Each one armed for its own time (one "run now" used to leave future ones waiting for a reboot)
            ScheduleWorker.enqueue(ctx, o.optLong("sendAt") - System.currentTimeMillis())
        } }
        j.objs("folders").forEach { o -> dao.addFolder(Folder(o.optLong("id"), o.optString("name"), o.optInt("sort"))) }
        j.objs("extras").forEach { o -> dao.putExtra(ContactExtra(o.getString("phoneKey"), o.optString("instagram"), o.optString("snapchat"),
            o.optString("tiktok"), o.optString("notes"), o.optString("birthday"), o.optString("life360Id"), o.optBoolean("priority"), o.optInt("groupColor"))) }
        j.objs("groups").forEach { o -> dao.putGroup(GroupInfo(o.getString("convKey"), o.optString("name"), o.optString("photoUri"))) }
        j.optJSONObject("orbit")?.let { o ->
            o.remove("repeat")
            // Hidden messages: swap old local ids for the new ones; drop ones that no longer exist
            o.optJSONObject("hidden")?.let { h ->
                val fixed = JSONObject()
                h.keys().forEach { conv ->
                    val a = h.optJSONArray(conv) ?: return@forEach
                    fixed.put(conv, JSONArray((0 until a.length()).map { a.getString(it) }.mapNotNull { k -> if (k.startsWith("l:")) idMap[k] else k }))
                }
                o.put("hidden", fixed)
            }
            Hub.orbit.importJson(o)
        }
        // Texts: only the default texting app can write them back
        val sms = j.objs("sms")
        var smsRestored = 0
        if (sms.isNotEmpty() && HubSms.isDefault(ctx)) smsRestored = runCatching { Hub.sms.restoreSms(sms) }.getOrElse { com.yatesv.contacthub.data.HubLog.w("Backup", "sms restore failed", it); 0 }
        Arrivals.syncAll(ctx)
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
        return when {
            sms.isEmpty() -> "Restored"
            HubSms.isDefault(ctx) -> "Restored (and $smsRestored texts that were missing)"
            else -> "Restored. To bring back texts too, make Orbit your default texting app and restore again."
        }
    }
}
