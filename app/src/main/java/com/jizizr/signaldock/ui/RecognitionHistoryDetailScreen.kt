package com.jizizr.signaldock.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.R
import com.jizizr.signaldock.AccessibilityScreenshotService
import com.jizizr.signaldock.AppShell
import com.jizizr.signaldock.AutoPageProfileStore
import com.jizizr.signaldock.RecognitionHistoryRecord
import com.jizizr.signaldock.RecognitionHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun RecognitionHistoryDetailScreen(
    recordId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var record by remember { mutableStateOf<RecognitionHistoryRecord?>(null) }
    var screenshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var replaying by remember { mutableStateOf(false) }
    var showAutoPageDialog by remember { mutableStateOf(false) }
    var autoPageSaved by remember { mutableStateOf(false) }
    var draftProfileName by remember { mutableStateOf("") }
    var draftKeywords by remember { mutableStateOf("") }
    var draftExcludedKeywords by remember { mutableStateOf("") }

    LaunchedEffect(recordId) {
        val loaded = withContext(Dispatchers.IO) {
            val historyRecord = RecognitionHistoryStore.load(context, recordId)
            val bitmap = RecognitionHistoryStore.screenshotFile(context, recordId)
                ?.absolutePath
                ?.let(BitmapFactory::decodeFile)
            historyRecord to bitmap
        }
        record = loaded.first
        screenshot = loaded.second?.asImageBitmap()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.history_detail_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        text = stringResource(
                            if (replaying) R.string.history_replaying
                            else R.string.history_replay,
                        ),
                        enabled = record != null && !replaying,
                        onClick = {
                            val current = record ?: return@TextButton
                            replaying = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    RecognitionHistoryStore.replay(context, current)
                                }
                                replaying = false
                                snackbarHostState.showSnackbar(
                                    resources.getString(
                                        if (result.isSuccess) R.string.history_replay_success
                                        else R.string.history_replay_failed,
                                    ),
                                )
                            }
                        },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        val current = record
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(layoutDirection),
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
        ) {
            if (current == null) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.history_loading),
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        )
                    }
                }
            } else {
                item(key = "auto_page") {
                    SmallTitle(stringResource(R.string.section_auto_page))
                    SectionCard {
                        ArrowPreference(
                            title = stringResource(
                                if (autoPageSaved) R.string.auto_page_added
                                else R.string.auto_page_add_from_history,
                            ),
                            summary = stringResource(R.string.auto_page_add_from_history_summary),
                            onClick = {
                                if (current.sourcePackage.isBlank()) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            resources.getString(R.string.auto_page_source_unavailable),
                                        )
                                    }
                                    return@ArrowPreference
                                }
                                val suggestions = AutoPageProfileStore.suggestedKeywords(current)
                                draftProfileName = current.merchant
                                    .ifBlank { current.miniProgramLabel }
                                    .ifBlank { current.sourcePackage }
                                draftKeywords = suggestions.joinToString("，")
                                draftExcludedKeywords = ""
                                showAutoPageDialog = true
                            },
                        )
                    }
                }
                item(key = "screenshot") {
                    SmallTitle(stringResource(R.string.history_screenshot))
                    SectionCard {
                        val image = screenshot
                        if (image != null) {
                            Image(
                                bitmap = image,
                                contentDescription = stringResource(R.string.history_screenshot),
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(image.width.toFloat() / image.height.toFloat()),
                            )
                        } else {
                            BasicComponent(
                                title = stringResource(R.string.history_screenshot_unavailable),
                                summary = stringResource(R.string.history_screenshot_unavailable_summary),
                            )
                        }
                    }
                }
                item(key = "recognized") {
                    SmallTitle(stringResource(R.string.history_recognition_data))
                    SectionCard {
                        HistoryField(stringResource(R.string.history_field_title), current.title)
                        HistoryField(stringResource(R.string.history_field_label), current.content)
                        HistoryField(stringResource(R.string.history_field_item), current.item)
                        HistoryField(stringResource(R.string.history_field_item_detail), current.itemDetail)
                        HistoryField(stringResource(R.string.history_field_merchant), current.merchant)
                        HistoryField(stringResource(R.string.history_field_price), current.price)
                        HistoryField(stringResource(R.string.history_field_button), current.buttonText)
                        HistoryField(stringResource(R.string.history_field_details), current.infoLines.joinToString("\n"))
                        HistoryField(stringResource(R.string.history_field_body), current.body)
                        HistoryField(stringResource(R.string.history_field_icon_type), current.iconType)
                        HistoryField(
                            stringResource(R.string.history_field_qr),
                            stringResource(
                                if (current.qrFound) R.string.yes else R.string.no,
                            ),
                        )
                        HistoryField(stringResource(R.string.history_field_error), current.error)
                    }
                }
                item(key = "metadata") {
                    SmallTitle(stringResource(R.string.history_metadata))
                    SectionCard {
                        val date = remember(current.createdAtMs) {
                            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault())
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(current.createdAtMs))
                        }
                        HistoryField(stringResource(R.string.history_field_time), date)
                        HistoryField(stringResource(R.string.history_field_provider), current.providerName)
                        HistoryField(stringResource(R.string.history_field_source_package), current.sourcePackage)
                        HistoryField(
                            stringResource(R.string.history_field_duration),
                            resources.getString(R.string.history_duration_value, current.analysisDurationMs),
                        )
                        HistoryField(
                            stringResource(R.string.history_field_resolution),
                            "${current.screenshotWidth} × ${current.screenshotHeight}",
                        )
                    }
                }
            }
        }
    }

    WindowDialog(
        title = stringResource(R.string.auto_page_add_from_history),
        show = showAutoPageDialog,
        onDismissRequest = { showAutoPageDialog = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StableTextField(
                value = draftProfileName,
                onValueChange = { draftProfileName = it },
                label = stringResource(R.string.auto_page_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            StableTextField(
                value = draftKeywords,
                onValueChange = { draftKeywords = it },
                label = stringResource(R.string.auto_page_keywords_input),
                modifier = Modifier.fillMaxWidth(),
            )
            StableTextField(
                value = draftExcludedKeywords,
                onValueChange = { draftExcludedKeywords = it },
                label = stringResource(R.string.auto_page_excluded_keywords_input),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.auto_page_learning_privacy),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showAutoPageDialog = false },
                )
                TextButton(
                    text = stringResource(R.string.save),
                    enabled = record != null && draftProfileName.isNotBlank() && draftKeywords.isNotBlank(),
                    onClick = {
                        val current = record ?: return@TextButton
                        val profile = AutoPageProfileStore.createFromHistory(
                            context = context,
                            record = current,
                            name = draftProfileName,
                            keywords = listOf(draftKeywords),
                            excludedKeywords = listOf(draftExcludedKeywords),
                        )
                        AutoPageProfileStore.save(profile)
                        AutoPageProfileStore.enabled = true
                        if (AccessibilityScreenshotService.instance == null) {
                            AppShell.enableAccessibility(context) { enabled ->
                                if (!enabled) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            resources.getString(
                                                R.string.auto_page_accessibility_failed,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        autoPageSaved = true
                        showAutoPageDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                resources.getString(R.string.auto_page_saved),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryField(title: String, value: String) {
    BasicComponent(
        title = title,
        summary = value.ifBlank { stringResource(R.string.history_value_empty) },
    )
}
