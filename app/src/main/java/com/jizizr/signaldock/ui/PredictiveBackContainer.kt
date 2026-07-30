package com.jizizr.signaldock.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Interactive secondary-page return modeled after InstallerX's Miuix navigation effects.
 * The destination follows the system back gesture and restores with a spring when cancelled.
 */
@Composable
internal fun PredictiveBackContainer(
    onBack: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

    PredictiveBackHandler(enabled = true) { backEvents ->
        try {
            backEvents.collect { event -> progress.snapTo(event.progress) }
            onBack()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                )
            }
        }
    }

    val value = progress.value
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val direction = if (isLtr) 1f else -1f
                    translationX = size.width * 0.12f * value * direction
                    scaleX = 1f - 0.08f * value
                    scaleY = 1f - 0.08f * value
                    transformOrigin = TransformOrigin(if (isLtr) 1f else 0f, 0.5f)
                    shadowElevation = 18.dp.toPx() * value
                    shape = RoundedCornerShape(32.dp)
                    clip = value > 0f
                }
                .background(MiuixTheme.colorScheme.surface),
            content = content,
        )
    }
}
