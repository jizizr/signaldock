package com.jizizr.signaldock.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shizuku 连接 / 授权状态（Compose 可观察）。
 *
 * 由 MainActivity 中的 Shizuku 监听器写入，UI 只读；
 * 取代了此前 Composable 里 `context as? MainActivity` 反向取 Activity 的做法。
 */
@Stable
class ShizukuState {
    /** Shizuku 服务是否在运行（binder 可 ping 通） */
    var available by mutableStateOf(false)

    /** 本应用是否已获得 Shizuku 授权 */
    var granted by mutableStateOf(false)

    /** Shizuku 服务进程 UID：0=root，2000=shell，-1=未知。 */
    var uid by mutableIntStateOf(-1)

    /** 已连接且已授权，可以执行特权操作 */
    val ready: Boolean get() = available && granted

    val isRoot: Boolean get() = ready && uid == 0

    val isShell: Boolean get() = ready && uid == android.os.Process.SHELL_UID
}
