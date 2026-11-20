package com.yatesv.contacthub.engine

import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.LatLng
import com.yatesv.contacthub.data.sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Map tiles, address search, snap-to-roads and elevation for mock locations.
 * Uses Mapbox when you've pasted a free Mapbox token, otherwise free OpenStreetMap services.
 */
object MockApi {
    data class Place(val name: String, val point: LatLng)

    private val token get() = Hub.settings.value.mapboxToken.trim()
    val hasMapbox get() = token.startsWith("pk.")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 15000
            setRequestProperty("User-Agent", "ContactHub/1.0 (Android; personal use)")
            setRequestProperty("Accept", "application/json")
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IllegalStateException(
            when (code) {
                401, 403 -> "Mapbox didn't accept your token. Check it in ⚙️."
                429 -> "Too many requests. Wait a minute and try again."
                else -> "Server error $code"
            })
        return body
    }

    // ---------------- Map tiles ----------------

    /** "streets", "satellite". Follows dark mode for streets. */
    fun tiles(style: String, dark: Boolean): ITileSource? {
        val t = token
        if (!hasMapbox) return if (style == "satellite") null else TileSourceFactory.MAPNIK
        val id = when {
            style == "satellite" -> "satellite-streets-v12"
            dark -> "dark-v11"
            else -> "streets-v12"
        }
        return object : OnlineTileSourceBase("mapbox-$id", 1, 20, 256, "", arrayOf("https://api.mapbox.com/styles/v1/mapbox/$id/tiles/256/"),
            "© Mapbox © OpenStreetMap") {
            override fun getTileURLString(index: Long): String =
                baseUrl + "${MapTileIndex.getZoom(index)}/${MapTileIndex.getX(index)}/${MapTileIndex.getY(index)}@2x?access_token=$t"
        }
    }

    fun attribution(style: String): String = if (hasMapbox) "© Mapbox © OpenStreetMap" else "© OpenStreetMap contributors"

    // ---------------- Search ----------------

    suspend fun search(query: String, near: LatLng?): List<Place> = withContext(Dispatchers.IO) {
        if (hasMapbox) {
            val prox = near?.let { "&proximity=${it.lng},${it.lat}" }.orEmpty()
            val j = JSONObject(get("https://api.mapbox.com/search/geocode/v6/forward?q=${enc(query)}&limit=8$prox&access_token=$token"))
            val f = j.optJSONArray("features") ?: JSONArray()
            (0 until f.length()).mapNotNull { i ->
                val o = f.getJSONObject(i)
                val c = o.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                val p = o.optJSONObject("properties")
                Place(p?.optString("full_address")?.ifBlank { null } ?: p?.optString("name").orEmpty(), LatLng(c.getDouble(1), c.getDouble(0)))
            }
        } else {
            val a = JSONArray(get("https://nominatim.openstreetmap.org/search?q=${enc(query)}&format=jsonv2&limit=8&countrycodes=us"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Place(o.optString("display_name"), LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
            }
        }
    }

    /** Short name for a spot (street address), or null if it can't be looked up. */
    suspend fun nameOf(p: LatLng): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (hasMapbox) {
                val f = JSONObject(get("https://api.mapbox.com/search/geocode/v6/reverse?longitude=${p.lng}&latitude=${p.lat}&limit=1&access_token=$token"))
                    .optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties")
                f?.optString("name")?.ifBlank { null } ?: f?.optString("full_address")
            } else {
                val o = JSONObject(get("https://nominatim.openstreetmap.org/reverse?lat=${p.lat}&lon=${p.lng}&format=jsonv2&zoom=18"))
                val a = o.optJSONObject("address")
                listOfNotNull(a?.optString("house_number")?.ifBlank { null }, a?.optString("road")?.ifBlank { null }).joinToString(" ").ifBlank { null }
                    ?: o.optString("name").ifBlank { null } ?: o.optString("display_name").substringBefore(',').ifBlank { null }
            }
        }.getOrNull()?.ifBlank { null }
    }

    // ---------------- Snap to roads ----------------

    /** Road-following line through the waypoints. Falls back to straight lines if routing fails. */
    suspend fun snap(waypoints: List<LatLng>, profile: String): Pair<List<LatLng>, String?> = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext waypoints to null
        runCatching {
            val coords = waypoints.take(25).joinToString(";") { "${it.lng},${it.lat}" }
            val json = if (hasMapbox) {
                val p = when (profile) { "walk" -> "walking"; "bike" -> "cycling"; else -> "driving" }
                get("https://api.mapbox.com/directions/v5/mapbox/$p/$coords?geometries=geojson&overview=full&access_token=$token")
            } else get("https://router.project-osrm.org/route/v1/driving/$coords?geometries=geojson&overview=full")
            val line = JSONObject(json).getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
            val pts = (0 until line.length()).map { val c = line.getJSONArray(it); LatLng(c.getDouble(1), c.getDouble(0)) }
            if (pts.size < 2) error("empty route")
            pts to null
        }.getOrElse {
            HubLog.w("Mock", "snap to roads failed", it)
            waypoints to "Couldn't snap to roads (${it.message}). Using straight lines."
        }
    }

    // ---------------- Elevation ----------------

    /** Ground height in meters for each point (Open-Meteo, free, no key). Max 100 points per call. */
    suspend fun elevation(points: List<LatLng>): List<Double>? = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext emptyList()
        runCatching {
            val p = points.sample(100)
            val lat = p.joinToString(",") { "%.5f".format(java.util.Locale.US, it.lat) }
            val lng = p.joinToString(",") { "%.5f".format(java.util.Locale.US, it.lng) }
            val a = JSONObject(get("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lng")).getJSONArray("elevation")
            (0 until a.length()).map { a.getDouble(it) }
        }.onFailure { HubLog.w("Mock", "elevation failed", it) }.getOrNull()
    }

    /** Quick reachability check for the self-test. Returns a short result. */
    fun ping(url: String): String = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "ContactHub/1.0 (Android; personal use)")
        }
        "HTTP ${c.responseCode}"
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }

    fun tileTestUrl(): String =
        if (hasMapbox) "https://api.mapbox.com/styles/v1/mapbox/streets-v12/tiles/256/1/0/0?access_token=$token"
        else "https://tile.openstreetmap.org/1/0/0.png"
}
