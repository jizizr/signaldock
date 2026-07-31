package com.jizizr.signaldock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jizizr.signaldock.R
import com.jizizr.signaldock.ui.navigation.FloatingBottomBar
import com.jizizr.signaldock.ui.navigation.FloatingBottomBarItem
import com.jizizr.signaldock.ui.navigation.FloatingBottomBarMode
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings

internal enum class RootDestination {
    SETTINGS,
    HISTORY,
}

internal val RootNavigationBarClearance = 96.dp

/** InstallerX-style liquid-glass root navigation. */
@Composable
internal fun RootFloatingNavigationBar(
    modifier: Modifier = Modifier,
    selected: RootDestination,
    backdrop: Backdrop,
    onSelected: (RootDestination) -> Unit,
) {
    val destinations = RootDestination.entries
    val labels = listOf(
        stringResource(R.string.navigation_settings),
        stringResource(R.string.navigation_history),
    )
    val icons = listOf(MiuixIcons.Settings, MiuixIcons.ListView)
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = modifier.fillMaxWidth()) {
        FloatingBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(bottom = bottomInset + 12.dp),
            selectedIndex = destinations.indexOf(selected),
            onSelected = { index -> onSelected(destinations[index]) },
            backdrop = backdrop,
            tabsCount = destinations.size,
            mode = FloatingBottomBarMode.LiquidGlass,
        ) {
            destinations.forEachIndexed { index, destination ->
                FloatingBottomBarItem(
                    onClick = { onSelected(destination) },
                    modifier = Modifier.defaultMinSize(minWidth = 84.dp),
                ) {
                    Icon(
                        imageVector = icons[index],
                        contentDescription = labels[index],
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = labels[index],
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}
