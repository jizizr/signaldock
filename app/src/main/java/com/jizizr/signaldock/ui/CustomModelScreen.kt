package com.jizizr.signaldock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jizizr.signaldock.AiConnectionConfiguration
import com.jizizr.signaldock.isAiConfigurationReady
import com.jizizr.signaldock.isValidAiBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun CustomModelScreen(
    settings: AiSettingsState,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var modelId by rememberSaveable { mutableStateOf(settings.modelId) }
    var reasoningEffort by rememberSaveable { mutableStateOf(settings.reasoningEffort) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val validUrl = isValidAiBaseUrl(baseUrl)
    val canSave = !saving && isAiConfigurationReady(
        apiKey, AiConnectionConfiguration(baseUrl, modelId, reasoningEffort),
    )

    PredictiveBackContainer(onBack = onBack) {
        Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.custom_model),
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
                        text = stringResource(if (saving) R.string.saving else R.string.save),
                        enabled = canSave,
                        onClick = {
                            saving = true
                            saveFailed = false
                            scope.launch {
                                try {
                                    settings.saveCustomConfiguration(
                                        apiKey = apiKey.trim(),
                                        baseUrl = baseUrl.trim(),
                                        modelId = modelId.trim(),
                                        reasoningEffort = reasoningEffort.trim(),
                                    )
                                    onSaved()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    saveFailed = true
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    )
                },
            )
        },
        ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(layoutDirection),
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
        ) {
            item(key = "connection") {
                SmallTitle(stringResource(R.string.connection_parameters))
                SectionCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StableTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = stringResource(R.string.api_key),
                            singleLine = true,
                            visualTransformation = if (keyVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next,
                            ),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        imageVector = if (keyVisible) {
                                            MiuixIcons.Hide
                                        } else {
                                            MiuixIcons.Show
                                        },
                                        contentDescription = stringResource(
                                            if (keyVisible) {
                                                R.string.hide_api_key
                                            } else {
                                                R.string.show_api_key
                                            },
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        StableTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = stringResource(R.string.base_url),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (baseUrl.isNotBlank() && !validUrl) {
                            Text(
                                text = stringResource(R.string.base_url_invalid),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                        if (saveFailed) {
                            Text(
                                text = stringResource(R.string.custom_model_save_failed),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                        StableTextField(
                            value = modelId,
                            onValueChange = { modelId = it },
                            label = stringResource(R.string.model_id),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        StableTextField(
                            value = reasoningEffort,
                            onValueChange = { reasoningEffort = it },
                            label = stringResource(R.string.reasoning_effort),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.reasoning_effort_summary),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        }
    }
}
