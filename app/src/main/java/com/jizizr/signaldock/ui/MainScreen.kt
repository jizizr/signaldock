package com.jizizr.signaldock.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.HyperIslandHelper
import com.jizizr.signaldock.R
import com.jizizr.signaldock.QuickSettingsTileHelper
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
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
                .imePadding()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(layoutDirection),
                bottom = padding.calculateBottomPadding() + 12.dp,
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
            item(key = "ai") {
                SmallTitle(stringResource(R.string.section_ai_service))
                SectionCard {
                    AiSettingsSection(
                        settings = settings,
                        allowSystemAccountImport = shizuku.isRoot,
                        onOpenCustomModel = { showCustomModel = true },
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
