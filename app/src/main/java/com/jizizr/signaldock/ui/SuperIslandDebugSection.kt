package com.jizizr.signaldock.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.AppShell
import com.jizizr.signaldock.R
import com.jizizr.signaldock.SuperIslandManager
import com.jizizr.signaldock.SuperIslandSettingsStore
import com.jizizr.signaldock.IslandShareEditorActivity
import com.jizizr.signaldock.NetworkBypassMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun SuperIslandDebugSection(
    shizukuReady: Boolean,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val unavailableMessage = stringResource(R.string.shizuku_not_ready)
    val unknownErrorMessage = stringResource(R.string.unknown_error)
    var isSending by remember { mutableStateOf(false) }
    var networkBypassEnabled by remember {
        mutableStateOf(SuperIslandSettingsStore.networkBypassEnabled)
    }
    var networkBypassMode by remember {
        mutableStateOf(SuperIslandSettingsStore.networkBypassMode)
    }
    var outerGlowEnabled by remember {
        mutableStateOf(SuperIslandSettingsStore.outerGlowEnabled)
    }
    var showDurationDialog by remember { mutableStateOf(false) }
    var durationDraft by remember {
        mutableStateOf(SuperIslandSettingsStore.networkBypassDurationMs.toString())
    }

    val bypassModeItems = listOf(
        DropdownItem(text = stringResource(R.string.super_island_bypass_mode_standard)),
        DropdownItem(text = stringResource(R.string.super_island_bypass_mode_custom)),
    )
    SwitchPreference(
        checked = networkBypassEnabled,
        onCheckedChange = { enabled ->
            networkBypassEnabled = enabled
            SuperIslandSettingsStore.networkBypassEnabled = enabled
            networkBypassMode = SuperIslandSettingsStore.networkBypassMode
        },
        title = stringResource(R.string.super_island_network_bypass),
        summary = stringResource(R.string.super_island_network_bypass_summary),
    )
    if (networkBypassEnabled) {
        OverlaySpinnerPreference(
            items = bypassModeItems,
            selectedIndex = if (networkBypassMode == NetworkBypassMode.CUSTOM) 1 else 0,
            title = stringResource(R.string.super_island_bypass_mode),
            summary = stringResource(R.string.super_island_bypass_mode_summary),
            onSelectedIndexChange = { index ->
                networkBypassMode = if (index == 1) NetworkBypassMode.CUSTOM else NetworkBypassMode.STANDARD
                SuperIslandSettingsStore.networkBypassMode = networkBypassMode
            },
        )
        if (networkBypassMode == NetworkBypassMode.CUSTOM) {
            ArrowPreference(
                title = stringResource(R.string.super_island_bypass_duration),
                summary = stringResource(
                    R.string.super_island_bypass_duration_summary,
                    SuperIslandSettingsStore.networkBypassDurationMs,
                ),
                onClick = {
                    durationDraft = SuperIslandSettingsStore.networkBypassDurationMs.toString()
                    showDurationDialog = true
                },
            )
        }
    }

    SwitchPreference(
        checked = outerGlowEnabled,
        onCheckedChange = { enabled ->
            outerGlowEnabled = enabled
            SuperIslandSettingsStore.outerGlowEnabled = enabled
        },
        title = stringResource(R.string.super_island_outer_glow),
        summary = stringResource(R.string.super_island_outer_glow_summary),
    )

    ArrowPreference(
        title = stringResource(R.string.super_island_share_template),
        summary = stringResource(R.string.super_island_share_template_summary),
        onClick = {
            context.startActivity(Intent(context, IslandShareEditorActivity::class.java))
        },
    )

    BasicComponent(
        title = stringResource(R.string.super_island_test),
        summary = stringResource(
            if (!networkBypassEnabled || shizukuReady) R.string.super_island_test_ready
            else R.string.super_island_test_requires_shizuku,
        ),
        endActions = {
            Button(
                onClick = {
                    if (isSending) return@Button
                    scope.launch {
                        if (networkBypassEnabled && !AppShell.isShizukuAvailable) {
                            onMessage(unavailableMessage)
                            return@launch
                        }
                        isSending = true
                        context.findActivity()?.moveTaskToBack(true)
                        delay(300)
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                SuperIslandManager.sendTestIslandNotification(context)
                            }
                        }.getOrElse { error ->
                            resources.getString(
                                R.string.send_failed_format,
                                error.message ?: unknownErrorMessage,
                            )
                        }
                        isSending = false
                        onMessage(result)
                    }
                },
                enabled = (!networkBypassEnabled || shizukuReady) && !isSending,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text(stringResource(R.string.send))
                }
            }
        },
    )

    WindowDialog(
        title = stringResource(R.string.super_island_bypass_duration),
        show = showDurationDialog,
        onDismissRequest = { showDurationDialog = false },
    ) {
        Column {
            StableTextField(
                value = durationDraft,
                onValueChange = { value -> durationDraft = value.filter(Char::isDigit).take(3) },
                label = stringResource(R.string.super_island_bypass_duration_input),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.save),
                onClick = {
                    durationDraft.toIntOrNull()?.let { SuperIslandSettingsStore.networkBypassDurationMs = it }
                    showDurationDialog = false
                },
            )
        }
    }

}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
