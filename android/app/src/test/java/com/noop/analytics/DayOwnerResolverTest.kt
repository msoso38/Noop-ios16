package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure day-owner resolution contract — mirrors the Swift DayOwnerResolverTests in
 * Packages/StrandAnalytics. No Room/Android: [DayOwnerResolver] is a pure function.
 */
class DayOwnerResolverTest {

    @Test
    fun activeStrapWinsSharedDay() {
        // Both the active strap (priority 0) and an import (priority 2) have data → the strap owns it.
        val candidates = listOf(
            DayOwnerResolver.Candidate("my-whoop", priority = 0, hasData = true),
            DayOwnerResolver.Candidate("oura", priority = 2, hasData = true),
        )
        assertEquals(
            "my-whoop",
            DayOwnerResolver.resolve("2026-06-15", lockedOwner = null, candidates = candidates),
        )
    }

    @Test
    fun importFillsGap() {
        // The strap has no data for the day → the import (the only candidate with data) owns it.
        val candidates = listOf(
            DayOwnerResolver.Candidate("my-whoop", priority = 0, hasData = false),
            DayOwnerResolver.Candidate("oura", priority = 2, hasData = true),
        )
        assertEquals(
            "oura",
            DayOwnerResolver.resolve("2026-06-15", lockedOwner = null, candidates = candidates),
        )
    }

    @Test
    fun lockedWins() {
        // A locked owner overrides priority/data — even though only the import has data, the locked
        // "my-whoop" wins.
        val candidates = listOf(
            DayOwnerResolver.Candidate("my-whoop", priority = 0, hasData = false),
            DayOwnerResolver.Candidate("oura", priority = 2, hasData = true),
        )
        assertEquals(
            "my-whoop",
            DayOwnerResolver.resolve("2026-06-15", lockedOwner = "my-whoop", candidates = candidates),
        )
    }

    @Test
    fun noDataNull() {
        // No candidate has data and there is no lock → no owner.
        val candidates = listOf(
            DayOwnerResolver.Candidate("my-whoop", priority = 0, hasData = false),
        )
        assertNull(DayOwnerResolver.resolve("2026-06-15", lockedOwner = null, candidates = candidates))
    }

    // §6.15: an active Oura ring with only a bare check_sleep window (richData=false) must NOT displace an
    // imported WHOOP night with a full HR-backed record (richData=true) — the richer record wins despite
    // the worse priority. Mirrors Swift testRichImportBeatsActiveWindowOnlyRing.
    @Test
    fun richImportBeatsActiveWindowOnlyRing() {
        val candidates = listOf(
            DayOwnerResolver.Candidate("oura", priority = 0, hasData = true, richData = false),
            DayOwnerResolver.Candidate("whoop-import", priority = 2, hasData = true, richData = true),
        )
        assertEquals(
            "whoop-import",
            DayOwnerResolver.resolve("2026-07-08", lockedOwner = null, candidates = candidates),
        )
    }

    // …but on a day nothing richer recorded, the window-only ring is the sole source and owns it.
    @Test
    fun windowOnlyRingOwnsDayWithNoRicherRecord() {
        val candidates = listOf(
            DayOwnerResolver.Candidate("oura", priority = 0, hasData = true, richData = false),
            DayOwnerResolver.Candidate("whoop-import", priority = 2, hasData = false, richData = true),
        )
        assertEquals(
            "oura",
            DayOwnerResolver.resolve("2026-07-09", lockedOwner = null, candidates = candidates),
        )
    }

    // Two window-only rings (both richData=false) still fall back to device priority (active wins).
    @Test
    fun windowOnlyTieBreaksOnPriority() {
        val candidates = listOf(
            DayOwnerResolver.Candidate("oura-active", priority = 0, hasData = true, richData = false),
            DayOwnerResolver.Candidate("oura-other", priority = 1, hasData = true, richData = false),
        )
        assertEquals(
            "oura-active",
            DayOwnerResolver.resolve("2026-07-09", lockedOwner = null, candidates = candidates),
        )
    }
}
