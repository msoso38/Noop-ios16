// Compiled ONLY when the CLOUD_SYNC compilation condition is set (by the untracked
// OuraSecrets.xcconfig — see OuraConfig.xcconfig). A default build contains none of this code,
// keeping "fully offline" a byte-level property of the shipped binary, not a runtime promise.
#if CLOUD_SYNC
#if os(iOS)
import Foundation
import BackgroundTasks

/// Registers and schedules the CLOUD_SYNC background-refresh `BGAppRefreshTask` (Cloud Sync v2) — the
/// iOS-only counterpart to `CloudSyncModel.autoSyncIfDue`'s on-launch catch-up, giving a backgrounded
/// app a chance to pick up confirmed edits (and re-upload changed data) without waiting for the next
/// foreground launch. Mirrors `ScheduledDebugExport`'s BGTask plumbing
/// (Strand/System/ScheduledDebugExport.swift) closely; the difference is this handler needs a real
/// `Repository`/`IntelligenceEngine` to run an actual sync (wired from the `AppModel` the app entry
/// point supplies), where that one only ever writes a local file.
///
/// HONEST about platform limits, same posture as `ScheduledDebugExport`: iOS decides IF and WHEN a
/// submitted request actually runs — this is a best-effort extra chance to catch up sooner than the
/// guaranteed 20h on-launch catch-up, never a promise of timely background delivery.
@MainActor
enum CloudSyncBackgroundRefresh {
    /// Must also appear in the iOS target's `BGTaskSchedulerPermittedIdentifiers` (project.yml) and be
    /// registered at launch (`register(model:)`, called from `StrandiOSApp.init()`) for `schedule()` to
    /// have any effect — see `ScheduledDebugExport.bgTaskIdentifier`'s doc comment for the identical
    /// contract.
    /// Resolved from the bundle, not hardcoded — same reason as `ScheduledDebugExport.bgTaskIdentifier`:
    /// the permitted entries are namespaced under the bundle id, so a personal-deploy remap silently
    /// stales a literal and takes this whole lane down with it. `BGTaskIdentifier` lives in
    /// `Strand/System/` (NOT here) so that fix stays independent of this fork-only directory.
    static let bgTaskIdentifier = BGTaskIdentifier.resolve(
        suffix: "cloudsync.refresh", upstream: "com.noopapp.noop.cloudsync.refresh")

    /// Register the BGTask handler. MUST be called from the app's launch (before launch finishes),
    /// same constraint as `ScheduledDebugExport.register()`. Safe to leave uncalled, or to have
    /// `schedule()`'s submit fail silently: the sync this handler runs is exactly what `autoSyncIfDue`
    /// already covers on the next foreground launch anyway — this is a strictly additive, best-effort
    /// earlier chance, never a dependency for correctness.
    static func register(model: AppModel) {
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: bgTaskIdentifier, using: nil) { task in
            // The launch handler runs on a queue the system chooses, NOT necessarily the main actor —
            // hop explicitly before touching `CloudSyncModel` (`@MainActor`) or `AppModel`.
            Task { @MainActor in
                handle(task, model: model)
            }
        }
        // Same contract as `ScheduledDebugExport.register()`: a false return means this identifier isn't
        // permitted and the 4-hourly background sync will never be delivered. Never swallow it.
        BGTaskIdentifier.report(bgTaskIdentifier, registered: registered)
    }

    /// Submit a background-refresh request for *no earlier than*
    /// `CloudSyncModel.backgroundSyncIntervalS` from now. Called when the app backgrounds
    /// (`StrandiOSApp`'s `scenePhase` handler) — deliberately NOT at launch/register time, so a user
    /// who keeps the app foregrounded for hours doesn't burn the window before ever backgrounding.
    /// `try?` swallows the "identifier not permitted/registered" error so a build that hasn't wired
    /// Info.plist yet still behaves (mirrors `ScheduledDebugExport.submitBackgroundRequest`).
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: bgTaskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: CloudSyncModel.backgroundSyncIntervalS)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// The delivered task's actual work: wire `CloudSyncModel` exactly like `RootTabView`'s on-launch
    /// catch-up does (same `postApplyRefresh` closure, same `model.intelligence`/`model.repo`), run the
    /// 4h-gated background sync, then unconditionally reschedule the next request and report
    /// completion. Rescheduling happens FIRST, before the sync even starts, so the next window is
    /// guaranteed to be requested whatever happens below — BGAppRefresh is single-shot, and nothing
    /// else will ever submit the next one.
    private static func handle(_ task: BGTask, model: AppModel) {
        schedule()

        let cloudSync = CloudSyncModel()
        let intelligence = model.intelligence
        let repository = model.repo
        cloudSync.postApplyRefresh = {
            await intelligence.analyzeRecent()
            await repository.refresh()
        }

        // Exactly one `task.setTaskCompleted` call per delivered task, whichever finishes first: the
        // sync itself, or iOS's expiration warning. `BGTaskCompletion` is a plain (non-actor-isolated)
        // class precisely so the expiration handler below — invoked by the system on a thread of ITS
        // choosing, not guaranteed to be the main actor — can call it directly with no `await`.
        let completion = BGTaskCompletion(task)
        let work = Task {
            _ = await cloudSync.backgroundSyncIfDue(repo: repository)
            completion.complete(success: true)
        }
        task.expirationHandler = {
            // Cooperative cancellation: `backgroundSyncIfDue`'s network calls observe this and unwind
            // via `URLError.cancelled`/`CancellationError`, which `performSync`'s `catch` already folds
            // into a status line like any other failure — never crash-safe territory, just a sync that
            // reports "cancelled" instead of a result. `Task.cancel()` is safe to call from any thread.
            work.cancel()
            completion.complete(success: false)
        }
    }
}

/// Guarantees `BGTask.setTaskCompleted` is called exactly once for a given task, however many of the
/// (up to two) call sites race to finish first. Deliberately NOT `@MainActor` or an `actor`: iOS invokes
/// `BGTask.expirationHandler` on a thread of its own choosing (undocumented, not guaranteed to be the
/// main actor), and that closure is synchronous (`() -> Void`, no `await` available) — so the guard
/// itself has to be usable synchronously from an arbitrary thread. `NSLock` provides that directly; the
/// sync `Task`'s own completion (which runs on the main actor, inherited from `handle`) calls the SAME
/// synchronous, lock-guarded method with no isolation mismatch either way.
private final class BGTaskCompletion {
    private let task: BGTask
    private let lock = NSLock()
    private var completed = false

    init(_ task: BGTask) {
        self.task = task
    }

    func complete(success: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard !completed else { return }
        completed = true
        task.setTaskCompleted(success: success)
    }
}
#endif // os(iOS)
#endif // CLOUD_SYNC
