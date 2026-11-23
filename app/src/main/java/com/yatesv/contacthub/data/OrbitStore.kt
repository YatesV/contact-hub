package com.yatesv.contacthub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

data class PinnedMsg(val msgKey: String, val body: String, val date: Long)
data class ContactGroup(val id: String, val name: String, val color: Int, val soundUri: String, val members: List<String>)
data class CallNote(val time: Long, val text: String)
data class ArrivalRule(
    val id: String, val place: String, val lat: Double, val lng: Double, val radiusM: Int,
    /** Phone number to text */
    val to: String, val text: String, val everyTime: Boolean, val lastFired: Long = 0, val on: Boolean = true,
)
data class TrailPoint(val t: Long, val lat: Double, val lng: Double)

/**
 * Small saved things for Orbit's extra features (snoozes, pinned messages, groups, notes, arrival texts…).
 * Kept in SharedPreferences as JSON so no database migration is needed. Phone only, never uploaded.
 */
class OrbitStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    /** Bumped on every change so screens can refresh. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version
    private fun bump() { _version.value = System.nanoTime() }

    private fun obj(k: String) = runCatching { JSONObject(prefs.getString(k, "{}")!!) }.getOrDefault(JSONObject())
    private fun arr(k: String) = runCatching { JSONArray(prefs.getString(k, "[]")!!) }.getOrDefault(JSONArray())
    private fun put(k: String, v: Any) { prefs.edit().putString(k, v.toString()).apply(); bump() }

    // ---------------- Snooze ----------------
    /** convKey → (until, snoozedAt) */
    fun snoozes(): Map<String, Pair<Long, Long>> = obj("snooze").let { o ->
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { it.getLong("until") to it.optLong("at") } }
    }
    @Synchronized fun snooze(key: String, until: Long) = put("snooze", obj("snooze").put(key, JSONObject().put("until", until).put("at", System.currentTimeMillis())))
    @Synchronized fun unsnooze(key: String) = put("snooze", obj("snooze").apply { remove(key) })

    // ---------------- Attachments of RCS messages ----------------
    /** msgKey ("l:<id>") → saved files (path, mime) */
    fun files(msgKey: String): List<Pair<String, String>> = obj("files").optJSONArray(msgKey)?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("p") to o.optString("m") } }
    }.orEmpty()
    @Synchronized fun setFiles(msgKey: String, list: List<Pair<String, String>>) =
        put("files", obj("files").put(msgKey, JSONArray().apply { list.forEach { (p, m) -> put(JSONObject().put("p", p).put("m", m)) } }))

    // ---------------- Pinned messages ----------------
    fun pins(key: String): List<PinnedMsg> = obj("pins").optJSONArray(key)?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> PinnedMsg(o.getString("k"), o.optString("b"), o.optLong("d")) } }
    }.orEmpty()
    @Synchronized fun pin(key: String, m: PinnedMsg) {
        val list = (pins(key).filter { it.msgKey != m.msgKey } + m).sortedBy { it.date }
        put("pins", obj("pins").put(key, JSONArray().apply { list.forEach { put(JSONObject().put("k", it.msgKey).put("b", it.body).put("d", it.date)) } }))
    }
    @Synchronized fun unpin(key: String, msgKey: String) {
        val list = pins(key).filter { it.msgKey != msgKey }
        put("pins", obj("pins").put(key, JSONArray().apply { list.forEach { put(JSONObject().put("k", it.msgKey).put("b", it.body).put("d", it.date)) } }))
    }

    // ---------------- Recurring scheduled texts ----------------
    /** Scheduled id → daily, weekly, monthly, yearly */
    fun repeatOf(id: Long): String = obj("repeat").optString(id.toString(), "")
    @Synchronized fun setRepeat(id: Long, rule: String) = put("repeat", obj("repeat").apply { if (rule.isBlank()) remove(id.toString()) else put(id.toString(), rule) })

    // ---------------- Nudges ----------------
    /** phoneKey → days */
    fun nudges(): Map<String, Int> = obj("nudge").let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }
    fun nudgeDays(phoneKey: String): Int = obj("nudge").optInt(phoneKey, 0)
    @Synchronized fun setNudge(phoneKey: String, days: Int) = put("nudge", obj("nudge").apply { if (days <= 0) remove(phoneKey) else put(phoneKey, days) })
    fun lastNudged(phoneKey: String): Long = prefs.getLong("nudged_$phoneKey", 0)
    fun markNudged(phoneKey: String) = prefs.edit().putLong("nudged_$phoneKey", System.currentTimeMillis()).apply()

    // ---------------- Birthdays ----------------
    fun birthdayDone(tag: String) = prefs.getBoolean("bday_$tag", false)
    fun markBirthday(tag: String) = prefs.edit().putBoolean("bday_$tag", true).apply()

    // ---------------- Contact groups ----------------
    fun groups(): List<ContactGroup> = arr("groups").let { a ->
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { o ->
                ContactGroup(o.getString("id"), o.optString("name"), o.optInt("color"), o.optString("sound"),
                    o.optJSONArray("m")?.let { m -> (0 until m.length()).map { m.getString(it) } }.orEmpty())
            }
        }
    }
    @Synchronized fun saveGroup(g: ContactGroup) {
        val list = groups().filter { it.id != g.id } + g
        put("groups", JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("sound", it.soundUri).put("m", JSONArray(it.members))) }
        })
    }
    @Synchronized fun deleteGroup(id: String) {
        val list = groups().filter { it.id != id }
        put("groups", JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("sound", it.soundUri).put("m", JSONArray(it.members))) }
        })
    }
    fun groupsOf(number: String): List<ContactGroup> = Phone.norm(number).let { k -> groups().filter { k in it.members } }

    // ---------------- Call notes ----------------
    fun callNotes(number: String): List<CallNote> = obj("notes").optJSONArray(Phone.norm(number))?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> CallNote(o.getLong("t"), o.optString("x")) } }
    }.orEmpty().sortedByDescending { it.time }
    @Synchronized fun addCallNote(number: String, text: String) {
        val k = Phone.norm(number)
        val a = obj("notes").optJSONArray(k) ?: JSONArray()
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("x", text))
        put("notes", obj("notes").put(k, a))
    }
    @Synchronized fun deleteCallNote(number: String, time: Long) {
        val k = Phone.norm(number)
        val keep = callNotes(number).filter { it.time != time }
        put("notes", obj("notes").put(k, JSONArray().apply { keep.forEach { put(JSONObject().put("t", it.time).put("x", it.text)) } }))
    }

    // ---------------- Spam ----------------
    fun spamList(): Set<String> = arr("spam").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    fun isReportedSpam(number: String) = Phone.norm(number) in spamList()
    @Synchronized fun setSpam(number: String, spam: Boolean) {
        val s = spamList().toMutableSet(); if (spam) s += Phone.norm(number) else s -= Phone.norm(number)
        put("spam", JSONArray(s.toList()))
    }

    // ---------------- Arrival texts ----------------
    fun arrivals(): List<ArrivalRule> = arr("arrive").let { a ->
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { o ->
                ArrivalRule(o.getString("id"), o.optString("place"), o.getDouble("lat"), o.getDouble("lng"), o.optInt("r", 150),
                    o.optString("to"), o.optString("text"), o.optBoolean("every", true), o.optLong("last"), o.optBoolean("on", true))
            }
        }
    }
    private fun writeArrivals(list: List<ArrivalRule>) = put("arrive", JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("place", it.place).put("lat", it.lat).put("lng", it.lng).put("r", it.radiusM)
            .put("to", it.to).put("text", it.text).put("every", it.everyTime).put("last", it.lastFired).put("on", it.on)) }
    })
    @Synchronized fun saveArrival(r: ArrivalRule) = writeArrivals(arrivals().filter { it.id != r.id } + r)
    @Synchronized fun deleteArrival(id: String) = writeArrivals(arrivals().filter { it.id != id })

    /** When a rule was last handed to Android (it reports "inside" right away if you're already there). */
    fun armedAt(id: String) = prefs.getLong("armed_$id", 0)
    fun setArmed(id: String) = prefs.edit().putLong("armed_$id", System.currentTimeMillis()).apply()

    // ---------------- Life360 trail ----------------
    fun trail(memberId: String): List<TrailPoint> = obj("trail").optJSONArray(memberId)?.let { a ->
        (0 until a.length()).map { a.getJSONArray(it).let { p -> TrailPoint(p.getLong(0), p.getDouble(1), p.getDouble(2)) } }
    }.orEmpty()
    @Synchronized fun addTrail(memberId: String, p: TrailPoint, now: Long = System.currentTimeMillis()) {
        val list = Trail.add(trail(memberId), p, now)
        put("trail", obj("trail").put(memberId, JSONArray().apply { list.forEach { put(JSONArray().put(it.t).put(it.lat).put(it.lng)) } }))
    }

    // ---------------- Messages removed from Orbit (Google Messages mode can't delete from the phone) ----------------
    fun hiddenMsgs(key: String): Set<String> = obj("hidden").optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
    @Synchronized fun hideMsgs(key: String, msgKeys: Collection<String>) {
        val all = (hiddenMsgs(key) + msgKeys).toList().takeLast(5000)
        put("hidden", obj("hidden").put(key, JSONArray(all)))
    }

    // ---------------- GIF search ----------------
    fun giphyKey(): String = prefs.getString("giphy", "").orEmpty()
    fun setGiphyKey(k: String) { prefs.edit().putString("giphy", k.trim()).apply(); bump() }

    /** Start recording automatically when a call connects */
    fun autoRecordCalls(): Boolean = prefs.getBoolean("autoRecord", false)
    fun setAutoRecordCalls(on: Boolean) { prefs.edit().putBoolean("autoRecord", on).apply(); bump() }
    /** User has seen the "check the law where you live" note */
    fun recordNoteSeen(): Boolean = prefs.getBoolean("recNote", false)
    fun setRecordNoteSeen() { prefs.edit().putBoolean("recNote", true).apply() }

    // ---------------- Person widgets ----------------
    fun widgetNumber(id: Int): String? = prefs.getString("widget_$id", null)
    fun setWidgetNumber(id: Int, number: String?) = prefs.edit().apply { if (number == null) remove("widget_$id") else putString("widget_$id", number) }.apply()

    /** For backups. */
    fun exportJson(): JSONObject = JSONObject().apply {
        for (k in listOf("snooze", "pins", "repeat", "nudge", "notes", "hidden")) put(k, obj(k))
        for (k in listOf("groups", "spam", "arrive")) put(k, arr(k))
    }
    fun importJson(j: JSONObject) {
        val e = prefs.edit()
        for (k in listOf("snooze", "pins", "repeat", "nudge", "notes", "hidden", "groups", "spam", "arrive")) j.opt(k)?.let { e.putString(k, it.toString()) }
        e.apply(); bump()
    }
}

/** Plain logic for the features above (unit tested). */
object Trail {
    /** Adds a point if you moved (or 30 min passed), keeps the last 48 hours. */
    fun add(list: List<TrailPoint>, p: TrailPoint, now: Long): List<TrailPoint> {
        val last = list.lastOrNull()
        val moved = last == null || Geo.miles(last.lat, last.lng, p.lat, p.lng) > 0.03 || p.t - last.t > 30 * 60_000
        val out = if (moved && (last == null || p.t > last.t)) list + p else list
        return out.filter { now - it.t < 48 * 3_600_000L }
    }

    /** Points from today (since local midnight). */
    fun today(list: List<TrailPoint>, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): List<TrailPoint> {
        val c = Calendar.getInstance(tz).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return list.filter { it.t >= c.timeInMillis }
    }
}

object Recurring {
    val RULES = listOf("" to "Never", "daily" to "Every day", "weekly" to "Every week", "monthly" to "Every month", "yearly" to "Every year")
    fun label(rule: String) = RULES.firstOrNull { it.first == rule.substringBefore('@') }?.second ?: ""

    /** "monthly" -> "monthly@31": remembers the original day so the 31st stays the 31st after a short month. */
    fun anchored(rule: String, firstSend: Long, tz: TimeZone = TimeZone.getDefault()): String {
        if ('@' in rule || rule !in listOf("monthly", "yearly")) return rule
        return rule + "@" + Calendar.getInstance(tz).apply { timeInMillis = firstSend }.get(Calendar.DAY_OF_MONTH)
    }

    /** Next send time after [from] (keeps the same time of day; skips times already in the past). */
    fun next(from: Long, rule: String, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): Long? {
        val base = rule.substringBefore('@')
        val field = when (base) { "daily" -> Calendar.DAY_OF_YEAR; "weekly" -> Calendar.WEEK_OF_YEAR; "monthly" -> Calendar.MONTH; "yearly" -> Calendar.YEAR; else -> return null }
        val c = Calendar.getInstance(tz).apply { timeInMillis = from }
        // The day it was first set for (Jan 31 -> Feb 28 -> Mar 31, not the 28th forever)
        val day = rule.substringAfter('@', "").toIntOrNull() ?: c.get(Calendar.DAY_OF_MONTH)
        var guard = 0
        do {
            c.add(field, 1)
            // Keep "the 31st" / "Feb 29" on the closest day instead of drifting
            if (field == Calendar.MONTH || field == Calendar.YEAR) c.set(Calendar.DAY_OF_MONTH, minOf(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        } while (c.timeInMillis <= now && ++guard < 5000)
        return c.timeInMillis
    }
}

object Birthdays {
    /** True if a saved birthday ("yyyy-MM-dd", "--MM-dd" or "MM-dd") is today. Feb 29 birthdays show on Feb 28 in other years. */
    fun isToday(bday: String, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): Boolean {
        val md = Regex("(\\d{1,2})-(\\d{1,2})$").find(bday.trim()) ?: return false
        val m = md.groupValues[1].toInt(); val d = md.groupValues[2].toInt()
        val c = Calendar.getInstance(tz).apply { timeInMillis = now }
        val tm = c.get(Calendar.MONTH) + 1; val td = c.get(Calendar.DAY_OF_MONTH)
        if (m == tm && d == td) return true
        val leap = c.getActualMaximum(Calendar.DAY_OF_YEAR) == 366
        return m == 2 && d == 29 && !leap && tm == 2 && td == 28
    }

    /** Age they're turning, if the year is known. */
    fun turning(bday: String, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): Int? {
        val y = Regex("^(\\d{4})-").find(bday.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (y < 1900) return null
        return Calendar.getInstance(tz).apply { timeInMillis = now }.get(Calendar.YEAR) - y
    }
}

object Nudge {
    /** Due if you haven't talked in [days] and you weren't nudged about them in the last [days]. */
    fun due(lastMessage: Long, days: Int, lastNudged: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (days <= 0) return false
        val span = days * 86_400_000L
        return now - lastMessage >= span && now - lastNudged >= span
    }
}

object Snooze {
    /** Hidden from the inbox: still snoozed and nothing new came in since you snoozed it. */
    fun hidden(until: Long, snoozedAt: Long, lastIncoming: Long, now: Long = System.currentTimeMillis()) = until > now && lastIncoming <= snoozedAt

    fun presets(now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): List<Pair<String, Long>> {
        fun at(addDays: Int, hour: Int) = Calendar.getInstance(tz).apply {
            timeInMillis = now; add(Calendar.DAY_OF_YEAR, addDays); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val out = mutableListOf("1 hour" to now + 3_600_000L, "3 hours" to now + 3 * 3_600_000L)
        if (at(0, 20) > now + 30 * 60_000) out += "Tonight, 8 PM" to at(0, 20)
        out += "Tomorrow, 9 AM" to at(1, 9)
        out += "Next week" to at(7, 9)
        return out
    }
}

/** History with one person, for the relationship card. */
data class Relationship(
    val first: Long, val total: Int, val mine: Int, val theirs: Int, val longestStreak: Int, val currentStreak: Int,
    /** 0..23, hour you text the most */
    val topHour: Int, val perDay: Double,
) {
    companion object {
        /** [msgs] = (date, outgoing). */
        fun compute(msgs: List<Pair<Long, Boolean>>, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): Relationship? {
            if (msgs.isEmpty()) return null
            val days = msgs.map { (it.first + tz.getOffset(it.first)) / 86_400_000L }.toSortedSet()
            var best = 0; var run = 0; var prev = Long.MIN_VALUE
            for (d in days) { run = if (d == prev + 1) run + 1 else 1; best = maxOf(best, run); prev = d }
            val today = (now + tz.getOffset(now)) / 86_400_000L
            val hours = IntArray(24)
            val cal = Calendar.getInstance(tz)
            msgs.forEach { cal.timeInMillis = it.first; hours[cal.get(Calendar.HOUR_OF_DAY)]++ }
            val first = msgs.minOf { it.first }
            val spanDays = ((now - first) / 86_400_000.0).coerceAtLeast(1.0)
            return Relationship(first, msgs.size, msgs.count { it.second }, msgs.count { !it.second }, best,
                Stats.streak(days, today), hours.indices.maxByOrNull { hours[it] } ?: 12, msgs.size / spanDays)
        }
    }
}

object Spam {
    /**
     * Unknown caller that looks like spam: you reported it, the carrier said the caller ID failed
     * verification, or it copies the first 6 digits of your own number ("neighbor spoofing").
     */
    fun reason(number: String, inContacts: Boolean, reported: Boolean, verificationFailed: Boolean, myNumbers: List<String>): String? {
        if (inContacts) return null
        if (reported) return "You reported this number"
        if (verificationFailed) return "Caller ID failed carrier verification"
        val n = Phone.norm(number)
        if (n.length == 10 && myNumbers.map { Phone.norm(it) }.any { it.length == 10 && it != n && it.take(6) == n.take(6) }) return "Looks like your own number (common spam trick)"
        return null
    }
}

/** Offline "catch me up" when there's no AI key: counts, questions for you, links, plans. */
object CatchUp {
    data class Line(val who: String, val text: String)

    fun summarize(msgs: List<Line>, myName: String): List<String> {
        if (msgs.isEmpty()) return listOf("Nothing new.")
        val out = ArrayList<String>()
        val byWho = msgs.groupingBy { it.who }.eachCount().entries.sortedByDescending { it.value }
        out += "${msgs.size} messages from " + byWho.joinToString { "${it.key} (${it.value})" }
        val questions = msgs.filter { it.text.trim().endsWith("?") || (myName.isNotBlank() && it.text.contains(myName, true)) }.takeLast(5)
        questions.forEach { out += "❓ ${it.who}: ${it.text.take(120)}" }
        val links = msgs.flatMap { m -> Links.find(m.text).filter { it.kind == Links.Kind.URL }.map { m.who to it.text } }.takeLast(3)
        links.forEach { out += "🔗 ${it.first} shared ${it.second.take(60)}" }
        val plans = Regex("(?i)\\b(today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\\d{1,2}(:\\d{2})?\\s?(am|pm))\\b")
        msgs.filter { plans.containsMatchIn(it.text) && it !in questions }.takeLast(3).forEach { out += "📅 ${it.who}: ${it.text.take(120)}" }
        if (out.size == 1) msgs.takeLast(3).forEach { out += "${it.who}: ${it.text.take(120)}" }
        return out
    }
}
