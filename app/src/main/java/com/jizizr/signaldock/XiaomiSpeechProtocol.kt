package com.jizizr.signaldock

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** AIVS binary WebSocket envelopes: protobuf Packet(type=2, payload=gzip(JSON)). */
internal object XiaomiSpeechProtocol {
    const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
    data class Instruction(val message: JSONObject, val fromPush: Boolean)

    fun event(namespace: String, name: String, payload: JSONObject, contexts: JSONArray? = null): JSONObject =
        JSONObject().put("header", JSONObject().put("namespace", namespace).put("name", name)
            .put("id", UUID.randomUUID().toString().replace("-", "")))
            .put("payload", payload).apply { contexts?.let { put("context", it) } }

    fun context(namespace: String, name: String, payload: JSONObject): JSONObject =
        JSONObject().put("header", JSONObject().put("namespace", namespace).put("name", name))
            .put("payload", payload)

    /** The AIVS SDK acknowledges push envelopes and terminal instructions on their dialog. */
    fun acknowledgment(message: JSONObject): JSONObject? {
        val header = message.optJSONObject("header") ?: return null
        val dialogId = header.optString("dialog_id").takeIf(String::isNotBlank) ?: return null
        val name = header.optString("namespace") + "." + header.optString("name")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        val type = when (name) {
            "General.Push" -> "Push"
            "Dialog.Finish" -> "Instruction"
            "System.Ping" -> payload.optString("type")
            else -> return null
        }
        val id = if (name == "System.Ping") payload.optString("id") else header.optString("id")
        if (type.isBlank() || id.isBlank()) return null
        return event("System", "Ack", JSONObject().put("type", type).put("id", id))
            .apply { getJSONObject("header").put("id", dialogId) }
    }

    /** Replies to push requests must retain their dialog id and the passive channel flag. */
    fun reply(instruction: Instruction, namespace: String, name: String, payload: JSONObject): JSONObject {
        val dialogId = instruction.message.getJSONObject("header").getString("dialog_id")
        require(dialogId.isNotBlank()) { "小爱未提供截图请求的会话编号" }
        return event(namespace, name, payload, JSONArray()
            .put(context("System", "EventRoute", JSONObject().put("id", dialogId))))
            .apply {
                if (instruction.fromPush) getJSONObject("header")
                    .put("id", dialogId).put("is_fetch_device_info", true)
            }
    }

    /** Keep memory/capability switches on passive replies without renewing their conversation. */
    fun pickupReplyContexts(original: JSONArray, dialogId: String): JSONArray = JSONArray().apply {
        for (index in 0 until original.length()) {
            val item = JSONObject(original.getJSONObject(index).toString())
            val header = item.getJSONObject("header")
            val namespace = header.optString("namespace")
            val name = header.optString("name")
            if (namespace == "MultiModal" || (namespace == "General" && name == "RenewSession") ||
                (namespace == "System" && name == "EventRoute")) continue
            if (namespace == "Application" && name == "State") {
                item.getJSONObject("payload").put("app_state", JSONObject()
                    .put("upload_mode", "AUTO").put("upload_sub_mode", "UNKNOWN"))
            }
            put(item)
        }
        put(context("System", "EventRoute", JSONObject().put("id", dialogId)))
    }

    fun encode(json: JSONObject): ByteArray {
        val text = json.toString().toByteArray()
        require(text.size <= MAX_MESSAGE_BYTES)
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text) } }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(8); write(2); write(18)
            var size = zipped.size
            while (size > 127) { write((size and 127) or 128); size = size ushr 7 }
            write(size); write(zipped)
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): JSONObject? {
        require(bytes.size <= MAX_MESSAGE_BYTES) { "小爱响应过大" }
        var offset = 0
        fun varint(): Int {
            var value = 0
            for (shift in 0..28 step 7) {
                require(offset < bytes.size) { "小爱响应不完整" }
                val part = bytes[offset++].toInt() and 255
                value = value or ((part and 127) shl shift)
                if (part and 128 == 0) return value
            }
            error("小爱响应包含无效长度")
        }
        var type = 0
        var payload: ByteArray? = null
        while (offset < bytes.size) {
            val key = varint()
            when (key and 7) {
                0 -> { val value = varint(); if (key ushr 3 == 1) type = value }
                2 -> {
                    val size = varint()
                    require(size >= 0 && size <= bytes.size - offset) { "小爱响应不完整" }
                    if (key ushr 3 == 2) payload = bytes.copyOfRange(offset, offset + size)
                    offset += size
                }
                else -> error("小爱响应格式不支持")
            }
        }
        if (type != 2 || payload == null) return null // Ignore audio packets.
        val text = GZIPInputStream(payload.inputStream()).use { it.readNBytes(MAX_MESSAGE_BYTES + 1) }
        require(text.size <= MAX_MESSAGE_BYTES) { "小爱响应过大" }
        return JSONObject(text.toString(Charsets.UTF_8))
    }

    /** Push instructions are nested envelopes and may arrive separately from Dialog.Finish. */
    fun flatten(message: JSONObject): List<Instruction> = buildList {
        fun visit(current: JSONObject, pushed: Boolean, depth: Int) {
            require(depth <= 8 && size < 1024) { "小爱指令嵌套过深" }
            val header = current.optJSONObject("header") ?: return
            if (header.optString("namespace") != "General" || header.optString("name") != "Push") {
                add(Instruction(current, pushed))
                return
            }
            val raw = current.optJSONObject("payload")?.opt("instructions") ?: return
            val array = if (raw is JSONArray) raw else JSONArray(raw.toString())
            for (index in 0 until array.length()) {
                val item = array.get(index)
                visit(if (item is JSONObject) item else JSONObject(item.toString()), true, depth + 1)
            }
        }
        visit(message, false, 0)
    }
}
