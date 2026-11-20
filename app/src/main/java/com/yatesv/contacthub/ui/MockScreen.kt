package com.yatesv.contacthub.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.LatLng
import com.yatesv.contacthub.data.MockItem
import com.yatesv.contacthub.data.MockMath
import com.yatesv.contacthub.data.RoutePlan
import com.yatesv.contacthub.engine.MockApi
import com.yatesv.contacthub.engine.MockSetup
import com.yatesv.contacthub.engine.MockStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

private val END_MODES = listOf("stay" to "Stay at last point", "stop" to "Stop", "loop" to "Loop", "pingpong" to "Back & forth")
private fun endLabel(m: String) = END_MODES.firstOrNull { it.first == m }?.second ?: m

/** Settings → Developer → Mock location. Works like "Mock Locations" by dvaoru. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MockScreen() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { MockStore.load() }
    val st by MockStore.state.collectAsState()
    val s by Hub.settings.flow.collectAsState()
    val favorites by MockStore.favorites.collectAsState()
    val history by MockStore.history.collectAsState()
    val schedules by MockStore.schedules.collectAsState()

    // Setup state, re-checked every time you come back from Android settings
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    @Suppress("UNUSED_EXPRESSION") tick
    val ready = MockSetup.ready(ctx)
    val hasLoc = MockSetup.hasLocation(ctx)

    // Editor
    var mode by rememberSaveable { mutableStateOf("point") }
    var point by remember { mutableStateOf<LatLng?>(null) }
    var pointName by remember { mutableStateOf("") }
    val waypoints = remember { mutableStateListOf<LatLng>() }
    var path by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var snapping by remember { mutableStateOf(false) }
    var speed by rememberSaveable { mutableFloatStateOf(35f) }
    var profile by rememberSaveable { mutableStateOf("car") }
    var endMode by rememberSaveable { mutableStateOf("stay") }
    var fromReal by rememberSaveable { mutableStateOf(false) }
    var routeName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    // Real location (only readable while not mocking)
    var real by remember { mutableStateOf<LatLng?>(null) }
    LaunchedEffect(st.active) { if (st.active) MockStore.lastReal()?.let { real = it.first } }
    RealLocation(enabled = hasLoc && !st.active) { p -> real = p; MockStore.saveReal(p) }
    val realOrLast = real ?: MockStore.lastReal()?.first

    // Dialogs
    var showSettings by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var scheduleFor by remember { mutableStateOf<MockItem?>(null) }
    var saveFor by remember { mutableStateOf<MockItem?>(null) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    fun pick(p: LatLng, name: String? = null) {
        Haptics.tap(view)
        if (mode == "point") {
            point = p; pointName = name ?: MockMath.fmt(p)
            if (name == null) scope.launch { MockApi.nameOf(p)?.let { n -> if (point == p) pointName = n } }
        } else waypoints += p
    }

    fun currentItem(): MockItem? = if (mode == "point") point?.let { MockItem(name = pointName.ifBlank { MockMath.fmt(it) }, type = "point", points = listOf(it)) }
    else if (path.size >= 2) {
        val wp = if (fromReal && realOrLast != null) listOf(realOrLast) + waypoints else waypoints.toList()
        MockItem(name = "Route to " + routeName.ifBlank { MockMath.fmt(wp.last()) }, type = "route",
            points = wp, path = path, speedMph = speed.toDouble(), endMode = endMode, profile = profile, startFromReal = fromReal)
    } else null

    fun start(item: MockItem) {
        if (!MockSetup.ready(ctx)) { message = "Finish the setup steps at the top first."; Haptics.reject(view); return }
        runCatching { MockStore.start(ctx, item) }
            .onSuccess { Haptics.confirm(view); message = "" }
            .onFailure { message = "Couldn't start: ${it.message}"; Haptics.reject(view) }
    }

    fun load(item: MockItem) {
        mode = item.type
        if (item.isRoute) {
            waypoints.clear(); waypoints.addAll(if (item.startFromReal) item.points.drop(1) else item.points)
            path = item.path; speed = item.speedMph.toFloat(); endMode = item.endMode; profile = item.profile; fromReal = item.startFromReal
            routeName = item.name.removePrefix("Route to ")
        } else { point = item.points.last(); pointName = item.name }
    }

    // Snap the route to roads whenever the waypoints change (short wait so taps don't spam the server)
    val routeKey = waypoints.toList() to (fromReal to profile)
    LaunchedEffect(routeKey, mode) {
        if (mode != "route") return@LaunchedEffect
        val wp = if (fromReal && realOrLast != null) listOf(realOrLast) + waypoints else waypoints.toList()
        if (wp.size < 2) { path = wp; return@LaunchedEffect }
        delay(450)
        snapping = true
        val (line, err) = MockApi.snap(wp, profile)
        path = line; snapping = false
        if (err != null) message = err
        MockApi.nameOf(wp.last())?.let { routeName = it }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Mock location") },
            navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton({ showSettings = true }) { Icon(Icons.Default.Settings, "Mock location settings") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ---------------- Map ----------------
            Box(Modifier.fillMaxWidth().weight(0.46f)) {
                MockMap(
                    style = s.mockMapStyle, mock = st.here.takeIf { st.active }, real = realOrLast, realStale = st.active || real == null,
                    point = point.takeIf { mode == "point" }, waypoints = if (mode == "route") waypoints.toList() else emptyList(),
                    path = if (mode == "route") path else emptyList(), activePath = st.item?.takeIf { st.active && it.isRoute }?.path.orEmpty(),
                    onLongPress = { pick(it) },
                )
                Column(Modifier.align(Alignment.TopEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallFloatingActionButton({
                        if (!MockApi.hasMapbox && s.mockMapStyle == "streets") { message = "Satellite needs a free Mapbox token (⚙️)."; showSettings = true }
                        else Hub.settings.update { it.copy(mockMapStyle = if (it.mockMapStyle == "satellite") "streets" else "satellite") }
                    }, containerColor = MaterialTheme.colorScheme.surface) { Icon(Icons.Default.Layers, "Street / satellite") }
                    SmallFloatingActionButton({
                        when {
                            !hasLoc -> { message = "Allow location so the map can find you."; perms.launch(locationPerms()) }
                            st.active -> {
                                // Android only gives out the fake location while mocking, so use the last real one
                                if (realOrLast != null) { MapCenter.go(realOrLast); message = "Showing your last real location from before mocking started." }
                                else message = "Your real location isn't known yet. Stop mocking, then tap this again."
                            }
                            else -> {
                                realOrLast?.let { MapCenter.go(it) } ?: run { message = "Finding your location…" }
                                freshFix(ctx) { p -> real = p; MockStore.saveReal(p); MapCenter.go(p); if (message == "Finding your location…") message = "" }
                            }
                        }
                    }, containerColor = MaterialTheme.colorScheme.surface) {
                        Icon(Icons.Default.MyLocation, "Go to my real location")
                    }
                    if (st.active) SmallFloatingActionButton({ MapCenter.go(st.here) }, containerColor = MaterialTheme.colorScheme.surface) {
                        Icon(Icons.Default.Place, "Go to mock location", tint = Color(0xFFFF453A))
                    }
                }
                Text(MockApi.attribution(s.mockMapStyle), style = MaterialTheme.typography.labelSmall, color = Color.Black,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp))
                if (st.active && realOrLast != null) Text(
                    "Blue dot: real location ${MockStore.lastReal()?.second?.let { com.yatesv.contacthub.data.Geo.ago(it) } ?: ""} (before mocking)",
                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp))
            }

            // ---------------- Panel ----------------
            Column(Modifier.fillMaxWidth().weight(0.54f).verticalScroll(rememberScrollState())) {
                AnimatedVisibility(!ready, enter = expandVertically(Motion.spec()) + fadeIn(Motion.spec()), exit = shrinkVertically(Motion.spec()) + fadeOut(Motion.spec())) {
                    SetupCard(ctx)
                }
                if (st.error.isNotBlank()) Banner(st.error) { MockStore.set(st.copy(error = "")) }
                if (message.isNotBlank()) Banner(message) { message = "" }
                AnimatedVisibility(st.active, enter = expandVertically(Motion.spec()) + fadeIn(Motion.spec()), exit = shrinkVertically(Motion.spec()) + fadeOut(Motion.spec())) {
                    ActiveCard(ctx)
                }

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    listOf("point" to "Single point", "route" to "Route").forEachIndexed { i, (v, label) ->
                        SegmentedButton(mode == v, { mode = v; Haptics.tap(view) }, SegmentedButtonDefaults.itemShape(i, 2),
                            icon = { Icon(if (v == "point") Icons.Default.Place else Icons.Default.Route, null, Modifier.size(18.dp)) }) { Text(label) }
                    }
                }

                PickBar(near = st.here ?: realOrLast, onPick = { p, n -> pick(p, n) }, onPaste = { showPaste = true }, onContact = { showContacts = true })

                AnimatedContent(mode, label = "mockMode", transitionSpec = { fadeIn(Motion.spec()) togetherWith fadeOut(Motion.spec()) }) { m ->
                    if (m == "point") Column(Modifier.padding(horizontal = 16.dp)) {
                        val p = point
                        if (p == null) Hint("Long-press the map, search an address, paste coordinates, or pick a contact.")
                        else {
                            Text(pointName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(MockMath.fmt(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ currentItem()?.let(::start) }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Mock here") }
                                OutlinedButton({ saveFor = currentItem() }) { Icon(Icons.Default.StarBorder, null) }
                                OutlinedButton({ scheduleFor = currentItem() }) { Icon(Icons.Default.Schedule, null) }
                            }
                        }
                    } else Column(Modifier.padding(horizontal = 16.dp)) {
                        SwitchRowCompact("Start from my real location", if (realOrLast == null) "Allow location in setup first" else null, fromReal) { fromReal = it }
                        if (waypoints.isEmpty()) Hint("Long-press the map (or search) to add stops. The route follows real roads.")
                        waypoints.forEachIndexed { i, w ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                    Text("${i + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(MockMath.fmt(w), Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                                IconButton({ waypoints.removeAt(i) }) { Icon(Icons.Default.Close, "Remove stop") }
                            }
                        }
                        if (waypoints.isNotEmpty()) TextButton({ waypoints.clear(); path = emptyList() }) { Text("Clear stops") }

                        Text("Speed", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MockMath.PRESETS.forEach { (k, mph) ->
                                FilterChip(profile == k && speed.toDouble() == mph, {
                                    speed = mph.toFloat(); profile = if (k == "highway") "car" else k; Haptics.tap(view)
                                }, { Text("${k.replaceFirstChar { it.uppercase() }} · ${mph.toInt()}") })
                            }
                        }
                        SliderRowCompact("${speed.toInt()} mph", speed, 1f..100f) { speed = it.toInt().toFloat() }

                        Text("At the end", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            END_MODES.forEach { (k, label) -> FilterChip(endMode == k, { endMode = k; Haptics.tap(view) }, { Text(label) }) }
                        }

                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (snapping) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Following roads…", style = MaterialTheme.typography.bodySmall) }
                            else if (path.size >= 2) {
                                val plan = remember(path, speed) { RoutePlan(path, speed.toDouble()) }
                                Text("%.1f mi · about %s · slows for turns".format(MockMath.miles(plan.totalMeters), MockMath.duration(plan.totalSeconds)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ currentItem()?.let(::start) }, enabled = !snapping && path.size >= 2) {
                                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start route")
                            }
                            OutlinedButton({ saveFor = currentItem() }, enabled = path.size >= 2) { Icon(Icons.Default.StarBorder, null) }
                            OutlinedButton({ scheduleFor = currentItem() }, enabled = path.size >= 2) { Icon(Icons.Default.Schedule, null) }
                        }
                    }
                }

                if (schedules.isNotEmpty()) {
                    SectionTitle("Scheduled")
                    schedules.forEach { sc ->
                        SettingRow(sc.item.name, "Starts ${fullTime(sc.at)}", Icons.Default.Schedule) {
                            IconButton({ MockStore.removeSchedule(ctx, sc.id) }) { Icon(Icons.Default.Close, "Cancel schedule") }
                        }
                    }
                }
                if (favorites.isNotEmpty()) {
                    SectionTitle("Favorites")
                    favorites.forEach { f -> ItemRow(f, onLoad = { load(f) }, onStart = { start(f) }, onSchedule = { scheduleFor = f }, onDelete = { MockStore.deleteFavorite(f.id) }) }
                }
                if (history.isNotEmpty()) {
                    SectionTitle("Recent")
                    history.take(10).forEach { h -> ItemRow(h, onLoad = { load(h) }, onStart = { start(h) }, onSchedule = { scheduleFor = h }, onSave = { saveFor = h }) }
                    TextButton({ MockStore.clearHistory() }, Modifier.padding(horizontal = 8.dp)) { Text("Clear recent") }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showSettings) MockSettingsSheet(ctx, onPerms = { perms.launch(locationPerms()) }) { showSettings = false }
    if (showPaste) PasteDialog(onDone = { showPaste = false }) { p -> showPaste = false; pick(p) }
    if (showContacts) ContactAddressDialog(onDone = { showContacts = false }) { p, n -> showContacts = false; pick(p, n) }
    saveFor?.let { item ->
        var name by remember(item) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { saveFor = null },
            title = { Text("Save favorite") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton({ MockStore.saveFavorite(item.copy(id = java.util.UUID.randomUUID().toString(), name = name.ifBlank { item.name })); saveFor = null; Haptics.confirm(view) }) { Text("Save") } },
            dismissButton = { TextButton({ saveFor = null }) { Text("Cancel") } },
        )
    }
    scheduleFor?.let { item ->
        ScheduleDialog(item, onDone = { scheduleFor = null }) { at ->
            scheduleFor = null
            MockStore.addSchedule(ctx, item, at)
            message = if (MockSetup.canExactAlarm(ctx)) "Scheduled for ${fullTime(at)}" else
                "Scheduled. Allow \"Alarms & reminders\" in ⚙️ so Android doesn't delay it."
        }
    }
}

private fun locationPerms() = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(vertical = 8.dp))

@Composable
private fun SwitchRowCompact(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked, onChange)
    }
}

@Composable
private fun SliderRowCompact(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Slider(value, onChange, Modifier.weight(1f), valueRange = range)
        Text(label, Modifier.width(72.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------- Setup walkthrough ----------------

@Composable
private fun SetupCard(ctx: Context) {
    val dev = MockSetup.devOptionsOn(ctx)
    val mockApp = MockSetup.isMockApp(ctx)
    HubCard {
        Text("Set up mock location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Android only lets one app fake your location. Two quick steps:", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        SetupStep(1, "Turn on Developer options", "About phone → Software information → tap Build number 7 times, then enter your PIN.", dev, "Open About phone") {
            ctx.startActivity(Intent(AndroidSettings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        SetupStep(2, "Pick Contact Hub as the mock app", "Developer options → scroll to Debugging → Select mock location app → Contact Hub. Then come back here.", mockApp, "Open Developer options", enabled = dev) {
            runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, body: String, done: Boolean, button: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (done) Color(0xFF34C759) else MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!done) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick, Modifier.padding(top = 6.dp), enabled = enabled) { Text(button) }
            }
        }
    }
}

// ---------------- Running card ----------------

@Composable
private fun ActiveCard(ctx: Context) {
    val st by MockStore.state.collectAsState()
    val item = st.item ?: return
    HubCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (st.paused) Color(0xFFFF9F0A) else Color(0xFF34C759)))
            Text(when {
                st.paused -> "Paused"
                item.isRoute && !st.parked -> "Driving · ${item.speedMph.toInt()} mph"
                st.parked -> "Parked at the end"
                else -> "Mocking"
            }, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
        }
        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        st.here?.let { Text(MockMath.fmt(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (item.isRoute && st.totalSec > 0 && item.endMode != "loop" && item.endMode != "pingpong") {
            val f = (st.progressSec / st.totalSec).toFloat().coerceIn(0f, 1f)
            val anim by androidx.compose.animation.core.animateFloatAsState(f, Motion.spec(), label = "routeProgress")
            LinearProgressIndicator({ anim }, Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(4.dp)))
            Text("${MockMath.duration(st.totalSec - st.progressSec)} left · then ${endLabel(item.endMode).lowercase()}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (item.isRoute) Text(endLabel(item.endMode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.paused) FilledTonalButton({ MockStore.resume(ctx) }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Resume") }
            else FilledTonalButton({ MockStore.pause(ctx) }) { Icon(Icons.Default.Pause, null); Spacer(Modifier.width(6.dp)); Text("Pause") }
            OutlinedButton({ MockStore.stop(ctx) }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop") }
        }
    }
}

// ---------------- Search / paste / contact ----------------

@Composable
private fun PickBar(near: LatLng?, onPick: (LatLng, String?) -> Unit, onPaste: () -> Unit, onContact: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MockApi.Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    fun search() {
        val q = query.trim(); if (q.isBlank()) return
        MockMath.parseCoords(q)?.let { onPick(it, null); query = ""; return }
        scope.launch {
            busy = true; err = ""
            runCatching { MockApi.search(q, near) }.onSuccess { results = it; if (it.isEmpty()) err = "No matches" }.onFailure { err = it.message ?: "Search failed" }
            busy = false
        }
    }
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("Search address or place") }, singleLine = true,
            leadingIcon = { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = ""; results = emptyList(); err = "" }) { Icon(Icons.Default.Close, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
            shape = RoundedCornerShape(14.dp))
        IconButton(onPaste) { Icon(Icons.Default.ContentPaste, "Paste coordinates") }
        IconButton(onContact) { Icon(Icons.Default.Person, "A contact's address") }
    }
    if (err.isNotBlank()) Text(err, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    AnimatedVisibility(results.isNotEmpty(), enter = expandVertically(Motion.spec()) + fadeIn(Motion.spec()), exit = shrinkVertically(Motion.spec()) + fadeOut(Motion.spec())) {
        SettingsGroup {
            results.forEach { r ->
                SettingRow(r.name.substringBefore(','), r.name.substringAfter(", ", ""), Icons.Default.Place, onClick = {
                    onPick(r.point, r.name.substringBefore(',')); results = emptyList(); query = ""
                })
            }
        }
    }
}

@Composable
private fun PasteDialog(onDone: () -> Unit, onPick: (LatLng) -> Unit) {
    val ctx = LocalContext.current
    var text by remember {
        val clip = runCatching { ctx.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
        mutableStateOf(if (MockMath.parseCoords(clip) != null) clip else "")
    }
    val parsed = MockMath.parseCoords(text)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Paste coordinates") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text("30.8466, -93.2891 or a Google Maps link") }, minLines = 2)
                Text(parsed?.let { "✓ ${MockMath.fmt(it)}" } ?: "Can't read coordinates yet", style = MaterialTheme.typography.bodySmall,
                    color = if (parsed != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton({ parsed?.let(onPick) }, enabled = parsed != null) { Text("Use") } },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}

@Composable
private fun ContactAddressDialog(onDone: () -> Unit, onPick: (LatLng, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var filter by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { contactAddresses(ctx) } }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("A contact's address") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(filter, { filter = it }, placeholder = { Text("Search contacts") }, singleLine = true)
                if (busy.isNotBlank()) Text(busy, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val l = list
                    when {
                        l == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                        l.isEmpty() -> Text("None of your contacts have an address saved.", Modifier.padding(top = 12.dp))
                        else -> l.filter { filter.isBlank() || it.first.contains(filter, true) || it.second.contains(filter, true) }.forEach { (name, addr) ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                scope.launch {
                                    busy = "Finding $name…"
                                    val hit = runCatching { MockApi.search(addr.replace('\n', ' '), null).firstOrNull() }.getOrNull()
                                    if (hit != null) onPick(hit.point, name) else busy = "Couldn't find that address on the map."
                                }
                            }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Text(addr.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDone) { Text("Close") } },
    )
}

private fun contactAddresses(ctx: Context): List<Pair<String, String>> = runCatching {
    val out = ArrayList<Pair<String, String>>()
    ctx.contentResolver.query(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.DISPLAY_NAME, ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
        null, null, ContactsContract.CommonDataKinds.StructuredPostal.DISPLAY_NAME + " COLLATE NOCASE")?.use { c ->
        while (c.moveToNext()) {
            val n = c.getString(0).orEmpty(); val a = c.getString(1).orEmpty()
            if (a.isNotBlank()) out += n to a
        }
    }
    out
}.getOrDefault(emptyList())

// ---------------- Lists ----------------

@Composable
private fun ItemRow(item: MockItem, onLoad: () -> Unit, onStart: () -> Unit, onSchedule: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val sub = if (item.isRoute) "%.1f mi · %d mph · %s".format(MockMath.miles(MockMath.length(item.path)), item.speedMph.toInt(), endLabel(item.endMode))
    else MockMath.fmt(item.points.last())
    SettingRow(item.name, sub, if (item.isRoute) Icons.Default.Route else Icons.Default.Place, onClick = onLoad) {
        IconButton(onStart) { Icon(Icons.Default.PlayArrow, "Start", tint = MaterialTheme.colorScheme.primary) }
        Box {
            IconButton({ menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Schedule…") }, { menu = false; onSchedule() }, leadingIcon = { Icon(Icons.Default.Schedule, null) })
                onSave?.let { DropdownMenuItem({ Text("Save as favorite") }, { menu = false; it() }, leadingIcon = { Icon(Icons.Default.Star, null) }) }
                onDelete?.let { DropdownMenuItem({ Text("Delete") }, { menu = false; it() }, leadingIcon = { Icon(Icons.Default.Delete, null) }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(item: MockItem, onDone: () -> Unit, onPick: (Long) -> Unit) {
    var step by remember { mutableStateOf(0) }
    val date = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    val now = java.util.Calendar.getInstance()
    val time = rememberTimePickerState(now.get(java.util.Calendar.HOUR_OF_DAY), (now.get(java.util.Calendar.MINUTE) + 5) % 60, false)
    if (step == 0) DatePickerDialog(onDone, confirmButton = { TextButton({ step = 1 }) { Text("Next") } }, dismissButton = { TextButton(onDone) { Text("Cancel") } }) {
        DatePicker(date, title = { Text("Start \"${item.name.take(30)}\" on", Modifier.padding(16.dp)) })
    } else AlertDialog(
        onDismissRequest = onDone,
        title = { Text("At what time?") },
        text = { TimePicker(time) },
        confirmButton = {
            TextButton({
                // DatePicker gives midnight UTC of the picked day
                val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = date.selectedDateMillis ?: System.currentTimeMillis() }
                val at = java.util.Calendar.getInstance().apply {
                    set(utc.get(java.util.Calendar.YEAR), utc.get(java.util.Calendar.MONTH), utc.get(java.util.Calendar.DAY_OF_MONTH), time.hour, time.minute, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (at > System.currentTimeMillis()) onPick(at) else onDone()
            }) { Text("Schedule") }
        },
        dismissButton = { TextButton({ step = 0 }) { Text("Back") } },
    )
}

// ---------------- Settings sheet ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MockSettingsSheet(ctx: Context, onPerms: () -> Unit, onDone: () -> Unit) {
    val s by Hub.settings.flow.collectAsState()
    var token by remember { mutableStateOf(s.mapboxToken) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    @Suppress("UNUSED_EXPRESSION") tick
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    ModalBottomSheet(onDone) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle("Map (Mapbox)")
            Text("Free for personal use. Make a free account at mapbox.com and paste your default public token (starts with pk.). Without one, Contact Hub uses OpenStreetMap (no satellite).",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(token, { token = it.trim() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Mapbox public token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                isError = token.isNotBlank() && !token.startsWith("pk."),
                supportingText = { if (token.isNotBlank() && !token.startsWith("pk.")) Text("Use the public token (pk.…), not a secret one (sk.…)") })
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ Hub.settings.update { it.copy(mapboxToken = token) } }, enabled = token != s.mapboxToken && (token.isBlank() || token.startsWith("pk."))) { Text("Save token") }
                OutlinedButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://account.mapbox.com/access-tokens/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Get a free token") }
            }
            ChoiceRow("Map style", listOf("streets" to "Street (follows dark mode)", "satellite" to "Satellite"), s.mockMapStyle) { v ->
                if (v == "satellite" && !MockApi.hasMapbox) return@ChoiceRow
                Hub.settings.update { it.copy(mockMapStyle = v) }
            }

            SectionTitle("Setup checklist")
            CheckRow("Developer options", MockSetup.devOptionsOn(ctx)) { ctx.startActivity(Intent(AndroidSettings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            CheckRow("Contact Hub is the mock location app", MockSetup.isMockApp(ctx)) {
                runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            CheckRow("Location (blue dot, start from real location)", MockSetup.hasLocation(ctx), onClick = onPerms)
            CheckRow("Notifications (pause / stop controls)", MockSetup.notificationsOn(ctx)) {
                if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            CheckRow("Alarms & reminders (scheduled starts on time)", MockSetup.canExactAlarm(ctx)) {
                if (Build.VERSION.SDK_INT >= 31) runCatching {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            SectionTitle("How it behaves")
            Text("Updates every 5 seconds. Parked spots wobble a few feet like real GPS. Routes speed up from a stop, slow for turns and slow to a stop at the end. " +
                "Altitude comes from real ground height. After a restart, a running mock picks up where it left off. Add the Quick Settings tile from your quick panel's edit (✏️) screen.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CheckRow(title: String, done: Boolean, onClick: () -> Unit) {
    SettingRow(title, null, if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        onClick = onClick) { if (!done) TextButton(onClick) { Text("Fix") } }
}

// ---------------- Real location ----------------

/** Listens to your real location while the screen is open and nothing is being mocked. */
@SuppressLint("MissingPermission")
@Composable
private fun RealLocation(enabled: Boolean, onFix: (LatLng) -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val lm = ctx.getSystemService(LocationManager::class.java)
        fun ok(l: Location) = isReal(l)
        val listener = LocationListener { l -> if (ok(l)) onFix(LatLng(l.latitude, l.longitude)) }
        val provs = realProviders(lm)
        provs.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.filter(::ok).maxByOrNull { it.time }?.let { onFix(LatLng(it.latitude, it.longitude)) }
        provs.forEach { runCatching { lm.requestLocationUpdates(it, 3000L, 2f, listener, Looper.getMainLooper()) } }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }
}

private fun isReal(l: Location) = if (Build.VERSION.SDK_INT >= 31) !l.isMock else @Suppress("DEPRECATION") !l.isFromMockProvider

private fun realProviders(lm: LocationManager) = buildList {
    if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
    add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
}.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

/** Asks Android for one fresh real fix (fastest provider wins). */
@SuppressLint("MissingPermission")
private fun freshFix(ctx: Context, onFix: (LatLng) -> Unit) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    var got = false
    val cb = { l: Location? -> if (l != null && isReal(l) && !got) { got = true; onFix(LatLng(l.latitude, l.longitude)) } }
    for (p in realProviders(lm)) runCatching {
        if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(p, null, ctx.mainExecutor) { cb(it) }
        else @Suppress("DEPRECATION") lm.requestSingleUpdate(p, { l: Location -> cb(l) }, Looper.getMainLooper())
    }
}

// ---------------- Map ----------------

/** Lets buttons outside the map move it. */
private object MapCenter {
    var map: MapView? = null
    fun go(p: LatLng?) {
        val m = map ?: return
        p ?: return
        val zoom = m.zoomLevelDouble.coerceAtLeast(16.0)
        m.post { m.controller.animateTo(GeoPoint(p.lat, p.lng), zoom, 600L) }
    }
}

private fun dot(color: Int, sizeDp: Int, ctx: Context) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    val d = ctx.resources.displayMetrics.density
    setStroke((2.5f * d).toInt(), android.graphics.Color.WHITE)
    setSize((sizeDp * d).toInt(), (sizeDp * d).toInt())
}

private class Ours(val o: Overlay)

@Composable
private fun MockMap(
    style: String, mock: LatLng?, real: LatLng?, realStale: Boolean, point: LatLng?, waypoints: List<LatLng>,
    path: List<LatLng>, activePath: List<LatLng>, onLongPress: (LatLng) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val pressCb = remember { arrayOf(onLongPress) }
    pressCb[0] = onLongPress
    val ours = remember { mutableListOf<Overlay>() }
    var centered by remember { mutableStateOf(false) }
    val mine = remember { arrayOfNulls<MapView>(1) }
    DisposableEffect(Unit) {
        onDispose {
            mine[0]?.onDetach()
            // Only forget the map if it's still this one (a new screen may have made its own already)
            if (MapCenter.map === mine[0]) MapCenter.map = null
        }
    }
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                controller.setZoom(4.0); controller.setCenter(GeoPoint(39.5, -98.35))
                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                    override fun longPressHelper(p: GeoPoint?): Boolean { p?.let { pressCb[0](LatLng(it.latitude, it.longitude)) }; return true }
                }))
                MapCenter.map = this
                mine[0] = this
            }
        },
        update = { map ->
            val ctx = map.context
            (MockApi.tiles(style, dark) ?: MockApi.tiles("streets", dark))?.let { if (map.tileProvider.tileSource.name() != it.name()) map.setTileSource(it) }
            map.overlayManager.tilesOverlay.setColorFilter(if (dark && !MockApi.hasMapbox) TilesOverlay.INVERT_COLORS else null)
            map.overlays.removeAll(ours.toSet()); ours.clear()
            fun line(pts: List<LatLng>, color: Int, width: Float) = Polyline(map).apply {
                setPoints(pts.map { GeoPoint(it.lat, it.lng) })
                outlinePaint.color = color; outlinePaint.strokeWidth = width * ctx.resources.displayMetrics.density
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                isGeodesic = false
            }.also { ours += it }
            fun marker(p: LatLng, icon: android.graphics.drawable.Drawable, title: String) = Marker(map).apply {
                position = GeoPoint(p.lat, p.lng); this.icon = icon; this.title = title
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
            }.also { ours += it }
            if (activePath.size >= 2) line(activePath, android.graphics.Color.argb(110, 52, 199, 89), 6f)
            if (path.size >= 2) line(path, accent, 5f)
            waypoints.forEachIndexed { i, w -> marker(w, dot(if (i == waypoints.lastIndex) accent else android.graphics.Color.DKGRAY, 14, ctx), "Stop ${i + 1}") }
            point?.let { marker(it, dot(accent, 20, ctx), "Picked spot") }
            real?.let { marker(it, dot(if (realStale) android.graphics.Color.argb(170, 10, 132, 255) else android.graphics.Color.rgb(10, 132, 255), 16, ctx), "Real location") }
            mock?.let { marker(it, dot(android.graphics.Color.rgb(255, 69, 58), 22, ctx), "Mock location") }
            map.overlays.addAll(ours)
            if (!centered) (mock ?: point ?: real)?.let { centered = true; map.controller.setZoom(15.5); map.controller.setCenter(GeoPoint(it.lat, it.lng)) }
            map.invalidate()
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Tiny pin shown on the Settings tab while a mock is running. */
@Composable
fun MockIndicatorDot(modifier: Modifier = Modifier) {
    val st by MockStore.state.collectAsState()
    AnimatedVisibility(st.active, modifier, enter = fadeIn(Motion.spec()), exit = fadeOut(Motion.spec())) {
        Surface(shape = CircleShape, color = if (st.paused) Color(0xFFFF9F0A) else MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp)) {
            Icon(Icons.Default.Place, "Mock location on", tint = Color.White, modifier = Modifier.padding(2.dp))
        }
    }
}
