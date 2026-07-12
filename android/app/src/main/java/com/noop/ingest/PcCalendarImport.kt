package com.noop.ingest

import com.noop.analytics.PeriodCalendar
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * One-click import for iOS "My Calendar" / Period Calendar `.pc` backups and UTF-8 CSV.
 *
 * Wire format (RE'd 2026-07-10 from a sample export):
 *  - Optional Java-serialization-ish prefix (`AC ED 00 05` …)
 *  - Embedded ZIP local-file members: `1.period`, `1.note`, `cloud.db`, …
 *  - `1.period` is proprietary packed binary — period days appear as repeated
 *    little-endian unix seconds (and sometimes millis / yyyymmdd ints)
 *
 * All decoding happens **in-app**. Never tell the user to run desktop Python.
 * We NEVER invent bleed days: only emit period_start when a day survives
 * conservative filters (valid calendar day in [2018-01-01, today+45d],
 * appears ≥ [MIN_HITS] times in the period blob).
 */
object PcCalendarImport {

    /** Minimum binary repetitions before a day is trusted as a period-start candidate. */
    const val MIN_HITS = 3

    data class Result(
        val events: List<PeriodCalendar.Event>,
        val message: String,
        val format: String,
    )

    fun parse(bytes: ByteArray, fileNameHint: String = ""): Result {
        if (bytes.isEmpty()) {
            return Result(emptyList(), "Empty file — pick a My Calendar .pc backup or a CSV export.", "empty")
        }
        // UTF-8 / UTF-16 CSV path first
        val asText = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
        if (asText != null && looksLikeCsv(asText)) {
            val ev = PeriodCalendar.parseImportCsv(asText)
            return if (ev.isNotEmpty()) {
                Result(ev, "Imported ${ev.size} events from CSV. Review the calendar.", "csv")
            } else {
                Result(
                    emptyList(),
                    "CSV recognized but no date,type rows found. Use columns like date,type,note " +
                        "(period_start, spotting, sex, …).",
                    "csv",
                )
            }
        }

        val isPc = fileNameHint.endsWith(".pc", ignoreCase = true) ||
            (bytes.size >= 4 && bytes[0] == 0xAC.toByte() && bytes[1] == 0xED.toByte()) ||
            indexOf(bytes, byteArrayOf(0x50, 0x4B, 0x03, 0x04)) >= 0

        if (!isPc) {
            val loose = bytes.toString(Charsets.UTF_8)
            val ev = PeriodCalendar.parseImportCsv(loose)
            return if (ev.isNotEmpty()) {
                Result(ev, "Imported ${ev.size} events. Review the calendar.", "text")
            } else {
                Result(
                    emptyList(),
                    "Unsupported file. Tap Import again and choose a My Calendar .pc backup " +
                        "from Downloads, or a UTF-8 CSV (date,type,note).",
                    "unknown",
                )
            }
        }

        // Prefer 1.period member (primary calendar store in this export format)
        val periodBlob = extractZipMember(bytes, "1.period")
            ?: extractZipMember(bytes, "period")
        val mined = if (periodBlob != null) minePeriodStarts(periodBlob) else emptyList()
        if (mined.isNotEmpty()) {
            val events = mined.map {
                PeriodCalendar.Event(
                    day = it,
                    kind = PeriodCalendar.EventKind.PERIOD_START,
                    note = "Imported from .pc",
                    source = "pc_import",
                )
            }
            return Result(
                events,
                "Imported ${events.size} period-start day(s) from My Calendar .pc. " +
                    "Review the calendar — edit or delete any day that looks wrong.",
                "pc_heuristic",
            )
        }

        // Secondary: mine whole file for the same epoch patterns (truncated exports)
        val whole = minePeriodStarts(bytes)
        if (whole.isNotEmpty()) {
            val events = whole.map {
                PeriodCalendar.Event(
                    day = it,
                    kind = PeriodCalendar.EventKind.PERIOD_START,
                    note = "Imported from .pc",
                    source = "pc_import",
                )
            }
            return Result(
                events,
                "Imported ${events.size} period-start day(s) from .pc. Review the calendar.",
                "pc_file_scan",
            )
        }

        return Result(
            emptyList(),
            "This looks like a My Calendar .pc backup, but no period-start days could be " +
                "decoded safely (need repeated date markers). Try another export from My Calendar, " +
                "or import a CSV with date,type,note columns. Bleed days are never invented.",
            "pc_undecoded",
        )
    }

    private fun looksLikeCsv(text: String): Boolean {
        val sample = text.take(400).lowercase()
        if (sample.contains('\u0000')) return false
        if (sample.contains("date") && (sample.contains("type") || sample.contains("period"))) return true
        return text.lineSequence().take(20).any { line ->
            line.contains(',') && Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}""").containsMatchIn(line)
        }
    }

    /**
     * Extract a ZIP member by name. Handles truncated central directories by walking local headers
     * and raw-deflate scanning (My Calendar exports often omit a clean EOCD).
     */
    fun extractZipMember(bytes: ByteArray, name: String): ByteArray? {
        val pk = indexOf(bytes, byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        if (pk < 0) return null
        val slice = bytes.copyOfRange(pk, bytes.size)
        runCatching {
            ZipInputStream(ByteArrayInputStream(slice)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.contains(name, ignoreCase = true)) {
                        return zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        // Manual local-header walk + raw inflate
        var pos = pk
        while (pos >= 0 && pos + 30 < bytes.size) {
            if (bytes[pos] != 0x50.toByte() || bytes[pos + 1] != 0x4B.toByte()) break
            if (bytes[pos + 2] != 0x03.toByte() || bytes[pos + 3] != 0x04.toByte()) break
            val nlen = u16(bytes, pos + 26)
            val elen = u16(bytes, pos + 28)
            val method = u16(bytes, pos + 8)
            if (pos + 30 + nlen > bytes.size) break
            val entryName = bytes.copyOfRange(pos + 30, pos + 30 + nlen).toString(Charsets.UTF_8)
            val dstart = pos + 30 + nlen + elen
            if (entryName.contains(name, ignoreCase = true)) {
                if (method == 0) {
                    val csize = u32(bytes, pos + 18).coerceAtLeast(0)
                    val end = (dstart + csize).coerceAtMost(bytes.size)
                    if (dstart < end) return bytes.copyOfRange(dstart, end)
                }
                if (method == 8) {
                    val inflated = inflateScan(bytes, dstart)
                    if (inflated != null) return inflated
                }
            }
            val next = indexOf(bytes, byteArrayOf(0x50, 0x4B, 0x03, 0x04), dstart + 1)
            if (next < 0) break
            pos = next
        }
        return null
    }

    private fun inflateScan(bytes: ByteArray, dstart: Int): ByteArray? {
        var best: ByteArray? = null
        val max = (bytes.size - dstart).coerceAtMost(8000)
        // Coarser step for speed; refine near best
        var len = 32
        while (len <= max) {
            val out = inflateRaw(bytes, dstart, len)
            if (out != null && (best == null || out.size > best.size)) best = out
            len += if (len < 512) 8 else 32
        }
        return best
    }

    private fun inflateRaw(bytes: ByteArray, dstart: Int, len: Int): ByteArray? {
        return try {
            val inf = Inflater(true) // nowrap raw deflate
            inf.setInput(bytes, dstart, len.coerceAtMost(bytes.size - dstart))
            val buf = ByteArray(64 * 1024)
            val baos = java.io.ByteArrayOutputStream()
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n <= 0) break
                baos.write(buf, 0, n)
                if (inf.needsInput()) break
            }
            inf.end()
            val out = baos.toByteArray()
            if (out.isEmpty()) null else out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Mine period-start ISO days from a proprietary period blob.
     * Pure — unit-tested against real export fixtures.
     */
    fun minePeriodStarts(
        blob: ByteArray,
        today: LocalDate = LocalDate.now(),
        minHits: Int = MIN_HITS,
    ): List<String> {
        val min = LocalDate.of(2018, 1, 1)
        val max = today.plusDays(45)
        val counts = HashMap<String, Int>()

        fun add(day: LocalDate) {
            if (day.isBefore(min) || day.isAfter(max)) return
            val key = day.toString()
            counts[key] = (counts[key] ?: 0) + 1
        }

        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 4 <= blob.size) {
            // yyyymmdd packed int (legacy heuristic)
            val v = bb.getInt(i)
            if (v in 20180101..20351231) {
                val s = v.toString()
                if (s.length == 8) {
                    runCatching {
                        add(
                            LocalDate.of(
                                s.substring(0, 4).toInt(),
                                s.substring(4, 6).toInt(),
                                s.substring(6, 8).toInt(),
                            ),
                        )
                    }
                }
            }
            // unix seconds (primary encoding in a sample My Calendar .pc).
            // MIN_HITS + consecutive-run collapse filters sliding-window noise.
            if (v in 1_500_000_000..1_900_000_000) {
                runCatching {
                    val day = Instant.ofEpochSecond(v.toLong()).atZone(ZoneOffset.UTC).toLocalDate()
                    add(day)
                }
            }
            i++
        }
        // unix millis as u64 LE
        i = 0
        while (i + 8 <= blob.size) {
            val millis = bb.getLong(i)
            if (millis in 1_500_000_000_000L..1_900_000_000_000L) {
                runCatching {
                    val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    add(day)
                }
            }
            i++
        }

        val rawDays = counts.filter { it.value >= minHits }.keys
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .sorted()
        // Collapse consecutive bleed days into a single period_start (first day of each run).
        // My Calendar packs many mid-period timestamps; importing every day as PERIOD_START
        // blows up cycle length. Keep only run starts, and optionally high-hit isolated days.
        val consecutiveStarts = ArrayList<LocalDate>()
        var prev: LocalDate? = null
        for (d in rawDays) {
            if (prev == null || java.time.temporal.ChronoUnit.DAYS.between(prev, d) > 1) {
                consecutiveStarts.add(d)
            }
            prev = d
        }
        // Second pass: cluster near-starts within 10d (spotting / mid-bleed noise that isn't
        // consecutive). Keep the highest-hit day in each cluster; ties keep the earliest.
        val starts = collapseNearStarts(consecutiveStarts, counts, maxGapDays = 10)
        // Soft cycle filter when still very dense
        if (starts.size <= 24) {
            return starts.map { it.toString() }
        }
        val filtered = starts.filter { day ->
            starts.any { other ->
                if (other == day) return@any false
                val gap = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(day, other))
                gap in 18..45
            } || (counts[day.toString()] ?: 0) >= minHits + 6
        }
        return (if (filtered.size >= 3) filtered else starts).map { it.toString() }
    }

    /**
     * Within [maxGapDays], keep one representative start per cluster (highest hit count,
     * else earliest). Pure — used by [minePeriodStarts] and by [PeriodCalendar] cleanup.
     */
    fun collapseNearStarts(
        days: List<LocalDate>,
        hitCounts: Map<String, Int> = emptyMap(),
        maxGapDays: Long = 10,
    ): List<LocalDate> {
        if (days.isEmpty()) return emptyList()
        val sorted = days.distinct().sorted()
        val out = ArrayList<LocalDate>()
        var cluster = arrayListOf(sorted.first())
        fun flush() {
            val best = cluster.maxWithOrNull(
                compareBy<LocalDate> { hitCounts[it.toString()] ?: 0 }
                    .thenByDescending { it }, // prefer later high-hit, then…
            ) ?: return
            // Prefer earliest when hit counts tie (true period start, not mid-bleed).
            val topHits = hitCounts[best.toString()] ?: 0
            val pick = cluster
                .filter { (hitCounts[it.toString()] ?: 0) == topHits }
                .minOrNull() ?: best
            out.add(pick)
        }
        for (i in 1 until sorted.size) {
            val d = sorted[i]
            val gap = java.time.temporal.ChronoUnit.DAYS.between(cluster.last(), d)
            if (gap <= maxGapDays) {
                cluster.add(d)
            } else {
                flush()
                cluster = arrayListOf(d)
            }
        }
        flush()
        return out
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
