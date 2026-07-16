import XCTest
import WhoopStore
@testable import Strand

/// Pins the Liquid Today PER-FIELD vitals carry for Blood Oxygen (SpO₂) and Skin Temp — the twin of
/// `LiquidChargeCarryTests` for the two fields the whole-row vitals carry (`lastVitalsDay`) does NOT check.
///
/// The v8 Liquid redesign wired its recovery-vitals (HRV / Rest HR / Respiratory) to the whole-row
/// `Repository.lastVitalsDay` carry so they hold through the post-04:00 rollover window, but the Blood Oxygen
/// tile read only `displayDay ?? vitalsDay` and the Skin Temp card wasn't wired at all — while classic
/// `TodayView.dashboardCardValue` carries BOTH per-field (`d?.field ?? lastVitalsDay?.field ??
/// last{Spo2,SkinTemp}Day?.field`). The gap bites precisely where the field is engine-nulled: the on-device
/// engine writes `spo2Pct`/`skinTempDevC = nil` on every computed "-noop" row (it banks only raw channels),
/// so the whole-row carry lands on a row whose value is nil even though an OLDER imported row holds a real
/// reading — and the tile blanks. `lastSpo2Day` / `lastSkinTempDay` resolve each field from its own freshest
/// strictly-prior row.
///
/// The selectors themselves are pinned headlessly in `DailyMetricLastVitalsDayTests` (package-side). This
/// pins the LIQUID presentation decision on top of them — the today-first → whole-row → per-field ordering —
/// so Liquid and Classic can't drift. Pure `perFieldVital`, so it needs no strap, no clock and no view.
final class LiquidVitalsCarryTests: XCTestCase {

    /// A day row carrying only the fields these tests exercise — mirrors the package test's `fieldDay`.
    private func day(_ key: String, hrv: Double? = nil, spo2: Double? = nil, skinTemp: Double? = nil) -> DailyMetric {
        DailyMetric(day: key, totalSleepMin: nil, efficiency: nil, deepMin: nil, remMin: nil,
                    lightMin: nil, disturbances: nil, restingHr: nil, avgHrv: hrv, recovery: nil,
                    strain: nil, exerciseCount: nil, spo2Pct: spo2, skinTempDevC: skinTemp, respRateBpm: nil)
    }

    // MARK: - The coalesce order: today wins, then the whole-row carry, then the per-field carry

    func testTodaysOwnValueWinsOverEitherCarry() {
        XCTAssertEqual(LiquidTodayView.perFieldVital(today: 97, vitalsCarry: 95, fieldCarry: 90), 97,
                       "today's own reading must never be displaced by a carry")
    }

    func testWholeRowVitalsCarryWinsOverThePerFieldCarry() {
        // When last night's vitals row ALSO carries the field, that fresher night beats the older per-field
        // row — today-first, then freshest-row-first, never the reverse.
        XCTAssertEqual(LiquidTodayView.perFieldVital(today: nil, vitalsCarry: 95, fieldCarry: 90), 95)
    }

    // MARK: - THE regression: the per-field carry is the only thing keeping the tile off "–"

    func testPerFieldCarrySurfaces_whenTodayAndTheWholeRowCarryAreBothNil() {
        // The exact rollover shape: today's row is empty and the whole-row vitals carry landed on a computed
        // "-noop" row whose spo2Pct/skinTempDevC is engine-written nil. Before the fix Liquid stopped at
        // `nil ?? nil` and blanked; the per-field carry supplies the real reading.
        XCTAssertEqual(LiquidTodayView.perFieldVital(today: nil, vitalsCarry: nil, fieldCarry: 96), 96,
                       "a null today AND null whole-row field must fall through to the per-field carry")
    }

    func testNothingAnywhereStaysNil_soTheTileHonestlyShowsNoData() {
        XCTAssertNil(LiquidTodayView.perFieldVital(today: nil, vitalsCarry: nil, fieldCarry: nil),
                     "no reading anywhere is honest No-Data — never a fabricated 0")
    }

    // MARK: - End-to-end rollover, composed with the real selectors (mirrors the classic behavior)

    func testSpo2Rollover_fallsThroughTheNullVitalsRowToTheImportedReading() {
        // Post-rollover: today empty; last night's computed row has HRV but spo2Pct = nil (so the WHOLE-ROW
        // lastVitalsDay lands on it with a null SpO₂); an older imported row holds a real 96%.
        let days = [day("2026-07-13", spo2: 96),            // imported, real reading
                    day("2026-07-14", hrv: 42, spo2: nil),  // last night: vitals yes, SpO₂ nil
                    day("2026-07-15")]                       // today, empty
        let tkey = "2026-07-15"
        let vitals = Repository.lastVitalsDay(days: days, todayKey: tkey)
        XCTAssertEqual(vitals?.day, "2026-07-14", "the whole-row carry lands on last night (it has HRV)…")
        XCTAssertNil(vitals?.spo2Pct, "…whose SpO₂ is nil — this is exactly what blanked the tile")
        let spo2 = LiquidTodayView.perFieldVital(
            today: days.last?.spo2Pct,
            vitalsCarry: vitals?.spo2Pct,
            fieldCarry: Repository.lastSpo2Day(days: days, todayKey: tkey)?.spo2Pct)
        XCTAssertEqual(spo2, 96, "SpO₂ must resolve to the imported reading, not blank on the vitals row's nil")
    }

    func testSkinTempRollover_fallsThroughToThePriorDeviation() {
        let days = [day("2026-07-13", skinTemp: -0.3),      // prior reading (signed deviation from baseline)
                    day("2026-07-14", hrv: 42),             // last night: vitals, no skin temp
                    day("2026-07-15")]                       // today, empty
        let tkey = "2026-07-15"
        let vitals = Repository.lastVitalsDay(days: days, todayKey: tkey)
        XCTAssertNil(vitals?.skinTempDevC, "the whole-row carry row has no skin temp")
        let skin = LiquidTodayView.perFieldVital(
            today: days.last?.skinTempDevC,
            vitalsCarry: vitals?.skinTempDevC,
            fieldCarry: Repository.lastSkinTempDay(days: days, todayKey: tkey)?.skinTempDevC)
        XCTAssertEqual(skin, -0.3, "skin temp must carry the prior day's deviation, not blank")
    }
}
