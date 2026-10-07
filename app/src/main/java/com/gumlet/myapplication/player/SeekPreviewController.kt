package com.gumlet.myapplication.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Carries scrub gestures from media3's `DefaultTimeBar` over to the Compose preview card.
 *
 * The time bar lives inside the SDK's view hierarchy while the preview is drawn by Compose on top
 * of it, so the bar's on-screen geometry is captured on scrub start and used to place the card
 * above the thumb.
 */
@Stable
class SeekPreviewController {

    var isScrubbing: Boolean by mutableStateOf(false)
        private set

    var positionMs: Long by mutableStateOf(0L)
        private set

    var durationMs: Long by mutableStateOf(0L)
        private set

    /** Time bar geometry in pixels, relative to the top-left of the player surface. */
    var barLeftPx: Int by mutableStateOf(0)
        private set
    var barTopPx: Int by mutableStateOf(0)
        private set
    var barWidthPx: Int by mutableStateOf(0)
        private set

    /**
     * Stream the preview decoder should open. `null` disables previews — used for DRM sources,
     * where a second unlicensed decoder would just fail.
     */
    var sourceUrl: String? by mutableStateOf(null)

    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    internal fun onScrubStart(position: Long, duration: Long, left: Int, top: Int, width: Int) {
        durationMs = duration
        barLeftPx = left
        barTopPx = top
        barWidthPx = width
        positionMs = position
        isScrubbing = true
    }

    internal fun onScrubMove(position: Long) {
        positionMs = position
    }

    internal fun onScrubStop() {
        isScrubbing = false
    }
}

@Composable
fun rememberSeekPreviewController(): SeekPreviewController = remember { SeekPreviewController() }
