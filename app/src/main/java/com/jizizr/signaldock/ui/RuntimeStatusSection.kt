package com.jizizr.signaldock.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RuntimeStatusSection(
    shizuku: ShizukuState,
    notificationGranted: Boolean,
    aiConfigured: Boolean,
    usesXiaomi: Boolean,
    usesPickup: Boolean,
    onRequestShizuku: () -> Unit,
    onRequestNotification: () -> Unit,
    onAddTile: () -> Unit,
) {
    val shizukuIcon = if (shizuku.ready) MiuixIcons.Basic.Check else MiuixIcons.Lock
    BasicComponent(
        title = when {
            !shizuku.available -> stringResource(R.string.shizuku_unavailable)
            shizuku.isRoot -> stringResource(R.string.shizuku_root_authorized)
            shizuku.isShell -> stringResource(R.string.shizuku_shell_authorized)
            shizuku.granted -> stringResource(R.string.shizuku_authorized)
            else -> stringResource(R.string.shizuku_connected)
        },
        summary = when {
            !shizuku.available -> stringResource(R.string.shizuku_unavailable_summary)
            shizuku.isRoot -> stringResource(R.string.shizuku_root_summary)
            shizuku.isShell -> stringResource(R.string.shizuku_shell_summary)
            shizuku.granted -> stringResource(R.string.shizuku_authorized_summary)
            else -> stringResource(R.string.shizuku_permission_required)
        },
        startAction = { StatusIcon(shizukuIcon, ready = shizuku.ready) },
        endActions = {
            if (shizuku.available && !shizuku.granted) {
                PrimaryActionButton(
                    text = stringResource(R.string.authorize),
                    onClick = onRequestShizuku,
                )
            }
        },
    )

    BasicComponent(
        title = stringResource(R.string.notification_permission),
        summary = stringResource(
            if (notificationGranted) R.string.notification_permission_granted
            else R.string.notification_permission_required,
        ),
        startAction = {
            StatusIcon(
                imageVector = if (notificationGranted) MiuixIcons.Basic.Check else MiuixIcons.Info,
                ready = notificationGranted,
            )
        },
        endActions = {
            if (!notificationGranted) {
                PrimaryActionButton(
                    text = stringResource(R.string.authorize),
                    onClick = onRequestNotification,
                )
            }
        },
    )

    BasicComponent(
        title = stringResource(R.string.ai_configuration),
        summary = stringResource(
            if (aiConfigured) R.string.ai_configuration_ready
            else if (usesPickup) R.string.xiaomi_pickup_unavailable
            else if (usesXiaomi) R.string.xiaomi_configuration_incomplete
            else R.string.ai_configuration_incomplete,
        ),
        startAction = {
            StatusIcon(
                imageVector = if (aiConfigured) MiuixIcons.Basic.Check else MiuixIcons.Info,
                ready = aiConfigured,
            )
        },
    )

    BasicComponent(
        title = stringResource(R.string.control_center_tile),
        summary = stringResource(R.string.control_center_tile_summary),
        startAction = {
            StatusIcon(
                imageVector = MiuixIcons.AddCircle,
                ready = true,
            )
        },
        endActions = {
            PrimaryActionButton(
                text = stringResource(R.string.add),
                onClick = onAddTile,
            )
        },
    )
}

@Composable
private fun StatusIcon(imageVector: ImageVector, ready: Boolean) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = if (ready) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurfaceVariantActions,
        modifier = Modifier
            .padding(end = 16.dp)
            .size(22.dp),
    )
}

@Composable
private fun PrimaryActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(text)
    }
}
