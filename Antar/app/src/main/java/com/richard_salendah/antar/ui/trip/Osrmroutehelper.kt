package com.richard_salendah.antar.ui.trip

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec

private const val TAG = "OsrmRouteHelper"

object OsrmRouteHelper {

    /**
     * Shared OkHttpClient — reused across all route requests so connections
     * are pooled. OkHttp handles TLS negotiation correctly with router.project-osrm.org
     * where HttpURLConnection fails with "Handshake failed" due to cipher suite
     * incompatibilities in Android's built-in Java TLS stack.
     */
    private val httpClient: OkHttpClient by lazy {
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()

            // FIX: Use "TLS" instead of "TLSv1.3". This works on all Android versions
            // and prevents the NoSuchAlgorithmException.
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                // Re-adding ConnectionSpecs just in case OSRM is being strict about Modern TLS
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Custom SSLContext initialization failed, falling back to default OkHttp", e)
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Fetches a road-following route between two coordinates using the public
     * OSRM demo server (no API key required, uses OpenStreetMap data).
     *
     * Returns a list of [GeoPoint]s suitable for drawing as a Polyline on
     * OSMDroid, or null if OSRM is unavailable / has no road data for the area.
     *
     * Callers should use [fetchRouteWithFallback] instead of this directly —
     * it adds a straight-line fallback for areas with sparse OSM data (e.g. Talaud).
     */
    suspend fun fetchRoute(
        originLat: Double, originLng: Double,
        destLat:   Double, destLng:   Double,
    ): List<GeoPoint>? = withContext(Dispatchers.IO) {
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

            val coords = routes
                .getJSONObject(0)
                .getJSONObject("geometry")
                .getJSONArray("coordinates")

            // OSRM returns [lng, lat] pairs — convert to GeoPoint(lat, lng)
            val points = (0 until coords.length()).map { i ->
                val pt = coords.getJSONArray(i)
                GeoPoint(pt.getDouble(1), pt.getDouble(0))
            }

            Log.d(TAG, "OSRM route fetched — ${points.size} points, " +
                    "~${routes.getJSONObject(0).optDouble("distance", 0.0).toInt()}m")
            points
        }.onFailure { e ->
            Log.e(TAG, "OSRM fetch failed: ${e.message}")
        }.getOrNull()
    }

    /**
     * Fetches road route from OSRM, falls back to a 2-point straight line
     * when OSRM returns null (no road data) or throws (network error).
     *
     * Talaud islands have sparse OSM road data so OSRM may return 0 routes.
     * The straight line is visually imperfect but far better than showing nothing.
     * Both driver and rider apps should use this instead of [fetchRoute] directly.
     */
    suspend fun fetchRouteWithFallback(
        originLat: Double, originLng: Double,
        destLat:   Double, destLng:   Double,
    ): List<GeoPoint> {
        if (originLat == 0.0 && originLng == 0.0) return emptyList()
        if (destLat   == 0.0 && destLng   == 0.0) return emptyList()

        val osrmResult = fetchRoute(originLat, originLng, destLat, destLng)
        if (osrmResult != null) {
            Log.d(TAG, "Using OSRM road route (${osrmResult.size} points)")
            return osrmResult
        }

        // Fallback: straight line between origin and destination.
        Log.w(TAG, "OSRM unavailable — drawing straight-line fallback " +
                "($originLat,$originLng) → ($destLat,$destLng)")
        return listOf(
            GeoPoint(originLat, originLng),
            GeoPoint(destLat,   destLng),
        )
    }

    /**
     * Haversine distance in metres between two lat/lng coordinates.
     * Used by callers to decide whether to re-fetch the route (>50 m threshold).
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
