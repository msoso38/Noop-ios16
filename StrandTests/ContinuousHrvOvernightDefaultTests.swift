import XCTest
@testable import Strand

/// #1008 — which way "Overnight only" falls when the user has never chosen. Twin of the Kotlin
/// `ContinuousHrvOvernightDefaultTest`; same cases in the same order.
///
/// WHOOP publishes no daytime HRV figure, so a 24/7 stream has no official-app analogue and costs
/// roughly twice the battery. The cheaper, WHOOP-comparable behaviour should be the default — but only
/// for someone not already running the other one.
///
/// The rule that must not break: an existing Continuous HRV user's capture is never silently narrowed.
/// They opted into "all day and night" and may be reading daytime Stress off it.
///
/// Note: `StrandTests` runs only under `xcodebuild` on macOS, and `app-build.yml` is disabled — so this
/// suite is not executed by CI today. The Kotlin twin is, under `testFullDebugUnitTest`.
final class ContinuousHrvOvernightDefaultTests: XCTestCase {

    /// A fresh install gets the WHOOP-comparable, cheaper default. This is the change.
    func testFreshInstallDefaultsToOvernightOnly() {
        XCTAssertTrue(PuffinExperiment.continuousHrvOvernightDefault(
            hasExplicitChoice: false, explicitChoice: false, hasUsedContinuousHrv: false))
    }

    /// The regression guard: an existing Continuous HRV user keeps always-on. Narrowing it under them
    /// would remove the daytime data they opted in for, without asking.
    func testAnExistingContinuousHrvUserKeepsAlwaysOn() {
        XCTAssertFalse(PuffinExperiment.continuousHrvOvernightDefault(
            hasExplicitChoice: false, explicitChoice: false, hasUsedContinuousHrv: true))
    }

    /// An explicit ON wins over anything the install age would imply.
    func testAnExplicitOnIsHonoured() {
        XCTAssertTrue(PuffinExperiment.continuousHrvOvernightDefault(
            hasExplicitChoice: true, explicitChoice: true, hasUsedContinuousHrv: true))
    }

    /// An explicit OFF wins too, including on a fresh install — the mirror of the guard above.
    func testAnExplicitOffIsHonouredEvenOnAFreshInstall() {
        XCTAssertFalse(PuffinExperiment.continuousHrvOvernightDefault(
            hasExplicitChoice: true, explicitChoice: false, hasUsedContinuousHrv: false))
    }
}
