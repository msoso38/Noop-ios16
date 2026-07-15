package com.noop.ble

import android.bluetooth.BluetoothGatt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [WhoopBleClient.connectionPriorityFor] — the pure GATT connection-priority decision (#477),
 * unit-testable without a BLE stack (the [ScanPowerBackoffTest] idiom).
 *
 * SAFE half: HIGH during an offload burst OR a live-HR session — a SHORTER interval than BALANCED, so
 * it can't cause a supervision-timeout drop and it shortens the radio-on window. RISKY half
 * ([idleThrottleEnabled], default off): LOW_POWER when idle. Off → BALANCED, today's default.
 */
class ConnectionPriorityTest {

    @Test fun activeWorkIsAlwaysHigh() {
        // offload OR live-HR → HIGH, and the idle throttle can't override active work
        assertEquals(
            BluetoothGatt.CONNECTION_PRIORITY_HIGH,
            WhoopBleClient.connectionPriorityFor(offloadActive = true, liveHrActive = false, idleThrottleEnabled = false),
        )
        assertEquals(
            BluetoothGatt.CONNECTION_PRIORITY_HIGH,
            WhoopBleClient.connectionPriorityFor(offloadActive = false, liveHrActive = true, idleThrottleEnabled = false),
        )
        assertEquals(
            BluetoothGatt.CONNECTION_PRIORITY_HIGH,
            WhoopBleClient.connectionPriorityFor(offloadActive = true, liveHrActive = false, idleThrottleEnabled = true),
        )
    }

    @Test fun idleWithThrottleOffStaysBalanced() {
        // The whole point of the safe half shipping default-on: idle == today's behaviour when the
        // risky throttle is off.
        assertEquals(
            BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
            WhoopBleClient.connectionPriorityFor(offloadActive = false, liveHrActive = false, idleThrottleEnabled = false),
        )
    }

    @Test fun idleWithThrottleOnDropsToLowPower() {
        assertEquals(
            BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER,
            WhoopBleClient.connectionPriorityFor(offloadActive = false, liveHrActive = false, idleThrottleEnabled = true),
        )
    }

    // --- battery-adaptive gate for the risky idle throttle (#477) ---

    @Test fun idleThrottleEngagesOnlyWhenDischargingAtOrBelowThreshold() {
        // at/below threshold, discharging → engage
        assertTrue(WhoopBleClient.idleThrottleActive(batteryPct = 20, charging = false, thresholdPct = 20))
        assertTrue(WhoopBleClient.idleThrottleActive(batteryPct = 12, charging = false, thresholdPct = 20))
        // above threshold → do not engage
        assertFalse(WhoopBleClient.idleThrottleActive(batteryPct = 21, charging = false, thresholdPct = 20))
    }

    @Test fun idleThrottleNeverEngagesWhenChargingOrDisabled() {
        // charging → never (battery isn't the concern)
        assertFalse(WhoopBleClient.idleThrottleActive(batteryPct = 5, charging = true, thresholdPct = 30))
        // threshold 0 → disabled (safe half only), even at 1%
        assertFalse(WhoopBleClient.idleThrottleActive(batteryPct = 1, charging = false, thresholdPct = 0))
    }
}
