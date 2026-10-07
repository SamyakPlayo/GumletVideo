package com.gumlet.myapplication.ui

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.gumlet.myapplication.player.SeekPreviewController
import com.gumlet.myapplication.ui.theme.Violet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale
import kotlin.math.roundToInt

private val CardWidth = 168.dp
private val CardHeight = 94.dp
private val EdgeMargin = 8.dp
private val BarGap = 14.dp

/** Grace period after a stream loads before the preview decoder starts competing for bandwidth. */
private const val WARMUP_DELAY_MS = 800L

/**
 * The frame preview that floats above the scrubber while the user drags it.
 *
 * There is no storyboard/sprite sheet for these sample streams, so frames come from a **second
 * ExoPlayer**: muted, never played, capped to a low rendition, and seeked to the scrub position
 * with `CLOSEST_SYNC` so it only has to decode a keyframe. It is warmed up shortly after the stream
 * loads so the first scrub already has a decoded frame waiting on the surface.
 *
 * If Gumlet-hosted assets expose a thumbnail sprite/VTT, swapping this decoder for sprite lookups
 * would be strictly cheaper — the placement logic below stays the same.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SeekPreview(controller: SeekPreviewController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val url = controller.sourceUrl

    // ONE TextureView for the whole overlay. AndroidView's factory runs exactly once, so a view
    // recreated alongside the player would never be the instance actually on screen — the decoder
    // would render into a detached surface and the preview would spin forever.
    val textureView = remember { TextureView(context) }
    var hasFrame by remember(url) { mutableStateOf(false) }

    // The decoder is prepared shortly after the stream loads rather than on the first scrub, so a
    // frame is already decoded and on the surface by the time the user touches the scrubber. The
    // short delay just lets the main player win the initial buffering race.
    var armed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        delay(WARMUP_DELAY_MS)
        armed = true
    }

    val previewPlayer = remember(url, armed) {
        if (url == null || !armed) return@remember null
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            volume = 0f
            playWhenReady = false
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                // A thumbnail never needs more than this, and it keeps the extra
                // bandwidth/decode cost of the second player small.
                .setMaxVideoSize(640, 360)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }

    DisposableEffect(previewPlayer) {
        val player = previewPlayer
        val frameListener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                hasFrame = true
            }
        }
        player?.addListener(frameListener)
        player?.setVideoTextureView(textureView)
        onDispose {
            player?.removeListener(frameListener)
            player?.clearVideoTextureView(textureView)
            player?.release()
        }
    }

    // collectLatest cancels the pending delay whenever a newer position arrives, which debounces
    // a fast drag down to one seek per idle moment instead of one per touch event.
    LaunchedEffect(previewPlayer) {
        val player = previewPlayer ?: return@LaunchedEffect
        snapshotFlow { controller.positionMs }.collectLatest { position ->
            delay(40)
            runCatching { player.seekTo(position) }
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (controller.isScrubbing) 1f else 0f,
        animationSpec = tween(140),
        label = "preview-alpha",
    )

    // Deliberately stays in composition when hidden: tearing the TextureView down between
    // scrubs would drop the decoder's output surface and force a re-decode every time.
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val cardWidthPx = with(density) { CardWidth.roundToPx() }
        val cardHeightPx = with(density) { CardHeight.roundToPx() }
        val marginPx = with(density) { EdgeMargin.roundToPx() }
        val gapPx = with(density) { BarGap.roundToPx() }
        val labelPx = with(density) { 22.dp.roundToPx() }

        // Follow the thumb, but never let the card run off either edge.
        val centerX = controller.barLeftPx + controller.fraction * controller.barWidthPx
        val maxX = (constraints.maxWidth - cardWidthPx - marginPx).coerceAtLeast(marginPx)
        val x = (centerX - cardWidthPx / 2f).roundToInt().coerceIn(marginPx, maxX)
        val y = (controller.barTopPx - cardHeightPx - labelPx - gapPx).coerceAtLeast(marginPx)

        Column(
            Modifier
                .offset { IntOffset(x, y) }
                .graphicsLayer { this.alpha = alpha },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .width(CardWidth)
                    .height(CardHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
                    .border(1.dp, Violet.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
            ) {
                AndroidView(
                    factory = { textureView },
                    modifier = Modifier.fillMaxSize(),
                )

                if (!hasFrame) {
                    CircularProgressIndicator(
                        color = Violet,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(20.dp),
                    )
                }
            }

            Text(
                text = formatTime(controller.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
