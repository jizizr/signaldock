package com.jizizr.signaldock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Finishing the login Activity must not discard an already-authorized, in-flight validation. */
internal object XiaomiWebLoginCoordinator {
    private val runner = LoginVerificationRunner(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { error ->
        AppLog.w("XiaomiWebLogin", "Web login validation failed: ${error.javaClass.simpleName}")
        error.message?.takeIf { !it.contains("http", true) }?.take(180)
            ?: "网页登录验证失败，原有连接未改变"
    }
    val state = runner.state

    fun reset() = runner.reset()

    fun begin(context: Context, credential: XiaomiWebServiceCredential) {
        val app = context.applicationContext
        runner.start(
            acquire = { XiaomiPassportClient.exchange(app, credential) },
            verify = { candidate ->
                val fixture = fixture()
                try {
                    val raw = XiaomiRecognitionClient.analyzeWithSession(app, fixture,
                        candidate, pickup = false, mode = XiaoAiMode.FAST, prompt = "")
                    val parsed = RustBridge.parseModelResult(fixture, raw)
                    check(parsed.error.isBlank() && parsed.title.contains("5312") &&
                        (parsed.body + parsed.infoLines.joinToString()).contains("芭乐奶绿")) {
                        "网页登录成功，但快速识别未通过测试；原有连接未改变"
                    }
                } finally { fixture.recycle() }
            },
            save = { candidate ->
                val previous = XiaomiSessionStore.load()?.takeIf { it.userId == candidate.userId }
                XiaomiSessionStore.save(XiaomiSession(candidate.accessToken, candidate.deviceId, candidate.expiresAtSeconds,
                    expertToken = previous?.expertToken.orEmpty(), userId = candidate.userId,
                    cUserId = previous?.cUserId.orEmpty(), independentDevice = true, refreshToken = candidate.refreshToken))
                AppLog.i("XiaomiWebLogin", "WEB_FAST_RECOGNITION_PASS sessionSaved=true")
            },
        )
    }

    private fun fixture(): Bitmap = Bitmap.createBitmap(800, 900, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f }
        canvas.drawText("蜜雪冰城 重庆邮电大学店", 36f, 100f, paint)
        canvas.drawText("取餐码", 36f, 250f, paint)
        paint.textSize = 120f
        canvas.drawText("5312", 36f, 405f, paint)
        paint.textSize = 44f
        canvas.drawText("芭乐奶绿 大杯 少冰 五分糖", 36f, 560f, paint)
        canvas.drawText("实付 9 元", 36f, 665f, paint)
    }
}
