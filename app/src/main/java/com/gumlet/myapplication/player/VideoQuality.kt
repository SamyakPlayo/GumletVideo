package com.gumlet.myapplication.player

import androidx.compose.runtime.Immutable
import androidx.media3.common.TrackGroup

/** Floor used by the "Auto" mode so adaptive selection never settles below SD. */
const val MIN_AUTO_HEIGHT = 480

/** One selectable video rendition, read off the manifest once the tracks are known. */
@Immutable
data class VideoQuality(
    val height: Int,
    val bitrate: Int,
    internal val group: TrackGroup,
    internal val trackIndex: Int,
) {
    val label: String get() = "${height}p"

    val detail: String
        get() = when {
            bitrate <= 0 -> ""
            bitrate >= 1_000_000 -> "%.1f Mbps".format(bitrate / 1_000_000f)
            else -> "${bitrate / 1000} kbps"
        }
}
