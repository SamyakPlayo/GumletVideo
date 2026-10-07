package com.gumlet.myapplication.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.gumlet.video.player.GumletPlayerView

/**
 * Bridge to the media3 objects that [GumletPlayerView] keeps private.
 *
 * The SDK's public surface is only `load` / `onPause` / `onResume` / `onDestroy`, but internally it
 * is a plain `ExoPlayer` + `PlayerView` pair. Quality selection and the in-player fullscreen button
 * both need those, so we read the two fields reflectively.
 *
 * Every lookup is failure-tolerant: if a future SDK release renames the fields, the extra controls
 * quietly disappear and normal playback is unaffected. If Gumlet ever exposes a `getPlayer()`,
 * this file is the only thing that needs deleting.
 */
internal object GumletInternals {

    private val playerField = runCatching {
        GumletPlayerView::class.java.getDeclaredField("player").apply { isAccessible = true }
    }.getOrNull()

    private val playerViewField = runCatching {
        GumletPlayerView::class.java.getDeclaredField("playerView").apply { isAccessible = true }
    }.getOrNull()

    /**
     * The SDK holds an `ExoPlayer`, but everything we need (tracks, track selection parameters,
     * stop) lives on the stable [Player] interface — so we deliberately never name ExoPlayer.
     */
    fun player(view: GumletPlayerView): Player? =
        runCatching { playerField?.get(view) as? Player }.getOrNull()

    @androidx.annotation.OptIn(UnstableApi::class)
    fun playerView(view: GumletPlayerView): PlayerView? =
        runCatching { playerViewField?.get(view) as? PlayerView }.getOrNull()
}
