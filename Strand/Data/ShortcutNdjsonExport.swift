import Foundation
import WhoopStore
import WhoopProtocol
import StrandAnalytics

/// NDJSON export for sideloaded iOS installs that can't use HealthKit directly. Produces 7 `.txt`
/// files (one per data category) in NDJSON format, compatible with iOS Shortcuts that import the
/// rows into Apple Health. Watermark-based delta export with anti-re-import truncation and
/// background trigger.
///
/// Platform-neutral enum in Strand/Data/ (no UIKit/AppKit). The iOS-only pieces live in StrandiOS/
/// — the scenePhase trigger and the opt-in toggle.
enum ShortcutNdjsonExport {

    // MARK: - Constants

    static let enabledKey = "noop.ndjsonExport.enabled"
    static let watermarkKey = "noop.ndjsonExport.lastExportTs"
    static let lookbackKey = "noop.ndjsonExport.lookbackDays"
    static let outputFolderKey = "noop.ndjsonExport.outputFolder"
    static let skipTruncationKey = "noop.ndjsonExport.skipNextTruncation"
    static let windowSeconds = 900
    static let readLimit = 2_000_000

    static let allFileNames = [
        "heart_rate.txt", "hrv.txt", "sleep_sleeping.txt", "sleep_awake.txt",
        "workouts.txt", "daily_metrics.txt", "summary.txt",
    ]

    /// Backed by UserDefaults so the flag survives app kills and the force-export→background race.
    /// Set by `forceFullExport` so the next background `writeIfEnabled` skips truncation,
    /// preserving the fresh foreground export for the Shortcut automation to read.
    static var skipNextTruncation: Bool {
        get { UserDefaults.standard.bool(forKey: skipTruncationKey) }
        set { UserDefaults.standard.set(newValue, forKey: skipTruncationKey) }
    }

    enum Outcome: Equatable {
        case written(fileCount: Int, recordCounts: [String: Int])
        case nothingNew
        case failure(String)
    }

    // MARK: - Data types

    struct SleepStage: Codable, Equatable {
        let start: Int
        let end: Int
        let stage: String
    }

    // MARK: - Sport mapping (NOOP sport → Apple Health workout type)

    static let sportMapping: [String: String] = [
        "Running": "Running",
        "Walking": "Walking",
        "Cycling": "Cycling",
        "Swimming": "Swimming",
        "FunctionalStrengthTraining": "Functional Strength Training",
        "Strength": "Functional Strength Training",
        "Yoga": "Yoga",
        "Hiking": "Hiking",
        "Rowing": "Rowing",
        "CrossTraining": "Cross Training",
        "Flexibility": "Flexibility",
        "FitnessGaming": "Fitness Gaming",
        "PreparationAndRecovery": "Preparation and Recovery",
        "WaterFitness": "Water Fitness",
        "Soccer": "Soccer",
        "TraditionalStrengthTraining": "Traditional Strength Training",
        "Other": "Other",
        "Workout": "Other",
    ]

    // MARK: - Entry points

    @MainActor
    static func writeIfEnabled(repo: Repository) async {
        migrateFromCsvIfNeeded()
        guard UserDefaults.standard.bool(forKey: enabledKey) else { return }
        _ = await writeNow(repo: repo)
    }

    /// One-time migration: if the old CSV export was enabled, auto-enable NDJSON and clear the
    /// legacy keys so the user doesn't have to re-toggle after the update.
    private static func migrateFromCsvIfNeeded() {
        let defaults = UserDefaults.standard
        let legacyKey = "noop.shortcutSync.enabled"
        guard defaults.bool(forKey: legacyKey),
              !defaults.bool(forKey: enabledKey) else { return }
        defaults.set(true, forKey: enabledKey)
        defaults.removeObject(forKey: legacyKey)
        defaults.removeObject(forKey: "noop.shortcutSync.lastExportTs")
    }

    @MainActor
    @discardableResult
    static func writeNow(repo: Repository) async -> Outcome {
        guard let store = await repo.storeHandle() else {
            return .failure("Couldn't open the local store.")
        }
        let directory = resolveOutputFolder()
            ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return await export(source: store,
                            importedIds: repo.importedReadIds,
                            computedIds: repo.computedReadIds,
                            now: Date(),
                            defaults: .standard,
                            directory: directory,
                            timeZone: .current)
    }

    /// Atomic forced full re-export: resets watermark and sets skip-truncation flag in a single
    /// awaited call, preventing the race where the background `writeIfEnabled` interleaves and
    /// sees watermark=0 before the force export finishes.
    @MainActor
    @discardableResult
    static func forceFullExport(repo: Repository) async -> Outcome {
        resetWatermark()
        skipNextTruncation = true
        return await writeNow(repo: repo)
    }

    // MARK: - Core export

    @discardableResult
    static func export(source: NdjsonExportReads,
                       importedIds: [String],
                       computedIds: [String],
                       now: Date,
                       defaults: UserDefaults,
                       directory: URL,
                       timeZone: TimeZone) async -> Outcome {
        let nowTs = Int(now.timeIntervalSince1970)
        let lookbackDays = max(defaults.integer(forKey: lookbackKey), 7)
        let watermark = defaults.integer(forKey: watermarkKey)
        let span = coverageSpan(nowTs: nowTs, watermark: watermark, lookbackDays: lookbackDays)

        guard span.from < span.end else {
            if !Self.skipNextTruncation {
                for name in allFileNames {
                    try? Data().write(to: directory.appendingPathComponent(name), options: .atomic)
                }
            } else {
                Self.skipNextTruncation = false
            }
            return .nothingNew
        }

        func unionQuery<T>(_ ids: [String], _ query: (String) async throws -> [T]) async throws -> [T] {
            var results: [T] = []
            for id in ids {
                results += try await query(id)
            }
            return results
        }

        do {
            let hrBuckets = try await unionQuery(importedIds) { id in
                try await source.hrBuckets(deviceId: id, from: span.from, to: span.end - 1, bucketSeconds: windowSeconds)
            }
            let rrIntervals = try await unionQuery(importedIds) { id in
                try await source.rrIntervals(deviceId: id, from: span.from, to: span.end - 1, limit: readLimit)
            }
            // Sleep: imported first (wins per-session), computed fills gaps
            var sleepSessions: [CachedSleepSession] = []
            for id in importedIds {
                sleepSessions += try await source.sleepSessions(deviceId: id, from: span.from, to: span.end - 1, limit: readLimit)
            }
            let importedStarts = Set(sleepSessions.map(\.startTs))
            for id in computedIds {
                let computed = try await source.sleepSessions(deviceId: id, from: span.from, to: span.end - 1, limit: readLimit)
                sleepSessions += computed.filter { !importedStarts.contains($0.startTs) }
            }
            let workouts = try await unionQuery(importedIds) { id in
                try await source.workouts(deviceId: id, from: span.from, to: span.end - 1, limit: readLimit)
            }

            let todayKey = formatDayKey(now, timeZone: timeZone)
            let fromKey = formatDayKey(Date(timeIntervalSince1970: TimeInterval(span.from)), timeZone: timeZone)

            // DailyMetrics: imported first (wins per-day), computed fills gaps
            var metrics: [DailyMetric] = []
            for id in importedIds {
                metrics += try await source.dailyMetrics(deviceId: id, from: fromKey, to: todayKey)
            }
            for id in computedIds {
                let computed = try await source.dailyMetrics(deviceId: id, from: fromKey, to: todayKey)
                let importedDays = Set(metrics.map(\.day))
                metrics += computed.filter { !importedDays.contains($0.day) }
            }

            let hrContent = renderHeartRate(buckets: hrBuckets, timeZone: timeZone)
            let hrvContent = renderHrv(rrIntervals: rrIntervals, timeZone: timeZone)
            let (sleeping, awake) = renderSleep(sessions: sleepSessions, timeZone: timeZone)
            let workoutsContent = renderWorkouts(workouts: workouts, timeZone: timeZone)
            let metricsContent = renderDailyMetrics(metrics: metrics, workouts: workouts, timeZone: timeZone)

            let hrvRecordCount = countHrvRecords(rrIntervals: rrIntervals)
            var recordCounts: [String: Int] = [:]
            recordCounts["heart_rate"] = hrBuckets.filter { $0.bpm > 0 }.count
            recordCounts["hrv"] = hrvRecordCount
            recordCounts["sleep_sleeping"] = newlineCount(sleeping)
            recordCounts["sleep_awake"] = newlineCount(awake)
            recordCounts["workouts"] = workouts.count
            recordCounts["daily_metrics"] = metrics.filter { $0.day < todayKey }.count
            let summaryContent = renderSummary(recordCounts: recordCounts,
                                               lookbackDays: String(lookbackDays))

            let files: [(String, String)] = [
                ("heart_rate.txt", hrContent),
                ("hrv.txt", hrvContent),
                ("sleep_sleeping.txt", sleeping),
                ("sleep_awake.txt", awake),
                ("workouts.txt", workoutsContent),
                ("daily_metrics.txt", metricsContent),
                ("summary.txt", summaryContent),
            ]
            for (name, content) in files {
                try Data(content.utf8).write(to: directory.appendingPathComponent(name), options: .atomic)
            }

            defaults.set(span.end, forKey: watermarkKey)
            return .written(fileCount: files.count, recordCounts: recordCounts)
        } catch {
            return .failure("NDJSON export failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Coverage span

    static func coverageSpan(nowTs: Int, watermark: Int, lookbackDays: Int) -> (from: Int, end: Int) {
        let from = max(watermark, nowTs - (lookbackDays * 86_400))
        let end = (nowTs / windowSeconds) * windowSeconds
        return (from, end)
    }

    // MARK: - Heart rate renderer

    static func renderHeartRate(buckets: [HRBucket], timeZone: TimeZone) -> String {
        var lines: [String] = []
        for bucket in buckets where bucket.bpm > 0 {
            let ts = formatTimestamp(bucket.ts, timeZone: timeZone)
            let bpm = Int(bucket.bpm.rounded())
            lines.append("{\"ts\":\"\(ts)\",\"bpm\":\(bpm)}")
        }
        return lines.joined(separator: "\n") + (lines.isEmpty ? "" : "\n")
    }

    // MARK: - HRV renderer (SDNN via HRVAnalyzer)

    static func renderHrv(rrIntervals: [RRInterval], timeZone: TimeZone) -> String {
        var rrByWindow: [Int: [Double]] = [:]
        for rr in rrIntervals where rr.ts < Int.max {
            let windowStart = (rr.ts / windowSeconds) * windowSeconds
            rrByWindow[windowStart, default: []].append(Double(rr.rrMs))
        }

        var lines: [String] = []
        for (windowStart, rrs) in rrByWindow.sorted(by: { $0.key < $1.key }) {
            let result = HRVAnalyzer.analyze(rawRR: rrs)
            guard let sdnn = result.sdnn else { continue }
            let ts = formatTimestamp(windowStart, timeZone: timeZone)
            lines.append("{\"ts\":\"\(ts)\",\"sdnn_ms\":\(String(format: "%.1f", sdnn))}")
        }
        return lines.joined(separator: "\n") + (lines.isEmpty ? "" : "\n")
    }

    /// Count how many 15-min windows produce a valid SDNN — mirrors renderHrv logic.
    static func countHrvRecords(rrIntervals: [RRInterval]) -> Int {
        var rrByWindow: [Int: [Double]] = [:]
        for rr in rrIntervals where rr.ts < Int.max {
            let windowStart = (rr.ts / windowSeconds) * windowSeconds
            rrByWindow[windowStart, default: []].append(Double(rr.rrMs))
        }
        var count = 0
        for (_, rrs) in rrByWindow {
            if HRVAnalyzer.analyze(rawRR: rrs).sdnn != nil { count += 1 }
        }
        return count
    }

    // MARK: - Sleep renderer

    static func renderSleep(sessions: [CachedSleepSession], timeZone: TimeZone)
        -> (sleeping: String, awake: String) {
        var sleepingLines: [String] = []
        var awakeLines: [String] = []

        for session in sessions {
            guard let stagesJSON = session.stagesJSON,
                  let data = stagesJSON.data(using: .utf8) else {
                continue
            }

            if let stages = try? JSONDecoder().decode([SleepStage].self, from: data) {
                // Segment array format: [{"start":epoch,"end":epoch,"stage":"light"},…]
                for stage in stages {
                    let start = formatTimestamp(stage.start, timeZone: timeZone)
                    let end = formatTimestamp(stage.end, timeZone: timeZone)
                    let line = "{\"start\":\"\(start)\",\"end\":\"\(end)\",\"stage\":\"\(stage.stage)\"}"

                    switch stage.stage {
                    case "light", "deep", "rem":
                        sleepingLines.append(line)
                    case "wake":
                        awakeLines.append(line)
                    default:
                        continue
                    }
                }
            } else if let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                // Duration-dict format from WHOOP CSV import: {"light":200,"deep":80,"rem":60,"awake":30}
                // Synthesize sequential blocks within [session.startTs, session.endTs].
                func dictMinutes(_ key: String) -> Double {
                    if let n = dict[key] as? NSNumber { return n.doubleValue }
                    if let d = dict[key] as? Double { return d }
                    return 0
                }
                let awakeMin = dictMinutes("awake")
                let lightMin = dictMinutes("light")
                let deepMin  = dictMinutes("deep")
                let remMin   = dictMinutes("rem")
                let totalMin = awakeMin + lightMin + deepMin + remMin
                guard totalMin > 0 else { continue }

                let windowStart = session.startTs
                var offset = 0

                for (stageName, durationMin) in [("awake", awakeMin), ("light", lightMin),
                                                  ("deep", deepMin), ("rem", remMin)] {
                    guard durationMin > 0 else { continue }
                    let durationS = Int(durationMin * 60)
                    let segStart = windowStart + offset
                    let segEnd = min(segStart + durationS, session.endTs)
                    offset += durationS

                    let start = formatTimestamp(segStart, timeZone: timeZone)
                    let end = formatTimestamp(segEnd, timeZone: timeZone)
                    let line = "{\"start\":\"\(start)\",\"end\":\"\(end)\",\"stage\":\"\(stageName)\"}"

                    switch stageName {
                    case "light", "deep", "rem":
                        sleepingLines.append(line)
                    case "awake":
                        awakeLines.append(line)
                    default:
                        break
                    }
                }
            }
        }

        return (
            sleeping: sleepingLines.joined(separator: "\n") + (sleepingLines.isEmpty ? "" : "\n"),
            awake: awakeLines.joined(separator: "\n") + (awakeLines.isEmpty ? "" : "\n")
        )
    }

    // MARK: - Workout renderer

    static func renderWorkouts(workouts: [WorkoutRow], timeZone: TimeZone) -> String {
        var lines: [String] = []
        for w in workouts {
            let start = formatTimestamp(w.startTs, timeZone: timeZone)
            let end = formatTimestamp(w.endTs, timeZone: timeZone)
            let appleType = sportMapping[w.sport] ?? "Other"
            let duration = w.durationS.map { String(format: "%.1f", $0) } ?? "0"
            let calories = w.energyKcal.map { String(format: "%.1f", $0) } ?? "0"
            let avgHr = String(w.avgHr ?? 0)
            let maxHr = String(w.maxHr ?? 0)
            let distance = w.distanceM.map { String(format: "%.1f", $0) } ?? "0"
            lines.append(
                "{\"sport\":\"\(w.sport)\",\"apple_type\":\"\(appleType)\",\"start\":\"\(start)\",\"end\":\"\(end)\",\"duration_s\":\(duration),\"calories\":\(calories),\"avg_hr\":\(avgHr),\"max_hr\":\(maxHr),\"distance_m\":\(distance)}"
            )
        }
        return lines.joined(separator: "\n") + (lines.isEmpty ? "" : "\n")
    }

    // MARK: - Daily metrics renderer

    static func renderDailyMetrics(metrics: [DailyMetric], workouts: [WorkoutRow],
                                   timeZone: TimeZone) -> String {
        var workoutKcalByDay: [String: Double] = [:]
        for w in workouts {
            let day = String(formatTimestamp(w.startTs, timeZone: timeZone).prefix(10))
            workoutKcalByDay[day, default: 0] += w.energyKcal ?? 0
        }

        let todayKey = formatDayKey(Date(), timeZone: timeZone)
        var lines: [String] = []
        for m in metrics where m.day < todayKey {
            guard m.day != "" else { continue }
            var activeKcal = m.activeKcalEst ?? 0
            if let workoutKcal = workoutKcalByDay[m.day] {
                activeKcal = max(0, activeKcal - workoutKcal)
            }
            let restingHr = String(m.restingHr ?? 0)
            let avgHrv = m.avgHrv.map { String(format: "%.1f", $0) } ?? "0"
            let respRate = m.respRateBpm.map { String(format: "%.1f", $0) } ?? "0"
            let activeKcalStr = String(format: "%.1f", activeKcal)
            let totalSleep = m.totalSleepMin.map { String(format: "%.1f", $0) } ?? "0"
            let recovery = m.recovery.map { String(format: "%.1f", $0) } ?? "0"
            let strain = m.strain.map { String(format: "%.2f", $0) } ?? "0"
            lines.append(
                "{\"day\":\"\(m.day)\",\"resting_hr\":\(restingHr),\"avg_hrv_ms\":\(avgHrv),\"resp_rate\":\(respRate),\"active_kcal\":\(activeKcalStr),\"total_sleep_min\":\(totalSleep),\"recovery_pct\":\(recovery),\"strain\":\(strain)}"
            )
        }
        return lines.joined(separator: "\n") + (lines.isEmpty ? "" : "\n")
    }

    // MARK: - Summary renderer

    static func renderSummary(recordCounts: [String: Int], lookbackDays: String) -> String {
        let now = isoTimestamp(Date(), timeZone: .current)
        let counts = recordCounts.map { "\"\($0.key)\":\($0.value)" }
            .sorted()
            .joined(separator: ",")
        return "{\"generated\":\"\(now)\",\"lookback_days\":\"\(lookbackDays)\",\"record_counts\":{\(counts)}}\n"
    }

    // MARK: - Output folder bookmark persistence

    static func saveOutputFolder(_ url: URL) {
        #if os(macOS)
        let opts: URL.BookmarkCreationOptions = [.withSecurityScope]
        #else
        let opts: URL.BookmarkCreationOptions = []
        #endif
        let data = try? url.bookmarkData(options: opts,
                                         includingResourceValuesForKeys: nil, relativeTo: nil)
        if let data {
            UserDefaults.standard.set(data, forKey: outputFolderKey)
        }
    }

    static func resolveOutputFolder() -> URL? {
        guard let data = UserDefaults.standard.data(forKey: outputFolderKey) else { return nil }
        var stale = false
        #if os(macOS)
        let opts: URL.BookmarkResolutionOptions = [.withSecurityScope]
        #else
        let opts: URL.BookmarkResolutionOptions = []
        #endif
        let url = try? URL(resolvingBookmarkData: data, options: opts,
                           relativeTo: nil, bookmarkDataIsStale: &stale)
        if stale, let url { saveOutputFolder(url) }
        return url
    }

    static func outputFolderLabel() -> String {
        resolveOutputFolder()?.lastPathComponent ?? "Documents"
    }

    static func resetWatermark(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: watermarkKey)
    }

    // MARK: - Formatting helpers

    private static let timestampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return f
    }()

    private static let dayKeyFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func formatTimestamp(_ ts: Int, timeZone: TimeZone) -> String {
        timestampFormatter.timeZone = timeZone
        return timestampFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(ts)))
    }

    static func isoTimestamp(_ date: Date, timeZone: TimeZone) -> String {
        timestampFormatter.timeZone = timeZone
        return timestampFormatter.string(from: date)
    }

    static func formatDayKey(_ date: Date, timeZone: TimeZone) -> String {
        dayKeyFormatter.timeZone = timeZone
        return dayKeyFormatter.string(from: date)
    }

    static func startOfDay(for date: Date) -> Date {
        Calendar.current.startOfDay(for: date)
    }

    static func newlineCount(_ s: String) -> Int {
        s.isEmpty ? 0 : s.components(separatedBy: "\n").filter { !$0.isEmpty }.count
    }
}

/// The three store reads the base export needs — a seam so the watermark/windowing logic is
/// testable without a live DB. WhoopStore's own methods match the signatures exactly.
protocol ShortcutExportReads {
    func hrBuckets(deviceId: String, from: Int, to: Int, bucketSeconds: Int) async throws -> [HRBucket]
    func rrIntervals(deviceId: String, from: Int, to: Int, limit: Int) async throws -> [RRInterval]
    func stepSamples(deviceId: String, from: Int, to: Int, limit: Int) async throws -> [StepSample]
}

/// Extended store reads for NDJSON export — adds sleep sessions, workouts, and daily metrics
/// to the base `ShortcutExportReads` seam.
protocol NdjsonExportReads: ShortcutExportReads {
    func sleepSessions(deviceId: String, from: Int, to: Int, limit: Int) async throws -> [CachedSleepSession]
    func workouts(deviceId: String, from: Int, to: Int, limit: Int) async throws -> [WorkoutRow]
    func dailyMetrics(deviceId: String, from: String, to: String) async throws -> [DailyMetric]
}

extension WhoopStore: ShortcutExportReads {}
extension WhoopStore: NdjsonExportReads {}
