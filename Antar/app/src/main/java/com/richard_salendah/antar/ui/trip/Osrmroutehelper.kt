package com.richard_salendah.antar.ui.trip

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "OsrmRouteHelper"

/**
 * Result returned by [OsrmRouteHelper.fetchRoute].
 * Callers are responsible for the straight-line fallback when this is null.
 */
data class RouteResult(
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

object OsrmRouteHelper {

    /**
     * Shared OkHttpClient — reused across all route requests so connections
     * are pooled. Uses an explicit SSLContext built from the system trust store
     * to avoid Firebase SSL context pollution (Firebase 34.x modifies the global
     * SSLContext which breaks OSRM TLS handshakes on Android 9).
     */
    private val httpClient: OkHttpClient by lazy {
        try {
            val tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            ).apply { init(null as KeyStore?) }
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()

            // "TLS" works on all Android versions and avoids NoSuchAlgorithmException
            // that "TLSv1.3" throws on Android 9.
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .connectionSpecs(
                    listOf(
                        ConnectionSpec.MODERN_TLS,
                        ConnectionSpec.COMPATIBLE_TLS,
                        ConnectionSpec.CLEARTEXT,
                    )
                )
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Custom SSLContext init failed, falling back to default OkHttp", e)
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Fetches a road-following route from OSRM and returns a [RouteResult]
     * containing the polyline points, distance (metres), and duration (seconds).
     *
     * Returns null when OSRM is unavailable or has no road data for the area.
     * Callers (the ViewModel) are responsible for drawing a straight-line
     * fallback and recording the Haversine distance in that case.
     */
    suspend fun fetchRoute(
        originLat: Double, originLng: Double,
        destLat:   Double, destLng:   Double,
    ): RouteResult? = withContext(Dispatchers.IO) {
        if (originLat == 0.0 && originLng == 0.0) return@withContext null
        if (destLat   == 0.0 && destLng   == 0.0) return@withContext null

        runCatching {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                    "$originLng,$originLat;$destLng,$destLat" +
                    "?overview=full&geometries=geojson"

            Log.d(TAG, "Fetching OSRM route: ($originLat,$originLng) → ($destLat,$destLng)")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AntarRiderApp/1.0")
                .build()

            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "OSRM returned HTTP ${response.code} — no road data")
                    return@runCatching null
                }
                response.body?.string() ?: return@runCatching null
            }

            val json   = JSONObject(body)
            val routes = json.getJSONArray("routes")

            if (routes.length() == 0) {
                Log.w(TAG, "OSRM returned 0 routes — no road data for this area")
                return@runCatching null
            }

            val route  = routes.getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")

            // OSRM returns [lng, lat] pairs — convert to GeoPoint(lat, lng)
            val points = (0 until coords.length()).map { i ->
                val pt = coords.getJSONArray(i)
                GeoPoint(pt.getDouble(1), pt.getDouble(0))
            }

            RouteResult(
                points          = points,
                distanceMeters  = route.getDouble("distance"),
                durationSeconds = route.getDouble("duration"),
            ).also {
                Log.d(TAG, "OSRM route fetched — ${points.size} points, " +
                        "~${it.distanceMeters.toInt()}m, ${it.durationSeconds.toInt()}s")
            }
        }.onFailure { e ->
            Log.e(TAG, "OSRM fetch failed: ${e.message}")
        }.getOrNull()
    }

    /**
     * Haversine distance in metres between two lat/lng coordinates.
     * Used by the ViewModel to decide whether to re-fetch the route (>50 m threshold)
     * and as the straight-line distance when OSRM is unavailable.
     */
    fun distanceMeters(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double,
    ): Double {
        if (lat2 == 0.0 && lng2 == 0.0) return Double.MAX_VALUE
        val r    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a    = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}