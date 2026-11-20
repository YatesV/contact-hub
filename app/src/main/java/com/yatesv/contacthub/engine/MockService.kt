package com.yatesv.contacthub.engine

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.R
import com.yatesv.contacthub.data.Drift
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.LatLng
import com.yatesv.contacthub.data.MockFix
import com.yatesv.contacthub.data.MockItem
import com.yatesv.contacthub.data.MockMath
import com.yatesv.contacthub.data.RoutePlan
import com.yatesv.contacthub.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** What's being mocked right now, for the screen, notification, tile and Life360 card. */
data class MockState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val item: MockItem? = null,
    val fix: MockFix? = null,
    val progressSec: Double = 0.0,
    val totalSec: Double = 0.0,
    /** Route finished and parked at the end ("stay at last point") */
    val parked: Boolean = false,
    val error: String = "",
) {
    val here: LatLng? get() = fix?.let { LatLng(it.lat, it.lng) }
}

data class MockSchedule(val id: Int, val item: MockItem, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("item", item.toJson()).put("at", at)
    companion object {
        fun fromJson(j: JSONObject) = MockSchedule(j.getInt("id"), MockItem.fromJson(j.getJSONObject("item")), j.getLong("at"))
    }
}

/** Favorites, history, schedules and the running session. Saved on the phone only. */
object MockStore {
    private val prefs by lazy { Hub.app.getSharedPreferences("mock", Context.MODE_PRIVATE) }
    const val UPDATE_MS = 5_000L

    private val _state = MutableStateFlow(MockState())
    val state: StateFlow<MockState> = _state
    internal fun set(s: MockState) { _state.value = s }

    private val _favorites = MutableStateFlow<List<MockItem>>(emptyList())
    val favorites: StateFlow<List<MockItem>> = _favorites
    private val _history = MutableStateFlow<List<MockItem>>(emptyList())
    val history: StateFlow<List<MockItem>> = _history
    private val _schedules = MutableStateFlow<List<MockSchedule>>(emptyList())
    val schedules: StateFlow<List<MockSchedule>> = _schedules

    private var loaded = false
    fun load() {
        if (loaded) return
        loaded = true
        _favorites.value = MockItem.listFromJson(prefs.getString("favorites", "[]")!!)
        _history.value = MockItem.listFromJson(prefs.getString("history", "[]")!!)
        _schedules.value = runCatching {
            val a = JSONArray(prefs.getString("schedules", "[]")); (0 until a.length()).map { MockSchedule.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        session()?.let { (item, progress, paused) ->
            _state.value = MockState(active = false, paused = paused, item = item, progressSec = progress)
        }
    }

    fun saveFavorite(item: MockItem) {
        load()
        _favorites.value = listOf(item) + _favorites.value.filter { it.id != item.id }
        prefs.edit().putString("favorites", MockItem.listToJson(_favorites.value)).apply()
    }

    fun deleteFavorite(id: String) {
        _favorites.value = _favorites.value.filter { it.id != id }
        prefs.edit().putString("favorites", MockItem.listToJson(_favorites.value)).apply()
    }

    fun addHistory(item: MockItem) {
        load()
        val sameSpot = { o: MockItem -> o.type == item.type && o.points == item.points }
        _history.value = (listOf(item.copy(time = System.currentTimeMillis())) + _history.value.filterNot(sameSpot)).take(25)
        prefs.edit().putString("history", MockItem.listToJson(_history.value)).apply()
    }

    fun clearHistory() { _history.value = emptyList(); prefs.edit().putString("history", "[]").apply() }

    private fun saveSchedules() =
        prefs.edit().putString("schedules", JSONArray().apply { _schedules.value.forEach { put(it.toJson()) } }.toString()).apply()

    fun addSchedule(ctx: Context, item: MockItem, at: Long) {
        load()
        val s = MockSchedule((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), item, at)
        _schedules.value = (_schedules.value + s).sortedBy { it.at }
        saveSchedules(); MockAlarms.arm(ctx, s)
    }

    fun removeSchedule(ctx: Context, id: Int) {
        _schedules.value.firstOrNull { it.id == id }?.let { MockAlarms.cancel(ctx, it) }
        _schedules.value = _schedules.value.filter { it.id != id }
        saveSchedules()
    }

    // The running session, saved so it can resume after a restart
    fun saveSession(item: MockItem?, progress: Double, paused: Boolean) {
        val e = prefs.edit()
        if (item == null) e.remove("session") else e.putString("session", JSONObject().put("item", item.toJson()).put("progress", progress).put("paused", paused).toString())
        e.apply()
    }

    fun session(): Triple<MockItem, Double, Boolean>? = runCatching {
        val j = JSONObject(prefs.getString("session", null) ?: return null)
        Triple(MockItem.fromJson(j.getJSONObject("item")), j.optDouble("progress", 0.0), j.optBoolean("paused", false))
    }.getOrNull()

    // Last real fix, from before mocking started (Android hands everyone the fake location while mocking)
    fun saveReal(p: LatLng) = prefs.edit().putString("real", "${p.lat},${p.lng}").putLong("realTime", System.currentTimeMillis()).apply()
    fun lastReal(): Pair<LatLng, Long>? = prefs.getString("real", null)?.split(',')?.let { (a, b) ->
        LatLng(a.toDouble(), b.toDouble()) to prefs.getLong("realTime", 0)
    }

    fun lastUsed(): MockItem? { load(); return _history.value.firstOrNull() }

    // ---------------- Control ----------------

    fun start(ctx: Context, item: MockItem) {
        saveSession(item, 0.0, false)
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MockService::class.java).setAction(MockService.START))
        } catch (e: Exception) {
            saveSession(null, 0.0, false); throw e
        }
        addHistory(item)
    }

    fun pause(ctx: Context) = send(ctx, MockService.PAUSE)
    fun resume(ctx: Context) = send(ctx, MockService.RESUME)
    fun stop(ctx: Context) = send(ctx, MockService.STOP)

    private fun send(ctx: Context, action: String) {
        if (!_state.value.active) { if (action == MockService.STOP) { saveSession(null, 0.0, false); _state.value = MockState() }; return }
        runCatching { ctx.startService(Intent(ctx, MockService::class.java).setAction(action)) }
            .onFailure { HubLog.w("Mock", "send $action failed", it) }
    }

    /** Orbit was opened and a mock should be running but isn't (Android stopped it): start it again. */
    fun resumeIfNeeded(ctx: Context) {
        load()
        if (session() != null && !_state.value.active) runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MockService::class.java).setAction(MockService.START))
        }.onFailure { HubLog.w("Mock", "resume on open failed", it) }
    }

    /** After a restart: resume a session that was running, and re-arm schedules. */
    fun onBoot(ctx: Context) {
        load()
        _schedules.value.filter { it.at > System.currentTimeMillis() - 60_000 }.forEach { MockAlarms.arm(ctx, it) }
        if (session() != null) runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MockService::class.java).setAction(MockService.START))
        }.onFailure { HubLog.w("Mock", "resume after boot failed", it) }
    }
}

/** What Android needs before a mock can run. */
object MockSetup {
    fun devOptionsOn(ctx: Context) =
        AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** Orbit is picked in Developer options → Select mock location app. */
    fun isMockApp(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Precise or approximate location allowed. */
    fun hasLocation(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canExactAlarm(ctx: Context) = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun notificationsOn(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun ready(ctx: Context) = devOptionsOn(ctx) && isMockApp(ctx)

    val providers: List<String>
        get() = buildList {
            add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }

    @Suppress("DEPRECATION")
    fun addProvider(lm: LocationManager, name: String) {
        runCatching { lm.removeTestProvider(name) }
        if (Build.VERSION.SDK_INT >= 31) {
            lm.addTestProvider(name, ProviderProperties.Builder()
                .setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW).setAccuracy(ProviderProperties.ACCURACY_FINE).build())
        } else {
            lm.addTestProvider(name, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        }
        lm.setTestProviderEnabled(name, true)
    }

    /** Self-test: adds and removes a throwaway provider. Doesn't touch your real location. */
    fun canAddProvider(ctx: Context): String {
        val lm = ctx.getSystemService(LocationManager::class.java)
        return runCatching { addProvider(lm, "contacthub_selftest"); lm.removeTestProvider("contacthub_selftest"); "ok" }
            .getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    }
}

/**
 * Feeds the fake location to Android's GPS, network and fused providers every 5 seconds,
 * so every app on the phone sees it.
 */
class MockService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> HubLog.w("Mock", "job failed", e) })
    private var loop: Job? = null
    private val lm by lazy { getSystemService(LocationManager::class.java) }
    private var added = emptyList<String>()

    private var item: MockItem? = null
    private var plan: RoutePlan? = null
    private var progress = 0.0
    private var paused = false
    private var parkedAt: LatLng? = null
    private var heading = 0f
    private val drift = Drift()
    private var altitudes: List<Double>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MockStore.load()
        when (intent?.action ?: START) {
            START -> begin()
            PAUSE -> { paused = true; save(); tick(false) }
            RESUME -> { paused = false; save(); tick(false) }
            STOP -> { end(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun begin() {
        stopped = false
        // Must go foreground right away, even if there's nothing to run
        createChannel(this)
        // When Android restarts the service in the background it may refuse foreground; keep the
        // session saved (it resumes the next time Orbit opens or after a restart) instead of crashing
        val fg = runCatching { startInForeground(note("Starting mock location…", null)) }
        if (fg.isFailure) { HubLog.w("Mock", "can't go foreground now, will resume when Orbit opens", fg.exceptionOrNull()); stopSelf(); return }
        val (it, prog, wasPaused) = MockStore.session() ?: run { end(); return }
        if (!MockSetup.isMockApp(this)) return fail("Pick Orbit in Developer options → Select mock location app.")
        item = it; progress = prog; paused = wasPaused
        heading = Drift.heading(it.id.hashCode().toLong())
        plan = if (it.isRoute) RoutePlan(it.path.ifEmpty { it.points }, it.speedMph) else null
        parkedAt = if (it.isRoute) null else it.points.last()
        if (plan != null && progress >= plan!!.totalSeconds && it.endMode != "loop" && it.endMode != "pingpong") parkedAt = it.target
        added = MockSetup.providers.filter { p ->
            runCatching { MockSetup.addProvider(lm, p); true }.getOrElse { e ->
                HubLog.w("Mock", "provider $p", e)
                if (e is SecurityException) return fail("Android blocked mocking. Pick Orbit in Developer options → Select mock location app.")
                false
            }
        }
        if (LocationManager.GPS_PROVIDER !in added) return fail("Couldn't take over GPS on this phone.")
        HubLog.d("Mock", "start ${it.type} providers=${added.joinToString()}")
        altitudes = null
        scope.launch { altitudes = MockApi.elevation(if (it.isRoute) it.path.ifEmpty { it.points } else it.points) }
        loop?.cancel()
        loop = scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                tick(true, (now - last) / 1000.0)
                last = now
                delay(MockStore.UPDATE_MS)
            }
        }
    }

    private fun fail(msg: String) {
        HubLog.w("Mock", msg)
        MockStore.set(MockState(error = msg, item = item))
        NotificationManagerCompat.from(this).runCatching {
            notify(NOTE_ERROR, NotificationCompat.Builder(this@MockService, CH_MOCK).setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Mock location didn't start").setContentText(msg).setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setContentIntent(openIntent(this@MockService)).setAutoCancel(true).build())
        }
        MockStore.saveSession(null, 0.0, false)
        removeProviders()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Synchronized
    private fun tick(advance: Boolean, dt: Double = 0.0) {
        if (stopped) return
        val it = item ?: return
        val p = plan
        var routeFix: MockFix? = null
        if (p != null && parkedAt == null) {
            if (advance && !paused) progress += dt
            val f = p.play(progress, it.endMode)
            if (f.done) {
                when (it.endMode) {
                    "stop" -> { push(f, 4f); HubLog.d("Mock", "route done, stopping"); end(); return }
                    else -> { parkedAt = LatLng(f.lat, f.lng); heading = f.bearing }
                }
            } else routeFix = f
        }
        val fix: MockFix
        val acc: Float
        if (routeFix != null && !paused) {
            fix = routeFix; acc = 4f
        } else {
            // Parked, paused, or a single point: wobble a few feet like a real phone
            val base = parkedAt ?: routeFix?.let { f -> LatLng(f.lat, f.lng) } ?: it.points.last()
            val (w, a) = drift.next(base)
            fix = MockFix(w.lat, w.lng, routeFix?.bearing ?: heading, 0f); acc = a
        }
        push(fix, acc)
        if (advance) save()
        val total = p?.totalSeconds ?: 0.0
        MockStore.set(MockState(true, paused, it, fix, progress.coerceAtMost(if (it.endMode == "loop" || it.endMode == "pingpong") Double.MAX_VALUE else total), total, parkedAt != null && p != null))
        NotificationManagerCompat.from(this).runCatching { notify(NOTE_ID, note(title(it), subtitle(it, total))) }
        MockTile.refresh(this)
    }

    private fun push(f: MockFix, acc: Float) {
        val alt = altitudeAt()
        for (prov in added) runCatching {
            val l = Location(prov).apply {
                latitude = f.lat; longitude = f.lng
                accuracy = if (prov == LocationManager.NETWORK_PROVIDER) acc * 3 else acc
                bearing = f.bearing; speed = f.speedMps
                if (alt != null) altitude = alt
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                verticalAccuracyMeters = 5f
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = if (f.speedMps > 0.5f) 8f else 45f
            }
            lm.setTestProviderLocation(prov, l)
        }.onFailure { e -> HubLog.w("Mock", "push $prov", e) }
    }

    /** Ground height where you are now, if it's been looked up. */
    private fun altitudeAt(): Double? {
        val alts = altitudes ?: return null
        if (alts.isEmpty()) return null
        val p = plan ?: return alts.first()
        if (parkedAt != null) return alts.last()
        val total = p.totalSeconds.takeIf { it > 0 } ?: return alts.first()
        val one = when (item?.endMode) {
            "loop" -> progress % total
            "pingpong" -> ((progress / total).toLong() % 2).let { lap -> val r = progress % total; if (lap == 0L) r else total - r }
            else -> progress.coerceAtMost(total)
        }
        // Elevation samples are evenly spaced along the line, so spread by distance travelled
        val idx = one / total * (alts.size - 1)
        val lo = idx.toInt().coerceIn(0, alts.lastIndex)
        val hi = (lo + 1).coerceAtMost(alts.lastIndex)
        return alts[lo] + (alts[hi] - alts[lo]) * (idx - lo)
    }

    private fun save() = MockStore.saveSession(item, progress, paused)

    private fun title(it: MockItem) = when {
        paused -> "Mock location paused"
        it.isRoute && parkedAt == null -> "Driving route · ${it.speedMph.toInt()} mph"
        else -> "Mock location on"
    }

    private fun subtitle(it: MockItem, total: Double): String {
        val p = plan
        return if (p != null && parkedAt == null && total > 0) {
            val done = MockMath.miles(p.totalMeters * ((progress % total) / total).coerceIn(0.0, 1.0))
            "${it.name} · %.1f of %.1f mi".format(done, MockMath.miles(p.totalMeters))
        } else (if (p != null) "Parked at end: " else "At ") + it.name
    }

    private fun note(title: String, text: String?): android.app.Notification {
        val b = NotificationCompat.Builder(this, CH_MOCK).setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent(this))
        if (item != null) {
            if (paused) b.addAction(R.drawable.ic_tile, "Resume", action(RESUME)) else b.addAction(R.drawable.ic_tile, "Pause", action(PAUSE))
            b.addAction(R.drawable.ic_tile, "Stop", action(STOP))
        }
        return b.build()
    }

    private fun action(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, MockService::class.java).setAction(a),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun startInForeground(n: android.app.Notification) {
        ServiceCompat.startForeground(this, NOTE_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    private fun removeProviders() {
        for (p in added) runCatching { lm.setTestProviderEnabled(p, false); lm.removeTestProvider(p) }
        added = emptyList()
    }

    @Volatile private var stopped = false

    @Synchronized
    private fun end() {
        stopped = true
        item = null
        loop?.cancel()
        removeProviders()
        MockStore.saveSession(null, 0.0, false)
        MockStore.set(MockState())
        HubLog.d("Mock", "stopped")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        MockTile.refresh(this)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        removeProviders()
        if (MockStore.state.value.active) MockStore.set(MockStore.state.value.copy(active = false))
        super.onDestroy()
    }

    companion object {
        const val START = "com.yatesv.contacthub.mock.START"
        const val PAUSE = "com.yatesv.contacthub.mock.PAUSE"
        const val RESUME = "com.yatesv.contacthub.mock.RESUME"
        const val STOP = "com.yatesv.contacthub.mock.STOP"
        const val CH_MOCK = "mock"
        const val NOTE_ID = 7400
        const val NOTE_ERROR = 7401

        fun createChannel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH_MOCK, "Mock location", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }

        fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 7400,
            Intent(ctx, MainActivity::class.java).putExtra("open", "mock").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Quick Settings tile: tap to stop, or to start the last thing you mocked. */
class MockTile : TileService() {
    override fun onStartListening() = update()

    override fun onClick() { com.yatesv.contacthub.data.safely("MockService") {
        val st = MockStore.state.value
        when {
            st.active -> MockStore.stop(this)
            MockStore.lastUsed() != null && MockSetup.ready(this) -> runCatching { MockStore.start(this, MockStore.lastUsed()!!) }
                .onFailure { HubLog.w("Mock", "tile start failed", it); openApp() }
            else -> openApp()
        }
        update()
    }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).putExtra("open", "mock").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 7402, i, PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(i)
    }

    private fun update() {
        val st = MockStore.state.value
        qsTile?.apply {
            state = if (st.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Mock location"
            subtitle = when {
                st.active && st.paused -> "Paused"
                st.active -> st.item?.name?.take(24) ?: "On"
                else -> MockStore.lastUsed()?.let { "Tap: ${it.name.take(18)}" } ?: "Off"
            }
            updateTile()
        }
    }

    companion object {
        fun refresh(ctx: Context) = runCatching {
            requestListeningState(ctx, android.content.ComponentName(ctx, MockTile::class.java))
        }
    }
}

/** Starts scheduled mocks at their time. */
object MockAlarms {
    private fun pi(ctx: Context, s: MockSchedule) = PendingIntent.getBroadcast(ctx, s.id,
        Intent(ctx, MockAlarmReceiver::class.java).putExtra("id", s.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun arm(ctx: Context, s: MockSchedule) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        runCatching {
            if (MockSetup.canExactAlarm(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.at, pi(ctx, s))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.at, pi(ctx, s))
        }.onFailure { HubLog.w("Mock", "arm schedule", it) }
    }

    fun cancel(ctx: Context, s: MockSchedule) = runCatching { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, s)) }
}

class MockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { com.yatesv.contacthub.data.safely("MockService") {
        MockStore.load()
        val id = intent.getIntExtra("id", -1)
        val s = MockStore.schedules.value.firstOrNull { it.id == id } ?: return
        MockStore.removeSchedule(ctx, id)
        HubLog.d("Mock", "scheduled start ${s.item.type}")
        runCatching { MockStore.start(ctx, s.item) }.onFailure {
            HubLog.w("Mock", "scheduled start blocked", it)
            MockService.createChannel(ctx)
            NotificationManagerCompat.from(ctx).runCatching {
                notify(MockService.NOTE_ERROR, NotificationCompat.Builder(ctx, MockService.CH_MOCK).setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("Scheduled mock location").setContentText("Android blocked the auto-start. Tap to start ${s.item.name}.")
                    .setContentIntent(MockService.openIntent(ctx)).setAutoCancel(true).build())
            }
        }
    }
    }
}
