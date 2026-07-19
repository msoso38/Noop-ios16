package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the additive v20 -> v21 Room migration (the `ppgRespSample` table, #103), the Android twin
 * of the Swift WhoopStore v28-ppg-resp-sample migration. This environment has no Robolectric / Room-
 * testing, so the migration's SQL is exposed as an internal constant
 * ([WhoopDatabase.PPG_RESP_SAMPLE_MIGRATION_SQL]) and pinned here to Room's generated shape for
 * [PpgRespSample]:
 *
 *  - one CREATE TABLE IF NOT EXISTS statement — deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm REAL
 *    NOT NULL, conf REAL NOT NULL, composite PRIMARY KEY (deviceId, ts) in declaration order.
 *  - ADDITIVE: CREATE TABLE only; no DROP/DELETE/UPDATE/INSERT/ALTER on existing data.
 */
class PpgRespSampleMigrationTest {

    @Test
    fun migration_isAdditive_onlyCreateTable() {
        val sql = WhoopDatabase.PPG_RESP_SAMPLE_MIGRATION_SQL
        assertEquals("one CREATE TABLE statement", 1, sql.size)
        for (s in sql) {
            val up = s.trimStart().uppercase()
            assertTrue("only CREATE TABLE allowed, got: $s", up.startsWith("CREATE TABLE"))
            for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "INSERT ", "ALTER ")) {
                assertTrue("additive migration must not contain '$banned': $s", !up.contains(banned))
            }
        }
    }

    @Test
    fun migration_createsExactTable() {
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS `ppgRespSample` (`deviceId` TEXT NOT NULL, " +
                    "`ts` INTEGER NOT NULL, `bpm` REAL NOT NULL, `conf` REAL NOT NULL, " +
                    "PRIMARY KEY(`deviceId`, `ts`))",
            ),
            WhoopDatabase.PPG_RESP_SAMPLE_MIGRATION_SQL,
        )
    }

    @Test
    fun migration_versionPair_is20to21() {
        assertEquals(20, WhoopDatabase.MIGRATION_20_21.startVersion)
        assertEquals(21, WhoopDatabase.MIGRATION_20_21.endVersion)
    }

    @Test
    fun ppgRespSample_entity_shape() {
        val entity = PpgRespSample(deviceId = "my-whoop", ts = 1_780_916_150L, bpm = 14.5, conf = 8.2)
        assertEquals("my-whoop", entity.deviceId)
        assertEquals(1_780_916_150L, entity.ts)
        assertEquals(14.5, entity.bpm, 0.0)
        assertEquals(8.2, entity.conf, 0.0)
    }
}
