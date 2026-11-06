package com.navigator.app.weather

import android.content.Context
import android.location.LocationManager
import com.navigator.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Current-conditions weather for the idle dash cards, via the free Open-Meteo
 * API (no key needed). Results are cached for [CACHE_TTL_MS] so the idle
 * rotation never hammers the network; position comes from the best last-known
 * location fix (no active GPS request - the ride features own that).
 */
object WeatherProvider {

    data class WeatherInfo(
        val tempC: Int,
        val feelsC: Int,
        val condition: String,
        val windKmh: Int,
    )

    private const val CACHE_TTL_MS = 15 * 60_000L

    @Volatile private var cache: WeatherInfo? = null
    @Volatile private var cacheAtMs: Long = 0L
    private val fetchLock = Mutex()

    /** Last successful fetch if still fresh, else null (call [refresh] to update). */
    fun cached(): WeatherInfo? =
        cache?.takeIf { System.currentTimeMillis() - cacheAtMs < CACHE_TTL_MS }

    /** Fetch (or return fresh cache). Best-effort: null on no location / no network. */
    suspend fun refresh(context: Context): WeatherInfo? = fetchLock.withLock {
        cached()?.let { return it }
        val loc = lastKnownLocation(context) ?: run {
            AppLogger.log("Weather", "No last-known location - skipping fetch")
            return null
        }
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=%.4f&longitude=%.4f".format(java.util.Locale.US, loc.latitude, loc.longitude) +
                        "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val current = JSONObject(body).getJSONObject("current")
                val info = WeatherInfo(
                    tempC = current.getDouble("temperature_2m").toInt(),
                    feelsC = current.getDouble("apparent_temperature").toInt(),
                    condition = conditionText(current.getInt("weather_code")),
                    windKmh = current.getDouble("wind_speed_10m").toInt(),
                )
                cache = info
                cacheAtMs = System.currentTimeMillis()
                AppLogger.log("Weather", "Fetched: ${info.condition} ${info.tempC}C wind ${info.windKmh}")
                info
            } catch (e: Exception) {
                AppLogger.log("Weather", "!! Fetch failed: ${e.message}")
                null
            }
        }
    }

    private fun lastKnownLocation(context: Context): android.location.Location? = try {
        val lm = context.getSystemService(LocationManager::class.java)
        lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }

    /** WMO weather code -> short ASCII text that fits the dash's 32-char road line. */
    private fun conditionText(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm hail"
        else -> "Weather"
    }
}
