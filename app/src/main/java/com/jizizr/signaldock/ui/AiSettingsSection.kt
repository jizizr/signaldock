package com.jizizr.signaldock.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.AppLog
import com.jizizr.signaldock.MiclawAgentClient
import com.jizizr.signaldock.MiclawAccountLoginClient
import com.jizizr.signaldock.MiclawCredentialImporter
import com.jizizr.signaldock.MiclawSessionStore
import com.jizizr.signaldock.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AiSettingsSection(
    settings: AiSettingsState,
    allowSystemAccountImport: Boolean,
    onOpenCustomModel: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val presetItems = remember(settings.presetNames) {
        settings.presetNames.map { DropdownItem(text = it) }
    }
    var showMiclawLogin by remember { mutableStateOf(false) }
    var draftServiceToken by remember { mutableStateOf("") }
    var draftPassToken by remember { mutableStateOf("") }
    var draftUserId by remember { mutableStateOf("") }
    var draftCUserId by remember { mutableStateOf("") }
    var importingMiclaw by remember { mutableStateOf(false) }
    var showMiclawAccountLogin by remember { mutableStateOf(false) }
    var accountLoginStage by remember { mutableStateOf(MiclawAccountLoginStage.Credentials) }
    var accountLoginBusy by remember { mutableStateOf(false) }
    var accountName by remember { mutableStateOf("") }
    var accountPassword by remember { mutableStateOf(TextFieldValue()) }
    var accountCaptcha by remember { mutableStateOf("") }
    var accountCaptchaImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var twoFactorOptions by remember { mutableStateOf(emptyList<Int>()) }
    var selectedTwoFactor by remember { mutableIntStateOf(PHONE_2FA) }
    var twoFactorTicket by remember { mutableStateOf("") }
    var twoFactorSent by remember { mutableStateOf(false) }
    var sendingTwoFactorTicket by remember { mutableStateOf(false) }
    var twoFactorResendSeconds by remember { mutableIntStateOf(0) }
    val accountLoginClient = remember { MiclawAccountLoginClient() }

    LaunchedEffect(showMiclawAccountLogin, accountLoginStage, twoFactorResendSeconds) {
        if (
            showMiclawAccountLogin &&
            accountLoginStage == MiclawAccountLoginStage.TwoFactor &&
            twoFactorResendSeconds > 0
        ) {
            delay(1_000)
            twoFactorResendSeconds--
        }
    }

    fun resetAccountLogin() {
        accountLoginClient.reset()
        accountLoginStage = MiclawAccountLoginStage.Credentials
        accountLoginBusy = false
        accountPassword = TextFieldValue()
        accountCaptcha = ""
        accountCaptchaImage = null
        twoFactorOptions = emptyList()
        selectedTwoFactor = PHONE_2FA
        twoFactorTicket = ""
        twoFactorSent = false
        sendingTwoFactorTicket = false
        twoFactorResendSeconds = 0
    }

    fun completeAccountLogin(session: com.jizizr.signaldock.MiclawSession) {
        MiclawSessionStore.save(session)
        settings.refreshMiclawSessionStatus()
        showMiclawAccountLogin = false
        showMiclawLogin = false
        resetAccountLogin()
        onMessage(resources.getString(R.string.miclaw_account_login_success))
    }

    fun handleAccountLoginOutcome(outcome: MiclawAccountLoginClient.Outcome) {
        when (outcome) {
            is MiclawAccountLoginClient.Outcome.Authenticated -> completeAccountLogin(outcome.session)
            is MiclawAccountLoginClient.Outcome.CaptchaRequired -> {
                accountCaptchaImage = BitmapFactory.decodeByteArray(
                    outcome.imageBytes,
                    0,
                    outcome.imageBytes.size,
                )?.asImageBitmap()
                accountCaptcha = ""
                accountLoginStage = MiclawAccountLoginStage.Captcha
            }
            is MiclawAccountLoginClient.Outcome.TwoFactorRequired -> {
                sendingTwoFactorTicket = false
                twoFactorSent = false
                twoFactorResendSeconds = 0
                twoFactorOptions = outcome.options
                selectedTwoFactor = if (PHONE_2FA in outcome.options) PHONE_2FA
                else outcome.options.first()
                twoFactorTicket = ""
                accountLoginStage = MiclawAccountLoginStage.TwoFactor
            }
            is MiclawAccountLoginClient.Outcome.Failed -> onMessage(outcome.message)
        }
    }

    OverlaySpinnerPreference(
        items = presetItems,
        selectedIndex = settings.presetIndex,
        title = stringResource(R.string.provider_preset),
        summary = stringResource(R.string.provider_preset_summary),
        onSelectedIndexChange = settings::selectPreset,
    )
    if (settings.usesMiclaw) {
        val compatibilitySupported = remember(context) {
            MiclawAgentClient.isCompatibilitySupported(context)
        }
        val issue = if (settings.miclawUseExternalAgent) {
            remember(context) { MiclawAgentClient.availability(context) }
        } else {
            null
        }
        BasicComponent(
            title = stringResource(R.string.miclaw_direct_connection),
            summary = when {
                settings.miclawUseExternalAgent -> issue
                    ?: stringResource(R.string.miclaw_external_agent_ready)
                settings.miclawSessionAvailable -> stringResource(R.string.miclaw_direct_connection_ready)
                else -> stringResource(R.string.miclaw_login_required)
            },
        )
        SwitchPreference(
            checked = settings.miclawThinkingEnabled,
            onCheckedChange = settings::updateMiclawThinkingEnabled,
            title = stringResource(R.string.miclaw_thinking),
            summary = stringResource(R.string.miclaw_thinking_summary),
        )
        if (compatibilitySupported) {
            SwitchPreference(
                checked = settings.miclawUseExternalAgent,
                onCheckedChange = settings::updateMiclawUseExternalAgent,
                title = stringResource(R.string.miclaw_compatibility_mode),
                summary = stringResource(R.string.miclaw_compatibility_mode_summary),
            )
        }
        ArrowPreference(
            title = stringResource(R.string.miclaw_login),
            summary = stringResource(
                if (settings.miclawSessionAvailable) R.string.miclaw_login_saved
                else R.string.miclaw_login_not_saved,
            ),
            holdDownState = showMiclawLogin,
            onClick = {
                val session = MiclawSessionStore.load()
                draftServiceToken = ""
                draftPassToken = session?.passToken.orEmpty()
                draftUserId = session?.userId.orEmpty()
                draftCUserId = session?.cUserId.orEmpty()
                showMiclawLogin = true
            },
        )
    } else {
        ArrowPreference(
            title = stringResource(R.string.connection_parameters),
            summary = if (settings.modelId.isBlank()) {
                stringResource(R.string.connection_parameters_incomplete)
            } else {
                settings.modelId
            },
            onClick = onOpenCustomModel,
        )
    }

    MiclawLoginDialog(
        show = showMiclawLogin,
        serviceToken = draftServiceToken,
        passToken = draftPassToken,
        userId = draftUserId,
        cUserId = draftCUserId,
        importing = importingMiclaw,
        hasSession = settings.miclawSessionAvailable,
        allowSystemImport = allowSystemAccountImport,
        onServiceTokenChange = { draftServiceToken = it },
        onPassTokenChange = { draftPassToken = it },
        onUserIdChange = { draftUserId = it },
        onCUserIdChange = { draftCUserId = it },
        onDismiss = { if (!importingMiclaw) showMiclawLogin = false },
        onAccountLogin = {
            if (importingMiclaw) return@MiclawLoginDialog
            resetAccountLogin()
            showMiclawLogin = false
            showMiclawAccountLogin = true
        },
        onImportSystem = {
            if (importingMiclaw) return@MiclawLoginDialog
            importingMiclaw = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    MiclawCredentialImporter.importFromSystemAccount(context)
                }
                importingMiclaw = false
                result.onSuccess {
                    settings.refreshMiclawSessionStatus()
                    showMiclawLogin = false
                    onMessage(resources.getString(R.string.miclaw_import_success))
                }.onFailure { error ->
                    onMessage(error.message ?: resources.getString(R.string.unknown_error))
                }
            }
        },
        onClear = {
            settings.clearMiclawSession()
            showMiclawLogin = false
            onMessage(resources.getString(R.string.miclaw_login_cleared))
        },
        onSave = {
            runCatching {
                settings.saveMiclawSession(
                    serviceToken = draftServiceToken,
                    passToken = draftPassToken,
                    userId = draftUserId,
                    cUserId = draftCUserId,
                )
            }
                .onSuccess {
                    showMiclawLogin = false
                    onMessage(resources.getString(R.string.miclaw_login_saved_message))
                }
                .onFailure { error ->
                    onMessage(error.message ?: resources.getString(R.string.unknown_error))
                }
        },
    )

    MiclawAccountLoginDialog(
        show = showMiclawAccountLogin,
        stage = accountLoginStage,
        busy = accountLoginBusy,
        account = accountName,
        password = accountPassword,
        captcha = accountCaptcha,
        captchaImage = accountCaptchaImage,
        twoFactorOptions = twoFactorOptions,
        selectedTwoFactor = selectedTwoFactor,
        ticket = twoFactorTicket,
        ticketSent = twoFactorSent,
        sendingTicket = sendingTwoFactorTicket,
        resendSeconds = twoFactorResendSeconds,
        onAccountChange = { accountName = it },
        onPasswordChange = { accountPassword = it },
        onCaptchaChange = { accountCaptcha = it },
        onSelectTwoFactor = {
            selectedTwoFactor = it
            twoFactorTicket = ""
            twoFactorSent = false
            sendingTwoFactorTicket = false
            twoFactorResendSeconds = 0
        },
        onTicketChange = { twoFactorTicket = it },
        onDismiss = {
            if (!accountLoginBusy) {
                showMiclawAccountLogin = false
                resetAccountLogin()
                showMiclawLogin = true
            }
        },
        onLogin = {
            if (accountLoginBusy) return@MiclawAccountLoginDialog
            accountLoginBusy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        accountLoginClient.login(
                            account = accountName,
                            password = accountPassword.text,
                            captcha = if (accountLoginStage == MiclawAccountLoginStage.Captcha) {
                                accountCaptcha
                            } else {
                                ""
                            },
                        )
                    }
                }
                accountLoginBusy = false
                result.onSuccess(::handleAccountLoginOutcome).onFailure { error ->
                    AppLog.e("MiclawAccountLogin", "Xiaomi login failed", error)
                    onMessage(error.message ?: resources.getString(R.string.unknown_error))
                }
            }
        },
        onSendTicket = {
            if (accountLoginBusy || twoFactorResendSeconds > 0) {
                return@MiclawAccountLoginDialog
            }
            accountLoginBusy = true
            sendingTwoFactorTicket = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { accountLoginClient.sendTicket(selectedTwoFactor) }
                }
                accountLoginBusy = false
                sendingTwoFactorTicket = false
                result.onSuccess {
                    twoFactorSent = true
                    twoFactorResendSeconds = TWO_FACTOR_RESEND_SECONDS
                    onMessage(resources.getString(R.string.miclaw_verification_sent))
                }.onFailure { error ->
                    AppLog.e("MiclawAccountLogin", "Sending Xiaomi 2FA ticket failed", error)
                    onMessage(error.message ?: resources.getString(R.string.unknown_error))
                }
            }
        },
        onVerifyTicket = {
            if (accountLoginBusy) return@MiclawAccountLoginDialog
            accountLoginBusy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        accountLoginClient.verifyTicket(selectedTwoFactor, twoFactorTicket)
                    }
                }
                accountLoginBusy = false
                result.onSuccess(::completeAccountLogin).onFailure { error ->
                    AppLog.e("MiclawAccountLogin", "Verifying Xiaomi 2FA ticket failed", error)
                    onMessage(error.message ?: resources.getString(R.string.unknown_error))
                }
            }
        },
    )
}

@Composable
private fun MiclawLoginDialog(
    show: Boolean,
    serviceToken: String,
    passToken: String,
    userId: String,
    cUserId: String,
    importing: Boolean,
    hasSession: Boolean,
    allowSystemImport: Boolean,
    onServiceTokenChange: (String) -> Unit,
    onPassTokenChange: (String) -> Unit,
    onUserIdChange: (String) -> Unit,
    onCUserIdChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAccountLogin: () -> Unit,
    onImportSystem: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    var tokenVisible by remember { mutableStateOf(false) }
    WindowDialog(
        title = stringResource(R.string.miclaw_login),
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.miclaw_login_summary),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TextButton(
                text = stringResource(R.string.miclaw_account_login),
                onClick = onAccountLogin,
                enabled = !importing,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            )
            StableTextField(
                value = serviceToken,
                onValueChange = onServiceTokenChange,
                label = stringResource(R.string.miclaw_service_token),
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) MiuixIcons.Hide else MiuixIcons.Show,
                            contentDescription = stringResource(
                                if (tokenVisible) R.string.hide_api_key else R.string.show_api_key,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            StableTextField(
                value = passToken,
                onValueChange = onPassTokenChange,
                label = stringResource(R.string.miclaw_pass_token),
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            StableTextField(
                value = userId,
                onValueChange = onUserIdChange,
                label = "userId",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            StableTextField(
                value = cUserId,
                onValueChange = onCUserIdChange,
                label = "cUserId",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (allowSystemImport) {
                TextButton(
                    text = stringResource(
                        if (importing) R.string.miclaw_importing else R.string.miclaw_import_system,
                    ),
                    onClick = onImportSystem,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(if (hasSession) R.string.clear else R.string.cancel),
                    onClick = if (hasSession) onClear else onDismiss,
                    enabled = !importing,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.save),
                    onClick = onSave,
                    enabled = !importing && (
                        serviceToken.isNotBlank() ||
                            passToken.isNotBlank() && userId.isNotBlank()
                        ),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private enum class MiclawAccountLoginStage { Credentials, Captcha, TwoFactor }

private const val PHONE_2FA = 4
private const val EMAIL_2FA = 8
private const val TWO_FACTOR_RESEND_SECONDS = 60

@Composable
private fun MiclawAccountLoginDialog(
    show: Boolean,
    stage: MiclawAccountLoginStage,
    busy: Boolean,
    account: String,
    password: TextFieldValue,
    captcha: String,
    captchaImage: ImageBitmap?,
    twoFactorOptions: List<Int>,
    selectedTwoFactor: Int,
    ticket: String,
    ticketSent: Boolean,
    sendingTicket: Boolean,
    resendSeconds: Int,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (TextFieldValue) -> Unit,
    onCaptchaChange: (String) -> Unit,
    onSelectTwoFactor: (Int) -> Unit,
    onTicketChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onLogin: () -> Unit,
    onSendTicket: () -> Unit,
    onVerifyTicket: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    WindowDialog(
        title = stringResource(R.string.miclaw_account_login),
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.miclaw_account_login_summary),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (stage != MiclawAccountLoginStage.TwoFactor) {
                StableTextField(
                    value = account,
                    onValueChange = onAccountChange,
                    label = stringResource(R.string.xiaomi_account),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.password),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) MiuixIcons.Hide else MiuixIcons.Show,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.hide_api_key else R.string.show_api_key,
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (stage == MiclawAccountLoginStage.Captcha) {
                    captchaImage?.let {
                        Image(
                            bitmap = it,
                            contentDescription = stringResource(R.string.captcha),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                        )
                    }
                    StableTextField(
                        value = captcha,
                        onValueChange = onCaptchaChange,
                        label = stringResource(R.string.captcha),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = stringResource(
                            if (busy) R.string.miclaw_logging_in else R.string.login,
                        ),
                        onClick = onLogin,
                        enabled = !busy && account.isNotBlank() && password.text.isNotBlank() &&
                            (stage != MiclawAccountLoginStage.Captcha || captcha.isNotBlank()),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.miclaw_two_factor_required),
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                if (twoFactorOptions.size > 1) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (PHONE_2FA in twoFactorOptions) {
                            TextButton(
                                text = stringResource(R.string.sms_verification),
                                onClick = { onSelectTwoFactor(PHONE_2FA) },
                                colors = if (selectedTwoFactor == PHONE_2FA) {
                                    ButtonDefaults.textButtonColorsPrimary()
                                } else {
                                    ButtonDefaults.textButtonColors()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (EMAIL_2FA in twoFactorOptions) {
                            TextButton(
                                text = stringResource(R.string.email_verification),
                                onClick = { onSelectTwoFactor(EMAIL_2FA) },
                                colors = if (selectedTwoFactor == EMAIL_2FA) {
                                    ButtonDefaults.textButtonColorsPrimary()
                                } else {
                                    ButtonDefaults.textButtonColors()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                TextButton(
                    text = when {
                        sendingTicket -> stringResource(R.string.sending)
                        resendSeconds > 0 -> stringResource(
                            R.string.miclaw_resend_countdown,
                            resendSeconds,
                        )
                        ticketSent -> stringResource(R.string.miclaw_resend_code)
                        else -> stringResource(R.string.send_verification_code)
                    },
                    onClick = onSendTicket,
                    enabled = !busy && resendSeconds == 0,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ticketSent) {
                    Text(
                        text = stringResource(R.string.miclaw_verification_sent_hint),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                StableTextField(
                    value = ticket,
                    onValueChange = onTicketChange,
                    label = stringResource(R.string.verification_code),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = stringResource(
                            if (busy && !sendingTicket) R.string.verifying
                            else R.string.verify_and_login,
                        ),
                        onClick = onVerifyTicket,
                        enabled = !busy && ticketSent && ticket.isNotBlank(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
