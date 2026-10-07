package com.gumlet.myapplication.player

import androidx.compose.runtime.Immutable

/** A playable sample entry shown in the source list. */
@Immutable
data class VideoSource(
    val id: String,
    val title: String,
    val subtitle: String,
    val format: String,
    val url: String,
    val drmLicenseUrl: String? = null,
    /** Shown as an inline warning on the card — used for the placeholder DRM entry. */
    val note: String? = null,
) {
    val isDrm: Boolean get() = drmLicenseUrl != null
}

object SampleSources {

    val hls = VideoSource(
        id = "hls",
        title = "Big Buck Bunny",
        subtitle = "Multi-bitrate adaptive ladder",
        format = "HLS",
        url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    )

    val dash = VideoSource(
        id = "dash",
        title = "Akamai 30fps Reference",
        subtitle = "Segmented MPEG-DASH manifest",
        format = "DASH",
        url = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd",
    )

    val drm = VideoSource(
        id = "drm",
        title = "Widevine Protected",
        subtitle = "DRM playback via license server",
        format = "DRM",
        url = "https://example.com/protected-video.mpd",
        drmLicenseUrl = "https://license-server.com/widevine",
        note = "Placeholder URLs — swap in your own manifest and Widevine license server.",
    )

    val all = listOf(hls, dash, drm)
}
