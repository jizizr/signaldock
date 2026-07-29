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
        val miclaw = AiSettingsStore.PRESETS.single {
            it.id == AiSettingsStore.MICLAW_PRESET_ID
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
            "Miclaw 当前模型",
            resolveAiConnectionConfiguration(miclaw, customConfiguration).modelId,
        )
        assertEquals(
            customConfiguration,
            resolveAiConnectionConfiguration(custom, customConfiguration),
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
    fun miclawCompatibility_requiresOneTrustedCallerCondition() {
        assertFalse(miclawVerifierAcceptsCaller(false, false, false))
        assertTrue(miclawVerifierAcceptsCaller(true, false, false))
        assertTrue(miclawVerifierAcceptsCaller(false, true, false))
        assertTrue(miclawVerifierAcceptsCaller(false, false, true))
    }

    @Test
    fun islandNarrowFont_appliesToNumericAndLatinCodes() {
        assertTrue(supportsNarrowFont("35060"))
        assertTrue(supportsNarrowFont("1234567"))
        assertTrue(supportsNarrowFont("AB-12345"))
        assertFalse(supportsNarrowFont("取餐码35060"))
    }

    @Test
    fun islandNarrowFont_addsRightSideMeasurementPadding() {
        assertTrue(islandCapsuleText("35060", narrowFont = true).endsWith("\u2009"))
        assertFalse(islandCapsuleText("取餐码", narrowFont = false).endsWith("\u2009"))
    }

    @Test
    fun islandPrice_addsPaddingToTheLastCapsuleField() {
        assertTrue(islandCapsuleText("¥29.90", narrowFont = true).endsWith("\u2009"))
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

}
