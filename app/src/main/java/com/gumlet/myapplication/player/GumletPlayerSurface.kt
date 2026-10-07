package com.gumlet.myapplication.player

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.gumlet.video.player.GumletPlayerListener
import com.gumlet.video.player.GumletPlayerView

/**
 * Compose interop for the SDK's [GumletPlayerView].
 *
 * The XML sample forwarded `onResume`/`onPause`/`onDestroy` from the Activity by hand; here the
 * same contract is honoured by observing the current [LocalLifecycleOwner], and `onDestroy` runs
 * when the composable leaves composition — so the player is released even if the screen goes away
 * while the Activity lives on.
 *
 * The transport controls (play/pause, scrubber, rewind/forward, fullscreen, subtitles) are media3's
 * own control bar, drawn inside the video. We only configure it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun GumletPlayerSurface(
    playerState: GumletPlayerState,
    seekPreview: SeekPreviewController,
    accentColor: Color,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFullscreenChange by rememberUpdatedState(onFullscreenChange)
    val accentArgb = accentColor.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GumletPlayerView(context).apply {
                setPlayerListener(object : GumletPlayerListener {
                    override fun onPlayerError(error: String) = playerState.onError(error)

                    override fun onPlayerStateChanged(isPlaying: Boolean) =
                        playerState.onStateChanged(isPlaying)
                })

                // Configure media3's control bar. The fullscreen button only becomes visible
                // once a click listener is registered.
                GumletInternals.playerView(this)?.apply {
                    // The boolean is the *new* mode. Do not toggle: setFullscreenButtonState
                    // (used below to sync the icon after system back) goes through this same
                    // listener, so flipping would re-enter fullscreen and bounce orientation.
                    setFullscreenButtonClickListener { requested ->
                        currentOnFullscreenChange(requested)
                    }
                    setShowSubtitleButton(true)
                    // Buffering is reported through GumletPlayerState and drawn by our own
                    // overlay; media3's stock spinner would be a second one on top of it.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    controllerShowTimeoutMs = 3_500
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            playerState.controlsVisible = visibility == View.VISIBLE
                        }
                    )
                }

                styleTimeBar(accentArgb)
                observeScrubbing(seekPreview, playerState)

                playerState.attach(this)
            }
        },
        update = { view ->
            // Keeps the button's enter/exit icon in sync when fullscreen is toggled elsewhere
            // (system back, or a device rotation).
            GumletInternals.playerView(view)?.setFullscreenButtonState(isFullscreen)
        },
        onRelease = { view ->
            playerState.detach()
            view.onDestroy()
        },
    )

    DisposableEffect(lifecycleOwner, playerState) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> playerState.view?.onResume()
                Lifecycle.Event.ON_PAUSE -> playerState.view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Repaints media3's stock grey scrubber in the app's accent colour. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun GumletPlayerView.styleTimeBar(accentArgb: Int) {
    timeBar()?.apply {
        setPlayedColor(accentArgb)
        setScrubberColor(accentArgb)
        setBufferedColor(Color.White.copy(alpha = 0.45f).toArgb())
        setUnplayedColor(Color.White.copy(alpha = 0.20f).toArgb())
    }
}

/**
 * Feeds the time bar's scrub gestures to the Compose preview card, along with the bar's geometry
 * measured relative to this view — which is also the Compose overlay's coordinate space, since the
 * player surface fills it exactly.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun GumletPlayerView.observeScrubbing(
    seekPreview: SeekPreviewController,
    playerState: GumletPlayerState,
) {
    val bar = timeBar() ?: return
    val host = this

    bar.addListener(object : TimeBar.OnScrubListener {
        override fun onScrubStart(timeBar: TimeBar, position: Long) {
            val barLocation = IntArray(2).also { bar.getLocationInWindow(it) }
            val hostLocation = IntArray(2).also { host.getLocationInWindow(it) }
            seekPreview.onScrubStart(
                position = position,
                duration = playerState.durationMs,
                left = barLocation[0] - hostLocation[0],
                top = barLocation[1] - hostLocation[1],
                width = bar.width,
            )
        }

        override fun onScrubMove(timeBar: TimeBar, position: Long) {
            seekPreview.onScrubMove(position)
        }

        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
            seekPreview.onScrubStop()
        }
    })
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun GumletPlayerView.timeBar(): DefaultTimeBar? =
    GumletInternals.playerView(this)?.findViewById(androidx.media3.ui.R.id.exo_progress)
