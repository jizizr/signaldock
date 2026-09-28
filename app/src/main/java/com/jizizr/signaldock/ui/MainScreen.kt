package com.jizizr.signaldock.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.HyperIslandHelper
import com.jizizr.signaldock.DiagnosticLogStore
import com.jizizr.signaldock.DiagnosticLogActivity
import com.jizizr.signaldock.AppUiSettingsStore
import com.jizizr.signaldock.AppShell
import com.jizizr.signaldock.AccessibilityScreenshotService
import com.jizizr.signaldock.AutoPageActivity
import com.jizizr.signaldock.AutoPageBackgroundPolicy
import com.jizizr.signaldock.AutoPageProfileStore
import com.jizizr.signaldock.R
import com.jizizr.signaldock.QuickSettingsTileHelper
import com.jizizr.signaldock.RecognitionHistoryDetailActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/** Main status and configuration surface for the screenshot analysis pipeline. */
@Composable
fun MainScreen(
    shizuku: ShizukuState,
    notificationGranted: Boolean,
    onRequestShizuku: () -> Unit,
    onRequestNotification: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val settings = rememberAiSettingsState()
    val supportsIsland = remember { HyperIslandHelper.shouldUseSuperIsland(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val customModelSavedMessage = stringResource(R.string.custom_model_saved)
    val autoPageEnableFailedMessage = stringResource(R.string.auto_page_accessibility_failed)
    val autoPageRepairSuccessMessage = stringResource(R.string.auto_page_accessibility_repair_success)
    var showCustomModel by rememberSaveable { mutableStateOf(false) }
    var splashAnimationEnabled by rememberSaveable {
        mutableStateOf(AppUiSettingsStore.splashAnimationEnabled)
    }
    var diagnosticLoggingEnabled by rememberSaveable { mutableStateOf(DiagnosticLogStore.enabled) }
    var autoPageEnabled by rememberSaveable { mutableStateOf(AutoPageProfileStore.enabled) }
    var autoPageCount by rememberSaveable { mutableIntStateOf(AutoPageProfileStore.loadAll().count { it.enabled }) }
    var showAutoPageBackgroundDialog by rememberSaveable { mutableStateOf(false) }
    var batteryUnrestricted by remember {
        mutableStateOf(AutoPageBackgroundPolicy.isBatteryUnrestricted(context))
    }
    var autoStartState by remember {
        mutableStateOf(AutoPageBackgroundPolicy.autoStartState(context))
    }
    var accessibilityConnected by remember {
        mutableStateOf(AccessibilityScreenshotService.instance != null)
    }
    var selectedPage by rememberSaveable { mutableIntStateOf(0) }
    val rootBackdrop = rememberLayerBackdrop()
    val pagerState = rememberPagerState(initialPage = selectedPage) {
        RootDestination.entries.size
    }
    val diagnosticLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        diagnosticLoggingEnabled = DiagnosticLogStore.enabled
    }
    val autoPageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        autoPageEnabled = AutoPageProfileStore.enabled
        autoPageCount = AutoPageProfileStore.loadAll().count { it.enabled }
        accessibilityConnected = AccessibilityScreenshotService.instance != null
    }
    val autoStartSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        autoStartState = AutoPageBackgroundPolicy.autoStartState(context)
        accessibilityConnected = AccessibilityScreenshotService.instance != null
    }
    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        batteryUnrestricted = AutoPageBackgroundPolicy.isBatteryUnrestricted(context)
        AccessibilityScreenshotService.instance?.refreshAutoConfiguration()
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { selectedPage = it }
    }

    LaunchedEffect(shizuku.ready, autoPageEnabled) {
        accessibilityConnected = AccessibilityScreenshotService.instance != null
        if (shizuku.ready && autoPageEnabled && AccessibilityScreenshotService.instance == null) {
            AppShell.enableAccessibility(context) { enabledAccessibility ->
                if (!enabledAccessibility) {
                    autoPageEnabled = false
                    AutoPageProfileStore.enabled = false
                    scope.launch {
                        snackbarHostState.showSnackbar(autoPageEnableFailedMessage)
                    }
                } else {
                    scope.launch {
                        delay(600)
                        accessibilityConnected = AccessibilityScreenshotService.instance != null
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (autoPageEnabled &&
            (!batteryUnrestricted || !notificationGranted)
        ) {
            autoPageEnabled = false
            AutoPageProfileStore.enabled = false
            showAutoPageBackgroundDialog = true
        }
    }

    val refreshAccessibilityStatus: () -> Unit = {
        scope.launch {
            delay(600)
            accessibilityConnected = AccessibilityScreenshotService.instance != null
        }
    }
    val repairAccessibility: () -> Unit = {
        if (!shizuku.ready) {
            scope.launch { snackbarHostState.showSnackbar(autoPageEnableFailedMessage) }
        } else {
            AppShell.repairAccessibility(context) { repaired ->
                if (repaired) refreshAccessibilityStatus()
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (repaired) autoPageRepairSuccessMessage
                        else autoPageEnableFailedMessage,
                    )
                }
            }
        }
    }

    PredictiveBackHandler(
        enabled = selectedPage == RootDestination.HISTORY.ordinal,
    ) { backEvents ->
        backEvents.collect()
        selectedPage = RootDestination.SETTINGS.ordinal
        scope.launch { pagerState.animateScrollToPage(RootDestination.SETTINGS.ordinal) }
    }

    if (showCustomModel) {
        CustomModelScreen(
            settings = settings,
            onBack = { showCustomModel = false },
            onSaved = {
                showCustomModel = false
                scope.launch {
                    snackbarHostState.showSnackbar(customModelSavedMessage)
                }
            },
        )
        return
    }
    Box(modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(rootBackdrop),
            beyondViewportPageCount = 1,
        ) { page ->
            if (page == RootDestination.HISTORY.ordinal) {
                RecognitionHistoryScreen(
                    onOpenDetail = { recordId ->
                        context.startActivity(
                            Intent(context, RecognitionHistoryDetailActivity::class.java)
                                .putExtra(RecognitionHistoryDetailActivity.EXTRA_RECORD_ID, recordId),
                        )
                    },
                )
            } else {
                SettingsRootScreen(
                    shizuku = shizuku,
                    notificationGranted = notificationGranted,
                    onRequestShizuku = onRequestShizuku,
                    onRequestNotification = onRequestNotification,
                    settings = settings,
                    supportsIsland = supportsIsland,
                    splashAnimationEnabled = splashAnimationEnabled,
                    diagnosticLoggingEnabled = diagnosticLoggingEnabled,
                    autoPageEnabled = autoPageEnabled,
                    autoPageCount = autoPageCount,
                    autoPageBackgroundReady = batteryUnrestricted &&
                        notificationGranted &&
                        autoStartState == AutoPageBackgroundPolicy.AutoStartState.ENABLED,
                    accessibilityConnected = accessibilityConnected,
                    rootBackdrop = rootBackdrop,
                    scrollBehavior = scrollBehavior,
                    snackbarHostState = snackbarHostState,
                    context = context,
                    scope = scope,
                    onOpenCustomModel = { showCustomModel = true },
                    onSplashAnimationChanged = { enabled ->
                        splashAnimationEnabled = enabled
                        AppUiSettingsStore.splashAnimationEnabled = enabled
                    },
                    onOpenDiagnostics = {
                        diagnosticLauncher.launch(Intent(context, DiagnosticLogActivity::class.java))
                    },
                    onAutoPageEnabledChanged = { enabled ->
                        if (!enabled) {
                            autoPageEnabled = false
                            AutoPageProfileStore.enabled = false
                        } else {
                            batteryUnrestricted =
                                AutoPageBackgroundPolicy.isBatteryUnrestricted(context)
                            showAutoPageBackgroundDialog = true
                        }
                    },
                    onRepairAccessibility = {
                        repairAccessibility()
                    },
                    onOpenAutoPageBackground = {
                        batteryUnrestricted =
                            AutoPageBackgroundPolicy.isBatteryUnrestricted(context)
                        autoStartState = AutoPageBackgroundPolicy.autoStartState(context)
                        accessibilityConnected = AccessibilityScreenshotService.instance != null
                        showAutoPageBackgroundDialog = true
                    },
                    onOpenAutoPages = {
                        autoPageLauncher.launch(Intent(context, AutoPageActivity::class.java))
                    },
                )
            }
        }

        RootFloatingNavigationBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            selected = RootDestination.entries[selectedPage.coerceIn(0, RootDestination.entries.lastIndex)],
            backdrop = rootBackdrop,
            onSelected = { destination ->
                selectedPage = destination.ordinal
                scope.launch { pagerState.animateScrollToPage(destination.ordinal) }
            },
        )
    }

    AutoPageBackgroundDialog(
        show = showAutoPageBackgroundDialog,
        batteryUnrestricted = batteryUnrestricted,
        autoStartState = autoStartState,
        accessibilityConnected = accessibilityConnected,
        notificationGranted = notificationGranted,
        onDismiss = { showAutoPageBackgroundDialog = false },
        onOpenAutoStart = {
            runCatching {
                autoStartSettingsLauncher.launch(
                    AutoPageBackgroundPolicy.autoStartSettingsIntent(context),
                )
            }
        },
        onOpenBattery = {
            batterySettingsLauncher.launch(
                AutoPageBackgroundPolicy.batterySettingsIntent(context),
            )
        },
        onRepairAccessibility = {
            repairAccessibility()
        },
        onRequestNotification = onRequestNotification,
        onEnable = {
            val enableAfterAccessibility = {
                autoPageEnabled = true
                AutoPageProfileStore.enabled = true
                accessibilityConnected = AccessibilityScreenshotService.instance != null
                showAutoPageBackgroundDialog = false
            }
            if (AccessibilityScreenshotService.instance != null) {
                enableAfterAccessibility()
            } else {
                AppShell.enableAccessibility(context) { enabledAccessibility ->
                    if (enabledAccessibility) {
                        enableAfterAccessibility()
                        refreshAccessibilityStatus()
                    } else {
                        autoPageEnabled = false
                        AutoPageProfileStore.enabled = false
                        scope.launch {
                            snackbarHostState.showSnackbar(autoPageEnableFailedMessage)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SettingsRootScreen(
    shizuku: ShizukuState,
    notificationGranted: Boolean,
    onRequestShizuku: () -> Unit,
    onRequestNotification: () -> Unit,
    settings: AiSettingsState,
    supportsIsland: Boolean,
    splashAnimationEnabled: Boolean,
    diagnosticLoggingEnabled: Boolean,
    autoPageEnabled: Boolean,
    autoPageCount: Int,
    autoPageBackgroundReady: Boolean,
    accessibilityConnected: Boolean,
    rootBackdrop: LayerBackdrop,
    scrollBehavior: ScrollBehavior,
    snackbarHostState: SnackbarHostState,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenCustomModel: () -> Unit,
    onSplashAnimationChanged: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onAutoPageEnabledChanged: (Boolean) -> Unit,
    onRepairAccessibility: () -> Unit,
    onOpenAutoPageBackground: () -> Unit,
    onOpenAutoPages: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                subtitle = settings.selectedPresetName,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = {
            SnackbarHost(
                state = snackbarHostState,
                modifier = Modifier.padding(bottom = RootNavigationBarClearance),
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .imePadding()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(layoutDirection),
                bottom = padding.calculateBottomPadding() + RootNavigationBarClearance,
            ),
        ) {
            item(key = "status") {
                SmallTitle(stringResource(R.string.section_runtime_status))
                SectionCard {
                    RuntimeStatusSection(
                        shizuku = shizuku,
                        notificationGranted = notificationGranted,
                        aiConfigured = settings.isConfigured,
                        usesXiaomi = settings.usesXiaomi,
                        usesPickup = settings.usesPickup,
                        onRequestShizuku = onRequestShizuku,
                        onRequestNotification = onRequestNotification,
                        onAddTile = {
                            QuickSettingsTileHelper.requestAdd(context) { message ->
                                scope.launch { snackbarHostState.showSnackbar(message) }
                            }
                        },
                    )
                }
            }
            item(key = "ai") {
                SmallTitle(stringResource(R.string.section_ai_service))
                SectionCard {
                    AiSettingsSection(
                        settings = settings,
                        allowSystemAccountImport = shizuku.isRoot,
                        onOpenCustomModel = onOpenCustomModel,
                        onMessage = { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        },
                    )
                }
            }
            item(key = "interface") {
                SmallTitle(stringResource(R.string.section_interface))
                SectionCard {
                    SwitchPreference(
                        checked = splashAnimationEnabled,
                        onCheckedChange = onSplashAnimationChanged,
                        title = stringResource(R.string.splash_animation),
                        summary = stringResource(R.string.splash_animation_summary),
                    )
                }
            }
            item(key = "auto_page") {
                SmallTitle(stringResource(R.string.section_auto_page))
                SectionCard {
                    SwitchPreference(
                        checked = autoPageEnabled,
                        onCheckedChange = onAutoPageEnabledChanged,
                        title = stringResource(R.string.auto_page_enabled),
                        summary = stringResource(R.string.auto_page_enabled_summary),
                    )
                    ArrowPreference(
                        title = stringResource(R.string.auto_page_title),
                        summary = stringResource(R.string.auto_page_count, autoPageCount),
                        onClick = onOpenAutoPages,
                    )
                    BasicComponent(
                        modifier = Modifier.clickable(onClick = onOpenAutoPageBackground),
                        title = stringResource(R.string.auto_page_background_title),
                        summary = stringResource(R.string.auto_page_background_summary),
                        endActions = {
                            PreferenceStatus(
                                text = stringResource(
                                    if (autoPageBackgroundReady) {
                                        R.string.auto_page_background_ready
                                    } else {
                                        R.string.auto_page_background_required
                                    },
                                ),
                                ready = autoPageBackgroundReady,
                            )
                        },
                    )
                    BasicComponent(
                        modifier = Modifier.clickable(onClick = onRepairAccessibility),
                        title = stringResource(R.string.auto_page_accessibility_repair),
                        summary = stringResource(R.string.auto_page_accessibility_repair_summary),
                        endActions = {
                            PreferenceStatus(
                                text = stringResource(
                                    if (accessibilityConnected) {
                                        R.string.auto_page_accessibility_connected
                                    } else {
                                        R.string.auto_page_accessibility_disconnected
                                    },
                                ),
                                ready = accessibilityConnected,
                            )
                        },
                    )
                }
            }
            if (supportsIsland) {
                item(key = "island") {
                    SmallTitle(stringResource(R.string.section_super_island))
                    SectionCard {
                        SuperIslandDebugSection(
                            shizukuReady = shizuku.ready,
                            onMessage = { message ->
                                scope.launch { snackbarHostState.showSnackbar(message) }
                            },
                        )
                    }
                }
            }
            item(key = "diagnostics") {
                SmallTitle(stringResource(R.string.section_diagnostics))
                SectionCard {
                    ArrowPreference(
                        title = stringResource(R.string.diagnostic_log_title),
                        summary = stringResource(
                            if (diagnosticLoggingEnabled) {
                                R.string.diagnostic_entry_summary_enabled
                            } else {
                                R.string.diagnostic_entry_summary_disabled
                            },
                        ),
                        onClick = onOpenDiagnostics,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoPageBackgroundDialog(
    show: Boolean,
    batteryUnrestricted: Boolean,
    autoStartState: AutoPageBackgroundPolicy.AutoStartState,
    accessibilityConnected: Boolean,
    notificationGranted: Boolean,
    onDismiss: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onOpenBattery: () -> Unit,
    onRepairAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onEnable: () -> Unit,
) {
    WindowDialog(
        title = stringResource(R.string.auto_page_background_dialog_title),
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column {
            BasicComponent(
                modifier = Modifier.clickable(onClick = onOpenAutoStart),
                title = stringResource(R.string.auto_page_autostart),
                summary = stringResource(R.string.auto_page_autostart_summary),
                endActions = {
                    val enabled = autoStartState ==
                        AutoPageBackgroundPolicy.AutoStartState.ENABLED
                    PreferenceStatus(
                        text = stringResource(
                            when (autoStartState) {
                                AutoPageBackgroundPolicy.AutoStartState.ENABLED ->
                                    R.string.auto_page_status_enabled
                                AutoPageBackgroundPolicy.AutoStartState.DISABLED ->
                                    R.string.auto_page_status_disabled
                                AutoPageBackgroundPolicy.AutoStartState.UNKNOWN ->
                                    R.string.auto_page_status_unknown
                            },
                        ),
                        ready = enabled,
                    )
                },
            )
            BasicComponent(
                modifier = Modifier.clickable(onClick = onOpenBattery),
                title = stringResource(R.string.auto_page_battery),
                summary = stringResource(R.string.auto_page_battery_summary),
                endActions = {
                    PreferenceStatus(
                        text = stringResource(
                            if (batteryUnrestricted) R.string.auto_page_battery_unrestricted
                            else R.string.auto_page_battery_restricted,
                        ),
                        ready = batteryUnrestricted,
                    )
                },
            )
            BasicComponent(
                modifier = Modifier.clickable(onClick = onRepairAccessibility),
                title = stringResource(R.string.auto_page_accessibility_status),
                summary = stringResource(R.string.auto_page_accessibility_repair_summary),
                endActions = {
                    PreferenceStatus(
                        text = stringResource(
                            if (accessibilityConnected) {
                                R.string.auto_page_accessibility_connected
                            } else {
                                R.string.auto_page_accessibility_disconnected
                            },
                        ),
                        ready = accessibilityConnected,
                    )
                },
            )
            if (!notificationGranted) {
                ArrowPreference(
                    title = stringResource(R.string.notification_permission),
                    summary = stringResource(R.string.auto_page_notification_required),
                    onClick = onRequestNotification,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                )
                TextButton(
                    text = stringResource(R.string.auto_page_background_enable),
                    enabled = batteryUnrestricted && notificationGranted,
                    onClick = onEnable,
                )
            }
        }
    }
}

@Composable
private fun PreferenceStatus(text: String, ready: Boolean) {
    Text(
        text = text,
        color = if (ready) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantActions
        },
    )
}

/** Standard spacing used by Miuix preference cards. */
@Composable
internal fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        content = content,
    )
}
