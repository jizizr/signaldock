package com.jizizr.signaldock.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用主题：HyperOS 风格（miuix）。
 *
 * [ColorSchemeMode.System] 跟随系统深浅色并使用 miuix 默认的 HyperOS 蓝主色；
 * 如需壁纸取色（Material You），改用 [ColorSchemeMode.MonetSystem] 即可。
 */
@Composable
fun SignaldockTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller, content = content)
}
