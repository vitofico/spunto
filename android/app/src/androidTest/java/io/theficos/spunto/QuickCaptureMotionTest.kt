package io.theficos.spunto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.design.builtInTokens
import io.theficos.spunto.core.design.paletteColorScheme
import io.theficos.spunto.core.design.tokenColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class QuickCaptureMotionTest {
    // Native test scripts disable system animations. Exercise the actual tween on the test clock.
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })

    @Test fun dockKeepsTheThemeTintAndSoftensAWallpaperTintThatHidesThePlaceholder() {
        val palette = builtInTokens.getValue("carta").getValue("dark")
        val colors = paletteColorScheme(palette, dark = true)
        val placeholder = tokenColor(palette.getValue("ink-3"))
        assertEquals(colors.primaryContainer, quickCaptureDockFill(colors, placeholder))

        val wallpaper = colors.copy(primaryContainer = Color(0xFF4A4458))
        val fill = quickCaptureDockFill(wallpaper, placeholder)
        assertTrue((placeholder.luminance() + .05f) / (wallpaper.primaryContainer.luminance() + .05f) < 4.5f)
        assertTrue((placeholder.luminance() + .05f) / (fill.luminance() + .05f) >= 4.5f)
    }

    @Test fun captureMorphsInBothDimensionsAndKeepsItsBottomEndAnchor() {
        val minimized = mutableStateOf(false)
        val openHeight = 148.dp
        compose.setContent {
            SpuntoTheme("carta", "light") {
                Box(
                    Modifier.size(width = 280.dp, height = 240.dp)
                        .testTag("capture-motion-viewport")
                ) {
                    val progress = rememberQuickCaptureProgress(minimized.value)
                    QuickCaptureMorph(
                        minimized = minimized.value,
                        progress = progress,
                        openHeight = openHeight,
                        interactive = !minimized.value && progress.value == 0f,
                        hasDraft = false,
                        onOpen = { minimized.value = false },
                        onOpenHeightChanged = {},
                        modifier = Modifier.align(Alignment.BottomEnd),
                    ) {
                        Box(Modifier.fillMaxWidth().height(openHeight))
                    }
                }
            }
        }

        val viewport = compose.onNodeWithTag("capture-motion-viewport").fetchSemanticsNode().boundsInRoot
        val open = shellBounds()
        assertEquals(pixels(openHeight), open.height, 1f)
        val roundedOpenMargin = with(compose.density) { 12.dp.roundToPx() }
        assertEquals(viewport.width - 2 * roundedOpenMargin, open.width, 1f)
        assertAnchor(viewport, open, end = 12.dp, bottom = 8.dp)
        val dock = compose.onNodeWithTag("quick-capture-dock")
        val pencil = compose.onNodeWithTag("quick-capture-pencil")
        dock.assertIsDisplayed()
        pencil.assertDoesNotExist()

        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { minimized.value = true }
            // First frames commit the target and start the animation; sample near its midpoint.
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.mainClock.advanceTimeBy(240)
            val closing = shellBounds()
            dock.assertDoesNotExist()
            pencil.assertDoesNotExist()

            compose.mainClock.advanceTimeBy(336)
            val compact = shellBounds()
            val compactPixels = pixels(56.dp)
            assertEquals(compactPixels, compact.width, 1f)
            assertEquals(compactPixels, compact.height, 1f)
            assertBetween(open, closing, compact)
            assertAnchor(viewport, compact, end = 16.dp, bottom = 12.dp)
            dock.assertDoesNotExist()

            pencil.assertIsDisplayed().performClick()
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.mainClock.advanceTimeBy(208)
            val opening = shellBounds()
            assertBetween(open, opening, compact)
            dock.assertDoesNotExist()
            pencil.assertDoesNotExist()

            compose.mainClock.advanceTimeBy(320)
            val restored = shellBounds()
            assertEquals(open.width, restored.width, 1f)
            assertEquals(open.height, restored.height, 1f)
            assertAnchor(viewport, restored, end = 12.dp, bottom = 8.dp)
            dock.assertIsDisplayed()
            pencil.assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun shellBounds(): Rect =
        compose.onNodeWithTag("quick-capture-morph").fetchSemanticsNode().boundsInRoot

    private fun pixels(dp: Dp): Float = with(compose.density) { dp.toPx() }

    private fun assertAnchor(viewport: Rect, shell: Rect, end: Dp, bottom: Dp) {
        assertEquals("End margin of $shell", pixels(end), viewport.right - shell.right, 1f)
        assertEquals("Bottom margin of $shell", pixels(bottom), viewport.bottom - shell.bottom, 1f)
    }

    private fun assertBetween(open: Rect, moving: Rect, compact: Rect) {
        assertTrue("Width must interpolate: open $open, moving $moving, compact $compact",
            moving.width > compact.width && moving.width < open.width)
        assertTrue("Height must interpolate: open $open, moving $moving, compact $compact",
            moving.height > compact.height && moving.height < open.height)
        assertTrue("End and bottom edges should stay between the open and compact anchors",
            moving.right in compact.right..open.right && moving.bottom in compact.bottom..open.bottom)
    }
}
