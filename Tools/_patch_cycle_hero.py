from pathlib import Path

p = Path("android/app/src/main/java/com/noop/ui/PeriodCalendarScreen.kt")
t = p.read_text(encoding="utf-8")
a = t.index("@Composable\nprivate fun PhaseHeroCard")
b = t.index("@Composable\nprivate fun PredictionCard")
new = """@Composable
private fun PhaseHeroCard(snap: PeriodCalendar.Snapshot) {
    // Quiet phase read — no radial glow orb (bolted AI chrome).
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Where you are", style = NoopType.overline, color = Palette.textTertiary)
        Text(snap.phase.label, style = NoopType.title1, color = Palette.textPrimary)
        snap.cycleDay?.let {
            Text("Day $it of this cycle", style = NoopType.subhead, color = Palette.textSecondary)
        }
        Text(snap.note, style = NoopType.body, color = Palette.textSecondary)
        StatePill(
            when (snap.whoopConfidence) {
                CyclePhaseEngine.Confidence.SOLID -> "WHOOP solid"
                CyclePhaseEngine.Confidence.BUILDING -> "WHOOP building"
                else -> if (snap.lastPeriodStart != null) "Logs" else "Learning"
            },
            tone = StrandTone.Accent,
            showsDot = false,
        )
    }
}

"""
t2 = t[:a] + new + t[b:]
t2 = t2.replace("/4 starts", "/2 starts")
p.write_text(t2, encoding="utf-8")
print("ok", a, b, "/4 left:", "/4 starts" in t2)
