package com.jizizr.signaldock

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.jizizr.signaldock.ui.theme.SignaldockTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * QrResultActivity
 *
 * 超级岛下拉小窗及普通通知按钮共用的二维码全页面。
 */
class QrResultActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QrResultActivity"
        const val EXTRA_MODE = "qr_result_mode"
        const val EXTRA_SESSION_ID = "qr_result_session_id"
        const val EXTRA_SOURCE_PACKAGE = "qr_result_source_package"
        const val EXTRA_SOURCE_TASK_ID = "qr_result_source_task_id"
        const val MODE_IMAGE = "image"
        const val MODE_OPEN_SOURCE = "open_source"
    }

    override fun onStop() {
        super.onStop()
        // 纯弹窗 Activity，离开即失去意义。若收起动画未播完用户就切走（帧时钟暂停），
        // onDismissFinished 不会再触发，这里兜底 finish，避免透明 Activity 残留在任务栈。
        if (!isChangingConfigurations) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        // 只读不删；bitmap 生命周期跟随通知，由 LiveUpdateService 统一回收
        val bitmap = if (sessionId != -1) SessionQrBitmapStore.bitmapFor(sessionId) else null
        val mode = intent.getStringExtra(EXTRA_MODE)

        Log.i(
            TAG,
            "Opened: session=$sessionId mode=$mode multiWindow=$isInMultiWindowMode " +
                "hasQr=${bitmap != null}",
        )

        if (mode != MODE_IMAGE && mode != MODE_OPEN_SOURCE) {
            finish(); return
        }
        // 无二维码时没有下拉内容，任何启动方式都应恢复来源应用。二维码模式下，
        // 普通点击恢复来源应用，只有系统的小窗启动才展示二维码。
        if (mode == MODE_OPEN_SOURCE || !isInMultiWindowMode) {
            openSourceApplication()
            return
        }
        // 只有下拉小窗需要二维码内容；没有二维码时直接关闭空白小窗。
        if (bitmap == null) {
            finish()
            return
        }
        setContent {
            SignaldockTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(start = 24.dp, end = 12.dp),
                        ) {
                            Text(
                                text = "取餐二维码",
                                style = MiuixTheme.textStyles.title2,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = ::finish) {
                                Icon(
                                    imageVector = MiuixIcons.Close,
                                    contentDescription = "关闭",
                                )
                            }
                        }
                        BoxWithConstraints(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                        ) {
                            val imageSize = minOf(maxWidth, maxHeight)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(imageSize)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                                    .padding(12.dp),
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "二维码图片",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openSourceApplication() {
        val sourceTaskId = intent.getIntExtra(EXTRA_SOURCE_TASK_ID, -1)
        if (AppShell.startActivityFromRecents(sourceTaskId)) {
            Log.i(TAG, "Restored source task: taskId=$sourceTaskId")
            finish()
            return
        }
        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
        val launchIntent = sourcePackage
            ?.let(packageManager::getLaunchIntentForPackage)
            ?: Intent(this, MainActivity::class.java)
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
        Log.i(TAG, "Launching source package: package=$sourcePackage")
        startActivity(launchIntent)
        finish()
    }
}
