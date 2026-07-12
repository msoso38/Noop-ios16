package com.noop.data

/**
 * Single source of truth for “complete vs not complete” — used by Goals UI + Test Centre.
 * Status is honest product truth for developer’s build, not marketing.
 *
 * Loops / schedulers should update these statuses when verified — goals outlive 7-day schedulers.
 */
object NoopGoalBoard {

    enum class Status { DONE, PARTIAL, NOT_DONE, BLOCKED }

    data class Goal(
        val id: String,
        val title: String,
        val status: Status,
        /** What a human should do to verify. */
        val howToTest: String,
        /** Why finishing this helps later (model / UX). */
        val futureImpact: String,
        /** What “nice” UI feels like when done. */
        val humanBenefit: String,
        /** One-line “does it actually work right now?” */
        val worksNow: String,
    ) {
        val isComplete: Boolean get() = status == Status.DONE
        val isNotComplete: Boolean get() = status != Status.DONE
    }

    fun all(): List<Goal> = listOf(
        Goal(
            id = "G1",
            title = "Auto-capture WHOOP app Recovery / Strain",
            status = Status.PARTIAL,
            howToTest = "Enable Accessibility “NOOP WHOOP app capture”, open WHOOP Today, return to NOOP — WHOOP app column fills without typing.",
            futureImpact = "Without app labels, ML cannot learn to track 14.7/21 Strain — only bracelet noise.",
            humanBenefit = "You stop manually typing scores; models update when you just use WHOOP.",
            worksNow = "Partial: Accessibility + notifications + adb script exist; not reliable every open without grant + WHOOP on screen.",
        ),
        Goal(
            id = "G2",
            title = "Compare NOOP algo vs WHOOP app (not bracelet)",
            status = Status.PARTIAL,
            howToTest = "Today card shows dual-scale (e.g. 14.7/21 · ≈70% vs Effort 35/100). WHOOP side empty until app labels exist.",
            futureImpact = "Pass score becomes real training signal instead of self-compare.",
            humanBenefit = "Honest gap between our Effort and the app — trustable, not fake match.",
            worksNow = "Partial: dual-scale + whoop-app-only column work; empty until real app labels land.",
        ),
        Goal(
            id = "G3",
            title = "Open BLE full listen + RAW inventory",
            status = Status.DONE,
            howToTest = "Live → RAW listen shows fd4b0005 / t47v18 / 2A37 packet counts growing while connected exclusive.",
            futureImpact = "Features for all models; decode unknown types overnight.",
            humanBenefit = "You can see the phone is actually receiving WHOOP telemetry.",
            worksNow = "Yes when exclusive-bonded: proven fd4b0005 type47 + 2A37 in log.",
        ),
        Goal(
            id = "G4",
            title = "Sleep staging real accuracy (κ on public data)",
            status = Status.NOT_DONE,
            howToTest = "Download PhysioNet sleep-accel; run Tools/sleep_stage_eval.py — report Cohen κ (not synthetic).",
            futureImpact = "Sleep stages drive Rest/Charge; bad stages poison recovery.",
            humanBenefit = "Sleep chart closer to reality; less “mystery stages”.",
            worksNow = "No real accuracy yet. Synthetic plumbing only (accuracy_valid=false).",
        ),
        Goal(
            id = "G5",
            title = "ML train loop improves Effort toward app Strain",
            status = Status.NOT_DONE,
            howToTest = "After ≥7 days of paired app labels + BLE, pass_score for Effort rises; weights file updates.",
            futureImpact = "Product differentiation — local model that tracks your WHOOP app.",
            humanBenefit = "NOOP Effort stops feeling random vs the app you already trust.",
            worksNow = "Engine runs; n_pairs=0 → NOT_READY. Needs WHOOP app labels.",
        ),
        Goal(
            id = "G6",
            title = "AirPods-style charging (ring + ding + ETA)",
            status = Status.PARTIAL,
            howToTest = "Plug strap; Live shows big charge ring, ding on start, days left / time-to-full estimates.",
            futureImpact = "Battery model training from SoC series.",
            humanBenefit = "Clear “how long until full” without guessing.",
            worksNow = "Partial: full-screen ring + ding + ETA ship; live strap needs charging=true. Debug: Test Centre → UI demo lab.",
        ),
        Goal(
            id = "G7",
            title = "M3 liquid Today + water nav + center +",
            status = Status.PARTIAL,
            howToTest = "Today sky + rings; bottom bar water pill slides; center + opens workout/strength.",
            futureImpact = "Lower friction → more data → better models.",
            humanBenefit = "App feels calm and intentional (your mockup + WHOOP density).",
            worksNow = "Partial: water nav + center + done; Today hero/rings still polish-in-progress.",
        ),
        Goal(
            id = "G8",
            title = "SpO2% / BP / ECG from open BLE",
            status = Status.BLOCKED,
            howToTest = "Remain blank unless Lab Book / banked calibrated source — never invent.",
            futureImpact = "Honesty preserves medical safety and legal stance.",
            humanBenefit = "No fake clinical numbers you might act on.",
            worksNow = "Blocked by design — open GATT has no SpO2%/BP/ECG fields.",
        ),
        Goal(
            id = "G9",
            title = "PC collectors always up (:8091)",
            status = Status.PARTIAL,
            howToTest = "http://127.0.0.1:8091/health → ok; log file grows with RAW_GATT.",
            futureImpact = "Overnight decode dies without collectors.",
            humanBenefit = "Data is saved for training without emailing logs.",
            worksNow = "Partial: works when KEEP_AWAKE/server running; flaky if PC sleeps.",
        ),
        Goal(
            id = "G10",
            title = "Goals board in app (loops → durable goals)",
            status = Status.DONE,
            howToTest = "More → Goals (or Settings → Goals). Each goal shows Complete / Partial / Not complete / Blocked + how to test + future impact.",
            futureImpact = "Schedulers expire; goals stay until verified DONE — no more “did the loop finish?”",
            humanBenefit = "One place to see what works, what’s left, and why it matters for humans.",
            worksNow = "Yes — this screen is the board.",
        ),
    )

    fun complete(): List<Goal> = all().filter { it.isComplete }
    fun notComplete(): List<Goal> = all().filter { it.isNotComplete }

    fun summary(): Triple<Int, Int, Int> {
        val list = all()
        val done = list.count { it.status == Status.DONE }
        val partial = list.count { it.status == Status.PARTIAL }
        val rest = list.size - done - partial
        return Triple(done, partial, rest)
    }

    fun statusLabel(s: Status): String = when (s) {
        Status.DONE -> "Complete"
        Status.PARTIAL -> "Partial"
        Status.NOT_DONE -> "Not complete"
        Status.BLOCKED -> "Blocked"
    }
}
