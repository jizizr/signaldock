package com.jizizr.signaldock

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XiaomiProtocolTest {
    @Test
    fun speechPacketPreservesChineseAndRequestIdentity() {
        val original = XiaomiSpeechProtocol.event("MultiModal", "ImageUnderstand",
            JSONObject().put("query", "取餐码 5312").put("image_ids", JSONArray().put("fixture")))
        val decoded = checkNotNull(XiaomiSpeechProtocol.decode(XiaomiSpeechProtocol.encode(original)))
        assertEquals(original.toString(), decoded.toString())
    }

    @Test
    fun asynchronousPushIsFlattenedWithoutExecutingActions() {
        val island = XiaomiSpeechProtocol.event("Agent", "SuperIsland", JSONObject()
            .put("scene", "pickup_drink").put("title", "5312").put("brand_name", "示例奶茶店")
            .put("subtitle", "示例奶茶店·大学店").put("content", "芭乐奶绿")
            .put("product_names", JSONArray().put("芭乐奶绿")))
        val push = XiaomiSpeechProtocol.event("General", "Push",
            JSONObject().put("instructions", JSONArray().put(island).toString()))
        val extracted = XiaomiSpeechProtocol.flatten(push).single()
        assertTrue(extracted.fromPush)
        val normalized = JSONObject(XiaomiRecognitionClient.normalizeIsland(extracted.message.getJSONObject("payload")))
        assertEquals("5312", normalized.getString("title"))
        assertEquals("取餐码", normalized.getString("content"))
        assertEquals("芭乐奶绿", normalized.getString("item"))
        assertEquals("示例奶茶店·大学店", normalized.getString("merchant"))
        assertEquals("", normalized.getString("info"))
    }

    @Test
    fun imageReplyRetainsPushDialogInsteadOfStartingAnotherConversation() {
        val request = XiaomiSpeechProtocol.event("Application", "UploadResource", JSONObject())
        request.getJSONObject("header").put("dialog_id", "native-dialog")
        val reply = XiaomiSpeechProtocol.reply(XiaomiSpeechProtocol.Instruction(request, true),
            "MultiModal", "ImageUnderstand", JSONObject())
        assertEquals("native-dialog", reply.getJSONObject("header").getString("id"))
        assertTrue(reply.getJSONObject("header").getBoolean("is_fetch_device_info"))
        assertEquals("native-dialog", reply.getJSONArray("context").getJSONObject(0)
            .getJSONObject("payload").getString("id"))
    }

    @Test
    fun nativeRecognitionRejectsUnrelatedDeviceActions() {
        val screen = JSONObject().put("type",
            "urn:aiot-spec-v3:com.mi.phones:action:[com.xiaomi.aicr/context/get_screen_content]:0:1.0")
        val payload = JSONObject().put("bussiness_type", "MEMORY").put("action", JSONArray().put(screen.toString()))
        assertTrue(XiaomiRecognitionClient.isScreenContentRequest(payload))
        payload.getJSONArray("action").put(JSONObject().put("type", "launch-app"))
        assertFalse(XiaomiRecognitionClient.isScreenContentRequest(payload))
    }

    @Test
    fun truncatedPacketIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiSpeechProtocol.decode(byteArrayOf(8, 2, 18, 127, 0))
        }
    }

    @Test
    fun pushAcknowledgmentUsesEnvelopeIdAndOriginalDialog() {
        val push = XiaomiSpeechProtocol.event("General", "Push", JSONObject())
        push.getJSONObject("header").put("dialog_id", "request-dialog")
        val acknowledgment = checkNotNull(XiaomiSpeechProtocol.acknowledgment(push))
        assertEquals("request-dialog", acknowledgment.getJSONObject("header").getString("id"))
        assertEquals("Push", acknowledgment.getJSONObject("payload").getString("type"))
        assertEquals(push.getJSONObject("header").getString("id"),
            acknowledgment.getJSONObject("payload").getString("id"))
        assertNull(XiaomiSpeechProtocol.acknowledgment(
            XiaomiSpeechProtocol.event("System", "Heartbeat", JSONObject())))
    }

    @Test
    fun imageReplyKeepsMemoryAndCapabilitiesWithoutResettingConversation() {
        val original = JSONArray()
            .put(XiaomiSpeechProtocol.context("General", "RenewSession", JSONObject()))
            .put(XiaomiSpeechProtocol.context("Application", "State", JSONObject()
                .put("switch_status", JSONArray().put(JSONObject().put("name", "MEMORY").put("enabled", true)))
                .put("next_level_state", JSONObject().put("super_xiaoai_on", true))))
            .put(XiaomiSpeechProtocol.context("Agent", "ActionState", JSONObject()
                .put("support_ddf", JSONArray().put(65548L))))
            .put(XiaomiSpeechProtocol.context("MultiModal", "MultiModalState", JSONObject()))
            .put(XiaomiSpeechProtocol.context("System", "EventRoute", JSONObject().put("id", "stale")))
        val reply = XiaomiSpeechProtocol.pickupReplyContexts(original, "current-MemoryPush-42")
        val byName = (0 until reply.length()).associate {
            val item = reply.getJSONObject(it)
            item.getJSONObject("header").getString("name") to item.getJSONObject("payload")
        }
        assertEquals(setOf("State", "ActionState", "EventRoute"), byName.keys)
        val state = checkNotNull(byName["State"])
        assertTrue(state.getJSONArray("switch_status").getJSONObject(0).getBoolean("enabled"))
        assertTrue(state.getJSONObject("next_level_state").getBoolean("super_xiaoai_on"))
        assertEquals("AUTO", state.getJSONObject("app_state").getString("upload_mode"))
        assertFalse(original.getJSONObject(1).getJSONObject("payload").has("app_state"))
        assertEquals(65548L, byName["ActionState"]!!.getJSONArray("support_ddf").getLong(0))
        assertEquals("current-MemoryPush-42", byName["EventRoute"]!!.getString("id"))
    }

    @Test
    fun nativeVoucherKeepsLeadingZerosAndRejectsNonVoucherResults() {
        val island = JSONObject().put("scene", "pickup_drink").put("title", "0076")
            .put("product_names", JSONArray().put("茉莉奶茶"))
        assertEquals("0076", JSONObject(XiaomiRecognitionClient.normalizeIsland(island)).getString("title"))
        island.put("scene", "photo")
        assertThrows(IllegalStateException::class.java) { XiaomiRecognitionClient.normalizeIsland(island) }
    }
}
