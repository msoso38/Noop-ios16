package com.noop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1008 — which way "Overnight only" falls when the user has never chosen.
 *
 * WHOOP publishes no daytime HRV figure, so a 24/7 stream has no official-app analogue and costs
 * roughly twice the battery. The cheaper, WHOOP-comparable behaviour should be the one you get by
 * default — but ONLY for someone who has not already been running the other one.
 *
 * The rule that must not break: an existing Continuous HRV user's capture is never silently narrowed.
 * They opted into "all day and night" and may be reading daytime Stress off it.
 */
class ContinuousHrvOvernightDefaultTest {

    /** A fresh install gets the WHOOP-comparable, cheaper default. This is the change. */
    @Test fun freshInstallDefaultsToOvernightOnly() {
        assertTrue(
            NoopPrefs.continuousHrvOvernightDefault(
                hasExplicitChoice = false, explicitChoice = false, hasUsedContinuousHrv = false,
            ),
        )
    }

    /**
     * The regression guard. Someone who already enabled Continuous HRV keeps always-on — narrowing it
     * under them would remove the daytime data they opted in for, without asking.
     */
    @Test fun anExistingContinuousHrvUserKeepsAlwaysOn() {
        assertFalse(
            NoopPrefs.continuousHrvOvernightDefault(
                hasExplicitChoice = false, explicitChoice = false, hasUsedContinuousHrv = true,
            ),
        )
    }

    /** An explicit ON wins over anything the install age would imply. */
    @Test fun anExplicitOnIsHonoured() {
        assertTrue(
            NoopPrefs.continuousHrvOvernightDefault(
                hasExplicitChoice = true, explicitChoice = true, hasUsedContinuousHrv = true,
            ),
        )
    }

    /**
     * An explicit OFF wins too — including on a fresh install. Without this the new default would
     * override someone who deliberately turned overnight-only off, which is the mirror of the bug the
     * second test guards.
     */
    @Test fun anExplicitOffIsHonouredEvenOnAFreshInstall() {
        assertFalse(
            NoopPrefs.continuousHrvOvernightDefault(
                hasExplicitChoice = true, explicitChoice = false, hasUsedContinuousHrv = false,
            ),
        )
    }
}
