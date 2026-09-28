package com.jizizr.signaldock

import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in only: uses a synthetic image and the connected phone's Xiaomi session. */
@RunWith(AndroidJUnit4::class)
class XiaomiLiveRecognitionTest {
    @Test
    fun recognizesFixtureThroughSelectedXiaomiChannel() {
        val args = InstrumentationRegistry.getArguments()
        val transportName = args.getString("xiaomiLiveTransport")
        assumeTrue("Pass xiaomiLiveTransport to run the real-device integration test", transportName != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = AiTransport.valueOf(checkNotNull(transportName))
        require(transport in setOf(AiTransport.SUPER_XIAOAI, AiTransport.XIAOMI_PICKUP))
        // Keep this opt-in test visible: the phone's background freezer destroys sockets
        // even while instrumentation is running. Production recognition uses a foreground service.
        ActivityScenario.launch(MainActivity::class.java).use {
            if (args.getString("xiaomiUseSavedSession") != "true") {
                XiaomiCredentialImporter.importFromXiaoAi(context).getOrThrow()
            }
            val bitmap = checkNotNull(BitmapFactory.decodeFile(File(context.cacheDir, "miclaw-self-test.png").path))
            try {
                val result = RustBridge.analyzeScreenshot(context, bitmap, AiSettingsStore.runtimeSnapshot().copy(
                    transport = transport,
                    xiaoAiMode = XiaoAiMode.valueOf(args.getString("xiaomiLiveMode") ?: "FAST"),
                ))
                assertEquals(result.error, "", result.error)
                assertEquals("5312", result.title)
                assertTrue("Missing merchant or product", result.body.contains("蜜雪冰城") ||
                    result.item.contains("芭乐奶绿") || result.merchant.contains("蜜雪冰城"))
            } finally {
                bitmap.recycle()
            }
        }
    }
}
