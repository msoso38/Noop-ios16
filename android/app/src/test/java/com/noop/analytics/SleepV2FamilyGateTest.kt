package com.noop.analytics

import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #319: the #277 V2-default promotion applies to WHOOP 5.0/MG only. WHOOP 4.0 always uses V1 — its sparse
 * motion makes V2 inflate the Rest restorative term AND defeat the H9 low-confidence guard, so a poor 4.0
 * night reads as a confident 85-100. The engine is now selected purely by device family (the per-user
 * "experimental V2" toggle was removed — issue #345). Pins [IntelligenceEngine.sleepStagerV2ForFamily]
 * (byte-parity twin of Swift `IntelligenceEngine.sleepStagerV2(family:)`).
 */
class SleepV2FamilyGateTest {

    @Test
    fun `V2 on 5MG, never on WHOOP 4`() {
        assertTrue("5.0/MG → V2",
            IntelligenceEngine.sleepStagerV2ForFamily(family = DeviceFamily.WHOOP5))
        assertFalse("WHOOP 4 → V1 (the #319 sparse-motion gate; needs real 4.0 raw before V2 can run here)",
            IntelligenceEngine.sleepStagerV2ForFamily(family = DeviceFamily.WHOOP4))
    }
}
