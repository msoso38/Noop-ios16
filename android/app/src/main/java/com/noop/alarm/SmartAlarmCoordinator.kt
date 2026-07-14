package com.noop.alarm

import android.content.Context
import com.noop.alarm.PhoneFire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

private data class StrapFirmwareAlarm(val id: String, val epochSec: Long?)
private data class DesiredStrapAlarm(val id: String, val epochSec: Long)

enum class StrapArmState { PENDING, ARMED }

data class StrapArmStatus(val alarmId: String, val state: StrapArmState)

/**
 * The runtime glue between UnifiedAlarmStore and the legacy phone/strap primitives.
 *
 * Recompute is event-driven:
 *   1. Alarm list changes that affect firing (add/update/delete/setEnabled). Reorder is display-only.
 *   2. BLE reconnect for a WHOOP - caller flips strapConnected = true then recomputes.
 *   3. Phone alarm fire, strap alarm fire, and strap dismiss events.
 *   4. Boot restore for phone alarms; strap state reconciles on the next BLE reconnect.
 *
 * The ONLY thing outside SmartAlarmScheduler / WhoopBleClient that touches arm/disable/run.
 */
class SmartAlarmCoordinator(
    private val context: Context,
    private val store: UnifiedAlarmStore,
    private val nowEpochMs: () -> Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val strapArmer: StrapArmer,
    private val phoneScheduler: PhoneScheduler,
    private val smartWakeStore: SmartAlarmStore = SmartAlarmStore.from(context),
) {

    // Mirrors the persisted value in UnifiedAlarmStore. This is the last alarm id we believe the
    // strap firmware actually accepted, not merely the resolver's current desired id. Keeping it
    // unchanged while disconnected lets reconnect disable or replace stale firmware state.
    private val _armedStrapAlarmId = MutableStateFlow(store.armedStrapAlarmId.value)

    /** Test seam/UI signal: the id we believe is armed on the firmware. Persists across process death. */
    val armedStrapAlarmId: StateFlow<String?> = _armedStrapAlarmId.asStateFlow()

    private val _strapArmStatus = MutableStateFlow<StrapArmStatus?>(null)

    /** UI signal for the next strap-backed alarm: pending until known firmware state matches it. */
    val strapArmStatus: StateFlow<StrapArmStatus?> = _strapArmStatus.asStateFlow()

    private fun firmwareAlarm(): StrapFirmwareAlarm? =
        _armedStrapAlarmId.value?.let { StrapFirmwareAlarm(it, store.armedStrapAlarmEpochSec()) }

    private var pendingSmartWakeConsumeAtMs: Long? = null

    init {
        val schedule = UnifiedAlarmResolver.resolveSchedule(store.alarms.value, nowEpochMs(), zone)
        publishStrapArmStatus(schedule.nextStrapArm?.let {
            DesiredStrapAlarm(id = it.alarmId, epochSec = it.wakeEpochMs / 1000L)
        })
    }

    /**
     * Whether the strap is currently connected. Affects whether strap commands are queued or sent.
     * When false, recompute records desired phone/smart-wake state but does not pretend a strap
     * command succeeded. The last-known firmware id is kept until reconnect can reconcile it.
     */
    var strapConnected: Boolean = false

    /**
     * Called when persisted alarm data or connection state changed and hardware needs reconciling.
     * Recomputes the schedule and reconciles phone + strap state.
     */
    fun recompute() {
        recompute(reconcilePhone = true, nowMs = nowEpochMs())
    }

    private fun recompute(reconcilePhone: Boolean, nowMs: Long = nowEpochMs()) {
        val schedule = UnifiedAlarmResolver.resolveSchedule(
            store.alarms.value, nowMs, zone,
        )

        // Phone path: hand the whole desired list to the scheduler; it diffs internally.
        if (reconcilePhone) phoneScheduler.reconcile(schedule.phoneAlarms)

        // Strap path: compare the desired head to the last firmware state we believe exists.
        val desired = schedule.nextStrapArm?.let {
            DesiredStrapAlarm(id = it.alarmId, epochSec = it.wakeEpochMs / 1000L)
        }
        val firmware = firmwareAlarm()

        if (desired == null) {
            // No strap alarm desired.
            if (firmware != null && strapConnected) {
                // Cancel whatever was actually armed. If disconnected, keep firmware so the
                // next reconnect still knows it must disable the stale strap alarm.
                strapArmer.disable()
                _armedStrapAlarmId.value = null
                store.setArmedStrapAlarm(null, null)
            }
            // else: both null, nothing to do.
        } else {
            // A strap alarm is desired.
            val needsArm = firmware == null || desired.id != firmware.id || desired.epochSec != firmware.epochSec
            if (needsArm && strapConnected) {
                if (firmware != null) {
                    // Preempt stale firmware before arming the desired id/time.
                    strapArmer.disable()
                }
                if (strapArmer.armAt(desired.epochSec)) {
                    _armedStrapAlarmId.value = desired.id
                    store.setArmedStrapAlarm(desired.id, desired.epochSec)
                } else {
                    _armedStrapAlarmId.value = null
                    store.setArmedStrapAlarm(null, null)
                }
            }
            // else: either firmware already matches desired, or strap is offline and reconciliation
            // is pending until the next BLE reconnect trigger.
        }

        publishStrapArmStatus(desired)

        mirrorSmartWakeWatcher(schedule)
    }

    private fun publishStrapArmStatus(desired: DesiredStrapAlarm?) {
        if (desired == null) {
            _strapArmStatus.value = null
            return
        }
        val firmware = firmwareAlarm()
        _strapArmStatus.value = if (firmware?.id == desired.id && firmware.epochSec == desired.epochSec) {
            StrapArmStatus(desired.id, StrapArmState.ARMED)
        } else {
            StrapArmStatus(desired.id, StrapArmState.PENDING)
        }
    }

    /**
     * Smart-wake fired (sleep watcher detected light sleep in window). Drives the strap's
     * firmware alarm now; the strap-start event then consumes/re-arms the next occurrence.
     *
     * No-op if the armed alarm is .phone-only (no strap to drive) or no alarm is armed.
     *
     * We deliberately do not call disable() here. The wrist alarm should be the same dismissible
     * firmware alarm UI as a scheduled strap alarm, not a short generic haptic buzz.
     */
    fun onSmartWakeFire() {
        if (_armedStrapAlarmId.value == null) return
        val deadlineMs = smartWakeStore.scheduledDeadlineMs
        if (strapConnected) {
            strapArmer.fireNow()
        }
        // When the strap reports the alarm started, consume against the original deadline. That
        // prevents an early smart wake from re-arming the same wall-clock occurrence.
        pendingSmartWakeConsumeAtMs = if (deadlineMs > 0L) maxOf(nowEpochMs(), deadlineMs) else nowEpochMs()
    }

    /** Called when the strap reports its firmware alarm fired. Firmware alarms are one-shot. */
    fun onStrapAlarmFired() {
        val firedId = _armedStrapAlarmId.value ?: return
        store.setAwaitingStrapDismissAlarmId(firedId)
        store.disableIfOneShot(firedId)
        _armedStrapAlarmId.value = null
        store.setArmedStrapAlarm(null, null)
        // Event 57 means the strap alarm started, not that the user dismissed it. Keep any phone
        // backup for this occurrence armed until the phone alarm itself fires or an actual dismiss
        // signal exists. This only re-arms the next firmware occurrence for recurring rows; one-shot
        // rows were disabled above and therefore drop out of the resolved schedule.
        val consumeAt = pendingSmartWakeConsumeAtMs ?: nowEpochMs()
        pendingSmartWakeConsumeAtMs = null
        recompute(reconcilePhone = false, nowMs = consumeAt)
    }

    /** Called when the strap reports its firmware alarm was disabled/dismissed after firing. */
    fun onStrapAlarmDismissed() {
        val dismissedId = store.awaitingStrapDismissAlarmId() ?: return
        phoneScheduler.cancel(dismissedId)
        store.setAwaitingStrapDismissAlarmId(null)
        recompute(reconcilePhone = true)
    }

    private fun mirrorSmartWakeWatcher(schedule: Schedule) {
        data class WatcherHead(
            val alarmId: String,
            val deadlineMs: Long,
            val windowStartMs: Long,
        )

        val strapHead = schedule.nextStrapArm
            ?.takeIf { it.smartWake }
            ?.let { WatcherHead(it.alarmId, it.wakeEpochMs, it.windowStartEpochMs) }
        val phoneHead = schedule.phoneAlarms
            .filter { it.smartWake && !it.isStrapPhoneBackup }
            .minByOrNull { it.fireAtEpochMs }
            ?.let { WatcherHead(it.alarmId, it.fireAtEpochMs, it.windowStartEpochMs) }
        val head = listOfNotNull(strapHead, phoneHead).minByOrNull { it.deadlineMs }

        if (head == null) {
            smartWakeStore.enabled = false
            smartWakeStore.scheduledAlarmId = null
            smartWakeStore.scheduledDeadlineMs = 0L
            smartWakeStore.scheduledWindowStartMs = 0L
        } else {
            smartWakeStore.enabled = true
            smartWakeStore.scheduledAlarmId = head.alarmId
            smartWakeStore.scheduledDeadlineMs = head.deadlineMs
            smartWakeStore.scheduledWindowStartMs = head.windowStartMs
        }
    }
}

/**
 * Seam over WhoopBleClient's strap-alarm primitives. The coordinator never touches the BLE
 * socket directly - it goes through this interface, which the application wires to
 * WhoopBleClient.armStrapAlarm / disableStrapAlarm.
 */
interface StrapArmer {
    /** Arm the strap to buzz at [epochSec] (seconds since Unix epoch). */
    fun armAt(epochSec: Long): Boolean

    /** Fire the strap firmware alarm immediately for a smart-wake advance. */
    fun fireNow()

    /** Cancel any armed firmware alarm. */
    fun disable()
}

/**
 * Seam over SmartAlarmScheduler. The coordinator never touches AlarmManager directly - it
 * delegates to this interface, which the application wires to SmartAlarmScheduler.
 *
 * The implementation must cancel all previously scheduled phone alarms, then schedule exactly
 * the [desired] list. Request codes are keyed by alarm id; the impl owns that bookkeeping.
 */
interface PhoneScheduler {
    /**
     * Cancel everything we've scheduled, then schedule [desired] exactly. Keyed by alarm id.
     */
    fun reconcile(desired: List<PhoneFire>)

    /** Cancel a single scheduled phone alarm by id and forget its persisted registration. */
    fun cancel(alarmId: String)
}
