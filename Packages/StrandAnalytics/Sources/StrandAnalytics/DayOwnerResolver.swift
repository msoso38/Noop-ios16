import Foundation

/// Decides which single device owns a given day's displayed/scored metrics, so scores are never
/// computed from a mix of sources (invariant I2). Pure — the caller supplies candidates (each device
/// that has any data near the day, with a priority) and any locked override from the dayOwnership table.
public enum DayOwnerResolver {
    public struct Candidate: Equatable {
        public let deviceId: String
        public let priority: Int     // 0 = active strap, 1 = other live straps, 2 = imports (lower wins)
        public let hasData: Bool
        /// A *full* record (HR-derived: stages, recovery, HRV) vs a bare duration window. A richer record
        /// outranks a window-only one regardless of device priority — displaying an active ring's bare
        /// sleep window in place of an import's full night (same duration, everything else blanked) is a
        /// downgrade, so the import keeps the day and the window only surfaces on days nothing richer owns.
        /// Defaults to `true` so every legacy candidate (all HR-backed) collapses back to priority-only order.
        public let richData: Bool
        public init(deviceId: String, priority: Int, hasData: Bool, richData: Bool = true) {
            self.deviceId = deviceId; self.priority = priority; self.hasData = hasData; self.richData = richData
        }
    }
    /// Returns the owning deviceId, or nil if no candidate has data for the day. Among candidates with
    /// data, a richer record wins first (`richData` true before false); ties break on device priority.
    public static func resolve(day: String, lockedOwner: String?, candidates: [Candidate]) -> String? {
        if let locked = lockedOwner { return locked }
        return candidates
            .filter { $0.hasData }
            .sorted { ($0.richData ? 0 : 1, $0.priority) < ($1.richData ? 0 : 1, $1.priority) }
            .first?.deviceId
    }
}
