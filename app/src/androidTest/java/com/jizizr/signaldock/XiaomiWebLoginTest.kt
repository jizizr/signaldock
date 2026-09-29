package com.jizizr.signaldock

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in real-device UI regression. Reads layout metadata only; never enters account credentials. */
@RunWith(AndroidJUnit4::class)
class XiaomiWebLoginTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun officialLoginFormRendersAfterColdOpenRecreationAndReopen() {
        openLogin().use { scenario ->
            assertVisibleLoginForm(scenario, "cold open")
            scenario.recreate()
            assertVisibleLoginForm(scenario, "Activity recreation")
        }
        openLogin().use { scenario ->
            assertVisibleLoginForm(scenario, "reopen")
        }
    }

    private fun openLogin(): ActivityScenario<Activity> {
        val context = instrumentation.targetContext
        return ActivityScenario.launch(Intent().setComponent(
            ComponentName(context.packageName, "com.jizizr.signaldock.XiaomiWebLoginActivity"),
        ))
    }

    private fun assertVisibleLoginForm(scenario: ActivityScenario<Activity>, stage: String) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        var last = "browser not attached"
        while (SystemClock.elapsedRealtime() < deadline) {
            val callback = CountDownLatch(1)
            var valid = false
            scenario.onActivity { activity ->
                val web = findWebView(activity.window.decorView)
                val visible = Rect()
                if (web == null || !web.isAttachedToWindow || !web.isShown ||
                    !web.getGlobalVisibleRect(visible) || visible.width() < 200 || visible.height() < 200) {
                    last = "browser has no usable visible viewport"
                    callback.countDown()
                } else {
                    web.evaluateJavascript(FORM_GEOMETRY) { raw ->
                        val geometry = runCatching {
                            JSONObject(JSONTokener(raw).nextValue() as String)
                        }.getOrNull()
                        // No URL, form values, page text, cookies, or account information are read.
                        val width = geometry?.optInt("width") ?: 0
                        val height = geometry?.optInt("height") ?: 0
                        val inputs = geometry?.optInt("visibleInputs") ?: 0
                        valid = width > 0 && height > 0 && inputs > 0
                        last = "viewport=${width}x$height, visibleInputs=$inputs"
                        callback.countDown()
                    }
                }
            }
            assertTrue("$stage: WebView JavaScript callback stalled", callback.await(8, TimeUnit.SECONDS))
            if (valid) return
            SystemClock.sleep(250)
        }
        throw AssertionError("$stage: official login form did not render ($last)")
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private companion object {
        const val FORM_GEOMETRY = """
            JSON.stringify({
              width: window.innerWidth,
              height: window.innerHeight,
              visibleInputs: Array.from(document.querySelectorAll('input')).filter(function(input) {
                var rect = input.getBoundingClientRect();
                var style = getComputedStyle(input);
                return input.type !== 'hidden' && rect.width > 0 && rect.height > 0 &&
                  style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
              }).length
            })
        """
    }
}
