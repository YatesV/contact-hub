package com.yatesv.contacthub.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain math and data for mock locations. No Android calls here, so it's unit tested. */
data class LatLng(val lat: Double, val lng: Double) {
    fun toJson(): JSONArray = JSONArray().put(lat).put(lng)
    companion object {
        fun fromJson(a: JSONArray) = LatLng(a.getDouble(0), a.getDouble(1))
    }
}

/** One saved thing: a point, or a route with its speed and end behavior. */
data class MockItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    /** "point" or "route" */
    val type: String,
    /** The point, or the waypoints you tapped */
    val points: List<LatLng>,
    /** Road-snapped line to drive along (routes only) */
    val path: List<LatLng> = emptyList(),
    val speedMph: Double = MockMath.PRESETS.getValue("car"),
    /** stay, stop, loop, pingpong */
    val endMode: String = "stay",
    /** walk, bike, car, custom (picks the road profile) */
    val profile: String = "car",
    val startFromReal: Boolean = false,
    val time: Long = System.currentTimeMillis(),
) {
    val isRoute get() = type == "route"
    val target get() = (if (isRoute) path.lastOrNull() else null) ?: points.last()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("type", type)
        put("points", JSONArray().apply { points.forEach { put(it.toJson()) } })
        put("path", JSONArray().apply { path.forEach { put(it.toJson()) } })
        put("speedMph", speedMph); put("endMode", endMode); put("profile", profile)
        put("startFromReal", startFromReal); put("time", time)
    }

    companion object {
        fun fromJson(j: JSONObject): MockItem {
            fun pts(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { LatLng.fromJson(a.getJSONArray(it)) } }.orEmpty()
            return MockItem(
                id = j.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                name = j.optString("name"), type = j.optString("type", "point"),
                points = pts("points"), path = pts("path"),
                speedMph = j.optDouble("speedMph", MockMath.PRESETS.getValue("car")),
                endMode = j.optString("endMode", "stay"), profile = j.optString("profile", "car"),
                startFromReal = j.optBoolean("startFromReal", false), time = j.optLong("time", 0),
            )
        }

        fun listFromJson(s: String): List<MockItem> = runCatching {
            val a = JSONArray(s); (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun listToJson(l: List<MockItem>): String = JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()
    }
}

/** One position to hand to Android. */
data class MockFix(val lat: Double, val lng: Double, val bearing: Float, val speedMps: Float, val done: Boolean = false)

object MockMath {
    const val EARTH_M = 6_371_000.0
    const val MPS_PER_MPH = 0.44704

    val PRESETS = linkedMapOf("walk" to 3.0, "bike" to 12.0, "car" to 35.0, "highway" to 65.0)

    fun meters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_M * Math.asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Compass bearing from a to b, 0..360. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun lerp(a: LatLng, b: LatLng, f: Double) = LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)

    /** Moves a point by north/east meters. */
    fun offset(p: LatLng, northM: Double, eastM: Double): LatLng {
        val dLat = northM / EARTH_M
        val dLng = eastM / (EARTH_M * cos(Math.toRadians(p.lat)))
        return LatLng(p.lat + Math.toDegrees(dLat), p.lng + Math.toDegrees(dLng))
    }

    fun length(path: List<LatLng>): Double = path.zipWithNext().sumOf { (a, b) -> meters(a, b) }

    /** How much the direction changes at each point, 0..180 degrees. */
    fun turnAngle(prev: LatLng, at: LatLng, next: LatLng): Double {
        if (meters(prev, at) < 0.5 || meters(at, next) < 0.5) return 0.0
        val d = abs(bearing(prev, at) - bearing(at, next)) % 360
        return if (d > 180) 360 - d else d
    }

    /** Slowest speed allowed through a turn, as a share of cruising speed. */
    fun turnFactor(angle: Double): Double = when {
        angle >= 100 -> 0.2
        angle >= 60 -> 0.35
        angle >= 30 -> 0.6
        angle >= 15 -> 0.85
        else -> 1.0
    }

    /**
     * Reads coordinates from what you paste: "30.84, -93.29", "30.84 -93.29",
     * a Google Maps link with "@30.84,-93.29" or "?q=30.84,-93.29", or "geo:30.84,-93.29".
     */
    fun parseCoords(text: String): LatLng? {
        val t = text.trim()
        val patterns = listOf(
            Regex("@(-?\\d{1,2}(?:\\.\\d+)?),\\s*(-?\\d{1,3}(?:\\.\\d+)?)"),
            Regex("[?&](?:q|query|ll|center|destination)=(-?\\d{1,2}(?:\\.\\d+)?)(?:,|%2C)\\s*(-?\\d{1,3}(?:\\.\\d+)?)"),
            Regex("geo:(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)"),
            Regex("^\\(?\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[, ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*\\)?$"),
        )
        for (p in patterns) {
            val m = p.find(t) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0) return LatLng(lat, lng)
        }
        return null
    }

    fun fmt(p: LatLng) = "%.6f, %.6f".format(java.util.Locale.US, p.lat, p.lng)

    fun miles(m: Double) = m / 1609.344

    fun duration(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} hr ${(s % 3600) / 60} min"
        }
    }
}

/**
 * A route turned into a timeline. Starts from a stop, slows for turns (sharper = slower)
 * and slows to a stop at the end, using a gentle acceleration so it looks like real driving.
 */
class RoutePlan(path: List<LatLng>, speedMph: Double, private val accel: Double = 1.6, stepM: Double = 8.0) {
    val points: List<LatLng>
    /** Seconds from the start to reach each point. */
    val times: DoubleArray
    val speeds: DoubleArray
    val totalMeters: Double
    val totalSeconds: Double get() = times.lastOrNull() ?: 0.0

    init {
        val cruise = (speedMph * MockMath.MPS_PER_MPH).coerceAtLeast(0.3)
        // Remember how sharp each original corner is, then cut the line into short steps
        val clean = path.fold(ArrayList<LatLng>()) { acc, p -> if (acc.isEmpty() || MockMath.meters(acc.last(), p) > 0.3) acc.add(p); acc }
        val pts = ArrayList<LatLng>()
        val limit = ArrayList<Double>()
        for (i in clean.indices) {
            val corner = if (i in 1 until clean.lastIndex) MockMath.turnFactor(MockMath.turnAngle(clean[i - 1], clean[i], clean[i + 1])) else 1.0
            pts += clean[i]; limit += cruise * corner
            if (i < clean.lastIndex) {
                val d = MockMath.meters(clean[i], clean[i + 1])
                val n = ceil(d / stepM).toInt().coerceAtLeast(1)
                for (k in 1 until n) { pts += MockMath.lerp(clean[i], clean[i + 1], k.toDouble() / n); limit += cruise }
            }
        }
        if (pts.size == 1) { pts += pts[0]; limit += 0.0 }
        val n = pts.size
        val dist = DoubleArray(n)
        for (i in 1 until n) dist[i] = MockMath.meters(pts[i - 1], pts[i])
        val v = DoubleArray(n) { limit[it] }
        v[0] = 0.0; v[n - 1] = 0.0
        // Can't speed up or slow down faster than [accel]
        for (i in 1 until n) v[i] = min(v[i], sqrt(v[i - 1] * v[i - 1] + 2 * accel * dist[i]))
        for (i in n - 2 downTo 0) v[i] = min(v[i], sqrt(v[i + 1] * v[i + 1] + 2 * accel * dist[i + 1]))
        val t = DoubleArray(n)
        for (i in 1 until n) {
            val avg = (v[i - 1] + v[i]) / 2
            t[i] = t[i - 1] + if (dist[i] == 0.0) 0.0 else dist[i] / max(avg, 0.25)
        }
        points = pts; times = t; speeds = v; totalMeters = dist.sum()
    }

    /** Where you are [sec] seconds into the route (one way). */
    fun at(sec: Double): MockFix {
        val n = points.size
        if (sec >= totalSeconds) {
            val b = if (n >= 2) MockMath.bearing(points[n - 2], points[n - 1]) else 0.0
            return MockFix(points.last().lat, points.last().lng, b.toFloat(), 0f, done = true)
        }
        if (sec <= 0) {
            val b = if (n >= 2) MockMath.bearing(points[0], points[1]) else 0.0
            return MockFix(points[0].lat, points[0].lng, b.toFloat(), 0f)
        }
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (times[mid] <= sec) lo = mid else hi = mid }
        val span = times[hi] - times[lo]
        val f = if (span <= 0) 0.0 else (sec - times[lo]) / span
        val p = MockMath.lerp(points[lo], points[hi], f)
        val speed = speeds[lo] + (speeds[hi] - speeds[lo]) * f
        return MockFix(p.lat, p.lng, MockMath.bearing(points[lo], points[hi]).toFloat(), speed.toFloat())
    }

    /**
     * Position for any time, following the end behavior:
     * stay/stop = end of the route (done = true), loop = start over, pingpong = drive back and forth.
     */
    fun play(sec: Double, endMode: String): MockFix {
        val total = totalSeconds
        if (total <= 0) return at(0.0).copy(done = true)
        return when (endMode) {
            "loop" -> at(sec % total).copy(done = false)
            "pingpong" -> {
                val lap = (sec / total).toLong()
                val inLap = sec - lap * total
                if (lap % 2 == 0L) at(inLap).copy(done = false)
                else at(total - inLap).let { f -> f.copy(bearing = (f.bearing + 180f) % 360f, done = false) }
            }
            else -> at(sec)
        }
    }
}

/** Keeps a parked location moving a few feet, like a real phone's GPS. */
class Drift(seed: Long = System.nanoTime(), private val maxM: Double = 3.5) {
    private val rnd = java.util.Random(seed)
    private var north = 0.0
    private var east = 0.0

    /** Next wobbled point around [p] and an accuracy in meters. */
    fun next(p: LatLng): Pair<LatLng, Float> {
        north = (north * 0.7 + rnd.nextGaussian() * 1.2).coerceIn(-maxM, maxM)
        east = (east * 0.7 + rnd.nextGaussian() * 1.2).coerceIn(-maxM, maxM)
        val acc = (3.0 + abs(rnd.nextGaussian()) * 2.0).coerceAtMost(8.0)
        return MockMath.offset(p, north, east) to acc.toFloat()
    }

    companion object {
        /** Random-looking heading for a parked phone, so it doesn't always point north. */
        fun heading(seed: Long) = ((seed % 360 + 360) % 360).toFloat()
    }
}

/** Picks a few evenly spaced points (APIs limit how many you can ask about at once). */
fun <T> List<T>.sample(max: Int): List<T> {
    if (size <= max || max < 2) return this
    return (0 until max).map { this[(it.toDouble() * (size - 1) / (max - 1)).toInt()] }
}
