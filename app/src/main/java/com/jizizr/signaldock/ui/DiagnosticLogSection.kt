package com.jizizr.signaldock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.DiagnosticLogExporter
import com.jizizr.signaldock.DiagnosticLogStore
import com.jizizr.signaldock.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun DiagnosticLogScreen(
    onBack: () -> Unit,
    onLoggingChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var loggingEnabled by rememberSaveable { mutableStateOf(DiagnosticLogStore.enabled) }
    var exporting by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val statusSummary = stringResource(
        if (loggingEnabled) R.string.diagnostic_log_status_enabled
        else R.string.diagnostic_log_status_disabled,
        DiagnosticLogStore.droppedCount,
    )

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.diagnostic_log_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(layoutDirection),
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
        ) {
            item(key = "recording") {
                SmallTitle(stringResource(R.string.diagnostic_log_recording_section))
                SectionCard {
                    SwitchPreference(
                        checked = loggingEnabled,
                        onCheckedChange = { enabled ->
                            loggingEnabled = enabled
                            DiagnosticLogStore.setEnabled(enabled)
                            onLoggingChanged(enabled)
                        },
                        title = stringResource(R.string.diagnostic_logging),
                        summary = stringResource(R.string.diagnostic_logging_summary),
                    )
                    BasicComponent(
                        title = stringResource(R.string.diagnostic_log_status),
                        summary = statusSummary,
                    )
                }
            }
            item(key = "actions") {
                SmallTitle(stringResource(R.string.diagnostic_actions_section))
                SectionCard {
                    if (exporting) {
                        BasicComponent(
                            title = stringResource(R.string.diagnostic_exporting),
                            summary = stringResource(R.string.diagnostic_export_summary),
                            endActions = {
                                CircularProgressIndicator()
                            },
                        )
                    } else {
                        ArrowPreference(
                            title = stringResource(R.string.diagnostic_export),
                            summary = stringResource(R.string.diagnostic_export_summary),
                            onClick = {
                                if (clearing) return@ArrowPreference
                                exporting = true
                                scope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            DiagnosticLogExporter.createArchive(context)
                                        }
                                    }
                                    exporting = false
                                    result.onSuccess { archive ->
                                        runCatching {
                                            DiagnosticLogExporter.share(context, archive)
                                        }.onFailure { error ->
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    resources.getString(
                                                        R.string.diagnostic_export_failed,
                                                        error.message ?: resources.getString(R.string.unknown_error),
                                                    ),
                                                )
                                            }
                                        }
                                    }.onFailure { error ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                resources.getString(
                                                    R.string.diagnostic_export_failed,
                                                    error.message ?: resources.getString(R.string.unknown_error),
                                                ),
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                    ArrowPreference(
                        title = stringResource(
                            if (clearing) R.string.diagnostic_clearing
                            else R.string.diagnostic_clear,
                        ),
                        summary = stringResource(R.string.diagnostic_clear_summary),
                        onClick = {
                            if (exporting || clearing) return@ArrowPreference
                            showClearDialog = true
                        },
                    )
                }
            }
            item(key = "privacy") {
                SmallTitle(stringResource(R.string.diagnostic_privacy_section))
                SectionCard {
                    Text(
                        text = stringResource(R.string.diagnostic_privacy_summary),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    OverlayDialog(
        title = stringResource(R.string.diagnostic_clear_confirm_title),
        show = showClearDialog,
        onDismissRequest = { showClearDialog = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.diagnostic_clear_confirm_message))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showClearDialog = false },
                )
                TextButton(
                    text = stringResource(R.string.clear),
                    onClick = {
                        showClearDialog = false
                        clearing = true
                        scope.launch {
                            val cleared = runCatching {
                                withContext(Dispatchers.IO) {
                                    DiagnosticLogExporter.clear(context)
                                }
                            }.getOrDefault(false)
                            clearing = false
                            snackbarHostState.showSnackbar(
                                resources.getString(
                                    if (cleared) R.string.diagnostic_cleared
                                    else R.string.diagnostic_clear_failed,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}
