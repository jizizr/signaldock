package com.jizizr.signaldock

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class XiaoAiMode { FAST, EXPERT }

// XiaoAi's speech gateway rejects queries over 2,000 characters.
internal const val XIAOMI_FAST_PROMPT = "识别图片中的取餐码等凭证，以JSON格式返回：title为号码，content为编号类型，" +
    "merchant为品牌和分店，item为商品，price为实付金额，itemDetail为规格，info为订单状态，" +
    "iconType为图标类型（MILK_TEA等），buttonText为完成按钮。缺失字段留空。"

/** Independent Xiaomi transports; none of these calls the retired PC beta endpoint. */
object XiaomiRecognitionClient {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS).callTimeout(110, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun analyze(context: Context, bitmap: Bitmap, pickup: Boolean, mode: XiaoAiMode, prompt: String): String {
        // The network path never silently starts a privileged account import.
        val activeSession = checkNotNull(XiaomiSessionStore.load()?.takeIf { it.isUsable }) {
            "小爱尚未连接或登录已过期，请在 AI 设置中重新连接"
        }
        return analyzeWithSession(context, bitmap, activeSession, pickup, mode, prompt)
    }

    internal fun analyzeWithSession(context: Context, bitmap: Bitmap, activeSession: XiaomiSession,
        pickup: Boolean, mode: XiaoAiMode, prompt: String): String {
        check(pickup || mode != XiaoAiMode.EXPERT || activeSession.expertToken.isNotBlank()) {
            "请先打开超级小爱的专家模式完成一次对话，再重新连接"
        }
        check(!pickup || activeSession.independentDevice) {
            "取餐码渠道需要独立网页登录，请在 AI 设置中连接小米账号"
        }
        val jpeg = bitmap.toUploadJpegBytes()
        val ua = userAgent(context)
        AppLog.i("XiaomiRecognition", "Starting ${if (pickup) "pickup" else mode.name} recognition")
        return if (!pickup && mode == XiaoAiMode.EXPERT) {
            expert(activeSession, jpeg, prompt, ua, XiaomiDeviceSigner.headers(context, activeSession.userId))
        } else {
            val imageId = upload(activeSession, jpeg, ua)
            AppLog.i("XiaomiRecognition", "Image upload completed")
            speech(activeSession, imageId, pickup, ua)
        }
    }

    private fun userAgent(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo("com.miui.voiceassist", 0) }.getOrNull()
        check(info != null) { "未安装超级小爱" }
        return "${Build.MODEL}; MIAI/${info.versionName} Build/${info.longVersionCode} " +
            "Channel/MIUI${Build.VERSION.INCREMENTAL} Device/${Build.DEVICE} " +
            "OS/${Build.VERSION.RELEASE} SDK/${Build.VERSION.SDK_INT} Flavors/miui " +
            "ro.miui.ui.version.name/${runCatching { Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java).invoke(null, "ro.miui.ui.version.name") as String }.getOrDefault("")} " +
            "PhoneLevel/High DeviceType/phone DeviceLevel/High"
    }

    private fun upload(session: XiaomiSession, jpeg: ByteArray, ua: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("prefix", "multimodal_")
            .addFormDataPart("requestId", UUID.randomUUID().toString().replace("-", ""))
            .addFormDataPart("file", "screenshot.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())).build()
        val request = Request.Builder().url("https://file.ai.xiaomi.com/file/image")
            .header("Authorization", session.authorization()).header("User-Agent", ua).post(body).build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "小爱图片上传失败（HTTP ${response.code}）" }
            JSONObject(readBody(response)).optString("fileId").also {
                check(it.isNotBlank()) { "小爱图片上传未返回图片编号" }
            }
        }
    }

    private fun speech(session: XiaomiSession, imageId: String, pickup: Boolean, ua: String): String {
        val completed = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val failure = AtomicReference<Throwable?>()
        val stream = StringBuilder()
        var serviceMessage = ""
        var imageRequests = 0
        var screenRequests = 0
        val imageDialogs = mutableSetOf<String>()
        var queryContexts = JSONArray()
        var requestDialog = ""
        val request = Request.Builder().url("wss://speech.xiaomixiaoai.com/speech/v1.0/longaccess")
            .header("Authorization", session.authorization()).header("User-Agent", ua)
            .header("supportCompress", "true").header("Heartbeat-Client", "90")
            .header("Client-Connection-Id", UUID.randomUUID().toString().replace("-", "")).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLog.i("XiaomiRecognition", "Speech channel connected")
                runCatching {
                    fun send(message: JSONObject) { check(webSocket.send(XiaomiSpeechProtocol.encode(message).toByteString())) }
                    send(XiaomiSpeechProtocol.event("Settings", "GlobalConfig", JSONObject()
                        .put("client_info", JSONObject().put("device_id", session.deviceId)
                            .put("capabilities_version", 7012001).put("engine_id", "SignalDock_" + UUID.randomUUID())
                            .put("time_zone", "Asia/Shanghai"))
                        .put("locale", JSONObject().put("langs", JSONArray().put("zh-CN")).put("location", "CN"))
                        .apply { if (pickup) put("sdk", JSONObject().put("lang", "JAVA").put("version", 1076007)) }
                        .put("push", JSONObject().put("disable_push", !pickup))))
                    val contexts = JSONArray()
                        .put(XiaomiSpeechProtocol.context("General", "RenewSession", JSONObject()))
                        .put(XiaomiSpeechProtocol.context("Execution", "RequestControl", JSONObject()
                            .put("disabled", JSONArray().put("TTS").put("TTS_STREAM").put("LLM_TTS_AUDIO"))))
                        .put(XiaomiSpeechProtocol.context("General", "RequestState", JSONObject()
                            .put("origin", if (pickup) "com.miui.voiceassist.query&&memory" else "com.miui.voiceassist.query&&screen_recognition")))
                        .put(XiaomiSpeechProtocol.context("Application", "State", JSONObject()
                            .apply { if (!pickup) put("app_state", JSONObject()
                                .put("upload_mode", "MANUAL").put("upload_sub_mode", "UNKNOWN")) }
                            .put("switch_status", JSONArray()
                                .put(JSONObject().put("name", "MEMORY").put("enabled", true))
                                .put(JSONObject().put("name", "LLM").put("enabled", true)))
                            .put("next_level_state", JSONObject().put("super_xiaoai_on", true)
                                .put("is_xiaoai_auto_mode", false).put("is_miclaw_expert_mode", false))))
                    if (pickup) {
                        // The gateway rejects collection without this capability declaration.
                        // We implement only get_screen_content, using this call's uploaded image.
                        contexts.put(XiaomiSpeechProtocol.context("Agent", "ActionState",
                            JSONObject().put("support_ddf", JSONArray().put(65548L))))
                        contexts.put(XiaomiSpeechProtocol.context("Application", "AppDetailV1", JSONObject()
                            .put("foreground_app", "com.jizizr.signaldock").put("foreground_app_name", "信岛")))
                        val query = XiaomiSpeechProtocol.event("Nlp", "Request",
                            JSONObject().put("query", "帮我记一下"), contexts)
                        requestDialog = query.getJSONObject("header").getString("id")
                        queryContexts = contexts
                        send(query)
                    } else {
                        send(XiaomiSpeechProtocol.event("MultiModal", "ImageUnderstand", JSONObject()
                            .put("image_ids", JSONArray().put(imageId))
                            .put("query", XIAOMI_FAST_PROMPT), contexts))
                    }
                }.onFailure { failure.set(it); completed.countDown() }
            }

            private fun receive(webSocket: WebSocket, message: JSONObject) {
                fun acknowledge(envelope: JSONObject) {
                    XiaomiSpeechProtocol.acknowledgment(envelope)?.let {
                        check(webSocket.send(XiaomiSpeechProtocol.encode(it).toByteString()))
                    }
                }
                acknowledge(message)
                for (instruction in XiaomiSpeechProtocol.flatten(message)) {
                    if (completed.count == 0L) return
                    if (instruction.message !== message) acknowledge(instruction.message)
                    val header = instruction.message.getJSONObject("header")
                    val name = header.optString("namespace") + "." + header.optString("name")
                    AppLog.d("XiaomiRecognition", "Speech instruction $name pushed=${instruction.fromPush} imageBound=${header.optString("dialog_id") in imageDialogs}")
                    val payload = instruction.message.optJSONObject("payload") ?: JSONObject()
                    fun reply(namespace: String, replyName: String, body: JSONObject) {
                        val response = XiaomiSpeechProtocol.reply(instruction, namespace, replyName, body)
                        response.put("context", XiaomiSpeechProtocol.pickupReplyContexts(
                            queryContexts, header.getString("dialog_id")))
                        check(webSocket.send(XiaomiSpeechProtocol.encode(response).toByteString()))
                    }
                    when (name) {
                        "Application.UploadResource" -> if (pickup) {
                            check(isPickupDialog(requestDialog, header.optString("dialog_id"))) {
                                "小米取图请求与当前识别会话不匹配，请重试"
                            }
                            check(++imageRequests <= 2) { "取餐码接口重复索取图片，请重试" }
                            check(payload.optString("upload_type") == "IMAGE" &&
                                payload.optString("upload_target_service") == "SPEECH_ACCESS") {
                                "小米取餐码协议已变化，请改用超级小爱"
                            }
                            reply("MultiModal", "ImageUnderstand", JSONObject()
                                .put("image_ids", JSONArray().put(imageId)).put("check_access_result_code", 0))
                            imageDialogs.add(header.getString("dialog_id"))
                        }
                        "Agent.Action" -> if (pickup) {
                            check(isPickupDialog(requestDialog, header.optString("dialog_id"))) {
                                "小米取图请求与当前识别会话不匹配，请重试"
                            }
                            check(++screenRequests <= 2 && isScreenContentRequest(payload)) {
                                "此截图未返回取餐码识别请求，请改用超级小爱"
                            }
                            // Only return metadata for the image already uploaded by this call.
                            // No instruction may execute arbitrary device actions or collect another screen.
                            reply("Agent", "UploadScreenEvent", JSONObject()
                                .put("appName", "信岛").put("packageName", "com.jizizr.signaldock")
                                .put("pageUrl", "").put("schemeUrl", "").put("pageType", "OTHER")
                                .put("urlType", "IMAGE").put("linkType", "IMAGE_PREVIEW").put("pageText", "")
                                .put("coverImageFileId", imageId).put("images", JSONArray()))
                            imageDialogs.add(header.getString("dialog_id"))
                        }
                        "Agent.SuperIsland" -> {
                            check(!pickup || (isPickupDialog(requestDialog, header.optString("dialog_id")) &&
                                header.optString("dialog_id") in imageDialogs)) {
                                "取餐码接口未确认使用当前截图，请改用快速模式"
                            }
                            result.set(normalizeIsland(payload)); completed.countDown()
                        }
                        "Template.ToastStream" -> {
                            stream.append(payload.optString("markdown_text"))
                            check(stream.length <= XiaomiSpeechProtocol.MAX_MESSAGE_BYTES) { "小爱返回内容过长" }
                        }
                        "Template.Toast" -> serviceMessage = payload.optString("text").take(160)
                        "System.Exception" -> error("小爱识别失败（${payload.optInt("code")}）")
                        "Nlp.FinishStream" -> {
                            if (!pickup && stream.isNotBlank()) { result.set(stream.toString()); completed.countDown() }
                        }
                        "Dialog.Finish" -> {
                            // A child MemoryPush may finish before the root returns the island.
                            if (pickup && header.optString("dialog_id") != requestDialog) continue
                            if (!pickup && stream.isNotBlank()) result.set(stream.toString())
                            else failure.set(IllegalStateException(serviceMessage.ifBlank {
                                if (pickup) "此截图未识别到可上岛凭证，请改用超级小爱"
                                else "小爱未返回识别结果，请重试"
                            }))
                            completed.countDown()
                        }
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { XiaomiSpeechProtocol.decode(bytes.toByteArray())?.let { receive(webSocket, it) } }
                    .onFailure { failure.set(it); completed.countDown() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { receive(webSocket, JSONObject(text)) }.onFailure { failure.set(it); completed.countDown() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (completed.count == 0L || result.get() != null) return
                AppLog.w("XiaomiRecognition", "Speech channel failed: ${t.javaClass.simpleName}: ${t.message}")
                if (result.get() == null) failure.compareAndSet(null,
                    IllegalStateException("小爱连接中断${response?.let { "（HTTP ${it.code}）" }.orEmpty()}", t))
                completed.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { completed.countDown() }
        })
        return try {
            check(completed.await(95, TimeUnit.SECONDS)) { "${if (pickup) "取餐码接口" else "小爱"}响应超时" }
            failure.get()?.let { throw it }
            checkNotNull(result.get()?.takeIf(String::isNotBlank)) { "小爱未返回有效识别结果" }.also { raw ->
                if (!pickup) {
                    // Log schema only; never record the image/model contents or credentials.
                    val shape = runCatching {
                        val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
                        listOf("title", "content", "merchant", "price", "item", "itemDetail", "info", "iconType", "buttonText")
                            .joinToString(",") { key -> "$key:${when {
                                !json.has(key) -> "missing"
                                json.isNull(key) -> "null"
                                else -> json.get(key).javaClass.simpleName
                            }}" }
                    }.getOrDefault("not_json_object")
                    AppLog.i("XiaomiRecognition", "FAST result schema=$shape length=${raw.length}")
                }
            }
        } finally { socket.cancel() }
    }

    internal fun isScreenContentRequest(payload: JSONObject): Boolean {
        if (payload.optString("bussiness_type") != "MEMORY") return false
        val actions = payload.optJSONArray("action") ?: return false
        return actions.length() == 1 && runCatching {
            val action = actions.get(0).let { if (it is JSONObject) it else JSONObject(it.toString()) }
            action.optString("type") == "urn:aiot-spec-v3:com.mi.phones:action:[com.xiaomi.aicr/context/get_screen_content]:0:1.0"
        }.getOrDefault(false)
    }

    internal fun normalizeIsland(payload: JSONObject): String {
        val scene = payload.optString("scene")
        check(scene in setOf("pickup_food", "pickup_drink", "delivery", "locker", "queue")) {
            "此截图未识别到可上岛凭证，请改用超级小爱"
        }
        val code = payload.optString("title")
        check(code.isNotBlank()) { "取餐码接口返回了空凭证" }
        val label = when (scene) {
            "pickup_food", "pickup_drink" -> "取餐码"
            "delivery" -> "取件码"
            "locker" -> "存取码"
            "queue" -> "排队号"
            else -> payload.optString("subtitle").ifBlank { "凭证" }
        }
        val item = payload.optJSONArray("product_names")?.let { a ->
            (0 until a.length()).joinToString("、") { a.optString(it) }
        }.orEmpty()
        return JSONObject().put("title", code).put("content", label)
            .put("merchant", payload.optString("subtitle").ifBlank { payload.optString("brand_name") })
            .put("item", item)
            .put("info", payload.optString("content").takeUnless { it == item }.orEmpty()).put("price", "").put("itemDetail", "")
            .put("iconType", if (scene == "pickup_drink") "MILK_TEA" else if (scene == "pickup_food") "TAKEOUT_BAG" else "PACKAGE")
            .put("buttonText", if (scene.startsWith("pickup_")) "已取餐" else "已完成").toString()
    }

    private fun expert(session: XiaomiSession, jpeg: ByteArray, prompt: String, ua: String,
        deviceHeaders: Map<String, String>): String {
        check(session.expertToken.isNotBlank()) { "请先打开超级小爱的专家模式完成一次对话，再重新连接" }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        val body = JSONObject().put("model", "mimo-omni")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("stream", false).put("temperature", 0.1).put("max_tokens", 4096)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", true))
        val cookie = "serviceToken=${session.expertToken}; miclaw_serviceToken=${session.expertToken}; userId=${session.userId}; cUserId=${session.cUserId}"
        val request = Request.Builder()
            .url("https://api.miclaw.xiaomi.net/osbot/api/llm/v2/chat/completions?bizId=xiaoai&featureId=common")
            .header("Cookie", cookie).header("User-Agent", ua)
            .apply { deviceHeaders.forEach { (name, value) -> header(name, value) } }
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "小爱专家模式调用失败（HTTP ${response.code}），请重新连接小爱" }
            JSONObject(readBody(response)).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.takeIf(String::isNotBlank)
                ?: error("小爱专家模式未返回有效结果")
        }
    }

    private fun readBody(response: Response): String {
        val data = checkNotNull(response.body).byteStream().use { it.readNBytes(XiaomiSpeechProtocol.MAX_MESSAGE_BYTES + 1) }
        check(data.size <= XiaomiSpeechProtocol.MAX_MESSAGE_BYTES) { "小爱返回内容过长" }
        return data.toString(Charsets.UTF_8)
    }
}
