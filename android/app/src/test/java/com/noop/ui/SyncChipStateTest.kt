package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #245: `SyncChipState.resolve` is the one place the Today top bar's `SyncStatusChip` decides which of
 * the four sync states to show. Mirrors the iOS `SyncChipStateTests` 1:1 — same priority order (backfilling
 * wins over a known last-sync, which wins over the 5/MG experimental fallback), same cold-start `Hidden` case.
 */
class SyncChipStateTest {

    @Test
    fun backfilling_isSyncingWithChunkCount() {
        val state = SyncChipState.resolve(
            backfilling = true, chunks = 7, lastSyncAtSec = null, historySyncExperimental = false,
        )
        assertEquals(SyncChipState.Syncing(7), state)
    }

    @Test
    fun lastSyncedAt_isSyncedWithAgeText() {
        val now = System.currentTimeMillis() / 1000L
        val state = SyncChipState.resolve(
            backfilling = false, chunks = 0, lastSyncAtSec = now - 65, historySyncExperimental = false,
        )
        assertEquals(SyncChipState.Synced("1m"), state)
    }

    @Test
    fun historySyncExperimental_withNoLastSync_isExperimentalLive() {
        val state = SyncChipState.resolve(
            backfilling = false, chunks = 0, lastSyncAtSec = null, historySyncExperimental = true,
        )
        assertEquals(SyncChipState.ExperimentalLive, state)
    }

    @Test
    fun coldStart_noBackfillNoSyncNoExperimental_isHidden() {
        val state = SyncChipState.resolve(
            backfilling = false, chunks = 0, lastSyncAtSec = null, historySyncExperimental = false,
        )
        assertEquals(SyncChipState.Hidden, state)
    }

    @Test
    fun backfilling_takesPriorityOverLastSyncedAt() {
        val now = System.currentTimeMillis() / 1000L
        val state = SyncChipState.resolve(
            backfilling = true, chunks = 2, lastSyncAtSec = now - 5, historySyncExperimental = false,
        )
        assertEquals(SyncChipState.Syncing(2), state)
    }

    @Test
    fun lastSyncedAt_takesPriorityOverHistorySyncExperimental() {
        val now = System.currentTimeMillis() / 1000L
        val state = SyncChipState.resolve(
            backfilling = false, chunks = 0, lastSyncAtSec = now - 5, historySyncExperimental = true,
        )
        assertTrue("A known last-sync should win over the experimental fallback", state is SyncChipState.Synced)
    }
}
