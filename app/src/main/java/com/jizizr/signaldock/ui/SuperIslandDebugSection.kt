package com.jizizr.signaldock.ui

import android.content.Intent
import androidx.compose.foundation.layout.size
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference

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

    SwitchPreference(
        checked = networkBypassEnabled,
        onCheckedChange = { enabled ->
            networkBypassEnabled = enabled
            SuperIslandSettingsStore.networkBypassEnabled = enabled
        },
        title = stringResource(R.string.super_island_network_bypass),
        summary = stringResource(R.string.super_island_network_bypass_summary),
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

}
