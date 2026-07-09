package com.noop.analytics

/**
 * Decides which single device owns a given day's displayed/scored metrics, so scores are never
 * computed from a mix of sources (invariant I2). Pure — the caller supplies the candidates (each
 * device with any data near the day, plus a priority) and any locked override from the dayOwnership
 * table. Port of the Swift `DayOwnerResolver` in Packages/StrandAnalytics.
 */
object DayOwnerResolver {
    /** A device in contention for owning [day]. [priority]: 0 = active strap, 1 = other live straps,
     *  2 = imports (lower wins). [hasData] = the device actually has data for the day. [richData] = a
     *  FULL record (HR-derived: stages, recovery, HRV) vs a bare duration window; a richer record
     *  outranks a window-only one regardless of priority, so an active ring's bare sleep window never
     *  displaces an import's full night (same duration, everything else blanked). Defaults true so every
     *  legacy candidate (all HR-backed) collapses back to priority-only order. */
    data class Candidate(
        val deviceId: String, val priority: Int, val hasData: Boolean, val richData: Boolean = true
    )

    /** The owning deviceId, or null if no candidate has data for the day. A non-null [lockedOwner]
     *  (an explicit dayOwnership decision) always wins, regardless of priority or data. Among candidates
     *  with data, a richer record wins first ([richData] true before false); ties break on priority. */
    fun resolve(day: String, lockedOwner: String?, candidates: List<Candidate>): String? =
        if (lockedOwner != null) lockedOwner
        else candidates.filter { it.hasData }
            .minWithOrNull(compareBy({ if (it.richData) 0 else 1 }, { it.priority }))?.deviceId
}
