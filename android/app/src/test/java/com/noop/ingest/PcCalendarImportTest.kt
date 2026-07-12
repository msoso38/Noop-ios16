package com.noop.ingest

import com.noop.analytics.PeriodCalendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue as assertTrueJunit
import org.junit.Test
import java.time.LocalDate

/**
 * Drives the real [PcCalendarImport.parse] / [PcCalendarImport.minePeriodStarts] entry points
 * with fixtures (synthetic epoch blob + optional real .pc on classpath).
 * Never invents bleed days; asserts no Python desktop instructions in user messages.
 */
class PcCalendarImportTest {

    @Test
    fun minePeriodStarts_fromUnixSeconds_requiresRepeatedHits() {
        // Build blob: three days, each repeated 4× as LE unix seconds
        val days = listOf("2023-03-02", "2023-04-01", "2023-04-29")
        val blob = java.io.ByteArrayOutputStream()
        for (d in days) {
            val ts = LocalDate.parse(d).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toInt()
            repeat(4) {
                blob.write(
                    byteArrayOf(
                        (ts and 0xFF).toByte(),
                        ((ts shr 8) and 0xFF).toByte(),
                        ((ts shr 16) and 0xFF).toByte(),
                        ((ts shr 24) and 0xFF).toByte(),
                        0, 0,
                    ),
                )
            }
        }
        val mined = PcCalendarImport.minePeriodStarts(
            blob.toByteArray(),
            today = LocalDate.of(2026, 7, 10),
            minHits = 3,
        )
        // Must recover every planted period-start (extra noise days are filtered separately).
        assertTrue(mined.containsAll(days))
        assertTrue(mined.size <= days.size + 2)
    }

    @Test
    fun minePeriodStarts_singleHitRejected() {
        val ts = LocalDate.of(2023, 5, 1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toInt()
        val blob = byteArrayOf(
            (ts and 0xFF).toByte(),
            ((ts shr 8) and 0xFF).toByte(),
            ((ts shr 16) and 0xFF).toByte(),
            ((ts shr 24) and 0xFF).toByte(),
        )
        val mined = PcCalendarImport.minePeriodStarts(blob, today = LocalDate.of(2026, 7, 10), minHits = 3)
        assertTrue(mined.isEmpty())
    }

    @Test
    fun minePeriodStarts_collapsesConsecutiveBleedDays() {
        // One period: Mar 1–4 each hit 4× → only Mar 1 should remain as start
        val days = listOf("2023-03-01", "2023-03-02", "2023-03-03", "2023-03-04")
        val blob = java.io.ByteArrayOutputStream()
        for (d in days) {
            val ts = LocalDate.parse(d).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toInt()
            repeat(4) {
                blob.write(
                    byteArrayOf(
                        (ts and 0xFF).toByte(),
                        ((ts shr 8) and 0xFF).toByte(),
                        ((ts shr 16) and 0xFF).toByte(),
                        ((ts shr 24) and 0xFF).toByte(),
                    ),
                )
            }
        }
        val mined = PcCalendarImport.minePeriodStarts(
            blob.toByteArray(),
            today = LocalDate.of(2026, 7, 10),
            minHits = 3,
        )
        assertEquals(listOf("2023-03-01"), mined)
    }

    @Test
    fun parse_csv_importsPeriodStarts() {
        val csv = """
            date,type,note
            2024-03-01,period_start,fixture
            2024-03-28,period_start,fixture
            2024-04-25,spotting,spot
        """.trimIndent()
        val result = PcCalendarImport.parse(csv.toByteArray(Charsets.UTF_8), "periods.csv")
        assertTrue(result.events.size >= 3)
        assertTrue(result.events.any { it.kind == PeriodCalendar.EventKind.PERIOD_START })
        assertTrue(result.message.contains("Imported", ignoreCase = true))
        assertFalse(result.message.contains("python", ignoreCase = true))
    }

    @Test
    fun parse_messagesNeverTellUserToRunPython() {
        val r1 = PcCalendarImport.parse(ByteArray(0), "x.pc")
        val r2 = PcCalendarImport.parse("not a backup".toByteArray(), "junk.bin")
        // garbage AC ED header without usable dates
        val r3 = PcCalendarImport.parse(byteArrayOf(0xAC.toByte(), 0xED.toByte(), 0, 5, 1, 2, 3), "empty.pc")
        for (r in listOf(r1, r2, r3)) {
            assertFalse(
                "User message must not instruct desktop Python: ${r.message}",
                r.message.contains("python", ignoreCase = true) ||
                    r.message.contains("extract_pc_calendar", ignoreCase = true),
            )
        }
    }

    @Test
    fun minePeriodStarts_collapsesNearNonConsecutiveBleedNoise() {
        // Mid-bleed days with gaps of 2–4d (not consecutive) used to each become period_start.
        // Use yyyymmdd packed ints (not unix seconds) so LE byte-overlap cannot invent ghosts.
        val days = listOf(
            "2023-04-07", "2023-04-10", "2023-04-12", "2023-04-15", // one period cluster
            "2023-05-27",
        )
        val blob = java.io.ByteArrayOutputStream()
        for (d in days) {
            val ymd = d.replace("-", "").toInt()
            repeat(4) {
                blob.write(
                    byteArrayOf(
                        (ymd and 0xFF).toByte(),
                        ((ymd shr 8) and 0xFF).toByte(),
                        ((ymd shr 16) and 0xFF).toByte(),
                        ((ymd shr 24) and 0xFF).toByte(),
                        0xFF.toByte(), 0xFF.toByte(), // separator
                    ),
                )
            }
        }
        val mined = PcCalendarImport.minePeriodStarts(
            blob.toByteArray(),
            today = LocalDate.of(2026, 7, 10),
            minHits = 3,
        )
        assertEquals(listOf("2023-04-07", "2023-05-27"), mined)
    }

    @Test
    fun parse_realPcFixture_whenPresent_importsOrHonestEmpty() {
        val stream = javaClass.classLoader?.getResourceAsStream("my_calendar_sample.pc")
            ?: return // fixture optional on CI without binary
        val bytes = stream.readBytes()
        val result = PcCalendarImport.parse(bytes, "My Calendar2026-07-10_iphone.pc")
        assertFalse(result.message.contains("python", ignoreCase = true))
        assertFalse(result.message.contains("extract_pc_calendar", ignoreCase = true))
        if (result.events.isNotEmpty()) {
            assertTrue(result.events.all { it.kind == PeriodCalendar.EventKind.PERIOD_START })
            assertTrue(result.events.size >= 3)
            assertTrue(result.format.startsWith("pc"))
            val today = LocalDate.of(2026, 7, 10)
            for (e in result.events) {
                val d = LocalDate.parse(e.day)
                assertTrue(d.year >= 2018)
                assertTrue(!d.isAfter(today.plusDays(45)))
            }
            val snap = PeriodCalendar.evaluate(
                today = today,
                events = result.events,
                prefs = PeriodCalendar.Prefs(enabled = true, whoopLearningEnabled = false),
                engine = null,
            )
            assertTrue("expected nextPeriodLikely from .pc starts", snap.nextPeriodLikely != null)
            assertTrue("expected ≥2 logged starts", snap.loggedStartCount >= 2)
            val grid = PeriodCalendar.monthGrid(
                yearMonth = java.time.YearMonth.of(2026, 7),
                today = today,
                events = result.events,
                snap = snap,
            )
            assertTrue(
                "month grid should mark predicted window or period cells",
                grid.any { it.isPredictedWindow || it.isPredictedPeriod || it.hasPeriod },
            )
        } else {
            assertTrue(
                result.message.contains("CSV", ignoreCase = true) ||
                    result.message.contains("export", ignoreCase = true) ||
                    result.message.contains("Import", ignoreCase = true),
            )
        }
    }
}
