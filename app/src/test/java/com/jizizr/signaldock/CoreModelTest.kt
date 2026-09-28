package com.jizizr.signaldock

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreModelTest {
    @Test
    fun activityTracking_ignoresWindowContainerClassNames() {
        assertTrue(
            shouldRememberActivityClassName(
                packageName = "com.tencent.mm",
                className = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
            ),
        )
        assertTrue(
            shouldRememberActivityClassName(
                packageName = "com.example.orders",
                className = "com.example.orders.OrderActivity",
            ),
        )
        assertFalse(
            shouldRememberActivityClassName(
                packageName = "com.tencent.mm",
                className = "android.widget.FrameLayout",
            ),
        )
        assertFalse(
            shouldRememberActivityClassName(
                packageName = "com.tencent.mm",
                className = "",
            ),
        )
    }

    @Test
    fun eventTextFallback_mergesOnlyShortLivedLocalKeywords() {
        val observation = PageObservationSnapshot(
            packageName = "com.tencent.mm",
            activityClassName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
            isWechatMiniProgram = true,
            miniProgramLabel = "示例小程序",
            miniProgramIconHash = "icon",
            stableKeywords = emptyList(),
        )
        val merged = observation.copy(stableKeywords = (observation.stableKeywords + "取餐码").distinct())
        assertTrue(merged.stableKeywords.contains("取餐码"))
    }

    @Test
    fun serviceConnection_evaluatesTheCurrentPageOnlyWhenAutoProfilesAreAvailable() {
        assertTrue(
            shouldEvaluateCurrentPageAfterServiceConnect(
                autoPageEnabled = true,
                availableProfileCount = 1,
            ),
        )
        assertFalse(
            shouldEvaluateCurrentPageAfterServiceConnect(
                autoPageEnabled = false,
                availableProfileCount = 1,
            ),
        )
        assertFalse(
            shouldEvaluateCurrentPageAfterServiceConnect(
                autoPageEnabled = true,
                availableProfileCount = 0,
            ),
        )
    }

    @Test
    fun accessibilityStartupRecovery_runsOnlyForAnUnboundAutoPageService() {
        assertTrue(
            shouldRecoverAccessibilityOnStartup(
                autoPageEnabled = true,
                hasEnabledProfiles = true,
                backgroundAllowed = true,
                serviceConnected = false,
            ),
        )
        assertFalse(
            shouldRecoverAccessibilityOnStartup(
                autoPageEnabled = false,
                hasEnabledProfiles = true,
                backgroundAllowed = true,
                serviceConnected = false,
            ),
        )
        assertFalse(
            shouldRecoverAccessibilityOnStartup(
                autoPageEnabled = true,
                hasEnabledProfiles = false,
                backgroundAllowed = true,
                serviceConnected = false,
            ),
        )
        assertFalse(
            shouldRecoverAccessibilityOnStartup(
                autoPageEnabled = true,
                hasEnabledProfiles = true,
                backgroundAllowed = false,
                serviceConnected = false,
            ),
        )
        assertFalse(
            shouldRecoverAccessibilityOnStartup(
                autoPageEnabled = true,
                hasEnabledProfiles = true,
                backgroundAllowed = true,
                serviceConnected = true,
            ),
        )
    }

    @Test
    fun customModel_defaultsToEmptyConnectionFields() {
        val custom = AiSettingsStore.PRESETS.single {
            it.id == AiSettingsStore.CUSTOM_PRESET_ID
        }

        assertEquals("", custom.baseUrl)
        assertEquals("", custom.modelId)
        assertEquals("", custom.reasoningEffort)
    }

    @Test
    fun providerSwitch_keepsCustomConnectionConfigurationIndependent() {
        val xiaoai = AiSettingsStore.PRESETS.single {
            it.id == AiSettingsStore.SUPER_XIAOAI_PRESET_ID
        }
        val custom = AiSettingsStore.PRESETS.single {
            it.id == AiSettingsStore.CUSTOM_PRESET_ID
        }
        val customConfiguration = AiConnectionConfiguration(
            baseUrl = "https://api.example.com/openai/v1",
            modelId = "vision-model",
            reasoningEffort = "low",
        )

        assertEquals(
            "",
            resolveAiConnectionConfiguration(xiaoai, customConfiguration).modelId,
        )
        assertEquals(
            customConfiguration,
            resolveAiConnectionConfiguration(custom, customConfiguration),
        )
    }

    @Test
    fun providersUseIndependentXiaomiTransportsAndRemoveClosedBeta() {
        assertFalse(AiSettingsStore.PRESETS.any { it.id == AiSettingsStore.MICLAW_PRESET_ID })
        assertEquals(AiTransport.XIAOMI_PICKUP,
            AiSettingsStore.PRESETS.single { it.id == AiSettingsStore.XIAOMI_PICKUP_PRESET_ID }.transport)
        assertEquals(AiTransport.SUPER_XIAOAI,
            AiSettingsStore.PRESETS.single { it.id == AiSettingsStore.SUPER_XIAOAI_PRESET_ID }.transport)
    }

    @Test
    fun aiConfigurationReadiness_handlesCustomAndMiclawRequirements() {
        val connection = AiConnectionConfiguration(
            baseUrl = "https://api.example.com/v1",
            modelId = "vision-model",
        )
        assertTrue(
            isAiConfigurationReady(
                usesMiclaw = false,
                miclawUseExternalAgent = false,
                miclawSessionAvailable = false,
                apiKey = "example-key",
                connection = connection,
            ),
        )
        assertFalse(
            isAiConfigurationReady(
                usesMiclaw = false,
                miclawUseExternalAgent = false,
                miclawSessionAvailable = false,
                apiKey = "",
                connection = connection,
            ),
        )
        assertTrue(
            isAiConfigurationReady(
                usesMiclaw = true,
                miclawUseExternalAgent = false,
                miclawSessionAvailable = true,
                apiKey = "",
                connection = AiConnectionConfiguration(),
            ),
        )
    }

    @Test
    fun legacyMigration_doesNotCopyMiclawModelIntoCustom() {
        assertNull(
            legacyCustomConfigurationOrNull(
                selectedPresetId = AiSettingsStore.CUSTOM_PRESET_ID,
                baseUrl = "",
                modelId = "Miclaw 当前模型",
                reasoningEffort = "",
            ),
        )
    }

    @Test
    fun legacyMigration_keepsAnExistingCustomConfiguration() {
        assertEquals(
            AiConnectionConfiguration(
                baseUrl = "https://api.example.com/v1",
                modelId = "vision-model",
                reasoningEffort = "medium",
            ),
            legacyCustomConfigurationOrNull(
                selectedPresetId = AiSettingsStore.CUSTOM_PRESET_ID,
                baseUrl = "https://api.example.com/v1",
                modelId = "vision-model",
                reasoningEffort = "medium",
            ),
        )
    }

    @Test
    fun miclawSession_requiresTokenOrRefreshCredentials() {
        assertFalse(MiclawSession().isUsable)
        assertTrue(MiclawSession(serviceToken = "service-token").isUsable)
        assertTrue(MiclawSession(passToken = "pass-token", userId = "123").isUsable)
        assertFalse(MiclawSession(passToken = "pass-token").isUsable)
    }

    @Test
    fun miclawUnauthorizedDiagnostic_usesSanitizedDiagnosticCode() {
        assertTrue(isMiclawUnauthorizedDiagnostic("miclaw_http_401"))
        assertFalse(isMiclawUnauthorizedDiagnostic("miclaw_http_500"))
        assertFalse(isMiclawUnauthorizedDiagnostic(""))
    }

    @Test
    fun miclawCompatibility_requiresOneTrustedCallerCondition() {
        assertFalse(miclawVerifierAcceptsCaller(false, false, false))
        assertTrue(miclawVerifierAcceptsCaller(true, false, false))
        assertTrue(miclawVerifierAcceptsCaller(false, true, false))
        assertTrue(miclawVerifierAcceptsCaller(false, false, true))
    }

    @Test
    fun resultIslandCapsule_alwaysUsesNarrowFontAndMeasurementPadding() {
        assertTrue(RESULT_ISLAND_NARROW_FONT)
        assertEquals("35060\u2009", resultIslandCapsuleText("35060"))
        assertEquals("取餐码 35060\u2009", resultIslandCapsuleText("取餐码 35060  "))
    }

    @Test
    fun recognizingIsland_usesHyperOsThinkingAnimationProtocolWhenAvailable() {
        assertEquals(
            IslandPicSpec(
                type = RECOGNIZING_SYSTEM_ICON_TYPE,
                key = RECOGNIZING_SYSTEM_ICON_KEY,
                autoplay = true,
            ),
            recognizingIslandPicSpec(),
        )
    }

    @Test
    fun recognizingIsland_startsCompactInsteadOfFirstFloat() {
        assertFalse(ISLAND_FIRST_FLOAT)
    }

    @Test
    fun islandProperty_keepsRecognitionTemporaryButResultsPersistent() {
        assertEquals(RECOGNIZING_ISLAND_PROPERTY, islandPropertyFor(statusOnly = true))
        assertEquals(RESULT_ISLAND_PROPERTY, islandPropertyFor(statusOnly = false))
        assertTrue(RESULT_ISLAND_PROPERTY != RECOGNIZING_ISLAND_PROPERTY)
    }

    @Test
    fun resultRepost_decidesAtExecutionTimeForCurrentSession() {
        assertEquals(
            ResultNotificationPublishDecision.FOREGROUND_REPOSTED,
            resultNotificationPublishDecision(
                isForeground = true,
                foregroundSessionId = 7003,
                notificationId = 7003,
                activeSession = true,
            ),
        )
        assertEquals(
            ResultNotificationPublishDecision.NORMAL_REPOST,
            resultNotificationPublishDecision(
                isForeground = true,
                foregroundSessionId = 7003,
                notificationId = 7004,
                activeSession = true,
            ),
        )
        assertEquals(
            ResultNotificationPublishDecision.NORMAL_REPOST,
            resultNotificationPublishDecision(
                isForeground = false,
                foregroundSessionId = 7003,
                notificationId = 7003,
                activeSession = true,
            ),
        )
        assertEquals(
            ResultNotificationPublishDecision.DROP,
            resultNotificationPublishDecision(
                isForeground = true,
                foregroundSessionId = 7003,
                notificationId = 7003,
                activeSession = false,
            ),
        )
    }

    @Test
    fun islandProductText_keepsLogoAndUsesTwoSmallerSecondaryTexts() {
        assertTrue(
            islandProductTexts(
                fallbackTitle = "5312",
                details = "芭乐奶绿\n正常冰 · 七分糖\n蜜雪冰城",
                itemTitle = "芭乐奶绿",
                itemSubtitle = "正常冰 · 七分糖",
                merchant = "蜜雪冰城",
            ) == IslandProductTexts(
                title = "芭乐奶绿",
                detail = "正常冰 · 七分糖",
                merchant = "蜜雪冰城",
            )
        )
    }

    @Test
    fun islandDetailTag_usesTheExpandedTitleSideTagProtocol() {
        assertEquals(
            IslandDetailTagSpec(
                text = "正常冰 · 七分糖",
                textColor = "#2F80ED",
                darkTextColor = "#79B8FF",
                backgroundColor = "#182F80ED",
            ),
            islandDetailTagSpec("  正常冰 · 七分糖  "),
        )
        assertNull(islandDetailTagSpec("  "))
    }

    @Test
    fun islandShareTemplate_rendersWithoutPrice() {
        val rendered = renderIslandShare(
            SuperIslandSettingsStore.defaultShareTemplate,
            IslandShareValues(
                label = "取餐码",
                code = "5312",
                item = "芭乐奶绿",
                detail = "正常冰 · 七分糖",
                merchant = "蜜雪冰城",
            ),
        )
        assertTrue(rendered.title == "蜜雪冰城")
        assertTrue(rendered.description == "取餐码 5312 · 芭乐奶绿")
        assertTrue(rendered.content.contains("商品：芭乐奶绿 · 正常冰 · 七分糖"))
        assertFalse(rendered.content.contains("价格"))
    }

    @Test
    fun islandShareTemplate_removesEmptyOptionalLines() {
        val rendered = renderIslandShare(
            SuperIslandSettingsStore.defaultShareTemplate,
            IslandShareValues("取餐码", "5312", "", "", ""),
        )
        assertTrue(rendered.description == "取餐码 5312")
        assertTrue(rendered.content == "取餐码：5312")
    }

    @Test
    fun islandShareEditor_insertsTokenAtSelection() {
        val result = insertIslandShareToken(
            TextFieldValue("前后", TextRange(1)),
            "{号码}",
        )

        assertEquals("前{号码}后", result.text)
        assertEquals(TextRange(5), result.selection)
    }

    @Test
    fun islandShareEditor_deletesWholeTokenAfterPartialBackspace() {
        val previous = TextFieldValue("取餐：{号码}", TextRange(7))
        val partiallyDeleted = TextFieldValue("取餐：{号}", TextRange(6))

        val result = applyAtomicIslandShareTokenEdit(previous, partiallyDeleted)

        assertEquals("取餐：", result.text)
        assertEquals(TextRange(3), result.selection)
    }

    @Test
    fun islandShareEditor_rejectsInsertionInsideToken() {
        val previous = TextFieldValue("{号码}", TextRange(2))
        val inserted = TextFieldValue("{号X码}", TextRange(3))

        assertEquals(previous, applyAtomicIslandShareTokenEdit(previous, inserted))
    }

    @Test
    fun diagnosticLogSanitizer_removesCredentialsAndPersonalIdentifiers() {
        val groqKey = listOf("gsk", "EXAMPLEONLY123456").joinToString("_")
        val bearerHeader = "Bearer " + "example-access-token"
        val serviceTokenName = "service" + "Token"
        val input = """
            Authorization: $bearerHeader
            api_key=$groqKey
            Cookie: $serviceTokenName=example-cookie; cUserId=123456789
            password: example-password
            request=https://example.com/v1?access_token=example-query-token&mode=test
            contact=example.user@example.com phone=13800138000
        """.trimIndent()

        val sanitized = DiagnosticLogStore.sanitizeForExport(input)

        assertFalse(sanitized.contains("example-access-token"))
        assertFalse(sanitized.contains(groqKey))
        assertFalse(sanitized.contains("example-cookie"))
        assertFalse(sanitized.contains("example-password"))
        assertFalse(sanitized.contains("example-query-token"))
        assertFalse(sanitized.contains("example.user@example.com"))
        assertFalse(sanitized.contains("13800138000"))
        assertTrue(sanitized.contains("<redacted"))
    }

    @Test
    fun recognitionHistorySummary_prefersMerchantAndItem() {
        val record = RecognitionHistoryRecord(
            id = "example-record",
            createdAtMs = 0,
            analysisDurationMs = 0,
            screenshotWidth = 1080,
            screenshotHeight = 2400,
            sourcePackage = "com.example.order",
            sourceTaskId = -1,
            providerName = "Custom",
            title = "A1024",
            body = "请到柜台取餐",
            infoLines = listOf("请到柜台取餐"),
            qrFound = false,
            content = "取餐码",
            iconType = "food",
            buttonText = "已取餐",
            price = "¥18.00",
            item = "示例饮品",
            itemDetail = "少冰",
            merchant = "示例门店",
            error = "",
            hasScreenshot = true,
            hasQrImage = false,
            hasSourceIcon = true,
        )

        assertEquals("示例门店 · 示例饮品", recognitionHistorySummary(record))
    }

    @Test
    fun autoPageKeywords_removeDynamicCredentialsAndPrices() {
        val keywords = AutoPageProfileStore.sanitizeKeywords(
            listOf("取餐码，示例门店，A1024，¥18.00，订单详情"),
        )

        assertTrue(keywords.contains("取餐码"))
        assertTrue(keywords.contains("示例门店"))
        assertTrue(keywords.contains("订单详情"))
        assertFalse(keywords.any { it.contains("1024") || it.contains("18") })
    }

    @Test
    fun autoPageMatcher_requiresIdentityAndFindsKeywords() {
        val profile = AutoPageProfile(
            id = "profile",
            name = "示例取餐页",
            enabled = true,
            packageName = "com.tencent.mm",
            activityClassName = "AppBrandUI",
            miniProgramLabel = "示例小程序",
            miniProgramIconHash = "abc",
            keywords = listOf("取餐码", "示例门店"),
            excludedKeywords = emptyList(),
            nodeSignature = listOf("订单详情"),
            createdAtMs = 0,
        )
        val observation = PageObservationSnapshot(
            packageName = "com.tencent.mm",
            activityClassName = "AppBrandUI1",
            isWechatMiniProgram = true,
            miniProgramLabel = "示例小程序",
            miniProgramIconHash = "abc",
            stableKeywords = listOf("订单详情", "示例门店", "取餐码"),
        )

        val match = AutoPageMatcher.rank(observation, listOf(profile)).single()

        assertTrue(match.textMatched)
        assertTrue(match.score > 0.8)
        assertFalse(
            AutoPageMatcher.rank(
                observation.copy(stableKeywords = listOf("示例门店", "订单详情")),
                listOf(profile),
            ).single().textMatched,
        )
        assertFalse(
            AutoPageMatcher.rank(
                observation.copy(stableKeywords = observation.stableKeywords + "已完成"),
                listOf(profile.copy(excludedKeywords = listOf("已完成"))),
            ).single().textMatched,
        )
        assertTrue(
            AutoPageMatcher.rank(
                observation.copy(
                    miniProgramLabel = "其他小程序",
                    miniProgramIconHash = "different",
                ),
                listOf(profile),
            ).isEmpty(),
        )
    }

    @Test
    fun autoPageMatcher_neverTreatsRegularWechatAsAMiniProgramPage() {
        val profile = AutoPageProfile(
            id = "wechat-profile",
            name = "示例小程序",
            enabled = true,
            packageName = SourceIconResolver.WECHAT_PACKAGE,
            activityClassName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
            miniProgramLabel = "示例小程序",
            miniProgramIconHash = "icon-hash",
            keywords = listOf("取餐码"),
            excludedKeywords = emptyList(),
            nodeSignature = emptyList(),
            createdAtMs = 0,
        )
        val regularWechat = PageObservationSnapshot(
            packageName = SourceIconResolver.WECHAT_PACKAGE,
            activityClassName = "com.tencent.mm.ui.LauncherUI",
            miniProgramLabel = "示例小程序",
            miniProgramIconHash = "icon-hash",
            stableKeywords = listOf("取餐码"),
        )

        assertTrue(AutoPageMatcher.rank(regularWechat, listOf(profile)).isEmpty())
    }

    @Test
    fun autoPageMatcher_requiresLearnedMiniProgramIdentityWhenAvailable() {
        val profile = AutoPageProfile(
            id = "wechat-profile",
            name = "示例小程序",
            enabled = true,
            packageName = SourceIconResolver.WECHAT_PACKAGE,
            activityClassName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
            miniProgramLabel = "示例小程序",
            miniProgramIconHash = "icon-hash",
            keywords = listOf("取餐码", "示例小程序"),
            excludedKeywords = emptyList(),
            nodeSignature = emptyList(),
            createdAtMs = 0,
        )
        val identityUnavailable = PageObservationSnapshot(
            packageName = SourceIconResolver.WECHAT_PACKAGE,
            activityClassName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI2",
            isWechatMiniProgram = true,
            miniProgramLabel = "",
            miniProgramIconHash = "",
            stableKeywords = listOf("取餐码"),
        )

        assertTrue(AutoPageMatcher.rank(identityUnavailable, listOf(profile)).isEmpty())
        assertTrue(isWechatMiniProgramActivity(identityUnavailable.activityClassName))
        assertFalse(isWechatMiniProgramActivity("com.tencent.mm.ui.LauncherUI"))
    }

    @Test
    fun autoTriggerCoordinator_allowsOneRetryThenRearmsAfterReset() {
        val coordinator = AutoTriggerCoordinator()

        assertTrue(coordinator.canAttempt("profile", "first"))
        coordinator.onAttempt("profile", "first")
        assertFalse(coordinator.canAttempt("profile", "first"))
        assertTrue(coordinator.canAttempt("profile", "changed"))
        coordinator.onAttempt("profile", "changed")
        assertFalse(coordinator.canAttempt("profile", "third"))

        coordinator.reset()
        assertTrue(coordinator.canAttempt("profile", "first"))
        coordinator.onAttempt("profile", "first")
        coordinator.onSuccess("profile")
        assertFalse(coordinator.canAttempt("profile", "changed"))
    }

    @Test
    fun autoPageMatcher_detectsDefiniteWindowIdentityExit() {
        val profile = AutoPageProfile(
            id = "wechat-profile",
            name = "示例小程序",
            enabled = true,
            packageName = SourceIconResolver.WECHAT_PACKAGE,
            activityClassName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
            miniProgramLabel = "示例小程序",
            miniProgramIconHash = "icon-hash",
            keywords = listOf("取餐码"),
            excludedKeywords = emptyList(),
            nodeSignature = emptyList(),
            createdAtMs = 0,
        )

        assertFalse(
            AutoPageMatcher.isDefiniteIdentityExit(
                profile,
                "com.miui.home",
                "com.miui.home.launcher.Launcher",
            ),
        )
        assertTrue(
            AutoPageMatcher.isDefiniteIdentityExit(
                profile,
                SourceIconResolver.WECHAT_PACKAGE,
                "com.tencent.mm.ui.LauncherUI",
            ),
        )
        assertFalse(
            AutoPageMatcher.isDefiniteIdentityExit(
                profile,
                SourceIconResolver.WECHAT_PACKAGE,
                "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
            ),
        )

        val legacyNativeProfile = profile.copy(
            packageName = "com.example.order",
            activityClassName = "android.widget.FrameLayout",
        )
        assertFalse(
            AutoPageMatcher.isDefiniteIdentityExit(
                legacyNativeProfile,
                "com.example.order",
                "com.example.order.OrderDetailActivity",
            ),
        )
        assertTrue(
            AutoPageMatcher.isDefiniteIdentityExit(
                legacyNativeProfile.copy(
                    activityClassName = "com.example.order.OrderDetailActivity",
                ),
                "com.example.order",
                "com.example.order.OrderListActivity",
            ),
        )
    }

    @Test
    fun autoPageMatcher_rejectsNonTargetActivitiesBeforeNodeInspection() {
        val wechatProfile = AutoPageProfile(
            id = "wechat-profile",
            name = "示例小程序",
            enabled = true,
            packageName = SourceIconResolver.WECHAT_PACKAGE,
            activityClassName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
            miniProgramLabel = "示例小程序",
            miniProgramIconHash = "icon-hash",
            keywords = listOf("取餐码"),
            excludedKeywords = emptyList(),
            nodeSignature = emptyList(),
            createdAtMs = 0,
        )

        assertTrue(
            AutoPageMatcher.canMatchIdentity(
                wechatProfile,
                SourceIconResolver.WECHAT_PACKAGE,
                "com.tencent.mm.plugin.appbrand.ui.AppBrandUI02",
            ),
        )
        assertFalse(
            AutoPageMatcher.canMatchIdentity(
                wechatProfile,
                SourceIconResolver.WECHAT_PACKAGE,
                "com.tencent.mm.ui.LauncherUI",
            ),
        )

        val nativeProfile = wechatProfile.copy(
            packageName = "com.example.order",
            activityClassName = "com.example.order.OrderDetailActivity",
        )
        assertTrue(
            AutoPageMatcher.canMatchIdentity(
                nativeProfile,
                "com.example.order",
                "com.example.order.OrderDetailActivity",
            ),
        )
        assertFalse(
            AutoPageMatcher.canMatchIdentity(
                nativeProfile,
                "com.example.order",
                "com.example.order.OrderListActivity",
            ),
        )
        assertTrue(
            AutoPageMatcher.canMatchIdentity(
                nativeProfile.copy(activityClassName = "android.widget.FrameLayout"),
                "com.example.order",
                "com.example.order.OrderDetailActivity",
            ),
        )
    }

    @Test
    fun autoPageMatcher_doesNotTextMatchAnOrderListWithoutTheConfiguredKeyword() {
        val profile = AutoPageProfile(
            id = "pickup-page",
            name = "取餐页",
            enabled = true,
            packageName = SourceIconResolver.WECHAT_PACKAGE,
            activityClassName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
            miniProgramLabel = "示例小程序",
            miniProgramIconHash = "icon-hash",
            keywords = listOf("取餐码", "示例小程序"),
            excludedKeywords = emptyList(),
            nodeSignature = emptyList(),
            createdAtMs = 0,
        )
        val orderList = PageObservationSnapshot(
            packageName = SourceIconResolver.WECHAT_PACKAGE,
            activityClassName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
            isWechatMiniProgram = true,
            miniProgramLabel = "示例小程序",
            miniProgramIconHash = "icon-hash",
            stableKeywords = listOf("示例小程序", "门店订单", "已完成", "再来一单"),
        )

        assertFalse(AutoPageMatcher.rank(orderList, listOf(profile)).single().textMatched)
    }

    @Test
    fun sourcePackage_prefersVerifiedForegroundTaskOverAccessibilityWindow() {
        assertEquals(
            "com.sankuai.meituan",
            resolveSourcePackageName(
                accessibilityPackageName = "com.miui.home",
                foregroundPackageName = "com.sankuai.meituan",
            ),
        )
        assertEquals(
            "com.miui.home",
            resolveSourcePackageName(
                accessibilityPackageName = "com.miui.home",
                foregroundPackageName = null,
            ),
        )
        assertEquals(
            SourceIconResolver.WECHAT_PACKAGE,
            resolveSourcePackageName(
                accessibilityPackageName = "com.miui.home",
                foregroundPackageName = SourceIconResolver.WECHAT_PACKAGE,
            ),
        )
    }

}
