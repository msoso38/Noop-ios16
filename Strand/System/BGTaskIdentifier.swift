import Foundation

/// The iOS `BGTaskScheduler` identifiers this build is actually permitted to register — and a canary
/// that reports a mismatch loudly instead of letting the whole background lane fail silently.
///
/// WHY THIS EXISTS: a BGTask identifier has to be written twice — once in the iOS target's
/// `BGTaskSchedulerPermittedIdentifiers` (project.yml, which XcodeGen expands into the generated
/// `StrandiOS/Resources/Info.plist`) and once in Swift. iOS compares the two as EXACT strings and
/// refuses to register a handler whose identifier isn't on the permitted list. The permitted entries
/// are namespaced under the bundle id, so the documented "build under your own Apple ID" path (see
/// `APP_GROUP_ID` in project.yml) rewrites the plist side and leaves a hardcoded Swift literal stale.
/// `BGTaskScheduler.register` then returns `false` and `submit` throws `.notPermitted` — and both call
/// sites discarded those signals, so the lane went dark with no symptom at all.
///
/// Same single-source-of-truth posture as `WidgetSnapshot.suiteName`: read what the bundle actually
/// carries, and fall back to the canonical upstream literal only if the key is missing.
///
/// macOS compiles this file too (it lives under `Strand/`), where `BGTaskScheduler` doesn't exist and
/// the plist key is absent — `permitted` is simply empty and nothing calls into here.
enum BGTaskIdentifier {
    /// The `BGTaskSchedulerPermittedIdentifiers` array from THIS process's bundle — the exact list iOS
    /// checks a registration against. Empty on macOS and on any build that hasn't wired the key.
    static let permitted: [String] = {
        Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? []
    }()

    /// The identifier this build may actually register for a given lane, e.g.
    /// `resolve(suffix: "debugexport", upstream: "com.noopapp.noop.debugexport")`.
    ///
    /// Resolution order, most authoritative first:
    /// 1. **The permitted list itself.** This is the exact array iOS compares a registration against,
    ///    so an entry from it is correct by construction — however the plist got its values, and even
    ///    if someone hand-edits it to something that isn't `<bundle id>.<suffix>`.
    /// 2. **This build's bundle id + the suffix.** Covers a target that hasn't wired the plist key.
    /// 3. **The canonical upstream literal**, so behaviour is unchanged on a stock build even if the
    ///    bundle id is somehow unreadable.
    ///
    /// The suffix match is anchored on a leading `.` so `debugexport` can never claim
    /// `cloudsync.refresh`'s entry or vice versa.
    static func resolve(suffix: String, upstream: String) -> String {
        if let permittedEntry = permitted.first(where: { $0.hasSuffix(".\(suffix)") }) { return permittedEntry }
        if let bundleID = Bundle.main.bundleIdentifier { return "\(bundleID).\(suffix)" }
        return upstream
    }

    /// Report the outcome of a `BGTaskScheduler.register` call. A failure is logged in EVERY
    /// configuration, not just Debug: a sideloaded personal build is exactly where a bundle-id remap
    /// breaks this, and a Debug-only signal would never reach that build.
    ///
    /// Deliberately does NOT `assertionFailure` the way `WidgetSnapshot.assertGroupProvisioned` does.
    /// A missing App Group entitlement breaks user-visible core features (widget, Live Activity, watch
    /// snapshot), so trapping in Debug is proportionate there. A missing BGTask permit only disables an
    /// opportunistic lane this file's own doc comments call "never a dependency for correctness" —
    /// turning that into a launch crash would be worse than the bug. Loud is enough.
    static func report(_ identifier: String, registered: Bool) {
        guard !registered else {
            NSLog("BGTaskIdentifier: registered '\(identifier)'")
            return
        }
        NSLog("""
              BGTaskIdentifier: FAILED to register '\(identifier)' — not in \
              BGTaskSchedulerPermittedIdentifiers \(permitted). This background lane will never run. \
              The identifier must match the iOS target's permitted list in project.yml exactly.
              """)
    }
}
