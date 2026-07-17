import XCTest
import WhoopStore
import WhoopProtocol
@testable import Strand

/// Pins the pure logic behind the NDJSON export: the NDJSON line format, the sport mapping,
/// sleep stage routing, HRV SDNN computation, calorie deduplication, watermark-based delta
/// export, and the anti-re-import truncation (#167 protection).
final class ShortcutNdjsonExportTests: XCTestCase {

    private let utc = TimeZone(secondsFromGMT: 0)!
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var dir: URL!

    override func setUpWithError() throws {
        suiteName = "ShortcutNdjsonExportTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(suiteName)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suiteName)
        UserDefaults.standard.removeObject(forKey: ShortcutNdjsonExport.skipTruncationKey)
        try? FileManager.default.removeItem(at: dir)
    }

    private struct FakeNdjsonReads: NdjsonExportReads {
        var hr: [HRBucket] = []
        var rr: [RRInterval] = []
        var steps: [StepSample] = []
        var sleepSessionsData: [CachedSleepSession] = []
        var sleepSessionsByDevice: [String: [CachedSleepSession]] = [:]
        var workoutsData: [WorkoutRow] = []
        var dailyMetricsData: [DailyMetric] = []
        var error: Error?
        struct Boom: Error {}

        func hrBuckets(deviceId: String, from: Int, to: Int, bucketSeconds: Int) async throws -> [HRBucket] {
            if let error { throw error }
            return hr
        }
        func rrIntervals(deviceId: String, from: Int, to: Int, limit: Int) async throws -> [RRInterval] {
            if let error { throw error }
            return rr
        }
        func stepSamples(deviceId: String, from: Int, to: Int, limit: Int) async throws -> [StepSample] {
            if let error { throw error }
            return steps
        }
        func sleepSessions(deviceId: String, from: Int, to: Int, limit: Int) async throws -> [CachedSleepSession] {
            if let error { throw error }
            return sleepSessionsByDevice[deviceId] ?? sleepSessionsData
        }
        func workouts(deviceId: String, from: Int, to: Int, limit: Int) async throws -> [WorkoutRow] {
            if let error { throw error }
            return workoutsData
        }
        func dailyMetrics(deviceId: String, from: String, to: String) async throws -> [DailyMetric] {
            if let error { throw error }
            return dailyMetricsData
        }
    }

    private func readFile(_ name: String) throws -> String {
        try String(contentsOf: dir.appendingPathComponent(name), encoding: .utf8)
    }

    // MARK: - Heart rate rendering

    func testHeartRateNormalBucket() {
        let buckets = [HRBucket(ts: 0, bpm: 62)]
        let text = ShortcutNdjsonExport.renderHeartRate(buckets: buckets, timeZone: utc)
        XCTAssertEqual(text, "{\"ts\":\"1970-01-01T00:00:00\",\"bpm\":62}\n")
    }

    func testHeartRateEmptyBucketSkipped() {
        let buckets = [HRBucket(ts: 0, bpm: 0), HRBucket(ts: 900, bpm: 65)]
        let text = ShortcutNdjsonExport.renderHeartRate(buckets: buckets, timeZone: utc)
        XCTAssertEqual(text, "{\"ts\":\"1970-01-01T00:15:00\",\"bpm\":65}\n")
    }

    func testHeartRateRounding() {
        let buckets = [HRBucket(ts: 0, bpm: 61.5)]
        let text = ShortcutNdjsonExport.renderHeartRate(buckets: buckets, timeZone: utc)
        XCTAssertTrue(text.contains("\"bpm\":62"))
    }

    func testHeartRateTimezoneConversion() {
        let tz = TimeZone(secondsFromGMT: 3600)!
        let buckets = [HRBucket(ts: 900, bpm: 70)]
        let text = ShortcutNdjsonExport.renderHeartRate(buckets: buckets, timeZone: tz)
        XCTAssertTrue(text.contains("\"ts\":\"1970-01-01T01:15:00\""))
    }

    func testHeartRateEmpty() {
        let text = ShortcutNdjsonExport.renderHeartRate(buckets: [], timeZone: utc)
        XCTAssertEqual(text, "")
    }

    // MARK: - HRV rendering

    func testHrvSufficientIntervals() {
        let rr = (0..<30).map { RRInterval(ts: 10 + $0, rrMs: $0 % 2 == 0 ? 800 : 810) }
        let text = ShortcutNdjsonExport.renderHrv(rrIntervals: rr, timeZone: utc)
        XCTAssertFalse(text.isEmpty)
        XCTAssertTrue(text.contains("\"sdnn_ms\":"))
        XCTAssertTrue(text.contains("\"ts\":\"1970-01-01T00:00:00\""))
    }

    func testHrvInsufficientIntervalsSkipped() {
        let rr = (0..<10).map { RRInterval(ts: 10 + $0, rrMs: 800) }
        let text = ShortcutNdjsonExport.renderHrv(rrIntervals: rr, timeZone: utc)
        XCTAssertEqual(text, "")
    }

    func testHrvPhysiologicalFilter() {
        // Intervals outside 300-2000ms should be dropped; if all are dropped, no output.
        let rr = [RRInterval(ts: 0, rrMs: 100), RRInterval(ts: 1, rrMs: 2500)]
        let text = ShortcutNdjsonExport.renderHrv(rrIntervals: rr, timeZone: utc)
        XCTAssertEqual(text, "")
    }

    func testHrvRecordCount() {
        let rr = (0..<30).map { RRInterval(ts: 10 + $0, rrMs: $0 % 2 == 0 ? 800 : 810) }
        XCTAssertEqual(ShortcutNdjsonExport.countHrvRecords(rrIntervals: rr), 1)
    }

    // MARK: - Sleep rendering

    func testSleepMultiStageSession() {
        let stagesJSON = """
        [{"start":0,"end":300,"stage":"light"},{"start":300,"end":600,"stage":"deep"},{"start":600,"end":900,"stage":"rem"},{"start":900,"end":1200,"stage":"wake"}]
        """
        let session = CachedSleepSession(startTs: 0, endTs: 1200, efficiency: 0.9,
                                          restingHr: 55, avgHrv: 42, stagesJSON: stagesJSON)
        let (sleeping, awake) = ShortcutNdjsonExport.renderSleep(sessions: [session], timeZone: utc)
        // light + deep + rem → sleeping, wake → awake
        XCTAssertEqual(sleeping.components(separatedBy: "\n").filter { !$0.isEmpty }.count, 3)
        XCTAssertEqual(awake.components(separatedBy: "\n").filter { !$0.isEmpty }.count, 1)
    }

    func testSleepStageMapping() {
        let stagesJSON = """
        [{"start":0,"end":300,"stage":"light"},{"start":300,"end":600,"stage":"wake"}]
        """
        let session = CachedSleepSession(startTs: 0, endTs: 600, efficiency: 0.9,
                                          restingHr: 55, avgHrv: 42, stagesJSON: stagesJSON)
        let (sleeping, awake) = ShortcutNdjsonExport.renderSleep(sessions: [session], timeZone: utc)
        XCTAssertTrue(sleeping.contains("\"stage\":\"light\""))
        XCTAssertTrue(awake.contains("\"stage\":\"wake\""))
    }

    func testSleepWakeOnlySession() {
        let stagesJSON = """
        [{"start":0,"end":300,"stage":"wake"}]
        """
        let session = CachedSleepSession(startTs: 0, endTs: 300, efficiency: 0.5,
                                          restingHr: nil, avgHrv: nil, stagesJSON: stagesJSON)
        let (sleeping, awake) = ShortcutNdjsonExport.renderSleep(sessions: [session], timeZone: utc)
        XCTAssertEqual(sleeping, "")
        XCTAssertTrue(awake.contains("\"stage\":\"wake\""))
    }

    func testSleepNilStagesJSONSkipped() {
        let session = CachedSleepSession(startTs: 0, endTs: 300, efficiency: 0.5,
                                          restingHr: nil, avgHrv: nil, stagesJSON: nil)
        let (sleeping, awake) = ShortcutNdjsonExport.renderSleep(sessions: [session], timeZone: utc)
        XCTAssertEqual(sleeping, "")
        XCTAssertEqual(awake, "")
    }

    func testSleepInvalidJSONSkipped() {
        let session = CachedSleepSession(startTs: 0, endTs: 300, efficiency: 0.5,
                                          restingHr: nil, avgHrv: nil, stagesJSON: "not json")
        let (sleeping, awake) = ShortcutNdjsonExport.renderSleep(sessions: [session], timeZone: utc)
        XCTAssertEqual(sleeping, "")
        XCTAssertEqual(awake, "")
    }

    func testSleepDictFormatSession() {
        // WHOOP CSV import stores stages as a duration-dict {"light":200,"deep":80,"rem":60,"awake":30}
        let stagesJSON = "{\"light\":5,\"deep\":3,\"rem\":2,\"awake\":1}"
        // startTs=0, endTs=3600 (60 minutes window; dict totals 11 minutes, fits within)
        let session = CachedSleepSession(startTs: 0, endTs: 3600, efficiency: 0.85,
                                          restingHr: 50, avgHrv: 55, stagesJSON: stagesJSON)
        let (sleeping, awake) = ShortcutNdjsonExport.renderSleep(sessions: [session], timeZone: utc)
        // awake(1min) → 1 awake line; light+deep+rem → 3 sleeping lines
        XCTAssertEqual(sleeping.components(separatedBy: "\n").filter { !$0.isEmpty }.count, 3)
        XCTAssertEqual(awake.components(separatedBy: "\n").filter { !$0.isEmpty }.count, 1)
        XCTAssertTrue(awake.contains("\"stage\":\"awake\""))
        XCTAssertTrue(sleeping.contains("\"stage\":\"light\""))
        XCTAssertTrue(sleeping.contains("\"stage\":\"deep\""))
        XCTAssertTrue(sleeping.contains("\"stage\":\"rem\""))
    }

    func testSleepDictFormatPartialStages() {
        // Dict with only light and awake — deep and rem default to 0
        let stagesJSON = "{\"light\":10,\"awake\":2}"
        let session = CachedSleepSession(startTs: 0, endTs: 3600, efficiency: 0.8,
                                          restingHr: nil, avgHrv: nil, stagesJSON: stagesJSON)
        let (sleeping, awake) = ShortcutNdjsonExport.renderSleep(sessions: [session], timeZone: utc)
        XCTAssertEqual(sleeping.components(separatedBy: "\n").filter { !$0.isEmpty }.count, 1)
        XCTAssertTrue(sleeping.contains("\"stage\":\"light\""))
        XCTAssertEqual(awake.components(separatedBy: "\n").filter { !$0.isEmpty }.count, 1)
        XCTAssertTrue(awake.contains("\"stage\":\"awake\""))
    }

    func testSleepDictFormatZeroMinutes() {
        // All zero durations → empty output
        let stagesJSON = "{\"light\":0,\"deep\":0,\"rem\":0,\"awake\":0}"
        let session = CachedSleepSession(startTs: 0, endTs: 3600, efficiency: 0.5,
                                          restingHr: nil, avgHrv: nil, stagesJSON: stagesJSON)
        let (sleeping, awake) = ShortcutNdjsonExport.renderSleep(sessions: [session], timeZone: utc)
        XCTAssertEqual(sleeping, "")
        XCTAssertEqual(awake, "")
    }

    func testExportIncludesComputedSleepSessions() async throws {
        // Strap-only users have all sleep under computedId ("my-whoop-noop").
        // The export must query computedIds for sleep, not just importedIds.
        let computedStages = """
        [{"start":0,"end":300,"stage":"light"},{"start":300,"end":600,"stage":"deep"},{"start":600,"end":900,"stage":"wake"}]
        """
        let computedSession = CachedSleepSession(startTs: 0, endTs: 900, efficiency: 0.85,
                                                  restingHr: 55, avgHrv: 42, stagesJSON: computedStages)
        let source = FakeNdjsonReads(
            sleepSessionsByDevice: ["my-whoop-noop": [computedSession]]
        )
        let outcome = await ShortcutNdjsonExport.export(
            source: source,
            importedIds: ["my-whoop"],
            computedIds: ["my-whoop-noop"],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        guard case .written = outcome else {
            return XCTFail("expected .written, got \(outcome)")
        }
        let sleeping = try readFile("sleep_sleeping.txt")
        let awake = try readFile("sleep_awake.txt")
        // light + deep → sleeping, wake → awake
        XCTAssertEqual(sleeping.components(separatedBy: "\n").filter { !$0.isEmpty }.count, 2)
        XCTAssertEqual(awake.components(separatedBy: "\n").filter { !$0.isEmpty }.count, 1)
    }

    func testExportImportedSleepWinsOverComputed() async throws {
        // When both imported and computed exist for the same startTs, imported wins.
        let importedStages = """
        [{"start":100,"end":400,"stage":"light"},{"start":400,"end":700,"stage":"rem"}]
        """
        let computedStages = """
        [{"start":100,"end":400,"stage":"deep"},{"start":400,"end":700,"stage":"wake"}]
        """
        let imported = CachedSleepSession(startTs: 100, endTs: 700, efficiency: 0.9,
                                           restingHr: 50, avgHrv: 50, stagesJSON: importedStages)
        let computed = CachedSleepSession(startTs: 100, endTs: 700, efficiency: 0.8,
                                           restingHr: 55, avgHrv: 45, stagesJSON: computedStages)
        let source = FakeNdjsonReads(
            sleepSessionsByDevice: ["my-whoop": [imported], "my-whoop-noop": [computed]]
        )
        let outcome = await ShortcutNdjsonExport.export(
            source: source,
            importedIds: ["my-whoop"],
            computedIds: ["my-whoop-noop"],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        guard case .written = outcome else {
            return XCTFail("expected .written, got \(outcome)")
        }
        let sleeping = try readFile("sleep_sleeping.txt")
        // Imported session has light+rem → 2 sleeping lines; computed (deep+wake) should NOT appear
        XCTAssertEqual(sleeping.components(separatedBy: "\n").filter { !$0.isEmpty }.count, 2)
        XCTAssertTrue(sleeping.contains("\"stage\":\"light\""))
        XCTAssertTrue(sleeping.contains("\"stage\":\"rem\""))
        XCTAssertFalse(sleeping.contains("\"stage\":\"deep\""))
    }

    // MARK: - Workout rendering

    func testWorkoutKnownSportType() {
        let workout = WorkoutRow(startTs: 0, endTs: 1800, sport: "Running", source: "strap",
                                  durationS: 1800, energyKcal: 350, avgHr: 145, maxHr: 175,
                                  strain: 12.5, distanceM: 5000, zonesJSON: nil, notes: nil)
        let text = ShortcutNdjsonExport.renderWorkouts(workouts: [workout], timeZone: utc)
        XCTAssertTrue(text.contains("\"sport\":\"Running\""))
        XCTAssertTrue(text.contains("\"apple_type\":\"Running\""))
        XCTAssertTrue(text.contains("\"duration_s\":1800.0"))
        XCTAssertTrue(text.contains("\"calories\":350.0"))
        XCTAssertTrue(text.contains("\"avg_hr\":145"))
        XCTAssertTrue(text.contains("\"max_hr\":175"))
        XCTAssertTrue(text.contains("\"distance_m\":5000.0"))
    }

    func testWorkoutUnknownSportType() {
        let workout = WorkoutRow(startTs: 0, endTs: 900, sport: "RockClimbing", source: "strap",
                                  durationS: 900, energyKcal: 200, avgHr: 120, maxHr: 150,
                                  strain: 8.0, distanceM: nil, zonesJSON: nil, notes: nil)
        let text = ShortcutNdjsonExport.renderWorkouts(workouts: [workout], timeZone: utc)
        XCTAssertTrue(text.contains("\"apple_type\":\"Other\""))
    }

    func testWorkoutMissingFieldsRendersZero() {
        let workout = WorkoutRow(startTs: 0, endTs: 900, sport: "Yoga", source: "strap",
                                  durationS: nil, energyKcal: nil, avgHr: nil, maxHr: nil,
                                  strain: nil, distanceM: nil, zonesJSON: nil, notes: nil)
        let text = ShortcutNdjsonExport.renderWorkouts(workouts: [workout], timeZone: utc)
        XCTAssertTrue(text.contains("\"duration_s\":0"))
        XCTAssertTrue(text.contains("\"calories\":0"))
        XCTAssertTrue(text.contains("\"avg_hr\":0"))
        XCTAssertTrue(text.contains("\"max_hr\":0"))
        XCTAssertTrue(text.contains("\"distance_m\":0"))
    }

    // MARK: - Daily metrics rendering

    func testDailyMetricsCalorieDedup() {
        let metric = DailyMetric(day: "2026-07-16", totalSleepMin: 480, efficiency: 0.95,
                                  deepMin: 60, remMin: 90, lightMin: 330, disturbances: 2,
                                  restingHr: 43, avgHrv: 87, recovery: 78.5, strain: 45.32,
                                  exerciseCount: 1, activeKcalEst: 2500)
        let workout = WorkoutRow(startTs: 1784180132, endTs: 1784181536, sport: "Strength",
                                  source: "strap", durationS: 1404, energyKcal: 290.5,
                                  avgHr: 134, maxHr: 174, strain: nil, distanceM: nil,
                                  zonesJSON: nil, notes: nil)
        // Workout starts 2026-07-16T05:35:32 UTC → day key "2026-07-16"
        // active_kcal after dedup: 2500 - 290.5 = 2209.5
        let text = ShortcutNdjsonExport.renderDailyMetrics(metrics: [metric],
                                                            workouts: [workout], timeZone: utc)
        XCTAssertTrue(text.contains("\"active_kcal\":2209.5"))
    }

    func testDailyMetricsDayOnlyRowSkipped() {
        // A metric with only a day and no actual metrics should still render
        // (it has resting_hr:0 and other defaults — the plan says skip only empty dict,
        // but DailyMetric always has the day field so count > 1 is always true).
        let metric = DailyMetric(day: "2026-07-16", totalSleepMin: nil, efficiency: nil,
                                  deepMin: nil, remMin: nil, lightMin: nil, disturbances: nil,
                                  restingHr: nil, avgHrv: nil, recovery: nil, strain: nil,
                                  exerciseCount: nil)
        let text = ShortcutNdjsonExport.renderDailyMetrics(metrics: [metric],
                                                            workouts: [], timeZone: utc)
        XCTAssertTrue(text.contains("\"day\":\"2026-07-16\""))
        XCTAssertTrue(text.contains("\"resting_hr\":0"))
    }

    func testDailyMetricsExcludesToday() {
        let today = ShortcutNdjsonExport.formatDayKey(Date(), timeZone: utc)
        let metric = DailyMetric(day: today, totalSleepMin: 480, efficiency: 0.95,
                                  deepMin: 60, remMin: 90, lightMin: 330, disturbances: 2,
                                  restingHr: 43, avgHrv: 87, recovery: 78.5, strain: 45.32,
                                  exerciseCount: 1)
        let text = ShortcutNdjsonExport.renderDailyMetrics(metrics: [metric],
                                                            workouts: [], timeZone: utc)
        XCTAssertEqual(text, "")
    }

    func testDailyMetricsAllFieldsRounded() {
        let metric = DailyMetric(day: "2026-07-16", totalSleepMin: 486.54, efficiency: 0.95,
                                  deepMin: 60, remMin: 90, lightMin: 330, disturbances: 2,
                                  restingHr: 43, avgHrv: 87.04, recovery: 78.56, strain: 45.321,
                                  exerciseCount: 1, respRateBpm: 14.12, activeKcalEst: 2127.89)
        let text = ShortcutNdjsonExport.renderDailyMetrics(metrics: [metric],
                                                            workouts: [], timeZone: utc)
        XCTAssertTrue(text.contains("\"resting_hr\":43"))
        XCTAssertTrue(text.contains("\"avg_hrv_ms\":87.0"))
        XCTAssertTrue(text.contains("\"resp_rate\":14.1"))
        XCTAssertTrue(text.contains("\"active_kcal\":2127.9"))
        XCTAssertTrue(text.contains("\"total_sleep_min\":486.5"))
        XCTAssertTrue(text.contains("\"recovery_pct\":78.6"))
        XCTAssertTrue(text.contains("\"strain\":45.32"))
    }

    // MARK: - Summary rendering

    func testSummaryNormal() {
        let counts = ["heart_rate": 105, "hrv": 102, "workouts": 2]
        let text = ShortcutNdjsonExport.renderSummary(recordCounts: counts, lookbackDays: "90")
        XCTAssertTrue(text.contains("\"lookback_days\":\"90\""))
        XCTAssertTrue(text.contains("\"heart_rate\":105"))
        XCTAssertTrue(text.contains("\"hrv\":102"))
        XCTAssertTrue(text.contains("\"workouts\":2"))
        XCTAssertTrue(text.hasSuffix("\n"))
    }

    func testSummaryEmptyRecordCounts() {
        let text = ShortcutNdjsonExport.renderSummary(recordCounts: [:], lookbackDays: "7")
        XCTAssertTrue(text.contains("\"record_counts\":{}"))
    }

    // MARK: - Coverage span

    func testCoverageSpanFirstRun() {
        let span = ShortcutNdjsonExport.coverageSpan(nowTs: 2_000_000_700, watermark: 0, lookbackDays: 90)
        XCTAssertEqual(span.from, 2_000_000_700 - 90 * 86_400)
        XCTAssertEqual(span.end, (2_000_000_700 / 900) * 900)
    }

    func testCoverageSpanSubsequentRun() {
        let span = ShortcutNdjsonExport.coverageSpan(nowTs: 1_000_000, watermark: 999_000, lookbackDays: 90)
        XCTAssertEqual(span.from, 999_000)
        XCTAssertEqual(span.end, 999_900)
    }

    func testCoverageSpanNothingNew() {
        let span = ShortcutNdjsonExport.coverageSpan(nowTs: 10_000, watermark: 9_900, lookbackDays: 7)
        // nowTs=10000, watermark=9900, lookback=7*86400=604800
        // from = max(9900, 10000-604800) = 9900
        // end = (10000/900)*900 = 9900
        // from == end → nothing new
        XCTAssertEqual(span.from, 9900)
        XCTAssertEqual(span.end, 9900)
    }

    func testCoverageSpanExcludesOpenWindow() {
        let span = ShortcutNdjsonExport.coverageSpan(nowTs: 2699, watermark: 0, lookbackDays: 90)
        XCTAssertEqual(span.from, 0)
        XCTAssertEqual(span.end, 1800)
    }

    // MARK: - Export + watermark

    func testExportWritesFilesAndAdvancesWatermark() async throws {
        let source = FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 62)])
        let outcome = await ShortcutNdjsonExport.export(
            source: source,
            importedIds: ["dev"],
            computedIds: [],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        guard case .written(let fileCount, _) = outcome else {
            return XCTFail("expected .written, got \(outcome)")
        }
        XCTAssertEqual(fileCount, 7)
        let hr = try readFile("heart_rate.txt")
        XCTAssertTrue(hr.contains("\"bpm\":62"))
        XCTAssertEqual(defaults.integer(forKey: ShortcutNdjsonExport.watermarkKey), 9_900)
    }

    func testExportNothingNewTruncatesByDefault() async throws {
        // Write some data first
        _ = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 62)]),
            importedIds: ["dev"],
            computedIds: [],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        let hr = try readFile("heart_rate.txt")
        XCTAssertFalse(hr.isEmpty)

        // Normal background export (skipNextTruncation = false) → truncate all 7 files
        ShortcutNdjsonExport.skipNextTruncation = false
        let second = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 62)]),
            importedIds: ["dev"],
            computedIds: [],
            now: Date(timeIntervalSince1970: 10_060),
            defaults: defaults, directory: dir, timeZone: utc)
        XCTAssertEqual(second, .nothingNew)
        for name in ShortcutNdjsonExport.allFileNames {
            let content = try readFile(name)
            XCTAssertEqual(content, "", "file \(name) must be truncated on nothingNew (#167)")
        }
    }

    func testExportNothingNewPreservesFilesAfterForeground() async throws {
        // Write some data first (simulating foreground export)
        ShortcutNdjsonExport.skipNextTruncation = true
        _ = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 62)]),
            importedIds: ["dev"],
            computedIds: [],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        let hr = try readFile("heart_rate.txt")
        XCTAssertFalse(hr.isEmpty)

        // Background export after foreground (skipNextTruncation true) → preserve files + consume flag
        let second = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 62)]),
            importedIds: ["dev"],
            computedIds: [],
            now: Date(timeIntervalSince1970: 10_060),
            defaults: defaults, directory: dir, timeZone: utc)
        XCTAssertEqual(second, .nothingNew)
        let hrAfter = try readFile("heart_rate.txt")
        XCTAssertFalse(hrAfter.isEmpty, "file must preserve data after foreground export")
        XCTAssertFalse(ShortcutNdjsonExport.skipNextTruncation,
                       "flag must be consumed after skipping truncation")

        // Next nothing-new export should truncate (flag consumed)
        let third = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 62)]),
            importedIds: ["dev"],
            computedIds: [],
            now: Date(timeIntervalSince1970: 10_060),
            defaults: defaults, directory: dir, timeZone: utc)
        XCTAssertEqual(third, .nothingNew)
        for name in ShortcutNdjsonExport.allFileNames {
            let content = try readFile(name)
            XCTAssertEqual(content, "", "file \(name) must truncate after flag is consumed")
        }
    }

    func testForceReExportThenBackgroundPreservesThenNextTruncates() async throws {
        // Simulate the full user flow: initial export → force re-export → background → next background

        // Step 1: Normal export (simulates background writing data)
        _ = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 62)]),
            importedIds: ["dev"], computedIds: [],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        let hr1 = try readFile("heart_rate.txt")
        XCTAssertFalse(hr1.isEmpty)
        XCTAssertEqual(defaults.integer(forKey: ShortcutNdjsonExport.watermarkKey), 9_900)

        // Step 2: User taps "Force full re-export" — reset watermark + set flag + write
        defaults.removeObject(forKey: ShortcutNdjsonExport.watermarkKey)
        ShortcutNdjsonExport.skipNextTruncation = true
        _ = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 70)]),
            importedIds: ["dev"], computedIds: [],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        let hr2 = try readFile("heart_rate.txt")
        XCTAssertTrue(hr2.contains("\"bpm\":70"), "force re-export must write fresh data")
        XCTAssertTrue(ShortcutNdjsonExport.skipNextTruncation,
                       "flag should still be true (not on nothing-new path)")

        // Step 3: Background transition — nothing new, but flag is set → preserve files
        let bg1 = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 70)]),
            importedIds: ["dev"], computedIds: [],
            now: Date(timeIntervalSince1970: 10_060),
            defaults: defaults, directory: dir, timeZone: utc)
        XCTAssertEqual(bg1, .nothingNew)
        let hr3 = try readFile("heart_rate.txt")
        XCTAssertFalse(hr3.isEmpty, "first background after force re-export must preserve files")
        XCTAssertFalse(ShortcutNdjsonExport.skipNextTruncation,
                       "flag must be consumed after preserving")

        // Step 4: Next background transition — nothing new, flag consumed → truncate (#167)
        let bg2 = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 70)]),
            importedIds: ["dev"], computedIds: [],
            now: Date(timeIntervalSince1970: 10_060),
            defaults: defaults, directory: dir, timeZone: utc)
        XCTAssertEqual(bg2, .nothingNew)
        for name in ShortcutNdjsonExport.allFileNames {
            let content = try readFile(name)
            XCTAssertEqual(content, "", "file \(name) must truncate on second nothing-new export")
        }
    }

    func testExportFailureLeavesWatermark() async {
        let source = FakeNdjsonReads(error: FakeNdjsonReads.Boom())
        let outcome = await ShortcutNdjsonExport.export(
            source: source,
            importedIds: ["dev"],
            computedIds: [],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        guard case .failure = outcome else { return XCTFail("expected .failure, got \(outcome)") }
        XCTAssertEqual(defaults.integer(forKey: ShortcutNdjsonExport.watermarkKey), 0)
    }

    func testForceFullExportResetsWatermarkAndWritesFiles() async throws {
        // Pre-existing data with a watermark set
        _ = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 62)]),
            importedIds: ["dev"], computedIds: [],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        XCTAssertEqual(defaults.integer(forKey: ShortcutNdjsonExport.watermarkKey), 9_900)

        // Simulate forceFullExport inline (can't call the real method without a Repository)
        ShortcutNdjsonExport.resetWatermark(defaults: defaults)
        ShortcutNdjsonExport.skipNextTruncation = true
        XCTAssertEqual(defaults.integer(forKey: ShortcutNdjsonExport.watermarkKey), 0)
        XCTAssertTrue(ShortcutNdjsonExport.skipNextTruncation)

        let outcome = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 70)]),
            importedIds: ["dev"], computedIds: [],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        guard case .written = outcome else {
            return XCTFail("expected .written, got \(outcome)")
        }
        let hr = try readFile("heart_rate.txt")
        XCTAssertTrue(hr.contains("\"bpm\":70"), "force export must write fresh data")
        // Watermark advanced, skip flag still set (export had data, not nothingNew)
        XCTAssertEqual(defaults.integer(forKey: ShortcutNdjsonExport.watermarkKey), 9_900)
        XCTAssertTrue(ShortcutNdjsonExport.skipNextTruncation)
    }

    func testSkipTruncationFlagSurvivesAcrossInstances() async throws {
        // Simulate: force export sets the flag, app "kills" (new static var would lose it),
        // but UserDefaults preserves it.
        ShortcutNdjsonExport.skipNextTruncation = true

        // Write files with skip flag
        _ = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 62)]),
            importedIds: ["dev"], computedIds: [],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)

        // Nothing-new export should preserve files (flag persisted in UserDefaults)
        let result = await ShortcutNdjsonExport.export(
            source: FakeNdjsonReads(hr: [HRBucket(ts: 0, bpm: 62)]),
            importedIds: ["dev"], computedIds: [],
            now: Date(timeIntervalSince1970: 10_060),
            defaults: defaults, directory: dir, timeZone: utc)
        XCTAssertEqual(result, .nothingNew)
        let hr = try readFile("heart_rate.txt")
        XCTAssertFalse(hr.isEmpty, "persisted skip flag must prevent truncation")
    }

    // MARK: - Multi-deviceId union

    func testExportUnionMultipleDeviceIds() async throws {
        let source = FakeNdjsonReads(
            hr: [HRBucket(ts: 0, bpm: 62)],
            workoutsData: [WorkoutRow(startTs: 0, endTs: 900, sport: "Running", source: "strap",
                                   durationS: 900, energyKcal: 300, avgHr: 140, maxHr: 170,
                                   strain: 10, distanceM: 5000, zonesJSON: nil, notes: nil)]
        )
        let outcome = await ShortcutNdjsonExport.export(
            source: source,
            importedIds: ["dev1", "dev2"],
            computedIds: ["dev1-noop"],
            now: Date(timeIntervalSince1970: 10_000),
            defaults: defaults, directory: dir, timeZone: utc)
        guard case .written(let fileCount, _) = outcome else {
            return XCTFail("expected .written, got \(outcome)")
        }
        XCTAssertEqual(fileCount, 7)
    }

    // MARK: - Timestamp formatting

    func testTimestampFormat() {
        // 1970-01-01T00:15:00 in UTC
        XCTAssertEqual(ShortcutNdjsonExport.formatTimestamp(900, timeZone: utc),
                        "1970-01-01T00:15:00")
    }

    func testTimestampTimezoneShift() {
        let tz = TimeZone(secondsFromGMT: 3600)!
        XCTAssertEqual(ShortcutNdjsonExport.formatTimestamp(900, timeZone: tz),
                        "1970-01-01T01:15:00")
    }

    func testDayKeyFormatting() {
        let date = Date(timeIntervalSince1970: 0)
        XCTAssertEqual(ShortcutNdjsonExport.formatDayKey(date, timeZone: utc), "1970-01-01")
    }
}
