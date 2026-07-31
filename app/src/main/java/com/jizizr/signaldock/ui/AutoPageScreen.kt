package com.jizizr.signaldock.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.AccessibilityScreenshotService
import com.jizizr.signaldock.AutoPageProfile
import com.jizizr.signaldock.AutoPageProfileStore
import com.jizizr.signaldock.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AutoPageScreen(
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    var profiles by remember { mutableStateOf(AutoPageProfileStore.loadAll()) }
    var editing by remember { mutableStateOf<AutoPageProfile?>(null) }
    var deleting by remember { mutableStateOf<AutoPageProfile?>(null) }
    var draftName by rememberSaveable { mutableStateOf("") }
    var draftKeywords by rememberSaveable { mutableStateOf("") }
    var draftExcludedKeywords by rememberSaveable { mutableStateOf("") }

    fun reload() {
        profiles = AutoPageProfileStore.loadAll()
        AccessibilityScreenshotService.instance?.refreshAutoConfiguration()
        onChanged()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.auto_page_title),
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
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding() + 12.dp,
                end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (profiles.isEmpty()) {
                item(key = "empty") {
                    SectionCard {
                        BasicComponent(
                            title = stringResource(R.string.auto_page_empty),
                            summary = stringResource(R.string.auto_page_empty_summary),
                        )
                    }
                }
            } else {
                items(profiles, key = AutoPageProfile::id) { profile ->
                    val unknownIdentity = stringResource(R.string.auto_page_identity_unknown)
                    val activityIdentity = profile.activityClassName
                        .takeIf(String::isNotBlank)
                        ?.let { className ->
                            if (className.startsWith(profile.packageName)) {
                                className.removePrefix(profile.packageName)
                            } else {
                                className
                            }
                        }
                        .orEmpty()
                    val identitySummary = buildString {
                        append(stringResource(R.string.auto_page_identity_package))
                        append("  ")
                        append(profile.packageName)
                        append('\n')
                        append(stringResource(R.string.auto_page_identity_activity))
                        append("  ")
                        append(activityIdentity.ifBlank { unknownIdentity })
                        if (profile.miniProgramLabel.isNotBlank()) {
                            append('\n')
                            append(stringResource(R.string.auto_page_identity_mini_program))
                            append("  ")
                            append(profile.miniProgramLabel)
                        }
                        if (profile.miniProgramIconHash.isNotBlank()) {
                            append('\n')
                            append(stringResource(R.string.auto_page_identity_icon_hash))
                            append("  ")
                            append(profile.miniProgramIconHash)
                        }
                    }
                    SectionCard {
                        SwitchPreference(
                            checked = profile.enabled,
                            onCheckedChange = { enabled ->
                                AutoPageProfileStore.save(profile.copy(enabled = enabled))
                                reload()
                            },
                            title = profile.name,
                            summary = profile.miniProgramLabel.ifBlank { profile.packageName },
                        )
                        BasicComponent(
                            title = stringResource(R.string.auto_page_identity),
                            summary = identitySummary,
                        )
                        BasicComponent(
                            title = stringResource(R.string.auto_page_keywords),
                            summary = profile.keywords.joinToString(" · ")
                                .ifBlank { stringResource(R.string.auto_page_no_keywords) },
                        )
                        BasicComponent(
                            title = stringResource(R.string.auto_page_excluded_keywords),
                            summary = profile.excludedKeywords.joinToString(" · ")
                                .ifBlank {
                                    stringResource(R.string.auto_page_no_excluded_keywords)
                                },
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                text = stringResource(R.string.edit),
                                onClick = {
                                    draftName = profile.name
                                    draftKeywords = profile.keywords.joinToString("，")
                                    draftExcludedKeywords =
                                        profile.excludedKeywords.joinToString("，")
                                    editing = profile
                                },
                            )
                            TextButton(
                                text = stringResource(R.string.delete),
                                onClick = { deleting = profile },
                            )
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        title = stringResource(R.string.auto_page_edit),
        show = editing != null,
        onDismissRequest = { editing = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StableTextField(
                value = draftName,
                onValueChange = { draftName = it },
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
                text = stringResource(R.string.auto_page_keywords_hint),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { editing = null },
                )
                TextButton(
                    text = stringResource(R.string.save),
                    enabled = draftName.isNotBlank() && draftKeywords.isNotBlank(),
                    onClick = {
                        val current = editing ?: return@TextButton
                        val requiredKeywords = AutoPageProfileStore.sanitizeKeywords(
                            listOf(draftKeywords),
                        )
                        AutoPageProfileStore.save(
                            current.copy(
                                name = draftName.trim(),
                                keywords = requiredKeywords,
                                excludedKeywords = AutoPageProfileStore.sanitizeKeywords(
                                    listOf(draftExcludedKeywords),
                                ).filterNot(requiredKeywords::contains),
                            ),
                        )
                        editing = null
                        reload()
                    },
                )
            }
        }
    }

    WindowDialog(
        title = stringResource(R.string.auto_page_delete_title),
        show = deleting != null,
        onDismissRequest = { deleting = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.auto_page_delete_message))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { deleting = null },
                )
                TextButton(
                    text = stringResource(R.string.delete),
                    onClick = {
                        deleting?.let { AutoPageProfileStore.delete(it.id) }
                        deleting = null
                        reload()
                    },
                )
            }
        }
    }
}
