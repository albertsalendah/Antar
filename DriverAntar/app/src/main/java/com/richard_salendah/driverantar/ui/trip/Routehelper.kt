package com.richard_salendah.driverantar.ui.trip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

object RouteHelper {

    suspend fun fetchRoute(
        originLat: Double, originLng: Double,
        destLat: Double,   destLng: Double,
    ): List<GeoPoint>? = withContext(Dispatchers.IO) {
        if (originLat == 0.0 && originLng == 0.0) return@withContext null
        if (destLat   == 0.0 && destLng   == 0.0) return@withContext null
        runCatching {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                    "$originLng,$originLat;$destLng,$destLat" +
                    "?overview=full&geometries=geojson"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "AntarDriverApp/1.0")
            conn.connectTimeout = 6_000
            conn.readTimeout    = 6_000
            val json   = JSONObject(conn.inputStream.bufferedReader().readText())
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) return@runCatching null
            val coords = routes.getJSONObject(0)
                .getJSONObject("geometry")
                .getJSONArray("coordinates")
            // OSRM returns [lng, lat] pairs — convert to GeoPoint(lat, lng)
            (0 until coords.length()).map { i ->
                val pt = coords.getJSONArray(i)
                GeoPoint(pt.getDouble(1), pt.getDouble(0))
            }
        }.getOrNull()
    }

    /** Haversine distance in metres — used to avoid re-fetching when driver barely moved */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        if (lat2 == 0.0 && lng2 == 0.0) return Double.MAX_VALUE
        val r    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a    = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}