package com.navigator.app.gemini

import com.navigator.app.logging.AppLogger
import com.navigator.app.notifications.NotificationEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Summarizes the current notification queue into single-line entries via
 * the Gemini API. Called on-demand when the Notification screen opens, not
 * continuously, to keep API usage and battery impact low.
 */
class GeminiSummarizer(
    private val apiKey: String,
    private val model: String = com.navigator.app.settings.AppSettings.DEFAULT_GEMINI_MODEL,
) {

    /** Returns one summary line per input entry, same order, best-effort (falls back to raw text on any failure). */
    suspend fun summarize(entries: List<NotificationEntry>): List<String> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext emptyList()
        try {
            val prompt = buildString {
                append("Summarize each of these phone notifications as one short line (under 60 characters), ")
                append("same order, one summary per line, no numbering, no extra commentary:\n\n")
                entries.forEachIndexed { i, e -> append("${i + 1}. [${e.appLabel}] ${e.title}: ${e.text}\n") }
            }
            val responseText = callGemini(prompt)
            val lines = responseText.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.size == entries.size) {
                AppLogger.log("Gemini", "Summarized ${entries.size} entries")
                lines
            } else {
                AppLogger.log("Gemini", "Line count mismatch (${lines.size} vs ${entries.size}), using fallback")
                entries.map { fallbackSummary(it) }
            }
        } catch (e: Exception) {
            AppLogger.log("Gemini", "!! Summarize failed: ${e.message}, using fallback")
            entries.map { fallbackSummary(it) }
        }
    }

    /**
     * Ask Google which models this key can actually use, newest-looking first.
     * This is the durable answer to "Gemini retired the model my app hardcodes":
     * verified against the live API on 2026-09-11, every 1.5 and 2.0 model the
     * app used to list is gone, and generateContent answers 404 with
     * "This model is no longer available" for them. Filtered to text
     * generateContent models - the same endpoint also lists TTS, image, audio
     * and robotics models that would just fail here.
     */
    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=200")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw RuntimeException("Gemini API error $code: $body")
            val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
            val out = mutableListOf<String>()
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                var generates = false
                for (j in 0 until methods.length()) {
                    if (methods.getString(j) == "generateContent") { generates = true; break }
                }
                if (!generates) continue
                val name = m.getString("name").removePrefix("models/")
                if (NON_TEXT_MODEL_MARKERS.any { name.contains(it) }) continue
                out += name
            }
            AppLogger.log("Gemini", "Fetched ${out.size} usable models from the API")
            out
        }.onFailure { AppLogger.log("Gemini", "!! Model list fetch failed: ${it.message}") }
    }

    /**
     * One tiny round-trip to prove the key AND the model name actually work.
     * Unlike [summarize], this does NOT swallow failures into a fallback - the
     * whole point is to surface the API's own error, which is what tells the
     * rider which half is wrong: a bad key comes back 400/403, a model name
     * that doesn't exist (or was retired) comes back 404.
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val reply = callGemini("Reply with exactly: OK").trim()
            AppLogger.log("Gemini", "Test call to \"$model\" succeeded: \"$reply\"")
            reply
        }.onFailure { AppLogger.log("Gemini", "!! Test call to \"$model\" failed: ${it.message}") }
    }

    private fun fallbackSummary(entry: NotificationEntry): String {
        val combined = if (entry.title.isNotBlank()) "${entry.title}: ${entry.text}" else entry.text
        return if (combined.length > 60) combined.take(57) + "..." else combined
    }

    private companion object {
        /** Substrings that mark a listed model as not a plain text generator. */
        val NON_TEXT_MODEL_MARKERS = listOf(
            "tts", "image", "banana", "lyria", "robotics", "transcribe",
            "computer-use", "embedding", "veo", "imagen",
        )
    }

    private fun callGemini(prompt: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        // Bound the wait so a slow/hung network can't keep the summarize
        // coroutine (and the loading spinner) alive indefinitely.
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000

        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream.bufferedReader().use { it.readText() }
        if (responseCode !in 200..299) throw RuntimeException("Gemini API error $responseCode: $responseBody")

        val json = JSONObject(responseBody)
        return json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
    }
}
