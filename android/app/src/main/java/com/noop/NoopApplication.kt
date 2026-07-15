package com.noop

import android.app.Application
import android.util.Log
import com.noop.alarm.SmartAlarmCoordinator
import com.noop.alarm.SmartAlarmScheduler
import com.noop.alarm.SmartAlarmStore
import com.noop.alarm.StrapArmer
import com.noop.alarm.UnifiedAlarmMigration
import com.noop.alarm.UnifiedAlarmStore
import com.noop.alarm.UnifiedPhoneScheduler
import com.noop.ble.SourceCoordinator
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.DeviceRegistry
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.ui.NoopPrefs
import kotlinx.coroutines.runBlocking
import java.time.ZoneId

/**
 * Application entry point.
 *
 * NOOP is a fully on-device WHOOP companion: it connects to the strap over BLE and persists
 * everything locally via Room. There is no network layer (the opt-in AI Coach aside).
 *
 * The data layer ([WhoopRepository]) and the BLE client ([WhoopBleClient]) are owned **here**, at the
 * process level, rather than by the Activity-scoped AppViewModel. That is what lets a connection keep
 * streaming when the app is backgrounded or closed: [com.noop.ble.WhoopConnectionService] holds the
 * process up with a foreground notification, and both it and the UI share this one BLE client. The
 * macOS app gets the same outcome for free — its `AppModel` is an app-level `@StateObject` kept alive
 * by the menu-bar extra.
 */
class NoopApplication : Application() {

    /**
     * Process-wide unified alarm store. Owned here so the BLE service and the UI share the exact
     * same instance and the coordinator is the single writer of armed state.
     */
    lateinit var unifiedAlarmStore: UnifiedAlarmStore
        private set

    /**
     * Process-wide smart alarm coordinator. Sole owner of arm/disable + AlarmManager calls. User
     * edits, BLE reconnect, boot restore, and alarm fire/dismiss events funnel through it.
     */
    lateinit var smartAlarmCoordinator: SmartAlarmCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        // Record any uncaught crash to a file so it rides along in the shareable strap log — a
        // device-specific crash (e.g. Insights #224/#267) is otherwise lost to an unreachable logcat.
        CrashCapture.install(this)

        // Smart Alarm (#207 v2): one-shot copy of the two legacy alarm stores into
        // [UnifiedAlarmStore]. Idempotent. All migration code is in one file for future deletion.
        val migrationChanged = UnifiedAlarmMigration.migrateIfNeeded(applicationContext)
        if (migrationChanged) {
            // The legacy single-slot phone alarm is retired once the unified store exists. Cancel its
            // fixed PendingIntent so migrated users do not get a duplicate wake beside the per-id one-shot.
            SmartAlarmScheduler.cancel(applicationContext, SmartAlarmStore.from(applicationContext))
        }
        val store = UnifiedAlarmStore.from(applicationContext)

        val strapArmer = object : StrapArmer {
            override fun armAt(epochSec: Long): Boolean = ble.armStrapAlarm(epochSec)
            override fun fireNow() { ble.runFirmwareAlarmNow() }
            override fun disable() { ble.disableStrapAlarm() }
        }

        val phoneScheduler = UnifiedPhoneScheduler(this, store)

        val coordinator = SmartAlarmCoordinator(
            context = this,
            store = store,
            nowEpochMs = { System.currentTimeMillis() },
            zone = ZoneId.systemDefault(),
            strapArmer = strapArmer,
            phoneScheduler = phoneScheduler,
        )

        unifiedAlarmStore = store
        smartAlarmCoordinator = coordinator

        // Only a just-written migration needs immediate reconciliation at process start. Existing
        // schedules are event-driven: user edits, BLE reconnect, boot restore, or alarm fire/dismiss.
        if (migrationChanged) coordinator.recompute()
    }

    /** Process-wide Room-backed store. One instance shared by the UI and the background service. */
    val repository: WhoopRepository by lazy {
        WhoopRepository(WhoopDatabase.get(this).whoopDao())
    }

    /** Process-wide device registry over the same Room DB — the single source of the active device id. */
    val deviceRegistry: DeviceRegistry by lazy { DeviceRegistry(WhoopDatabase.get(this)) }

    /**
     * Active device id resolved once at startup from the registry, falling back to the legacy
     * "my-whoop" if the registry has none yet (so behaviour is unchanged today). Read with a guarded
     * blocking call — a one-off indexed `LIMIT 1` query at composition time. Any failure (e.g. an early
     * read before migration) is swallowed and falls back, so startup can never be broken by this.
     */
    val activeDeviceId: String by lazy {
        runCatching { runBlocking { deviceRegistry.activeDeviceId() } }
            .onFailure { Log.w("NoopApplication", "activeDeviceId resolve failed; using fallback", it) }
            .getOrNull() ?: WhoopBleClient.DEFAULT_DEVICE_ID
    }

    /** Process-wide BLE client. Owns the GATT connection and outlives any single Activity/ViewModel. */
    val ble: WhoopBleClient by lazy {
        WhoopBleClient(applicationContext, repository = repository, deviceId = activeDeviceId).apply {
            // Apply the persisted "Debug logging" preference at the composition root so the low-level
            // client never has to read the UI/prefs layer. Default OFF — see WhoopBleClient.debugLogcat.
            debugLogcat = NoopPrefs.debugLogging(applicationContext)
        }
    }

    /**
     * Multi-source coordinator (Phase 1B): runs exactly one device's live BLE at a time, driven by the
     * registry's active device id. DORMANT whenever the active device is the WHOOP (the default and every
     * single-WHOOP install), so the existing WHOOP flow is untouched. Only when a non-WHOOP generic HR
     * strap becomes active does it pause WHOOP and run the isolated [com.noop.ble.StandardHrSource].
     *
     * Wired to the EXISTING [ble] entry points via closures — it never touches [WhoopBleClient]
     * internals. Strap live HR is pushed into the same [ble] state flow the UI observes via
     * [WhoopBleClient.publishExternalLiveHr]. [SourceCoordinator.start] reconciles once against the
     * current active id at launch (a no-op for a single-WHOOP install); the Devices screen (next task)
     * calls [SourceCoordinator.onActiveDeviceChanged] after a setActive.
     *
     * Multi-WHOOP identity adoption: AppViewModel's init collects [WhoopBleClient.connectedPeripheralAddress]
     * (distinctUntilChanged) into [SourceCoordinator.connectedPeripheralChanged] — the Kotlin analogue of
     * macOS wiring `BLEManager.connectedPeripheralUUID` into the coordinator's adoption sink. Kept beside
     * the other `ble`-flow collectors there (see AppViewModel init).
     */
    val sourceCoordinator: SourceCoordinator by lazy {
        SourceCoordinator(
            context = applicationContext,
            registry = deviceRegistry,
            repository = repository,
            liveSink = { hr, rr -> ble.publishExternalLiveHr(hr, rr) },
            // #74: reconnect on the PERSISTED family, not the WhoopModel.WHOOP4 default - otherwise a
            // 5/MG WHOOP->WHOOP switch rescans the wrong service and misses the 5/MG direct-bond fast
            // path (status=133 on an OS-bonded strap). Mirrors macOS AppModel.scan() reading the persisted
            // "selectedWhoopModel". Same-strap switches now adopt in place (no reconnect) via the
            // coordinator, so this only fires for a genuinely different WHOOP.
            startWhoop = { ble.connect(persistedWhoopModel()) },
            stopWhoop = { ble.disconnect() },
            // Multi-WHOOP (MW-2/MW-3): pin the connection to the active WHOOP's persisted address and
            // re-attribute live samples to it on a WHOOP→WHOOP switch. Both inert on the single-WHOOP
            // path — the coordinator only invokes them for a non-legacy WHOOP / a non-null peripheralId.
            setWhoopPreferredAddress = { addr -> ble.preferredAddress = addr },
            setWhoopActiveDeviceId = { id -> ble.setActiveDeviceId(id) },
            // Generic-HR connect lifecycle → the SAME in-app strap log the user exports, so a
            // "connected but no data" report (issue #421) is no longer blind to the Polar/Wahoo/etc path.
            straplog = { ble.externalLog(it) },
            // A generic strap's standard battery (0x180F) → the same live battery field the WHOOP uses.
            batterySink = { pct -> ble.publishExternalBattery(pct) },
        )
    }

    /** The WHOOP family last seen advertising, persisted by [WhoopBleClient.persistSelectedModel] under
     *  "noop.selectedWhoopModel" in the shared noop_prefs store. Defaults to [WhoopModel.WHOOP4] when
     *  unset or unparseable (the historical connect() default), so a fresh install is unchanged. Used to
     *  reconnect on the right service after a WHOOP->WHOOP switch (#74). */
    private fun persistedWhoopModel(): WhoopModel =
        NoopPrefs.of(this).getString("noop.selectedWhoopModel", null)
            ?.let { runCatching { WhoopModel.valueOf(it) }.getOrNull() }
            ?: WhoopModel.WHOOP4
}
