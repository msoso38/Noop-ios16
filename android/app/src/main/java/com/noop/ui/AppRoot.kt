package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.outlined.GridView

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.FusionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.noop.BuildConfig
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Navigation model
//
// The macOS app's sidebar holds many sections; on Android (mirroring the iOS RootTabView) we surface
// them through a unified floating "glass" bottom bar (Today · Trends · Sleep · More) for the everyday
// screens, with a "More" sheet that lists the full grouped set — so every destination is one tap away
// without a global hamburger/drawer. Destinations are grouped exactly as the sidebar groups them.
// Routes whose screens belong to later waves point at a ComingSoon placeholder so the app compiles today.

/** A single drawer destination: stable route, display title (localized via [titleRes]), sidebar icon. */
private enum class Destination(
    val route: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    // Group: Today
    Today("today", R.string.nav_today, Icons.Filled.Home),
    Intelligence("intelligence", R.string.nav_intelligence, Icons.Filled.Psychology),
    // Optional, default-OFF (task #43): the Coupled view (WHOOP-style day read). Reached ONLY via the
    // Today dashboard "Coupled view" card tap-through, so it is deliberately NOT in any [DrawerGroup].
    CoupledView("coupled_view", R.string.nav_coupled_view, Icons.Filled.Hexagon),

    // Group: Live
    Live("live", R.string.nav_live, Icons.Filled.FavoriteBorder),
    Intervals("intervals", R.string.nav_intervals, Icons.Filled.Timeline),

    // Group: Recovery
    Sleep("sleep", R.string.nav_sleep, Icons.Filled.Bedtime),
    Breathe("breathe", R.string.nav_breathe, Icons.Filled.Air),
    Stress("stress", R.string.nav_stress, Icons.Filled.Spa),

    // Group: Activity
    Workouts("workouts", R.string.nav_workouts, Icons.Filled.FitnessCenter),
    Trends("trends", R.string.nav_trends, Icons.AutoMirrored.Filled.TrendingUp),

    // Group: Insight
    Coach("coach", R.string.nav_coach, Icons.Filled.AutoAwesome),
    InsightsHub("insights_hub", R.string.nav_insights_hub, Icons.Filled.Insights),
    Insights("insights", R.string.nav_insights, Icons.Filled.Insights),
    Explore("explore", R.string.nav_explore, Icons.Filled.Explore),
    Compare("compare", R.string.nav_compare, Icons.AutoMirrored.Filled.CompareArrows),

    // Group: Health
    Health("health", R.string.nav_health, Icons.Filled.MonitorHeart),
    Hydration("hydration", R.string.nav_hydration, Icons.Filled.WaterDrop),
    VitalSigns("vital_signs", R.string.nav_vital_signs, Icons.Filled.HealthAndSafety),
    VitalSignsDetail("vital_detail/{key}", R.string.nav_vital_signs, Icons.Filled.HealthAndSafety),
    LabBook("lab_book", R.string.nav_lab_book, Icons.Filled.HealthAndSafety),
    PeriodCalendar("period_calendar", R.string.nav_period_calendar, Icons.Filled.CalendarMonth),
    Rhythm("rhythm", R.string.nav_rhythm, Icons.Filled.MonitorHeart),
    AppleHealth("apple_health", R.string.nav_apple_health, Icons.Filled.HealthAndSafety),

    // Group: System
    Automations("automations", R.string.nav_automations, Icons.Filled.Bolt),
    // "Alarms" is the ONE alarm surface (#766): the phone-based Wake Window (light-sleep detection with a
    // guaranteed OS backup), the strap's own firmware wake-alarm, and the wind-down reminder, all in one
    // place. Previously "Wake Window" (#730), but the strap alarm moved in from Automations so the broader
    // name fits. Route id stays "smart_alarm" (display string only).
    SmartAlarm("smart_alarm", R.string.nav_alarms, Icons.Filled.Alarm),
    Devices("devices", R.string.nav_devices, Icons.Filled.Sensors),
    DataSources("data_sources", R.string.nav_data_sources, Icons.Filled.Storage),
    BackupSync("backup_sync", R.string.nav_backup_sync, Icons.Filled.CloudSync),
    FusedRecord("fused_record", R.string.nav_fused_record, Icons.AutoMirrored.Filled.CompareArrows),
    Notifications("notifications", R.string.nav_notifications, Icons.Filled.Notifications),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
    TestCentre("test_centre", R.string.nav_test_centre, Icons.Filled.BugReport),
    Goals("goals", R.string.nav_goals, Icons.Filled.Flag),
    StepTraining("step_training", R.string.nav_step_training, Icons.Filled.FitnessCenter),
    QuickStart("quick_start", R.string.nav_quick_start, Icons.Filled.Explore),

    // The "More" tab: its own navigated page (mirroring the iOS More tab) that hosts the full
    // grouped destination list. It is NOT itself in any [DrawerGroup] — it's the door to them.
    More("more", R.string.nav_more, Icons.Filled.MoreHoriz);

    companion object {
        /** Resolve the destination owning the current back-stack route (defaults to Today). */
        fun forRoute(route: String?): Destination =
            entries.firstOrNull {
                // Match parameterised routes (e.g. "vital_detail/rhr" vs "vital_detail/{key}") by
                // base path so the top-bar title resolves correctly on a detail screen, not "Today".
                it.route == route || it.route.substringBefore('/') == route?.substringBefore('/')
            } ?: Today
    }
}

/** More-page groups, mirroring the iOS More tab exactly: Insights · Body · Data · App. `defaultExpanded`
 *  mirrors the iOS S2 default: Insights + Body open at rest, Data + App collapsed to just their header. */
// [header] is the STABLE persistence key (stored in SharedPreferences and kept byte-identical to iOS's
// `more.expandedSections` CSV — see [MoreSectionPrefs]); it must NEVER be localized. [headerRes] is the
// localized DISPLAY label the More page shows. Decoupling the two lets the label translate without
// touching the persisted open/closed state or the iOS parity of the stored string.
private data class DrawerGroup(
    val header: String,
    @StringRes val headerRes: Int,
    val items: List<Destination>,
    val defaultExpanded: Boolean,
)

// Mirrors the iOS RootTabView `moreTab` grouping + order one-for-one. Today / Trends / Sleep are NOT
// listed (they're bottom-bar tabs, exactly as on iOS). Android-only screens (Vital Signs, Wake Window,
// Notifications, Devices) are slotted into the matching iOS group.
private val drawerGroups: List<DrawerGroup> = listOf(
    DrawerGroup("Insights", R.string.more_group_insights, listOf(
        Destination.InsightsHub, Destination.Intelligence, Destination.Coach,
        Destination.Insights, Destination.Explore, Destination.Compare,
    ), defaultExpanded = true),
    DrawerGroup("Body", R.string.more_group_body, listOf(
        Destination.Live, Destination.Workouts, Destination.Health, Destination.VitalSigns,
        Destination.LabBook, Destination.PeriodCalendar, Destination.Stress, Destination.Breathe,
        Destination.Intervals, Destination.Rhythm,
    ), defaultExpanded = true),
    DrawerGroup("Data", R.string.more_group_data, listOf(
        Destination.FusedRecord, Destination.AppleHealth, Destination.DataSources,
        Destination.BackupSync, Destination.Devices,
    ), defaultExpanded = false),
    DrawerGroup("App", R.string.more_group_app, listOf(
        Destination.Automations, Destination.SmartAlarm, Destination.Notifications,
        Destination.Goals, Destination.TestCentre, Destination.Settings,
    ), defaultExpanded = false),
)

/** The headers open by default at first run, derived from [drawerGroups.defaultExpanded] (Insights +
 *  Body), so the seed lives in one place and the persistence default can't drift from the UI default. */
private fun defaultExpandedHeaders(): Set<String> =
    drawerGroups.filter { it.defaultExpanded }.map { it.header }.toSet()

/**
 * Persisted open/closed state of the More page's collapsible groups (#860 item 2) - the Android twin of
 * the iOS `MoreSectionPrefs`. The set of EXPANDED group headers is stored as one sorted comma-joined
 * string under a single SharedPreferences key, encoded identically to iOS (same `more.expandedSections`
 * suffix, same CSV-of-headers, same Insights+Body default) so the two platforms behave the same. An empty
 * stored string is a valid state (everything collapsed), distinct from "never set" (which yields the seed).
 */
internal object MoreSectionPrefs {
    const val KEY = "noop.more.expandedSections"

    /** Read the expanded-header set; returns [default] when the key was never written (first run). */
    fun read(prefs: android.content.SharedPreferences, default: Set<String>): Set<String> {
        val raw = prefs.getString(KEY, null) ?: return default
        return decode(raw)
    }

    /** Persist the expanded-header set as a sorted, comma-joined string. */
    fun write(prefs: android.content.SharedPreferences, headers: Set<String>) {
        prefs.edit().putString(KEY, encode(headers)).apply()
    }

    /** Encode the set of expanded headers to a sorted, comma-joined string. */
    fun encode(headers: Set<String>): String = headers.sorted().joinToString(",")

    /** Decode the stored string to a set of expanded headers; blank tokens dropped, empty string -> empty set. */
    fun decode(raw: String): Set<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

/**
 * App shell: a single [Scaffold] with a floating [GlassBottomBar]
 * (Today · Trends · P.C. · Sleep · More)
 * driving one [NavHost], mirroring the iOS RootTabView. There is NO global toolbar and no nav drawer
 * — every screen self-titles via [ScreenScaffold], and the "More" sheet (opened from the bar) reaches
 * every destination in [drawerGroups], so nothing is lost. A single [AppViewModel] is created here and
 * shared with every screen, so the BLE connection and cached metrics stay app-wide singletons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val nav = rememberNavController()

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val current = Destination.forRoute(currentRoute)
    var showQuickActions by remember { mutableStateOf(false) }
    // The Updates inbox sheet (opened by the Today header bell). The store is a process singleton so
    // the Today cards and the import path post to the same inbox this sheet renders.
    val context = androidx.compose.ui.platform.LocalContext.current
    val updateStore = remember { UpdateStore.from(context) }
    var showUpdatesInbox by remember { mutableStateOf(false) }
    // First-run Quick Start disabled — Goals board is the durable weighted checklist.
    // Mark seen so old installs do not re-trigger if something else sets the flag false.
    LaunchedEffect(Unit) {
        NoopPrefs.of(context).edit().putBoolean("noop.quickStartSeen", true).apply()
    }

    val cycleNavVisible by viewModel.cycleTrackingEnabled.collectAsStateWithLifecycle()
    // If Cycle tab is turned off while sitting on that route, land on Today.
    LaunchedEffect(cycleNavVisible, currentRoute) {
        if (!cycleNavVisible && currentRoute == Destination.PeriodCalendar.route) {
            nav.navigateTopLevel(Destination.Today.route)
        }
    }
    // Full-screen charging (AirPods-style) + ding — any tab, not only Live.
    StrapChargingHost(viewModel)

    // Post-workout sport label ask (trains sport ID / ML).
    val pendingSport by viewModel.pendingSportConfirm.collectAsStateWithLifecycle()
    pendingSport?.let { row ->
        WorkoutSportConfirmSheet(
            suggested = row.sport,
            onConfirm = { sport -> viewModel.confirmWorkoutSport(sport) },
            onDismiss = { viewModel.dismissSportConfirm() },
        )
    }

    // Phone grip-pulse gestures (experimental approximation of Watch hand-clench).
    // Double pulse → New Workout; single → haptic only (avoid fighting system Back).
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    DisposableEffect(Unit) {
        val grip = com.noop.motion.GripGestureController(
            context = context,
            onSingle = {
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                )
            },
            onDouble = {
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                )
                nav.navigatePush(Destination.Workouts.route)
            },
        )
        grip.start()
        onDispose { grip.stop() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Palette.surfaceBase,
            bottomBar = {
                // Cycle tab only when opt-in is on — Settings / Health toggle actually removes it.
                // Cycle stays reachable from More → For your body when the tab is hidden.
                GlassBottomBar(
                    current = current,
                    showPeriodCalendarTab = cycleNavVisible,
                    onTabSelected = { dest ->
                        if (dest.route != currentRoute) nav.navigateTopLevel(dest.route)
                    },
                    // Double-tap a tab: pop nested pushes and land on that tab's main page.
                    onTabReselected = { dest ->
                        val popped = nav.popBackStack(dest.route, inclusive = false)
                        if (!popped) nav.navigateTopLevel(dest.route)
                    },
                    onLogWorkout = { nav.navigatePush(Destination.Workouts.route) },
                    onStrengthTrainer = { nav.navigatePush(Destination.StepTraining.route) },
                    onOpenSettings = { nav.navigatePush(Destination.Settings.route) },
                    onQuickRoute = { route -> nav.navigatePush(route) },
                )
            },
        ) { inner ->
            val barSwipeRoutes = remember(cycleNavVisible) {
                buildList {
                    add(Destination.Today.route)
                    add(Destination.Trends.route)
                    if (cycleNavVisible) add(Destination.PeriodCalendar.route)
                    add(Destination.Sleep.route)
                    add(Destination.More.route)
                }
            }
            NavHost(
                navController = nav,
                startDestination = Destination.Today.route,
                modifier = Modifier
                    .padding(inner)
                    // Edge swipe between primary bar tabs when already on a bar root.
                    .pointerInput(currentRoute, barSwipeRoutes) {
                        if (currentRoute !in barSwipeRoutes) return@pointerInput
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val idx = barSwipeRoutes.indexOf(currentRoute)
                                if (idx < 0) return@detectHorizontalDragGestures
                                when {
                                    total < -80f && idx < barSwipeRoutes.lastIndex ->
                                        nav.navigateTopLevel(barSwipeRoutes[idx + 1])
                                    total > 80f && idx > 0 ->
                                        nav.navigateTopLevel(barSwipeRoutes[idx - 1])
                                }
                                total = 0f
                            },
                            onHorizontalDrag = { _, dx -> total += dx },
                        )
                    },
                // Smooth slide + fade for push/pop; bar tabs still feel calm.
                enterTransition = {
                    slideInHorizontally(animationSpec = navSlideSpec) { it / 5 } + fadeIn(navFadeSpec)
                },
                exitTransition = {
                    slideOutHorizontally(animationSpec = navSlideSpec) { -it / 8 } + fadeOut(navFadeSpec)
                },
                popEnterTransition = {
                    slideInHorizontally(animationSpec = navSlideSpec) { -it / 5 } + fadeIn(navFadeSpec)
                },
                popExitTransition = {
                    slideOutHorizontally(animationSpec = navSlideSpec) { it / 5 } + fadeOut(navFadeSpec)
                },
            ) {
                // --- Live, working screens (existing waves) ---
                composable(Destination.Today.route) {
                    TodayScreen(
                        viewModel = viewModel,
                        // The quick-action "+" lives in the Today header's top-right now (off the
                        // bottom bar) — it opens the same quick-action sheet the bar used to.
                        onQuickActions = { showQuickActions = true },
                        // The Updates "ringer" — the bell sits before the +, and opens the inbox
                        // sheet AppRoot presents (it owns the nav for deep-links).
                        updateStore = updateStore,
                        onOpenUpdates = { showUpdatesInbox = true },
                        // The leading profile avatar opens Settings (where the photo is set/changed),
                        // mirroring iOS's avatar-leading Today header. The drawer hamburger is unchanged.
                        onOpenSettings = { nav.navigatePush(Destination.Settings.route) },
                        // The opt-in Hydration card (only shown when Hydration tracking is on) pushes its
                        // detail. A normal push so the back-stack returns to Today.
                        onOpenHydration = { nav.navigate(Destination.Hydration.route) },
                        // #706/#684: the dashboard cards draw a tappable chevron; wire each to its detail,
                        // matching iOS. Stress + the vitals are pushes; Sleep is a top-level tab switch.
                        onOpenStress = { nav.navigatePush(Destination.Stress.route) },
                        onOpenHealth = { nav.navigatePush(Destination.Health.route) },
                        // Every metric/vital card opens its OWN focused detail trend (vital_detail/<key>),
                        // not the shared Health hub (2026-07-03). Mirrors the iOS liquidCard metricDetail.
                        onOpenMetric = { key -> nav.navigatePush("vital_detail/$key") },
                        onOpenSleep = { nav.navigateTopLevel(Destination.Sleep.route) },
                        // Optional Coupled view card (task #43): a normal push so back returns to Today.
                        onOpenCoupled = { nav.navigatePush(Destination.CoupledView.route) },
                        // The "workout in progress" indicator: raise the one-shot the Live screen consumes to
                        // re-open the in-exercise overlay, then route to Live. One tap from Today (iOS parity).
                        onOpenActiveWorkout = {
                            viewModel.openActiveWorkout()
                            nav.navigatePush(Destination.Live.route)
                        },
                        // The liquid header's strap battery ring taps through to Devices (iOS parity: the
                        // battery ring → router.openDevices()).
                        onOpenDevices = { nav.navigatePush(Destination.Devices.route) },
                    )
                }
                composable(Destination.Live.route) {
                    LiveScreen(
                        viewModel = viewModel,
                        onManageDevices = { nav.navigatePush(Destination.Devices.route) },
                    )
                }
                composable(Destination.Sleep.route) {
                    SleepScreen(
                        vm = viewModel,
                        onOpenJournal = { nav.navigatePush(Destination.Insights.route) },
                        onOpenAlarm = { nav.navigatePush(Destination.SmartAlarm.route) },
                    )
                }
                composable(Destination.CoupledView.route) {
                    CoupledScreen(
                        vm = viewModel,
                        // Tapping Sleep in the coupled read opens the full Sleep screen (iOS parity).
                        onOpenSleep = { nav.navigateTopLevel(Destination.Sleep.route) },
                    )
                }
                composable(Destination.Intervals.route) { IntervalsScreen(viewModel) }
                composable(Destination.Breathe.route) { BreatheScreen(viewModel) }
                composable(Destination.Coach.route) { CoachScreen() }
                composable(Destination.Explore.route) { TrendsExploreScreen(viewModel) }
                composable(Destination.Automations.route) { AutomationsScreen(viewModel) }
                composable(Destination.SmartAlarm.route) { SmartAlarmScreen(viewModel) }
                composable(Destination.Workouts.route) { WorkoutsScreen(viewModel) }
                composable(Destination.Intelligence.route) { IntelligenceScreen(viewModel) }

                // --- Placeholder routes (later waves fill these in) ---
                composable(Destination.Stress.route) {
                    StressScreen(
                        vm = viewModel,
                        onBreathe = { nav.navigatePush(Destination.Breathe.route) },
                    )
                }
                composable(Destination.Trends.route) { TrendsScreen(viewModel) }
                composable(Destination.Insights.route) {
                    InsightsScreen(viewModel, onOpenInsightsHub = { nav.navigatePush(Destination.InsightsHub.route) })
                }
                composable(Destination.Compare.route) { CompareScreen(viewModel) }
                composable(Destination.Health.route) {
                    HealthScreen(
                        vm = viewModel,
                        onVitalClick = { nav.navigatePush("vital_detail/$it") },
                        onOpenLabBook = { nav.navigatePush(Destination.LabBook.route) },
                        onOpenFusedRecord = { nav.navigatePush(Destination.FusedRecord.route) },
                        onOpenPeriodCalendar = { nav.navigateTopLevel(Destination.PeriodCalendar.route) },
                    )
                }
                composable(Destination.Hydration.route) { HydrationScreen(viewModel) }
                composable(Destination.VitalSigns.route) {
                    VitalSignsScreen(
                        vm = viewModel,
                        onVitalClick = { nav.navigatePush("vital_detail/$it") },
                    )
                }
                composable(Destination.VitalSignsDetail.route) { backStackEntry ->
                    VitalDetailScreen(
                        vm = viewModel,
                        key = backStackEntry.arguments?.getString("key").orEmpty(),
                    )
                }
                // --- v5 pillar screens (Wave 3 wiring) ---
                composable(Destination.InsightsHub.route) { InsightsHubScreen(viewModel) }
                composable(Destination.LabBook.route) { LabBookScreen(viewModel) }
                composable(Destination.PeriodCalendar.route) { PeriodCalendarScreen(viewModel) }
                composable(Destination.Rhythm.route) {
                    // EXPERIMENTAL: self-gates on its own consent clickwrap (default OFF). The night
                    // summary + per-window Poincaré results land with the rhythm capture pipeline; until
                    // then it renders its honest "no clear reading yet" empty state behind the gate.
                    RhythmScreen(night = null, windows = emptyList())
                }
                composable(Destination.FusedRecord.route) { FusedRecordRoute(viewModel) }
                composable(Destination.AppleHealth.route) { AppleHealthScreen(viewModel) }
                composable(Destination.Devices.route) {
                    DevicesScreen(
                        viewModel,
                        onUseFileImport = { nav.navigatePush(Destination.DataSources.route) },
                        onOpenLive = { nav.navigatePush(Destination.Live.route) },
                    )
                }
                composable(Destination.DataSources.route) { DataSourcesScreen(viewModel) }
                composable(Destination.BackupSync.route) { BackupSyncScreen() }
                composable(Destination.Notifications.route) { NotificationsSettingsScreen(viewModel) }
                composable(Destination.Settings.route) {
                    SettingsScreen(
                        viewModel,
                        onOpenTestCentre = { nav.navigatePush(Destination.TestCentre.route) },
                        onOpenGoals = { nav.navigatePush(Destination.Goals.route) },
                        onOpenBackupSync = { nav.navigatePush(Destination.BackupSync.route) },
                        onOpenStepTraining = { nav.navigatePush(Destination.StepTraining.route) },
                        onOpenQuickStart = { nav.navigatePush(Destination.QuickStart.route) },
                    )
                }
                composable(Destination.TestCentre.route) { TestCentreScreen(viewModel) }
                composable(Destination.Goals.route) {
                    GoalsBoardScreen(
                        onOpenLive = { nav.navigatePush(Destination.Live.route) },
                        onOpenSettings = { nav.navigatePush(Destination.Settings.route) },
                        onOpenTestCentre = { nav.navigatePush(Destination.TestCentre.route) },
                        onOpenSleep = { nav.navigateTopLevel(Destination.Sleep.route) },
                        onOpenToday = { nav.navigateTopLevel(Destination.Today.route) },
                        onOpenDevices = { nav.navigatePush(Destination.Devices.route) },
                        onOpenHealth = { nav.navigatePush(Destination.Health.route) },
                    )
                }
                composable(Destination.StepTraining.route) { StepTrainingScreen(viewModel) }
                composable(Destination.QuickStart.route) {
                    QuickStartGuideScreen(onDone = { nav.popBackStack() })
                }
                // The "More" page — drill-ins PUSH so system back returns to More, not Home.
                composable(Destination.More.route) {
                    MoreScreen(onNavigate = { nav.navigatePush(it) })
                }
            }
        }

        // Quick-actions sheet, opened by the raised gold centre FAB. Each row routes to an
        // existing destination — nothing new is built here, the FAB is just a faster door in.
        if (showQuickActions) {
            ModalBottomSheet(
                onDismissRequest = { showQuickActions = false },
                containerColor = Palette.surfaceRaised,
                contentColor = Palette.textPrimary,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 24.dp),
                ) {
                    Overline(
                        "Quick actions",
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp),
                        color = Palette.textTertiary,
                    )
                    // Updates inbox — relocated here off the Today header (the liquid Today header mirrors iOS,
                    // which has no notifications bell). The feature is fully intact and one tap away: this row
                    // opens the same inbox sheet, showing the unread count as a trailing badge.
                    NavigationDrawerItem(
                        selected = false,
                        onClick = {
                            showQuickActions = false
                            showUpdatesInbox = true
                        },
                        icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                        label = { Text("Updates", style = NoopType.body) },
                        badge = {
                            val unread = updateStore.unreadCount
                            if (unread > 0) {
                                Text(
                                    if (unread > 99) "99+" else unread.toString(),
                                    style = NoopType.captionNumber,
                                    color = Palette.statusCritical,
                                )
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Palette.surfaceRaised,
                            unselectedIconColor = Palette.accent,
                            unselectedTextColor = Palette.textPrimary,
                        ),
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                    quickActions.forEach { action ->
                        NavigationDrawerItem(
                            selected = false,
                            onClick = {
                                showQuickActions = false
                                if (action.route != currentRoute) {
                                    nav.navigateTopLevel(action.route)
                                }
                            },
                            icon = { Icon(action.icon, contentDescription = null) },
                            label = { Text(stringResource(action.titleRes), style = NoopType.body) },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = Palette.surfaceRaised,
                                unselectedIconColor = Palette.accent,
                                unselectedTextColor = Palette.textPrimary,
                            ),
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            }
        }

        // The Updates inbox (opened by the Today header bell). Presented here so it has the nav for
        // deep-links — a row's "trends" key switches the bottom tab, mirroring the iOS NavRouter route.
        if (showUpdatesInbox) {
            ModalBottomSheet(
                onDismissRequest = { showUpdatesInbox = false },
                // Open full-height (no half-pull) so it reads like the iOS Updates sheet, and use the
                // BEIGE surfaceBase so the white NoopCards POP — surfaceRaised made white cards sit on a
                // white sheet (no contrast), which is why the Android inbox looked flat vs iOS.
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Palette.surfaceBase,
                contentColor = Palette.textPrimary,
            ) {
                UpdatesInboxScreen(
                    store = updateStore,
                    onClose = { showUpdatesInbox = false },
                    onDeepLink = { key ->
                        // Map the inbox deep-link key to a route (only known keys route). "trends" is
                        // the one real poster's target today; unknown keys just close the sheet.
                        val route = when (key) {
                            "trends" -> Destination.Trends.route
                            else -> null
                        }
                        if (route != null && route != currentRoute) nav.navigateTopLevel(route)
                    },
                    onRestore = { cardId ->
                        // Flip the shared dismissed flag back off so the card reappears, and signal a
                        // mounted Today to re-read it immediately (SharedPreferences isn't reactive).
                        TodayCardDismissal.setDismissed(context, cardId, false)
                        updateStore.restoreRequest = cardId
                    },
                )
            }
        }

        // DEBUG: pin notes anywhere so multiple UI fixes can be batched for the agent.
        if (BuildConfig.DEBUG) {
            ReviewPinOverlay(currentRoute = currentRoute)
        }
    }
}

// MARK: - More page
//
// The "More" tab's destination — a full navigated page (mirroring the iOS More tab's NavigationStack
// List), replacing the old pull-up ModalBottomSheet. It hosts the SAME grouped destinations
// ([drawerGroups]) inside a [ScreenScaffold], with the exact section-header + row styling the sheet
// used (uppercase [Overline] group labels, icon + label [NavigationDrawerItem] rows) — now with a
// trailing chevron so each row reads as a navigation push, matching the iOS disclosure rows. Tapping a
// row navigates top-level; there is no sheet to dismiss. The floating bottom bar stays visible because
// this is just another NavHost destination under the same Scaffold.

/** The full grouped destination list as a navigated page (the iOS More tab's twin). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreScreen(onNavigate: (String) -> Unit) {
    // S2 parity: each group's open/closed state, seeded from `defaultExpanded` (Insights + Body open,
    // Data + App collapsed). PERSISTED (#860 item 2): the user's open/closed choice must survive leaving
    // and re-entering the More page (and relaunch), not reset to the seed every visit. Backed by
    // [MoreSectionPrefs] (a CSV of expanded headers in SharedPreferences), mirroring the iOS
    // @AppStorage("more.expandedSections"). Seeded ONCE from the stored value so first run still shows the
    // Insights+Body default; every toggle writes through so the next visit reflects the saved state.
    val context = androidx.compose.ui.platform.LocalContext.current
    val expanded = remember {
        val stored = MoreSectionPrefs.read(NoopPrefs.of(context), defaultExpandedHeaders())
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
            drawerGroups.forEach { put(it.header, stored.contains(it.header)) }
        }
    }
    // Impeccable: liquid sky + short destination copy so Cycle / Lab Book / training are first-class.
    LazyScreenScaffold(
        title = "More",
        subtitle = "Cycle · Lab Book · train · settings",
        topBackground = { LiquidScreenSky() },
    ) {
        // Debug builds: charging preview at the top of More (Test Centre is under collapsed App).
        if (BuildConfig.DEBUG) {
            item {
                NoopCard(tint = Palette.statusPositive) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("UI demo", style = NoopType.headline, color = Palette.textPrimary)
                        Text(
                            "Preview the charging overlay without a strap. Also under More → App → Test Centre.",
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                        NoopButton(
                            text = "Preview charging animation",
                            leadingIcon = Icons.Filled.Bolt,
                            fullWidth = true,
                            onClick = { ChargingUiPreview.show(67.0) },
                        )
                        TextButton(onClick = { onNavigate(Destination.TestCentre.route) }) {
                            Text("Open Test Centre", style = NoopType.body, color = Palette.accent)
                        }
                    }
                }
            }
        }
        // Pin body-care destinations at the top so Cycle / Lab Book are never buried in a collapsed group.
        item {
            NoopCard(tint = Palette.restColor) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("For your body", style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        "Cycle calendar, Lab Book (cuff BP), and step training — one tap each.",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        }
        item {
            NoopCard(padding = 0.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        Destination.PeriodCalendar,
                        Destination.LabBook,
                        Destination.StepTraining,
                        Destination.Health,
                    ).forEachIndexed { i, dest ->
                        MoreRow(dest = dest, onClick = { onNavigate(dest.route) })
                        if (i < 3) {
                            HorizontalDivider(
                                color = Palette.hairline,
                                modifier = Modifier.padding(start = 50.dp),
                            )
                        }
                    }
                }
            }
        }
        // Mirror the iOS More page groups (collapsible).
        drawerGroups.forEach { group ->
            val isOpen = expanded[group.header] ?: group.defaultExpanded
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoreGroupHeader(
                        title = stringResource(group.headerRes),
                        expanded = isOpen,
                        onToggle = {
                            expanded[group.header] = !isOpen
                            val open = drawerGroups.map { it.header }.filter { expanded[it] == true }.toSet()
                            MoreSectionPrefs.write(NoopPrefs.of(context), open)
                        },
                    )
                    if (isOpen) {
                        NoopCard(padding = 0.dp) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                group.items.forEachIndexed { i, dest ->
                                    MoreRow(dest = dest, onClick = { onNavigate(dest.route) })
                                    if (i < group.items.lastIndex) {
                                        HorizontalDivider(
                                            color = Palette.hairline,
                                            modifier = Modifier.padding(start = 50.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A tappable group header for the More page (S2): the same UPPERCASE [Overline] label as before, now
 *  with a trailing chevron that rotates between open (0deg) and closed (-90deg), mirroring the iOS
 *  collapsible More sections. Tapping toggles the group; the whole row is the tap target. */
@Composable
private fun MoreGroupHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 240, easing = NavEasing),
        label = "moreGroupChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = title
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Overline(title, modifier = Modifier.weight(1f), color = Palette.textTertiary)
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier
                .size(Metrics.iconSmall)
                .rotate(rotation),
        )
    }
}

/** One tappable destination row in the More page — accent icon + title + trailing chevron in a
 *  comfortable tap target, mirroring the iOS MoreRow. */
@Composable
private fun MoreRow(dest: Destination, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(dest.icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(stringResource(dest.titleRes), style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(Metrics.iconSmall),
        )
    }
}

/**
 * Crescent capsule: stadium with a shallow circular bite for the centre +.
 * Bite is shallow so the rim nearly kisses the +; deep bites leave a canyon
 * even when Row spacing is tight.
 */
private class CrescentBarShape(
    private val biteFromRight: Boolean,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = size.height / 2f
        val bar = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
        }
        // Shallow cup: rim barely kisses the + (deeper bites / far centres leave a chasm).
        val biteR = size.height * 0.48f
        val cx = if (biteFromRight) size.width + biteR * 0.12f else -biteR * 0.12f
        val bite = Path().apply {
            addOval(Rect(center = Offset(cx, size.height / 2f), radius = biteR))
        }
        val out = Path().apply {
            op(bar, bite, PathOperation.Difference)
        }
        return Outline.Generic(out)
    }
}

// MARK: - Glass bottom bar
//
// Three crescent islands: left tabs · floating + · right tabs. Theme packs may enable frosted glass.

/** A single bottom-bar nav slot: the destination it switches to, plus the bar-specific icon/label. */
private data class BarTab(val dest: Destination, val icon: ImageVector, @StringRes val labelRes: Int)

/** The nav slots in iOS order: Today · Trends · Sleep · More.
 *  More is special-cased (it opens the sheet rather than a route), so it is appended at the call site. */
// Bottom bar: Today · Trends · P.C. · Sleep · More  (P.C. sits between Trends and Sleep).
private val barLeadingTabs = listOf(
    BarTab(Destination.Today, Icons.Outlined.GridView, R.string.nav_today),
    BarTab(Destination.Trends, Icons.AutoMirrored.Filled.TrendingUp, R.string.nav_trends),
    BarTab(Destination.PeriodCalendar, Icons.Filled.CalendarMonth, R.string.nav_period_calendar),
)
private val barTrailingTabs = listOf(
    BarTab(Destination.Sleep, Icons.Filled.Bedtime, R.string.nav_sleep),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlassBottomBar(
    current: Destination,
    onTabSelected: (Destination) -> Unit,
    onTabReselected: (Destination) -> Unit,
    /** Cycle only appears on the bar when period tracking is opted in; otherwise use More → Cycle. */
    showPeriodCalendarTab: Boolean = false,
    onLogWorkout: () -> Unit = {},
    onStrengthTrainer: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onQuickRoute: (String) -> Unit = {},
) {
    val barShape = RoundedCornerShape(50)
    val leftCrescent = remember { CrescentBarShape(biteFromRight = true) }
    val rightCrescent = remember { CrescentBarShape(biteFromRight = false) }
    val frosted = ThemePackPrefs.current.frostedNav
    val islandColor = if (frosted) {
        if (Palette.isLight) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.14f)
    } else {
        Palette.surfaceRaised.copy(alpha = 0.88f)
    }
    val islandBorder = if (frosted) {
        Color.White.copy(alpha = if (Palette.isLight) 0.55f else 0.28f)
    } else {
        Palette.hairline.copy(alpha = 0.45f)
    }
    val leading = remember(showPeriodCalendarTab) {
        if (showPeriodCalendarTab) barLeadingTabs
        else barLeadingTabs.filter { it.dest != Destination.PeriodCalendar }
    }
    val allTabs = remember(leading) {
        leading + barTrailingTabs + listOf(
            BarTab(Destination.More, Icons.Filled.MoreHoriz, R.string.nav_more),
        )
    }
    // Balanced crescents: odd counts put the extra on the LEFT so Cycle stays with
    // Today/Trends (DESIGN.md order) and Sleep|More stay a paired right island.
    val mid = barLeftTabCount(allTabs.size)
    var showPlusSheet by remember { mutableStateOf(false) }

    if (showPlusSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPlusSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.surfaceRaised,
            contentColor = Palette.textPrimary,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Quick actions", style = NoopType.headline, color = Palette.textPrimary)
                Text(
                    "Log workouts or jump to appearance. Themes and layout live in Settings.",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                WetBounceButton(
                    label = "Log workout",
                    modifier = Modifier.fillMaxWidth(),
                    tint = Palette.effortColor,
                    onClick = {
                        showPlusSheet = false
                        onLogWorkout()
                    },
                )
                WetBounceButton(
                    label = "Strength trainer",
                    modifier = Modifier.fillMaxWidth(),
                    tint = Palette.accent,
                    onClick = {
                        showPlusSheet = false
                        onStrengthTrainer()
                    },
                )
                WetBounceButton(
                    label = "Themes & appearance",
                    modifier = Modifier.fillMaxWidth(),
                    tint = Palette.metricRose,
                    onClick = {
                        showPlusSheet = false
                        onOpenSettings()
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp)
            .padding(top = 0.dp, bottom = Metrics.space8),
        contentAlignment = Alignment.Center,
    ) {
        // + lives IN the gutter between weighted crescents (not Box-centered on full
        // width). Uneven 3|2 weights otherwise leave the + floating off the bite seam.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            contentAlignment = Alignment.Center,
        ) {
            val leftTabs = allTabs.take(mid)
            val rightTabs = allTabs.drop(mid)
            val leftWeight = leftTabs.size.coerceAtLeast(1).toFloat()
            val rightWeight = rightTabs.size.coerceAtLeast(1).toFloat()
            // Plus diameter 50dp; gutter ≈ plus so crescents nest and kiss (no floating chasm).
            val plusGutter = 36.dp

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = leftCrescent,
                    color = islandColor,
                    tonalElevation = 0.dp,
                    shadowElevation = if (frosted) 2.dp else 5.dp,
                    modifier = Modifier
                        .weight(leftWeight)
                        .border(1.dp, islandBorder, leftCrescent),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leftTabs.forEach { tab ->
                            BarSlot(
                                icon = tab.icon,
                                label = stringResource(tab.labelRes),
                                active = isBarTabActive(tab, current),
                                modifier = Modifier.weight(1f),
                                onClick = { onTabSelected(tab.dest) },
                                onDoubleClick = { onTabReselected(tab.dest) },
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .width(plusGutter)
                        .height(58.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CenterPlusButton(
                        onClick = { showPlusSheet = true },
                        radialActions = listOf(
                            // Triangle: Workout at TOP (most common), Live / Journal at base.
                            PlusRadialAction("New Workout", Icons.Filled.FitnessCenter, Destination.Workouts.route),
                            PlusRadialAction("Live HR", Icons.Filled.MonitorHeart, Destination.Live.route),
                            PlusRadialAction("Journal", Icons.Filled.Edit, Destination.Insights.route),
                        ),
                        onRadialSelect = { action ->
                            onQuickRoute(action.route)
                        },
                    )
                }

                Surface(
                    shape = rightCrescent,
                    color = islandColor,
                    tonalElevation = 0.dp,
                    shadowElevation = if (frosted) 2.dp else 5.dp,
                    modifier = Modifier
                        .weight(rightWeight)
                        .border(1.dp, islandBorder, rightCrescent),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rightTabs.forEach { tab ->
                            BarSlot(
                                icon = tab.icon,
                                label = stringResource(tab.labelRes),
                                active = isBarTabActive(tab, current),
                                onClick = { onTabSelected(tab.dest) },
                                onDoubleClick = { onTabReselected(tab.dest) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * How many tabs sit in the left crescent.
 * 4 tabs → 2|2. 5 tabs (Cycle on) → 3|2 so Cycle stays with Today/Trends.
 */
internal fun barLeftTabCount(totalTabs: Int): Int = when {
    totalTabs <= 0 -> 0
    totalTabs <= 4 -> totalTabs / 2
    else -> (totalTabs + 1) / 2
}

private fun isBarTabActive(tab: BarTab, current: Destination): Boolean {
    return if (tab.dest == Destination.More) {
        current != Destination.Today && current != Destination.Trends &&
            current != Destination.PeriodCalendar && current != Destination.Sleep
    } else {
        current == tab.dest
    }
}

/** One vertex of the hold-to-reveal triangle around the centre +. */
private data class PlusRadialAction(
    val label: String,
    val icon: ImageVector,
    val route: String,
)

/** Pulsing centre + with frosted-glass hold bloom and triangular radial actions (hold-and-swipe). */
@Composable
private fun CenterPlusButton(
    onClick: () -> Unit,
    radialActions: List<PlusRadialAction>,
    onRadialSelect: (PlusRadialAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var holding by remember { mutableStateOf(false) }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = LocalDensity.current
    val aura = ThemePackPrefs.current.swatch
    val infinite = rememberInfiniteTransition(label = "plusPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "plusPulseScale",
    )
    val glow by infinite.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "plusPulseGlow",
    )
    val auraSpin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "plusAura",
    )
    val holdBloom by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 420f),
        label = "plusHoldBloom",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (holding) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessHigh),
        label = "plusPress",
    )
    val elev by animateFloatAsState(
        targetValue = if (holding) 2f else 10f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessHigh),
        label = "plusElev",
    )

    // Triangle: top (New Workout) · bottom-left (Live) · bottom-right (Journal).
    val radialOffsetsDp = listOf(
        0.dp to (-128).dp,
        (-108).dp to 58.dp,
        108.dp to 58.dp,
    )

    fun pickIndex(localX: Float, localY: Float): Int? {
        if (radialActions.isEmpty()) return null
        val cx = with(density) { 28.dp.toPx() }
        val cy = with(density) { 28.dp.toPx() }
        var best = -1
        var bestDist = Float.MAX_VALUE
        radialOffsetsDp.forEachIndexed { i, (ox, oy) ->
            if (i >= radialActions.size) return@forEachIndexed
            val tx = cx + with(density) { ox.toPx() }
            val ty = cy + with(density) { oy.toPx() }
            val d = (localX - tx) * (localX - tx) + (localY - ty) * (localY - ty)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        val minSwipe = with(density) { 36.dp.toPx() }
        val fromCentre = kotlin.math.sqrt((localX - cx) * (localX - cx) + (localY - cy) * (localY - cy))
        return if (fromCentre >= minSwipe && best >= 0) best else null
    }

    Box(
        modifier = modifier.size(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Soft theme aura under the + (visible idle glow — not bolted chrome).
        Canvas(
            Modifier
                .size(64.dp)
                .graphicsLayer { alpha = 0.55f + 0.35f * glow },
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        aura.copy(alpha = 0.55f),
                        aura.copy(alpha = 0.18f),
                        Color.Transparent,
                    ),
                ),
                radius = size.minDimension * 0.52f,
            )
        }
        Canvas(
            Modifier
                .size(56.dp)
                .graphicsLayer { alpha = 0.55f + 0.40f * holdBloom },
        ) {
            val r = size.minDimension * (0.44f + 0.10f * holdBloom)
            drawArc(
                color = aura.copy(alpha = 0.70f + 0.25f * holdBloom),
                startAngle = -90f + auraSpin * 360f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        if (holding || holdBloom > 0.02f) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.Center,
                onDismissRequest = { },
                properties = androidx.compose.ui.window.PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = holdBloom }
                        .then(
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                Modifier.blur((18 + 10 * holdBloom).dp)
                            } else {
                                Modifier
                            },
                        )
                        .background(Color.Black.copy(alpha = 0.38f * holdBloom)),
                )
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .padding(bottom = 78.dp)
                            .size(300.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size((148 + 36 * holdBloom).dp)
                                .graphicsLayer { alpha = holdBloom * 0.85f }
                                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                        )
                        radialActions.zip(radialOffsetsDp).forEachIndexed { i, (action, off) ->
                            val selected = highlighted == i
                            val pop by animateFloatAsState(
                                targetValue = if (holdBloom > 0.5f) 1f else holdBloom,
                                animationSpec = spring(
                                    dampingRatio = 0.38f,
                                    stiffness = 380f + i * 40f,
                                ),
                                label = "radialPop$i",
                            )
                            val scaleSel = if (selected) 1.16f else 1f
                            Column(
                                Modifier
                                    .offset(x = off.first, y = off.second)
                                    .scale(scaleSel * (0.72f + 0.28f * pop))
                                    .graphicsLayer { alpha = pop },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(56.dp)
                                        .shadow(if (selected) 14.dp else 6.dp, CircleShape, clip = false)
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) aura.copy(alpha = 0.95f)
                                            else Color.White.copy(alpha = 0.20f),
                                        )
                                        .border(
                                            1.5.dp,
                                            Color.White.copy(alpha = if (selected) 0.85f else 0.38f),
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        action.icon,
                                        contentDescription = action.label,
                                        tint = if (selected) Color(0xFF1A1208) else Color.White,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                                Text(
                                    action.label,
                                    style = NoopType.caption,
                                    color = Color.White.copy(alpha = if (selected) 1f else 0.78f),
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(50.dp)
                .scale(pulse * pressScale)
                .shadow(elev.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.42f),
                            aura,
                            aura.copy(alpha = 0.88f),
                        ),
                    ),
                )
                .border(1.5.dp, Color.White.copy(alpha = glow + 0.20f * holdBloom), CircleShape)
                .pointerInput(radialActions) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            holding = true
                            highlighted = null
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                            )
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                val selected = pickIndex(change.position.x, change.position.y)
                                if (selected != highlighted) {
                                    highlighted = selected
                                    if (selected != null) {
                                        haptic.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                        )
                                    }
                                }
                                if (!change.pressed) break
                            } while (true)
                            holding = false
                            val pick = highlighted
                            highlighted = null
                            if (pick != null && pick in radialActions.indices) {
                                onRadialSelect(radialActions[pick])
                            } else {
                                val travel = down.position
                                val cx = with(density) { 28.dp.toPx() }
                                val cy = with(density) { 28.dp.toPx() }
                                val dist = kotlin.math.sqrt(
                                    (travel.x - cx) * (travel.x - cx) + (travel.y - cy) * (travel.y - cy),
                                )
                                if (dist < with(density) { 24.dp.toPx() }) {
                                    onClick()
                                }
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription =
                        "Quick actions. Press and hold, then swipe to New Workout (top), Live HR, or Journal."
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .size(width = 22.dp, height = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.30f)),
            )
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = Color(0xFF1A1208),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Tab label only — liquid pill is the sole active chrome (no second stacked chip). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BarSlot(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    val tint = if (active) Palette.accent else Palette.textSecondary
    Column(
        modifier = modifier
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onDoubleClick = onDoubleClick,
            )
            .padding(vertical = 4.dp, horizontal = 1.dp)
            .semantics { contentDescription = "$label. Double tap to return to its main page." },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Metrics.iconSmall))
        Text(
            label,
            style = NoopType.footnote.copy(
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = tint,
            maxLines = 1,
        )
    }
}

/** A centre-FAB quick action: a display title, an icon and the destination route it opens. */
private data class QuickAction(@StringRes val titleRes: Int, val icon: ImageVector, val route: String)

/** The quick actions on the gold centre FAB, each routing to an existing destination. Live HR leads
 *  — it moved off the bottom bar (so the FAB no longer overlaps a tab) but stays one tap away here. */
private val quickActions: List<QuickAction> = listOf(
    QuickAction(R.string.action_live_hr, Destination.Live.icon, Destination.Live.route),
    QuickAction(R.string.action_start_workout, Icons.Filled.FitnessCenter, Destination.Workouts.route),
    QuickAction(R.string.action_log_journal, Icons.Filled.Edit, Destination.Insights.route),
    QuickAction(R.string.action_breathe, Icons.Filled.Air, Destination.Breathe.route),
)

// MARK: - Navigation motion (README §Motion)
//
// The global easing is the calm, decelerating cubic-bezier(0.22, 1, 0.36, 1) — nothing
// bounces or overshoots. Top-level destination switches crossfade over ~240ms (README
// "Tab crossfade"); the same spec drives back navigation so the bar never feels jerky.

/** The calm global easing curve from the handoff (cubic-bezier 0.22, 1, 0.36, 1). */
private val NavEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** Snappier ~160ms crossfade — still calm easing, less lag between roots. */
private val navFadeSpec = tween<Float>(durationMillis = 160, easing = NavEasing)
private val navSlideSpec = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 180, easing = NavEasing)

/**
 * BrandMark — the NOOP logo glyph at a small in-app size: an OPEN recovery ring (≈80%
 * arc, round caps, starting at −90° / 12 o'clock, clockwise) in the gold gradient with a
 * solid gold core dot at the centre. This is the same brand glyph the RecoveryRing hero
 * carries (the "O" of NOOP), shrunk for the top bar / drawer header so the logo reads in
 * app. CLEAN/flat per the v3 restraint brief — no bloom, no halo, just the gradient ring.
 * Token-only (gold gradient + hairline track); decorative, so it carries no content label.
 */
@Composable
internal fun BrandMark(size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.13f          // ~2px-equivalent at 22dp
        val radius = (this.size.minDimension - stroke) / 2f
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val capStroke = Stroke(width = stroke, cap = StrokeCap.Round)

        // Faint full-ring track (navy hairline) behind the open arc.
        drawCircle(
            color = Palette.hairline.copy(alpha = 0.5f),
            radius = radius,
            center = center,
            style = capStroke,
        )
        // Open recovery-ring arc: ~80% (288°), −90° start (12 o'clock), clockwise.
        drawArc(
            color = Palette.chargeColor,
            startAngle = -90f,
            sweepAngle = 288f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = capStroke,
        )
        // No centre glow-dot — user feedback: mid-element dots looked noisy on many screens.
    }
}

/**
 * Bottom-bar tab switch: single-top + save/restore state for each tab root.
 * Use ONLY for Today / Trends / P.C. / Sleep / More bar slots.
 */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
            // Keep the start destination so the stack is [Today, …] and back from a bar tab
            // can still pop intermediate push destinations correctly.
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Drill-in navigation that PRESERVES the back stack (Settings, Lab Book from Health, etc.).
 * System back / edge swipe returns to the previous screen, not always Home.
 */
private fun NavHostController.navigatePush(route: String) {
    navigate(route) {
        launchSingleTop = true
        // restoreState not used — each push is a fresh instance of the destination on the stack
    }
}

/** Primary bottom-bar routes in left-to-right order for edge-swipe between menus.
 *  Period calendar is inserted dynamically when cycle tracking is on (see GlassBottomBar). */
private val barSwipeRoutesBase = listOf(
    Destination.Today.route,
    Destination.Trends.route,
    Destination.Sleep.route,
    Destination.More.route,
)

/**
 * Loader for the v5 "Your Data, Fused" screen: assembles today's [FusedRecord] off the repository via
 * [AppViewModel.fusedRecordForToday] (the pure FusionResolver per metric) and hands the pure
 * [FusedRecordScreen] its read-model. Keeps the screen itself I/O-free + previewable. Re-loads on entry.
 */
@Composable
private fun FusedRecordRoute(viewModel: AppViewModel) {
    var record by remember {
        mutableStateOf(FusedRecord(rows = emptyList(), dayOwner = null as FusionSource?, contributingSourceCount = 0))
    }
    LaunchedEffect(Unit) {
        record = runCatching { viewModel.fusedRecordForToday() }.getOrDefault(record)
    }
    FusedRecordScreen(record = record)
}

/**
 * Placeholder screen for routes later waves will build. Uses [ScreenScaffold] so the
 * dark, instrument-grade chrome is already correct when a real screen replaces it.
 */
@Composable
fun ComingSoon(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NoopCard(padding = 28.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Sensors,
                    contentDescription = null,
                    tint = Palette.textTertiary,
                )
                Spacer(Modifier.height(4.dp))
                Text(text, style = NoopType.title2, color = Palette.textPrimary, textAlign = TextAlign.Center)
                Overline("Coming soon", color = Palette.textSecondary)
                Text(
                    "This section is on the way.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
