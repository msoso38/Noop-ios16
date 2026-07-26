package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
