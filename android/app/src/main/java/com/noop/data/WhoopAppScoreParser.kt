package com.noop.data

import java.time.LocalDate
import java.util.Locale
import java.util.regex.Pattern

/**
 * Pure parsers for **official WHOOP app** UI / notification text → Recovery %, Day Strain 0–21.
 * Used by Accessibility scrape + NotificationListener. Never invents from BLE.
 */
object WhoopAppScoreParser {

    data class Parsed(
        val recoveryPct: Double? = null,
        val dayStrain021: Double? = null,
        val sleepPct: Double? = null,
        val rawHits: List<String> = emptyList(),
    )

    /** Join all visible strings with newlines for multi-pattern matching. */
    fun parseScreenText(fullText: String): Parsed {
        if (fullText.isBlank()) return Parsed()
        val t = fullText.replace('\u00a0', ' ')
        val hits = ArrayList<String>()
        var recovery: Double? = null
        var strain: Double? = null
        var sleep: Double? = null

        // Recovery: "Recovery 67%" / "67% Recovery" / "RECOVERY\n67"
        recovery = firstDouble(
            t,
            "(?i)recovery[^0-9%]{0,24}(\\d{1,3}(?:\\.\\d+)?)\\s*%",
            "(?i)(\\d{1,3}(?:\\.\\d+)?)\\s*%[^\\n]{0,16}recovery",
            "(?i)recovery\\s*\\n\\s*(\\d{1,3})",
        )?.also { hits.add("recovery=$it") }?.coerceIn(0.0, 100.0)

        // Day Strain 0–21: "14.7" near "Strain" / "Day Strain" / "14,7" locale
        strain = firstDouble(
            t,
            "(?i)(?:day\\s*)?strain[^0-9]{0,24}(\\d{1,2}(?:[.,]\\d+)?)\\s*(?:/\\s*21)?",
            "(?i)(\\d{1,2}(?:[.,]\\d+)?)\\s*/\\s*21",
            "(?i)(\\d{1,2}(?:[.,]\\d+)?)\\s*strain",
        )?.also { hits.add("strain=$it") }?.let { normalizeStrain(it) }

        sleep = firstDouble(
            t,
            "(?i)sleep(?:\\s*performance)?[^0-9%]{0,24}(\\d{1,3}(?:\\.\\d+)?)\\s*%",
            "(?i)(\\d{1,3}(?:\\.\\d+)?)\\s*%[^\\n]{0,16}sleep",
        )?.also { hits.add("sleep=$it") }?.coerceIn(0.0, 100.0)

        return Parsed(recoveryPct = recovery, dayStrain021 = strain, sleepPct = sleep, rawHits = hits)
    }

    fun parseNotification(title: CharSequence?, text: CharSequence?, bigText: CharSequence?): Parsed {
        val joined = listOfNotNull(title, text, bigText).joinToString("\n")
        return parseScreenText(joined)
    }

    fun toDayScores(parsed: Parsed, day: String = LocalDate.now().toString(), source: String): WhoopAppScoreStore.DayScores? {
        if (parsed.recoveryPct == null && parsed.dayStrain021 == null && parsed.sleepPct == null) return null
        return WhoopAppScoreStore.DayScores(
            day = day,
            recoveryPct = parsed.recoveryPct,
            dayStrain021 = parsed.dayStrain021,
            sleepPct = parsed.sleepPct,
            source = source,
        )
    }

    private fun normalizeStrain(v: Double): Double {
        // If OCR saw 67 meaning percent of 21, leave only if already ≤21.
        return if (v <= 21.0 + 1e-6) v.coerceIn(0.0, 21.0)
        else if (v <= 100.0) (v / 100.0 * 21.0).coerceIn(0.0, 21.0)
        else 21.0
    }

    private fun firstDouble(text: String, vararg patterns: String): Double? {
        for (p in patterns) {
            val m = Pattern.compile(p).matcher(text)
            if (m.find()) {
                val g = m.group(1)?.replace(',', '.') ?: continue
                return g.toDoubleOrNull()
            }
        }
        return null
    }
}
