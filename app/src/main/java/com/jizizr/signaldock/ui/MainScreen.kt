package com.jizizr.signaldock.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import com.jizizr.signaldock.R
import com.jizizr.signaldock.QuickSettingsTileHelper
import com.jizizr.signaldock.RecognitionHistoryDetailActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

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
    var showCustomModel by rememberSaveable { mutableStateOf(false) }
    var splashAnimationEnabled by rememberSaveable {
        mutableStateOf(AppUiSettingsStore.splashAnimationEnabled)
    }
    var diagnosticLoggingEnabled by rememberSaveable { mutableStateOf(DiagnosticLogStore.enabled) }
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

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { selectedPage = it }
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
    Box(modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
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
    rootBackdrop: LayerBackdrop,
    scrollBehavior: ScrollBehavior,
    snackbarHostState: SnackbarHostState,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenCustomModel: () -> Unit,
    onSplashAnimationChanged: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                subtitle = settings.selectedPresetName,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
                .imePadding()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(layoutDirection),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
        ) {
            item(key = "status") {
                SmallTitle(stringResource(R.string.section_runtime_status))
                SectionCard {
                    RuntimeStatusSection(
                        shizuku = shizuku,
                        notificationGranted = notificationGranted,
                        aiConfigured = settings.isConfigured,
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
