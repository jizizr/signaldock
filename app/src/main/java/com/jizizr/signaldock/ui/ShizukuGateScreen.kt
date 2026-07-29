package com.jizizr.signaldock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ShizukuGateScreen(
    available: Boolean,
    permissionPending: Boolean,
    permissionDenied: Boolean,
    onOpenShizuku: () -> Unit,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
) {
    val title = when {
        !available -> stringResource(R.string.shizuku_gate_service_title)
        permissionPending -> stringResource(R.string.shizuku_gate_pending_title)
        permissionDenied -> stringResource(R.string.shizuku_gate_denied_title)
        else -> stringResource(R.string.shizuku_gate_permission_title)
    }
    val summary = when {
        !available -> stringResource(R.string.shizuku_gate_service_summary)
        permissionPending -> stringResource(R.string.shizuku_gate_pending_summary)
        permissionDenied -> stringResource(R.string.shizuku_gate_denied_summary)
        else -> stringResource(R.string.shizuku_gate_permission_summary)
    }

    Scaffold { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.size(24.dp))
            Text(
                text = title,
                style = MiuixTheme.textStyles.title1,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            Spacer(Modifier.size(28.dp))

            if (permissionPending) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                Button(
                    onClick = if (available) onRequestPermission else onOpenShizuku,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        stringResource(
                            when {
                                !available -> R.string.open_shizuku
                                permissionDenied -> R.string.shizuku_reauthorize
                                else -> R.string.shizuku_authorize_and_continue
                            },
                        ),
                    )
                }
            }

            if (!permissionPending) {
                Spacer(Modifier.size(10.dp))
                TextButton(
                    text = stringResource(R.string.retry_detection),
                    onClick = onRetry,
                )
            }
        }
    }
}
