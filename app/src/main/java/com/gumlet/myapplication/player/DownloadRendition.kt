package com.gumlet.myapplication.player

import androidx.compose.runtime.Immutable

/** One video ladder rung the user can pick before an offline download starts. */
@Immutable
data class DownloadRendition(
    val label: String,
    val detail: String,
    val height: Int,
    val bitrate: Int,
)
