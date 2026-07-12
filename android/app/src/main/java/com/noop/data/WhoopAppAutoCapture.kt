package com.noop.data

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Central sink for automatic WHOOP **app** score capture (Accessibility + notifications + export).
 * Writes [WhoopAppScoreStore] + Room device [WhoopAppScoreStore.DEVICE_ID] for the compare card.
 */
object WhoopAppAutoCapture {
    private const val TAG = "WhoopAppAutoCapture"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val PREFS = "noop_whoop_app_auto"
    const val KEY_ENABLED = "autoCaptureEnabled"
    const val WHOOP_PACKAGE = "com.whoop.android"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Merge a parse hit into today's (or [day]) official app labels.
     * Prefer newer non-null fields; keep previous if new parse only has one field.
     */
    fun ingestParsed(
        context: Context,
        parsed: WhoopAppScoreParser.Parsed,
        source: String,
        day: String = LocalDate.now().toString(),
        repository: WhoopRepository? = null,
    ) {
        if (!isEnabled(context)) return
        val scores = WhoopAppScoreParser.toDayScores(parsed, day, source) ?: return
        val store = WhoopAppScoreStore.from(context)
        val prev = store.get(day)
        val merged = WhoopAppScoreStore.DayScores(
            day = day,
            recoveryPct = scores.recoveryPct ?: prev?.recoveryPct,
            dayStrain021 = scores.dayStrain021 ?: prev?.dayStrain021,
            sleepPct = scores.sleepPct ?: prev?.sleepPct,
            stressNote = scores.stressNote ?: prev?.stressNote,
            stressPct = scores.stressPct ?: prev?.stressPct,
            source = source,
        )
        // Avoid thrashing identical values
        if (prev != null &&
            prev.recoveryPct == merged.recoveryPct &&
            prev.dayStrain021 == merged.dayStrain021 &&
            prev.sleepPct == merged.sleepPct
        ) return
        store.put(merged)
        Log.i(TAG, "Captured WHOOP app scores day=$day rec=${merged.recoveryPct} strain=${merged.dayStrain021} sleep=${merged.sleepPct} via $source hits=${parsed.rawHits}")
        scope.launch {
            val repo = repository ?: return@launch
            runCatching {
                repo.upsertDevice(WhoopAppScoreStore.DEVICE_ID, name = "WHOOP app (auto)")
                val existing = repo.days(WhoopAppScoreStore.DEVICE_ID).firstOrNull { it.day == day }
                val row = DailyMetric(
                    deviceId = WhoopAppScoreStore.DEVICE_ID,
                    day = day,
                    recovery = merged.recoveryPct,
                    strain = merged.dayStrain021,
                    totalSleepMin = existing?.totalSleepMin,
                    avgHrv = existing?.avgHrv,
                    restingHr = existing?.restingHr,
                )
                repo.upsertDailyMetrics(listOf(row))
                // Sleep % stays in WhoopAppScoreStore (not a DailyMetric column); compare/hero read it there.
            }.onFailure { Log.w(TAG, "Room write failed: ${it.message}") }
        }
    }
}
