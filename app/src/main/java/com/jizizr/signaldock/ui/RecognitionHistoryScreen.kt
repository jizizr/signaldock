package com.jizizr.signaldock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.R
import com.jizizr.signaldock.AppLog
import com.jizizr.signaldock.RecognitionHistoryRecord
import com.jizizr.signaldock.RecognitionHistoryStore
import com.jizizr.signaldock.recognitionHistorySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Redo
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun RecognitionHistoryScreen(
    onOpenDetail: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = MiuixScrollBehavior()
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("MM月dd日 HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }
    var records by remember { mutableStateOf<List<RecognitionHistoryRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<RecognitionHistoryRecord?>(null) }
    var replayingId by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(refreshKey, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            RecognitionHistoryStore.changes.collectLatest {
                loading = records.isEmpty()
                records = withContext(Dispatchers.IO) { RecognitionHistoryStore.loadAll(context) }
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.history_title),
                subtitle = stringResource(R.string.history_count, records.size),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                },
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
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                bottom = padding.calculateBottomPadding() + RootNavigationBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (records.isEmpty()) {
                item(key = "empty") {
                    HistoryEmptyState(loading = loading)
                }
            } else {
                items(records, key = RecognitionHistoryRecord::id) { record ->
                    RecognitionHistoryCard(
                        record = record,
                        timeText = timeFormatter.format(Instant.ofEpochMilli(record.createdAtMs)),
                        replaying = replayingId == record.id,
                        actionsEnabled = replayingId == null,
                        onClick = { onOpenDetail(record.id) },
                        onDelete = { pendingDelete = record },
                        onReplay = {
                            if (replayingId != null) return@RecognitionHistoryCard
                            replayingId = record.id
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    RecognitionHistoryStore.replay(context, record)
                                }
                                replayingId = null
                                snackbarHostState.showSnackbar(
                                    resources.getString(
                                        if (result.isSuccess) R.string.history_replay_success
                                        else R.string.history_replay_failed,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    WindowDialog(
        title = stringResource(R.string.history_delete_title),
        show = pendingDelete != null,
        onDismissRequest = { pendingDelete = null },
        onDismissFinished = { pendingDelete = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.history_delete_message))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { pendingDelete = null },
                )
                TextButton(
                    text = stringResource(R.string.delete),
                    onClick = {
                        val record = pendingDelete ?: return@TextButton
                        pendingDelete = null
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) {
                                RecognitionHistoryStore.delete(context, record.id)
                            }
                            AppLog.i(
                                "RecognitionHistory",
                                "Delete requested id=${record.id} result=$deleted",
                            )
                            if (deleted) records = records.filterNot { it.id == record.id }
                            snackbarHostState.showSnackbar(
                                resources.getString(
                                    if (deleted) R.string.history_deleted
                                    else R.string.history_delete_failed,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryEmptyState(loading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        } else {
            Column {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.history_empty_summary),
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun RecognitionHistoryCard(
    record: RecognitionHistoryRecord,
    timeText: String,
    replaying: Boolean,
    actionsEnabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onReplay: () -> Unit,
) {
    val summary = recognitionHistorySummary(record)
    val secondary = listOf(record.itemDetail, record.content)
        .filter { it.isNotBlank() && !summary.contains(it) }
        .joinToString(" · ")
        .ifBlank { record.body }
    val badge = when {
        record.error.isNotBlank() -> stringResource(R.string.history_failed)
        record.price.isNotBlank() -> record.price
        record.qrFound -> stringResource(R.string.history_has_qr)
        else -> ""
    }
    val badgeColor = when {
        record.error.isNotBlank() -> MiuixTheme.colorScheme.error
        badge.isNotBlank() -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (badge.isNotBlank()) {
                    Text(
                        text = badge,
                        style = MiuixTheme.textStyles.subtitle,
                        fontWeight = FontWeight.Medium,
                        color = badgeColor,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }

            if (secondary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = secondary,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = timeText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
                if (record.providerName.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = record.providerName,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryIconAction(
                    icon = MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MiuixTheme.colorScheme.error,
                    enabled = actionsEnabled,
                    onClick = onDelete,
                )
                HistoryIconAction(
                    icon = MiuixIcons.Redo,
                    contentDescription = stringResource(R.string.history_replay),
                    tint = MiuixTheme.colorScheme.primary,
                    enabled = actionsEnabled,
                    loading = replaying,
                    onClick = onReplay,
                )
            }
        }
    }
}

@Composable
private fun HistoryIconAction(
    icon: ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        enabled = enabled,
        backgroundColor = MiuixTheme.colorScheme.surface,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            )
        }
    }
}
