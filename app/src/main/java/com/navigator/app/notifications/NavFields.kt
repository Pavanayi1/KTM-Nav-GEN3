package com.navigator.app.notifications

/**
 * Splits a navigation notification into the dash's two guidance slots: the
 * short countdown (TURN_DISTANCE, 8 chars) and the road line (TURN_ROAD).
 *
 * Google Maps has used two layouts:
 *
 * - **Classic:** title = "400 m", text = "towards 1st Main Rd". The title is the
 *   distance and the text is the road line, as-is.
 * - **Newer** (GitHub issue #8): title = "400 m · Turn left onto 1st Main Rd",
 *   text = null, with the bare distance repeated in `android.shortCriticalText`.
 *   Taking the title as the distance put the whole instruction into the 8-char
 *   slot ("400 m · …") and left the road line empty.
 *
 * Pure string logic, no Android types, so it is unit-tested on the JVM.
 */
object NavFields {

    /** The extras key behind Notification.Builder.setShortCriticalText (API 36). */
    const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

    data class Split(val distance: String?, val road: String?)

    private val DISTANCE = Regex("""^\d+(?:[.,]\d+)?\s*(?:km|mi|ft|yd|m)$""", RegexOption.IGNORE_CASE)

    /**
     * Separators Maps puts between distance and instruction. Hyphens and dashes
     * are deliberately absent: they occur inside road names ("NH-44").
     */
    private val SEPARATOR = Regex("""\s*[·•|]\s*""")

    fun looksLikeDistance(s: String?): Boolean = s != null && DISTANCE.matches(s.trim())

    fun split(title: String?, text: String?, shortCriticalText: String?): Split {
        val t = title?.trim().orEmpty()
        val x = text?.trim().orEmpty()
        val critical = shortCriticalText?.trim()?.takeIf { it.isNotEmpty() }

        // Classic layout, or anything with a real text line: unchanged behaviour.
        if (x.isNotEmpty()) return Split(t.ifEmpty { critical }, x)
        if (t.isEmpty()) return Split(critical, null)

        // Newer layout: "<distance> · <instruction>" in the title alone.
        val parts = t.split(SEPARATOR, limit = 2)
        if (parts.size == 2 && looksLikeDistance(parts[0])) {
            return Split(parts[0].trim(), parts[1].trim().ifEmpty { null })
        }
        // The distance only in shortCriticalText, the title leading with it.
        if (critical != null && t.startsWith(critical)) {
            val rest = t.removePrefix(critical).trimStart(' ', '·', '•', '|').trim()
            return Split(critical, rest.ifEmpty { null })
        }
        // A bare distance title with no instruction.
        if (looksLikeDistance(t)) return Split(t, null)
        // An instruction with no distance in it ("Head north on MG Rd").
        return Split(critical, t)
    }
}
