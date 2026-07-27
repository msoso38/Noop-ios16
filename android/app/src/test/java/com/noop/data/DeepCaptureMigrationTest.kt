package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * MIGRATION_24_25 — the additive shape that lets the four named v18 channels and the fifteen leftover
 * slots be banked at all. Twin of the Swift GRDB `v31-deep-capture-channels` migration test.
 *
 * The SQL here is hand-written, which is exactly where a mistake is possible (Room does not validate it
 * at build time with `exportSchema=false`), so this test guards its shape. The end-to-end read/write is
 * exercised on the Swift twin (`WhoopStoreTests.DeepCaptureChannelsTests`, which CI runs); there is no
 * SQLite driver on this unit-test classpath, matching how every other migration here is covered.
 */
class DeepCaptureMigrationTest {

    @Test
    fun migrationIsAdditiveAndNullablePreserving() {
        val sql = WhoopDatabase.DEEP_CAPTURE_MIGRATION_SQL
        assertEquals(5, sql.size)

        val alters = sql.take(4)
        assertEquals(
            listOf(
                "ALTER TABLE `gravitySample` ADD COLUMN `dynAccel` REAL",
                "ALTER TABLE `sleepStateSample` ADD COLUMN `rawByte` INTEGER",
                "ALTER TABLE `skinTempSample` ADD COLUMN `aux1Raw` INTEGER",
                "ALTER TABLE `skinTempSample` ADD COLUMN `aux2Raw` INTEGER",
            ),
            alters,
        )
        for (stmt in alters) {
            val upper = stmt.uppercase()
            assertTrue(upper.startsWith("ALTER TABLE"))
            assertTrue("must be additive", upper.contains("ADD COLUMN"))
            // NULL is load-bearing: a WHOOP 4.0 never reports these, and history banked before this
            // migration cannot be backfilled (the strap already trimmed it), so an absent channel must
            // stay absent rather than become a fabricated 0 indistinguishable from a real zero reading.
            assertFalse("added columns must be nullable", upper.contains("NOT NULL"))
            assertFalse("no default — absent means the strap never reported it", upper.contains("DEFAULT"))
        }

        // The new table's column order must match V18AuxSampleEntity's field order (deviceId, ts,
        // fields) and the GRDB schema, or a fresh install and a migrated install disagree.
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `v18AuxSample` (`deviceId` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, `fields` BLOB NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`))",
            sql[4],
        )

        // Nothing in this migration may touch an existing row.
        for (stmt in sql) {
            val upper = stmt.uppercase()
            for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "INSERT ")) {
                assertFalse("migration must not contain $banned", upper.contains(banned))
            }
        }
        assertEquals(24, WhoopDatabase.MIGRATION_24_25.startVersion)
        assertEquals(25, WhoopDatabase.MIGRATION_24_25.endVersion)
    }

    /** The added columns must be absent — not 0 — on a row that never carried them. */
    @Test
    fun entitiesDefaultTheNewColumnsToNullSoLegacyRowsStayHonest() {
        assertEquals(null, GravitySample("d", 1L, 0.0, 0.0, 1.0).dynAccel)
        assertEquals(null, SleepStateSampleEntity("d", 1L, 2).rawByte)
        assertEquals(null, SkinTempSample("d", 1L, 3057).aux1Raw)
        assertEquals(null, SkinTempSample("d", 1L, 3057).aux2Raw)
    }

    /** `state` must stay exactly the high nibble of the raw byte — #175's behaviour is bit-identical. */
    @Test
    fun sleepStateColumnStaysTheHighNibbleOfTheRawByte() {
        for (raw in 0..0xFF) {
            val e = SleepStateSampleEntity("d", 1L, (raw shr 4) and 3, rawByte = raw)
            assertEquals((e.rawByte!! shr 4) and 3, e.state)
        }
    }

    // MARK: - Rolling retention (insert plumbing through a Proxy DAO, the RawImuMigrationTest pattern)

    /** One aux row banked, and the device+keep the repository passed to the sweep (null = never swept). */
    private class AuxSweepRecorder {
        var insertedBatches = 0
        var insertedRows = 0
        var prunedDevice: String? = null
        var prunedKeep = -1
        var sweeps = 0

        val dao: WhoopDao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "insertV18Aux" -> {
                    @Suppress("UNCHECKED_CAST")
                    val rows = args[0] as List<V18AuxSampleEntity>
                    insertedBatches += 1
                    insertedRows += rows.size
                    List(rows.size) { 1L }
                }
                "pruneV18Aux" -> {
                    prunedDevice = args[0] as String; prunedKeep = args[1] as Int; sweeps += 1; Unit
                }
                else -> throw UnsupportedOperationException("v18-aux insert must not call ${method.name}")
            }
        } as WhoopDao
    }

    /** [n] aux rows that each pack to a non-empty blob, at distinct timestamps from [firstTs]. */
    private fun auxRows(n: Int, firstTs: Long = 1_780_916_150L) =
        StreamBatch(v18Aux = (0 until n).map { V18AuxRow(ts = firstTs + it, statusWord = 1_792L) })

    /**
     * `v18AuxSample` is CAPPED, not unbounded — the insert must follow the write with the rolling prune,
     * passing the shipped retention constant. Twin of the Swift `testAuxRowsAreCappedNewestFirst`; the
     * DELETE's own semantics are proved end-to-end there (no SQLite driver on this classpath).
     *
     * Passes `v18AuxPruneEveryRows = 1` for the same reason the Swift retention tests do since #888:
     * the sweep is amortised in production, so a one-row batch would otherwise bank its row and defer.
     */
    @Test
    fun repositoryInsertV18Aux_insertsThenPrunes() = runBlocking {
        val rec = AuxSweepRecorder()

        WhoopRepository(rec.dao).insert(
            auxRows(1),
            "my-whoop",
            v18AuxPruneEveryRows = 1,
        )

        assertEquals(1, rec.insertedRows)
        assertEquals("my-whoop", rec.prunedDevice)
        assertEquals(WhoopRepository.V18_AUX_RETENTION_ROWS, rec.prunedKeep)
    }

    // MARK: - Amortisation (#888) — Kotlin twins of the Swift DeepCaptureChannelsTests cases

    /** Below the threshold the rows are banked and the walk is deferred: written, but never swept. */
    @Test
    fun repositoryInsertV18Aux_belowThresholdDefersTheSweep() = runBlocking {
        val rec = AuxSweepRecorder()
        WhoopRepository(rec.dao).insert(auxRows(3), "dev1", v18AuxPruneEveryRows = 5_000)
        assertEquals(3, rec.insertedRows)
        assertEquals(0, rec.sweeps)
        assertNull(rec.prunedDevice)
    }

    /** Crossing the threshold — across batches, since the counter accumulates — sweeps exactly once. */
    @Test
    fun repositoryInsertV18Aux_sweepsOnceTheThresholdIsCrossed() = runBlocking {
        val rec = AuxSweepRecorder()
        val repo = WhoopRepository(rec.dao)
        repo.insert(auxRows(3, firstTs = 1_000L), "dev1", v18AuxPruneEveryRows = 7)
        assertEquals(0, rec.sweeps)
        repo.insert(auxRows(3, firstTs = 2_000L), "dev1", v18AuxPruneEveryRows = 7)
        assertEquals(0, rec.sweeps)
        repo.insert(auxRows(3, firstTs = 3_000L), "dev1", v18AuxPruneEveryRows = 7)
        assertEquals(1, rec.sweeps)
        assertEquals("dev1", rec.prunedDevice)
    }

    /** The counter resets on a successful sweep, so a long offload sweeps repeatedly rather than once. */
    @Test
    fun repositoryInsertV18Aux_theAmortisationCounterResetsAfterEachSweep() = runBlocking {
        val rec = AuxSweepRecorder()
        val repo = WhoopRepository(rec.dao)
        repeat(4) { repo.insert(auxRows(4, firstTs = 1_000L * (it + 1)), "dev1", v18AuxPruneEveryRows = 4) }
        assertEquals(4, rec.sweeps)
    }

    /** The budget is per device — one strap's inserts must not spend another's. Fails on a shared counter. */
    @Test
    fun repositoryInsertV18Aux_theAmortisationBudgetIsNotSharedBetweenDevices() = runBlocking {
        val rec = AuxSweepRecorder()
        val repo = WhoopRepository(rec.dao)
        repo.insert(auxRows(2, firstTs = 1_000L), "dev1", v18AuxPruneEveryRows = 4)
        repo.insert(auxRows(2, firstTs = 2_000L), "dev2", v18AuxPruneEveryRows = 4)
        // A shared counter would now stand at 4 and sweep here on dev1's second batch.
        assertEquals(0, rec.sweeps)
        repo.insert(auxRows(2, firstTs = 3_000L), "dev1", v18AuxPruneEveryRows = 4)
        assertEquals(1, rec.sweeps)
        assertEquals("dev1", rec.prunedDevice)
    }

    /** A batch whose aux rows all pack to nothing writes no row, so it must not sweep either. */
    @Test
    fun repositoryInsertV18Aux_allAbsentTouchesNoDao(): Unit = runBlocking {
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, _ ->
            throw AssertionError("an all-absent aux batch must not touch the DAO (${method.name})")
        } as WhoopDao
        WhoopRepository(dao).insert(StreamBatch(v18Aux = listOf(V18AuxRow(ts = 1L))), "my-whoop")
        Unit
    }

    /**
     * A batch of ONLY aux rows must not read as empty. `insert` early-returns on [StreamBatch.isEmpty],
     * so leaving `v18Aux` out of it silently banks nothing — and Swift's `Streams.isEmpty` counts it, so
     * the same offload would drop rows on Android alone.
     */
    @Test
    fun auxOnlyBatchIsNotEmpty() {
        assertFalse(StreamBatch(v18Aux = listOf(V18AuxRow(ts = 1L, statusWord = 7L))).isEmpty)
        assertTrue(StreamBatch().isEmpty)
    }

    /** The shipped cap is a real bound, matching the Swift constant. */
    @Test
    fun shippedRetentionConstant() {
        assertEquals(604_800, WhoopRepository.V18_AUX_RETENTION_ROWS)   // 7 x 86_400 strap-seconds
        assertTrue(WhoopRepository.V18_AUX_RETENTION_ROWS > WhoopRepository.RAW_IMU_RETENTION_ROWS)
    }
}
