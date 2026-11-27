package io.theficos.spunto

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp

private val quickCaptureEasing = CubicBezierEasing(.38f, .08f, .43f, 1f)

@Composable
internal fun rememberQuickCaptureProgress(minimized: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (minimized) 1f else 0f,
        animationSpec = tween(if (minimized) 540 else 480, easing = quickCaptureEasing),
        label = "Quick Capture shape",
    )

@Composable
internal fun QuickCaptureMorph(
    minimized: Boolean,
    progress: State<Float>,
    openHeight: Dp,
    interactive: Boolean,
    hasDraft: Boolean,
    onOpen: () -> Unit,
    onOpenHeightChanged: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val fraction by progress
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(lerp(20.dp, 17.dp, fraction))
    val dockAlpha = 1f - (fraction / .28f).coerceIn(0f, 1f)
    val pencilAlpha = ((fraction - .54f) / .4f).coerceIn(0f, 1f)
    val showDock = !minimized || dockAlpha > 0f
    val pencilInteractive = minimized && fraction >= .76f

    Layout(
        modifier = modifier
            .padding(start = 12.dp, end = lerp(12.dp, 16.dp, fraction), bottom = lerp(8.dp, 12.dp, fraction))
            .testTag("quick-capture-morph")
            .shadow(lerp(2.dp, 6.dp, fraction), shape, clip = true)
            .background(lerp(colors.surfaceContainerLow, colors.primary, fraction), shape)
            .border(1.dp, colors.outlineVariant.copy(alpha = colors.outlineVariant.alpha * (1f - fraction)), shape)
            // The moving surface remains a hit boundary even before the pencil becomes active.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        content = {
            if (showDock) {
                val input = if (interactive) Modifier.testTag("quick-capture-dock") else Modifier
                    .clearAndSetSemantics { hideFromAccessibility() }
                    .focusProperties { onEnter = { cancelFocusChange() } }
                    .focusGroup()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    }
                Box(Modifier.fillMaxWidth()
                    .onSizeChanged { onOpenHeightChanged(with(density) { it.height.toDp() }) }
                    .graphicsLayer { alpha = dockAlpha }
                    .then(input)) { content() }
            }
            val pencilAction = if (pencilInteractive) Modifier
                .testTag("quick-capture-pencil")
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics {
                    if (hasDraft) stateDescription = context.getString(R.string.settings_capture_mode_has_draft)
                }
            else Modifier.clearAndSetSemantics { hideFromAccessibility() }
            Box(Modifier.size(56.dp).graphicsLayer {
                alpha = pencilAlpha
                scaleX = .76f + .24f * pencilAlpha
                scaleY = scaleX
            }.then(pencilAction)) {
                Icon(painterResource(R.drawable.ic_quick_capture_pencil),
                    contentDescription = context.getString(R.string.settings_plugin_quick_capture_name),
                    modifier = Modifier.align(Alignment.Center), tint = colors.onPrimary)
                if (hasDraft) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(7.dp)
                    .background(colors.onPrimary, CircleShape))
            }
        },
    ) { children, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val dock = if (showDock) children.first().measure(childConstraints) else null
        val pencil = children.last().measure(childConstraints)
        val dockHeight = constraints.constrainHeight(dock?.height ?: openHeight.roundToPx())
        val width = constraints.constrainWidth(lerp(constraints.maxWidth, pencil.width, fraction))
        val height = constraints.constrainHeight(lerp(dockHeight, pencil.height, fraction))
        layout(width, height) {
            // Keep text at its original scale as the same surface clips inward from the top and start.
            dock?.placeRelative(width - dock.width, height - dock.height)
            pencil.placeRelative((width - pencil.width) / 2, (height - pencil.height) / 2)
        }
    }
}
