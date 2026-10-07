package com.gumlet.myapplication.player

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.gumlet.video.player.GumletInitParams
import com.gumlet.video.player.GumletPlayerView

private const val TAG = "GumletPlayer"

/** Coarse playback state, driven by the SDK listener callbacks. */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Loading : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data class Failed(val message: String) : PlaybackState
}

/** One line in the on-screen event log — handy when demoing the SDK. */
data class PlayerEvent(val label: String, val isError: Boolean = false)

/**
 * Holds everything the UI needs to know about the player, and owns the reference to the
 * underlying [GumletPlayerView] once [GumletPlayerSurface] has created it.
 *
 * A load requested before the view exists is queued and flushed on attach, so callers never
 * have to care whether the surface is composed yet.
 */
@Stable
class GumletPlayerState {

    var state: PlaybackState by mutableStateOf<PlaybackState>(PlaybackState.Idle)
        private set

    var currentSource: VideoSource? by mutableStateOf(null)
        private set

    /** Renditions advertised by the current manifest, highest first. Empty until tracks resolve. */
    var qualities: List<VideoQuality> by mutableStateOf(emptyList())
        private set

    /** `null` means adaptive/Auto. */
    var selectedQuality: VideoQuality? by mutableStateOf(null)
        private set

    /**
     * True once the current stream has rendered playback. Distinguishes the opening load — which
     * deserves a full-cover overlay — from a mid-playback stall, where dimming the video would be
     * more disruptive than the stall itself.
     */
    var hasPlayed: Boolean by mutableStateOf(false)
        private set

    /** Mirrors the SDK control bar's visibility so our overlay chrome can fade with it. */
    var controlsVisible: Boolean by mutableStateOf(true)
        internal set

    val events = mutableStateListOf<PlayerEvent>()

    val qualityLabel: String
        get() = selectedQuality?.label ?: "Auto"

    /** Read on demand (not observable) — the scrub listener only needs it at gesture start. */
    val durationMs: Long
        get() = player?.duration?.takeIf { it > 0 } ?: 0L

    internal var view: GumletPlayerView? = null
    private var player: Player? = null
    private var pending: VideoSource? = null

    /**
     * The SDK builds a brand new ExoPlayer on every `load()`, so this listener is re-attached
     * each time rather than once at startup.
     */
    private val trackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) = onTracksResolved(tracks)

        /**
         * The SDK's own callback only reports isPlaying, which cannot distinguish "paused" from
         * "stalled mid-playback". Reading the media3 state directly lets a single overlay cover
         * both the initial load and later rebuffering.
         */
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> if (currentSource != null) state = PlaybackState.Loading
                Player.STATE_READY -> state =
                    if (player?.isPlaying == true) PlaybackState.Playing else PlaybackState.Paused
                Player.STATE_ENDED -> state = PlaybackState.Paused
            }
        }
    }

    fun load(source: VideoSource) {
        currentSource = source
        state = PlaybackState.Loading
        hasPlayed = false
        qualities = emptyList()
        selectedQuality = null
        log("Loading ${source.format} · ${source.title}")

        val target = view
        if (target == null) {
            pending = source
            return
        }
        target.load(source.toInitParams())
        bindPlayer(target)
    }

    fun replay() {
        currentSource?.let(::load)
    }

    fun clear() {
        // load() creates the player, so stopping has to go through the media3 instance directly.
        runCatching { player?.stop() }
        player = null
        currentSource = null
        pending = null
        qualities = emptyList()
        selectedQuality = null
        hasPlayed = false
        state = PlaybackState.Idle
        events.clear()
    }

    /** `null` selects adaptive streaming with a [MIN_AUTO_HEIGHT] floor. */
    fun selectQuality(quality: VideoQuality?) {
        selectedQuality = quality
        val target = player ?: return

        runCatching {
            target.trackSelectionParameters = target.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .apply {
                    if (quality == null) {
                        // ExoPlayer relaxes this if no rendition qualifies, so low-res-only
                        // streams still play rather than failing to select a track.
                        setMinVideoSize(0, MIN_AUTO_HEIGHT)
                    } else {
                        setMinVideoSize(0, 0)
                        setOverrideForType(
                            TrackSelectionOverride(quality.group, quality.trackIndex)
                        )
                    }
                }
                .build()
        }

        log(quality?.let { "Quality locked to ${it.label}" } ?: "Quality: Auto (${MIN_AUTO_HEIGHT}p+)")
    }

    internal fun attach(playerView: GumletPlayerView) {
        view = playerView
        val queued = pending
        pending = null
        val restore = queued ?: currentSource.takeIf { player == null }
        if (restore != null) {
            playerView.load(restore.toInitParams())
            bindPlayer(playerView)
        }
    }

    internal fun detach() {
        runCatching { player?.removeListener(trackListener) }
        player = null
        view = null
    }

    internal fun onStateChanged(isPlaying: Boolean) {
        // isPlaying also drops to false while the player stalls; letting that through would
        // flip the overlay to "Paused" mid-rebuffer.
        if (player?.playbackState == Player.STATE_BUFFERING) return

        state = if (isPlaying) PlaybackState.Playing else PlaybackState.Paused
        if (isPlaying) hasPlayed = true
        log(if (isPlaying) "Playing" else "Paused")
    }

    internal fun onError(message: String) {
        state = PlaybackState.Failed(message)
        log(message, isError = true)
    }

    /** Grabs the freshly created media3 player and applies the default quality policy. */
    private fun bindPlayer(playerView: GumletPlayerView) {
        val resolved = GumletInternals.player(playerView)
        if (resolved == null) {
            log("Quality control unavailable on this SDK build", isError = false)
            return
        }
        player = resolved
        resolved.addListener(trackListener)
        selectQuality(null)
    }

    private fun onTracksResolved(tracks: Tracks) {
        val found = tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    val format = group.getTrackFormat(index)
                    if (!group.isTrackSupported(index) || format.height <= 0) null
                    else VideoQuality(format.height, format.bitrate, group.mediaTrackGroup, index)
                }
            }
            .distinctBy { it.height }
            .sortedByDescending { it.height }

        if (found != qualities) {
            qualities = found
            if (found.isNotEmpty()) {
                log("${found.size} renditions: ${found.joinToString { it.label }}")
            }
        }
    }

    private fun log(label: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, label) else Log.d(TAG, label)
        events.add(0, PlayerEvent(label, isError))
        while (events.size > MAX_EVENTS) events.removeAt(events.lastIndex)
    }

    private fun VideoSource.toInitParams(): GumletInitParams {
        val builder = GumletInitParams.Builder()
            .setVideoUrl(url)
            .setAutoPlay(true)
            .setControlsEnabled(true)
            // videoId is how the SDK finds a completed download; enableOfflinePlayback
            // falls back to the stream URL when nothing is cached for this id.
            .setVideoId(id)
            .setEnableOfflinePlayback(!url.startsWith("content:") && !url.startsWith("file:"))
        drmLicenseUrl?.let { builder.setDrmLicenseUrl(it) }
        return builder.build()
    }

    private companion object {
        const val MAX_EVENTS = 12
    }
}

@Composable
fun rememberGumletPlayerState(): GumletPlayerState = remember { GumletPlayerState() }
