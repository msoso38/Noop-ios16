package com.noop.alarm

import com.noop.alarm.PhoneFire
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SmartAlarmCoordinatorTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val day0 = ZonedDateTime.of(LocalDate.of(2026, 6, 22), java.time.LocalTime.NOON, zone)
    private val nowMs = day0.toInstant().toEpochMilli()
    private val allDays = setOf(1, 2, 3, 4, 5, 6, 7)

    private class FakeStrapArmer : StrapArmer {
        val calls = mutableListOf<Pair<String, Long?>>()
        var armSucceeds = true
        override fun armAt(epochSec: Long): Boolean {
            calls += "armAt" to epochSec
            return armSucceeds
        }
        override fun fireNow() { calls += "fireNow" to null }
        override fun disable() { calls += "disable" to null }
    }

    private class FakePhoneScheduler : PhoneScheduler {
        var lastDesired: List<PhoneFire> = emptyList()
        val cancelledIds = mutableListOf<String>()
        var reconcileCount = 0
        override fun reconcile(desired: List<PhoneFire>) { lastDesired = desired; reconcileCount++ }
        override fun cancel(alarmId: String) { cancelledIds += alarmId }
    }

    private fun newCoordinator(
        store: UnifiedAlarmStore,
        strapConnected: Boolean = true,
        now: () -> Long = { nowMs },
    ): Triple<SmartAlarmCoordinator, FakeStrapArmer, FakePhoneScheduler> {
        val strap = FakeStrapArmer()
        val phone = FakePhoneScheduler()
        val c = SmartAlarmCoordinator(
            context = androidContextStub(),
            store = store,
            nowEpochMs = now,
            zone = zone,
            strapArmer = strap,
            phoneScheduler = phone,
            smartWakeStore = SmartAlarmStore(InMemorySharedPreferences()),
        )
        c.strapConnected = strapConnected
        return Triple(c, strap, phone)
    }

    @Test fun emptyStoreReconcilesPhoneToEmptyAndDoesNotArmStrap() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        assertTrue(phone.lastDesired.isEmpty())
        assertNull(c.armedStrapAlarmId.value)
        assertTrue(strap.calls.isEmpty())
    }

    @Test fun singleStrapAlarmArmsTheStrapAndSchedulesNoPhone() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        val a = UnifiedAlarm(
            id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP,
        )
        store.add(a)
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        assertEquals("a1", c.armedStrapAlarmId.value)
        assertEquals(StrapArmStatus("a1", StrapArmState.ARMED), c.strapArmStatus.value)
        // exactly one armAt call, no disable (nothing was armed before)
        assertEquals(1, strap.calls.size)
        assertEquals("armAt", strap.calls[0].first)
        assertTrue(phone.lastDesired.isEmpty())
    }

    @Test fun failedStrapArmKeepsAlarmPendingAndDoesNotPersistFirmwareState() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        val (c, strap, phone) = newCoordinator(store)
        strap.armSucceeds = false

        c.recompute()

        assertEquals(listOf("armAt"), strap.calls.map { it.first })
        assertNull(c.armedStrapAlarmId.value)
        assertNull(store.armedStrapAlarmId.value)
        assertNull(store.armedStrapAlarmEpochSec())
        assertEquals(StrapArmStatus("a1", StrapArmState.PENDING), c.strapArmStatus.value)
        assertTrue(phone.lastDesired.isEmpty())
    }

    @Test fun newEarlierStrapAlarmPreemptsTheArmedHead() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        val (c, strap, _) = newCoordinator(store)
        c.recompute()
        strap.calls.clear()

        // Add a 06:00 alarm - earlier than the 06:30 head.
        store.add(UnifiedAlarm(id = "a2", enabled = true, wakeMinutes = 6 * 60,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        c.recompute()
        assertEquals("a2", c.armedStrapAlarmId.value)
        // disable + armAt, in that order.
        assertEquals(listOf("disable", "armAt"), strap.calls.map { it.first })
    }

    @Test fun strapDisconnectedQueuesIntentAndAppliesOnBleConnect() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        val (c, strap, _) = newCoordinator(store, strapConnected = false)
        c.recompute()
        // Nothing sent to the strap yet - it is offline.
        assertTrue(strap.calls.isEmpty())
        // armedStrapAlarmId stays null while disconnected: we must not claim the alarm is armed
        // on the firmware when the strap has not physically accepted it (iOS parity, I4).
        assertNull(c.armedStrapAlarmId.value)
        assertEquals(StrapArmStatus("a1", StrapArmState.PENDING), c.strapArmStatus.value)

        // Now BLE connects: recompute can send the pending firmware arm.
        c.strapConnected = true
        c.recompute()
        // Only now does the coordinator update the observable state.
        assertEquals("a1", c.armedStrapAlarmId.value)
        assertEquals(StrapArmStatus("a1", StrapArmState.ARMED), c.strapArmStatus.value)
        assertEquals(1, strap.calls.size)
        assertEquals("armAt", strap.calls[0].first)
    }

    @Test fun disablingWhileDisconnectedKeepsKnownFirmwareUntilReconnectCanDisableIt() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        val alarm = UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP)
        store.add(alarm)
        val knownEpoch = UnifiedAlarmResolver.nextFireAtEpochMs(alarm, nowMs, zone)!! / 1000L
        store.setArmedStrapAlarm("a1", knownEpoch)

        val (c, strap, _) = newCoordinator(store, strapConnected = false)
        store.delete("a1")
        c.recompute()

        assertTrue(strap.calls.isEmpty())
        assertEquals("a1", c.armedStrapAlarmId.value)
        assertEquals(knownEpoch, store.armedStrapAlarmEpochSec())
        assertNull(c.strapArmStatus.value)

        c.strapConnected = true
        c.recompute()

        assertEquals(listOf("disable"), strap.calls.map { it.first })
        assertNull(c.armedStrapAlarmId.value)
        assertNull(store.armedStrapAlarmEpochSec())
        assertNull(c.strapArmStatus.value)
    }

    @Test fun editingSameAlarmTimeWhileDisconnectedReplacesFirmwareOnReconnect() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        val oldAlarm = UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP)
        store.add(oldAlarm)
        val oldEpoch = UnifiedAlarmResolver.nextFireAtEpochMs(oldAlarm, nowMs, zone)!! / 1000L
        store.setArmedStrapAlarm("a1", oldEpoch)

        val (c, strap, _) = newCoordinator(store, strapConnected = false)
        val newAlarm = oldAlarm.copy(wakeMinutes = 6 * 60)
        val newEpoch = UnifiedAlarmResolver.nextFireAtEpochMs(newAlarm, nowMs, zone)!! / 1000L
        store.update("a1", newAlarm)
        c.recompute()

        assertTrue(strap.calls.isEmpty())
        assertEquals("a1", c.armedStrapAlarmId.value)
        assertEquals(oldEpoch, store.armedStrapAlarmEpochSec())
        assertEquals(StrapArmStatus("a1", StrapArmState.PENDING), c.strapArmStatus.value)

        c.strapConnected = true
        c.recompute()

        assertEquals(listOf("disable", "armAt"), strap.calls.map { it.first })
        assertEquals(newEpoch, strap.calls.last().second)
        assertEquals("a1", c.armedStrapAlarmId.value)
        assertEquals(newEpoch, store.armedStrapAlarmEpochSec())
        assertEquals(StrapArmStatus("a1", StrapArmState.ARMED), c.strapArmStatus.value)
    }

    @Test fun differentDesiredAlarmWhileDisconnectedReplacesFirmwareOnReconnect() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        val a1 = UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP)
        val a2 = UnifiedAlarm(id = "a2", enabled = true, wakeMinutes = 6 * 60,
            weekdays = emptySet(), source = AlarmSource.STRAP)
        store.add(a1)
        val a1Epoch = UnifiedAlarmResolver.nextFireAtEpochMs(a1, nowMs, zone)!! / 1000L
        store.setArmedStrapAlarm("a1", a1Epoch)

        val (c, strap, _) = newCoordinator(store, strapConnected = false)
        store.add(a2)
        c.recompute()

        assertTrue(strap.calls.isEmpty())
        assertEquals("a1", c.armedStrapAlarmId.value)
        assertEquals(StrapArmStatus("a2", StrapArmState.PENDING), c.strapArmStatus.value)

        c.strapConnected = true
        c.recompute()

        assertEquals(listOf("disable", "armAt"), strap.calls.map { it.first })
        assertEquals("a2", c.armedStrapAlarmId.value)
        assertEquals(StrapArmStatus("a2", StrapArmState.ARMED), c.strapArmStatus.value)
    }

    @Test fun phoneOnlyAlarmRoutesToPhoneSchedulerAndNotStrap() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "p1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.PHONE,
            smartWake = true, preWakeWindowMinutes = 30))
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        assertEquals(1, phone.lastDesired.size)
        assertEquals("p1", phone.lastDesired[0].alarmId)
        assertTrue(strap.calls.isEmpty())
        assertNull(c.armedStrapAlarmId.value)
    }

    @Test fun strapAndPhoneAlarmArmsBothPaths() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "ab", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP_AND_PHONE))
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        assertEquals("ab", c.armedStrapAlarmId.value)
        assertEquals(1, strap.calls.count { it.first == "armAt" })
        assertEquals(1, phone.lastDesired.size)
        assertEquals("ab", phone.lastDesired[0].alarmId)
    }

    @Test fun reorderDoesNotTriggerStrapOrPhoneIO() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        store.add(UnifiedAlarm(id = "a2", enabled = true, wakeMinutes = 9 * 60,
            weekdays = setOf(7), source = AlarmSource.STRAP))
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        strap.calls.clear()
        val callsBefore = phone.reconcileCount

        // Pure reorder - same data, list order flipped. Spec rule: not a coordinator trigger.
        store.reorder(0, 1)
        // We deliberately do NOT call c.recompute() here, mirroring how the UI is wired:
        // store.reorder is a list-only path and the screen doesn't fire the coordinator on it.
        assertTrue(strap.calls.isEmpty())
        assertEquals(callsBefore, phone.reconcileCount)
    }

    @Test fun onSmartWakeFireRunsFirmwareAlarmAndRepeatingStrapEventRearmsNextOccurrence() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = allDays, source = AlarmSource.STRAP, smartWake = true))
        var currentNow = nowMs
        val (c, strap, _) = newCoordinator(store, now = { currentNow })
        c.recompute()
        strap.calls.clear()

        currentNow = ZonedDateTime.of(
            LocalDate.of(2026, 6, 23),
            java.time.LocalTime.of(6, 5),
            zone,
        ).toInstant().toEpochMilli()
        c.onSmartWakeFire()
        assertEquals(listOf("fireNow"), strap.calls.map { it.first })
        assertEquals("a1", c.armedStrapAlarmId.value)

        c.onStrapAlarmFired()

        assertEquals(listOf("fireNow", "armAt"), strap.calls.map { it.first })
        assertEquals("a1", c.armedStrapAlarmId.value)
    }

    @Test fun onSmartWakeFireNoOpsWhenNoArmedAlarm() {
        // Empty store - nothing armed.
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        val (c, strap, _) = newCoordinator(store, strapConnected = true)
        // Do NOT call recompute() - armedStrapAlarmId is null.
        c.onSmartWakeFire()
        // Must not send a spurious disable to the firmware.
        assertTrue(strap.calls.isEmpty())
        assertNull(c.armedStrapAlarmId.value)
    }

    @Test fun onStrapAlarmFiredDisablesOneShotAndDoesNotRearm() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP))
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        strap.calls.clear()
        val phoneReconcilesBefore = phone.reconcileCount

        c.onStrapAlarmFired()

        assertEquals(false, store.alarms.value.single { it.id == "a1" }.enabled)
        assertNull(c.armedStrapAlarmId.value)
        assertTrue(strap.calls.isEmpty())
        assertEquals(phoneReconcilesBefore, phone.reconcileCount)
    }

    @Test fun onStrapAlarmFiredKeepsRepeatingAlarmEnabledAndRearmsNextOccurrence() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "a1", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = allDays, source = AlarmSource.STRAP))
        val (c, strap, phone) = newCoordinator(store)
        c.recompute()
        strap.calls.clear()
        val phoneReconcilesBefore = phone.reconcileCount

        c.onStrapAlarmFired()

        assertEquals(true, store.alarms.value.single { it.id == "a1" }.enabled)
        assertEquals("a1", c.armedStrapAlarmId.value)
        assertEquals(listOf("armAt"), strap.calls.map { it.first })
        assertEquals(phoneReconcilesBefore, phone.reconcileCount)
    }

    @Test fun onStrapAlarmDismissedCancelsBackupOnlyAfterFiredEvent() {
        var currentNow = ZonedDateTime.of(
            LocalDate.of(2026, 6, 22),
            java.time.LocalTime.NOON,
            zone,
        ).toInstant().toEpochMilli()
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "ab", enabled = true, wakeMinutes = 12 * 60 + 1,
            weekdays = allDays, source = AlarmSource.STRAP_AND_PHONE))
        val (c, strap, phone) = newCoordinator(store, now = { currentNow })
        c.recompute()
        assertEquals(1, phone.lastDesired.size)
        val initialBackupAt = phone.lastDesired.single().fireAtEpochMs

        currentNow = ZonedDateTime.of(
            LocalDate.of(2026, 6, 22),
            java.time.LocalTime.of(12, 2),
            zone,
        ).toInstant().toEpochMilli()
        strap.calls.clear()
        val phoneReconcilesBefore = phone.reconcileCount

        c.onStrapAlarmFired()

        assertEquals("ab", store.awaitingStrapDismissAlarmId())
        assertTrue(phone.cancelledIds.isEmpty())
        assertEquals(phoneReconcilesBefore, phone.reconcileCount)

        c.onStrapAlarmDismissed()

        assertEquals(listOf("ab"), phone.cancelledIds)
        assertNull(store.awaitingStrapDismissAlarmId())
        assertEquals(phoneReconcilesBefore + 1, phone.reconcileCount)
        assertTrue(phone.lastDesired.single().fireAtEpochMs > initialBackupAt)
    }

    @Test fun onStrapAlarmDismissedNoOpsWithoutPriorFiredEvent() {
        val store = UnifiedAlarmStore(InMemorySharedPreferences())
        store.add(UnifiedAlarm(id = "ab", enabled = true, wakeMinutes = 6 * 60 + 30,
            weekdays = emptySet(), source = AlarmSource.STRAP_AND_PHONE))
        val (c, _, phone) = newCoordinator(store)
        c.recompute()
        val phoneReconcilesBefore = phone.reconcileCount

        c.onStrapAlarmDismissed()

        assertTrue(phone.cancelledIds.isEmpty())
        assertEquals(phoneReconcilesBefore, phone.reconcileCount)
    }

    private fun androidContextStub(): android.content.Context = AlarmTestContext()
}
