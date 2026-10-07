package com.gumlet.myapplication.ui

import android.Manifest
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gumlet.myapplication.player.GumletPlayerState
import com.gumlet.myapplication.player.GumletPlayerSurface
import com.gumlet.myapplication.player.MIN_AUTO_HEIGHT
import com.gumlet.myapplication.player.PlaybackState
import com.gumlet.myapplication.player.PlayerEvent
import com.gumlet.myapplication.player.SampleSources
import com.gumlet.myapplication.player.SeekPreviewController
import com.gumlet.myapplication.player.VideoSource
import com.gumlet.myapplication.player.rememberGumletDownloadController
import com.gumlet.myapplication.player.rememberGumletPlayerState
import com.gumlet.myapplication.player.rememberSeekPreviewController
import com.gumlet.myapplication.ui.theme.Amber
import com.gumlet.myapplication.ui.theme.Aqua
import com.gumlet.myapplication.ui.theme.Danger
import com.gumlet.myapplication.ui.theme.Ink
import com.gumlet.myapplication.ui.theme.Success
import com.gumlet.myapplication.ui.theme.TextSecondary
import com.gumlet.myapplication.ui.theme.Violet
import com.gumlet.video.player.GumletDownloadState
import kotlinx.coroutines.launch

private val ScreenPadding = 20.dp

@Composable
fun PlayerScreen(playerState: GumletPlayerState = rememberGumletPlayerState()) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    val seekPreview = rememberSeekPreviewController()
    val downloads = rememberGumletDownloadController()
    val scope = rememberCoroutineScope()
    val state = playerState.state
    var pendingDownload by remember { mutableStateOf<VideoSource?>(null) }
    var qualityPickerSource by remember { mutableStateOf<VideoSource?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val source = pendingDownload ?: return@rememberLauncherForActivityResult
        pendingDownload = null
        val allowed = result.values.all { it } ||
            (downloads.hasNotificationPermission() && downloads.hasStoragePermission())
        if (allowed && downloads.hasNotificationPermission() && downloads.hasStoragePermission()) {
            qualityPickerSource = source
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Allow notifications and storage so the video can save to Downloads",
                )
            }
        }
    }

    fun requestDownload(source: VideoSource) {
        if (downloads.hasNotificationPermission() && downloads.hasStoragePermission()) {
            qualityPickerSource = source
        } else {
            pendingDownload = source
            val needed = buildList {
                if (!downloads.hasNotificationPermission()) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (!downloads.hasStoragePermission()) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            notificationPermission.launch(needed.toTypedArray())
        }
    }

    // DRM sources are skipped: a second, unlicensed decoder could not decode them anyway.
    LaunchedEffect(playerState.currentSource) {
        seekPreview.sourceUrl = playerState.currentSource?.takeUnless { it.isDrm }?.url
    }

    var fullscreenRequested by rememberSaveable { mutableStateOf(false) }

    // The layout follows the real window orientation rather than the request, so during the
    // rotation that a toggle kicks off we never draw the portrait layout into a still-landscape
    // window (or the reverse). The request drives orientation and system bars; the configuration
    // drives what gets composed.
    val isFullscreen =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    ApplyFullscreen(fullscreenRequested)
    // Stay registered until the window has actually left landscape. Dropping the callback in
    // the same back event lets the system treat back as "leave the Activity", which recreates
    // it (and flashes) instead of just exiting fullscreen.
    BackHandler(enabled = fullscreenRequested || isFullscreen) {
        fullscreenRequested = false
    }

    // Surface SDK errors as a snackbar instead of the sample's Toast.
    LaunchedEffect(state) {
        if (state is PlaybackState.Failed) {
            snackbarHostState.showSnackbar(state.message)
        }
    }

    qualityPickerSource?.let { source ->
        DownloadQualityDialog(
            source = source,
            probe = downloads::probeQualities,
            onDismiss = { qualityPickerSource = null },
            onPick = { rendition ->
                qualityPickerSource = null
                downloads.download(source, rendition)
            },
        )
    }

    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            Box(Modifier.padding(bottom = 24.dp)) { SnackbarHost(snackbarHostState) }
        },
    ) { _ ->
        Column(
            Modifier
                .fillMaxSize()
                .then(if (isFullscreen) Modifier else Modifier.verticalScroll(scrollState))
        ) {
            val downloadBanner = downloads.banner.takeUnless { isFullscreen }
            if (downloadBanner != null) {
                DownloadProgressBanner(downloadBanner)
            }

            PlayerStage(
                playerState = playerState,
                seekPreview = seekPreview,
                isFullscreen = isFullscreen,
                // The banner already consumes the status-bar inset when it is showing.
                consumeStatusBar = downloadBanner == null,
                onFullscreenChange = { fullscreenRequested = it },
            )

            if (!isFullscreen) {
                Column(
                    Modifier.padding(horizontal = ScreenPadding, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionHeader(
                        title = "Sample streams",
                        caption = "${SampleSources.all.size} sources",
                    )

                    SampleSources.all.forEach { source ->
                        SourceCard(
                            source = source,
                            selected = playerState.currentSource?.id == source.id,
                            downloadState = downloads.stateOf(source.id),
                            qualityLabel = downloads.qualityLabel(source.id),
                            onClick = {
                                val local = downloads.localPlaybackUrl(source.id)
                                playerState.load(
                                    if (local != null) source.copy(url = local) else source,
                                )
                            },
                            onDownload = { requestDownload(source) },
                            onPause = { downloads.pause(source.id) },
                            onResume = { downloads.resume(source.id) },
                            onRemove = { downloads.remove(source.id) },
                            onRetry = { requestDownload(source) },
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    TransportControls(
                        enabled = playerState.currentSource != null,
                        onReplay = playerState::replay,
                        onStop = playerState::clear,
                    )

                    if (playerState.events.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        SectionHeader(title = "Player events", caption = "latest first")
                        EventLog(playerState.events)
                    }
                }

                // Keep the last card clear of the gesture bar without letting the theme inset
                // the whole screen.
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/* ------------------------------------------------------------------ player stage */

@Composable
private fun PlayerStage(
    playerState: GumletPlayerState,
    seekPreview: SeekPreviewController,
    isFullscreen: Boolean,
    consumeStatusBar: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .then(if (isFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .background(Color.Black)
    ) {
        // Always composed (height 0 when another surface owns the inset) so toggling it
        // does not shift the player Box to a new slot — Compose would otherwise dispose
        // the AndroidView and the SDK would tear the decoder down.
        Spacer(
            Modifier.windowInsetsTopHeight(
                if (isFullscreen || !consumeStatusBar) WindowInsets(0) else WindowInsets.statusBars
            )
        )

        Box(
            Modifier.then(
                if (isFullscreen) Modifier.fillMaxSize()
                else Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
        ) {
            GumletPlayerSurface(
                playerState = playerState,
                seekPreview = seekPreview,
                accentColor = Violet,
                isFullscreen = isFullscreen,
                onFullscreenChange = onFullscreenChange,
                modifier = Modifier.fillMaxSize(),
            )

            AnimatedContent(
                targetState = playerState.state,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "stage-overlay",
            ) { state ->
                when (state) {
                    is PlaybackState.Idle -> IdleOverlay()
                    is PlaybackState.Loading -> LoadingOverlay(
                        source = playerState.currentSource,
                        dimmed = !playerState.hasPlayed,
                    )
                    is PlaybackState.Failed -> ErrorOverlay(state.message, playerState::replay)
                    else -> Box(Modifier.fillMaxSize())
                }
            }

            // Our chrome fades in and out together with media3's control bar, so tapping the
            // video hides everything at once like any other player.
            // Fully qualified so this picks the plain overload rather than the ColumnScope
            // extension that the enclosing Column brings into scope.
            androidx.compose.animation.AnimatedVisibility(
                visible = playerState.controlsVisible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                StageHeader(
                    playerState = playerState,
                    isFullscreen = isFullscreen,
                )
            }

            // Sits above everything else — it is a transient popup anchored to the scrubber.
            SeekPreview(
                controller = seekPreview,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun StageHeader(
    playerState: GumletPlayerState,
    isFullscreen: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                )
            )
            .then(
                // Landscape notches sit right where the header does.
                if (isFullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Gumlet Player",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                text = playerState.currentSource?.title ?: "No stream loaded",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        QualityMenu(playerState)
        Spacer(Modifier.width(8.dp))
        StatusPill(playerState.state)
    }
}

@Composable
private fun QualityMenu(playerState: GumletPlayerState) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = playerState.qualities.isNotEmpty()

    Box {
        Row(
            Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .clickable(enabled = enabled) { expanded = true }
                .alpha(if (enabled) 1f else 0.45f)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.HighQuality,
                contentDescription = "Video quality",
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = playerState.qualityLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            QualityItem(
                label = "Auto",
                detail = "adaptive, ${MIN_AUTO_HEIGHT}p floor",
                selected = playerState.selectedQuality == null,
                onClick = {
                    playerState.selectQuality(null)
                    expanded = false
                },
            )
            playerState.qualities.forEach { quality ->
                QualityItem(
                    label = quality.label,
                    detail = quality.detail,
                    selected = playerState.selectedQuality == quality,
                    onClick = {
                        playerState.selectQuality(quality)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun QualityItem(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) Violet else MaterialTheme.colorScheme.onSurface,
                )
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        },
        trailingIcon = {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Violet)
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun StatusPill(state: PlaybackState) {
    val (label, tint) = when (state) {
        is PlaybackState.Idle -> "Idle" to TextSecondary
        is PlaybackState.Loading -> "Buffering" to Amber
        is PlaybackState.Playing -> "Live" to Success
        is PlaybackState.Paused -> "Paused" to Aqua
        is PlaybackState.Failed -> "Error" to Danger
    }
    val color by animateColorAsState(tint, label = "status-tint")

    // The dot breathes while the player is busy so the state reads at a glance.
    val animated = state is PlaybackState.Loading || state is PlaybackState.Playing
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse-alpha",
    )

    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .alpha(if (animated) pulse else 1f)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun IdleOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Violet.copy(alpha = 0.35f), Color.Black, Aqua.copy(alpha = 0.12f))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(Color.White.copy(alpha = 0.10f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Pick a stream to start",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun LoadingOverlay(source: VideoSource?, dimmed: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (dimmed) 0.55f else 0f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Violet,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp),
            )
            // Once frames are on screen a stall only needs the spinner — the caption and scrim
            // belong to the opening load.
            if (dimmed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = source?.let { "Opening ${it.format} stream…" } ?: "Loading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Danger)
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            PillButton(icon = Icons.Rounded.Refresh, label = "Retry", onClick = onRetry)
        }
    }
}

/* ------------------------------------------------------------------ source list */

@Composable
private fun SectionHeader(title: String, caption: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun SourceCard(
    source: VideoSource,
    selected: Boolean,
    downloadState: GumletDownloadState,
    qualityLabel: String?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val accent = source.accent()
    val offline = downloadState is GumletDownloadState.Completed
    val border by animateColorAsState(
        if (selected) accent.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline,
        label = "card-border",
    )
    val container by animateColorAsState(
        if (selected) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        label = "card-bg",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FormatBadge(source.format, accent)
            if (offline) {
                Spacer(Modifier.width(8.dp))
                FormatBadge(qualityLabel?.let { "Offline · $it" } ?: "Offline", Success)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = source.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = source.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .background(accent.copy(alpha = if (selected) 0.9f else 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (source.isDrm) Icons.Rounded.Lock else Icons.Rounded.PlayArrow,
                    contentDescription = "Play ${source.title}",
                    tint = if (selected) Ink else accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = source.url,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        source.note?.let { note ->
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Amber.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
            }
        }

        // Placeholder DRM URLs cannot be fetched; skip the native download option there.
        if (!source.isDrm) {
            Spacer(Modifier.height(12.dp))
            OfflineActions(
                state = downloadState,
                qualityLabel = qualityLabel,
                accent = accent,
                onDownload = onDownload,
                onPause = onPause,
                onResume = onResume,
                onRemove = onRemove,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun OfflineActions(
    state: GumletDownloadState,
    qualityLabel: String?,
    accent: Color,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val quality = qualityLabel?.let { " · $it" }.orEmpty()
    val label = when (state) {
        is GumletDownloadState.NotDownloaded -> "Save to Downloads"
        is GumletDownloadState.Queued -> "Queued$quality"
        is GumletDownloadState.Downloading -> "Saving to Downloads$quality"
        is GumletDownloadState.Stopped -> "Paused$quality"
        is GumletDownloadState.Completed -> "Saved to Downloads$quality"
        is GumletDownloadState.Failed -> state.reason.ifBlank { "Download failed" }
        is GumletDownloadState.Removing -> "Removing…"
        else -> "Save to Downloads"
    }
    val tint = when (state) {
        is GumletDownloadState.Completed -> Success
        is GumletDownloadState.Failed -> Danger
        is GumletDownloadState.Downloading, is GumletDownloadState.Queued -> accent
        else -> TextSecondary
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = when (state) {
                is GumletDownloadState.Completed -> Icons.Rounded.DownloadDone
                is GumletDownloadState.Failed -> Icons.Rounded.ErrorOutline
                else -> Icons.Rounded.CloudDownload
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (state) {
            is GumletDownloadState.NotDownloaded -> {
                OfflineChip(label = "Download", filled = true, onClick = onDownload)
            }
            is GumletDownloadState.Queued, is GumletDownloadState.Downloading -> {
                OfflineChip(label = "Pause", icon = Icons.Rounded.Pause, onClick = onPause)
                Spacer(Modifier.width(8.dp))
                OfflineChip(label = "Cancel", icon = Icons.Rounded.Delete, onClick = onRemove)
            }
            is GumletDownloadState.Stopped -> {
                OfflineChip(label = "Resume", filled = true, onClick = onResume)
                Spacer(Modifier.width(8.dp))
                OfflineChip(label = "Remove", icon = Icons.Rounded.Delete, onClick = onRemove)
            }
            is GumletDownloadState.Completed -> {
                OfflineChip(label = "Remove", icon = Icons.Rounded.Delete, onClick = onRemove)
            }
            is GumletDownloadState.Failed -> {
                OfflineChip(label = "Retry", filled = true, onClick = onRetry)
                Spacer(Modifier.width(8.dp))
                OfflineChip(label = "Remove", icon = Icons.Rounded.Delete, onClick = onRemove)
            }
            else -> Unit
        }
    }
}

@Composable
private fun DownloadProgressBanner(banner: com.gumlet.myapplication.player.DownloadBannerState) {
    // Background draws edge-to-edge under the status bar; only the text/progress are padded.
    val contentInsets = WindowInsets.statusBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Violet.copy(alpha = 0.22f))
            .windowInsetsPadding(contentInsets)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Saving ${banner.title}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatDownloadPercent(banner.percent)} · ${banner.quality}",
                style = MaterialTheme.typography.titleMedium,
                color = Violet,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { (banner.percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = Violet,
            trackColor = Color.White.copy(alpha = 0.16f),
        )
    }
}

@Composable
private fun OfflineChip(
    label: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val content = if (filled) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (filled) Violet else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (filled) Color.Transparent else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

private fun formatDownloadPercent(percent: Float): String =
    if (percent < 10f) "%.1f%%".format(percent) else "${percent.toInt()}%"

@Composable
private fun FormatBadge(format: String, accent: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = format,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
    }
}

/* ------------------------------------------------------------------ controls & log */

@Composable
private fun TransportControls(enabled: Boolean, onReplay: () -> Unit, onStop: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton(
            icon = Icons.Rounded.Refresh,
            label = "Reload",
            enabled = enabled,
            onClick = onReplay,
            modifier = Modifier.weight(1f),
        )
        PillButton(
            icon = Icons.Rounded.Stop,
            label = "Stop",
            enabled = enabled,
            filled = false,
            onClick = onStop,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val content = if (filled) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .clip(CircleShape)
            .background(if (filled) Violet else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (filled) Color.Transparent else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = content)
    }
}

@Composable
private fun EventLog(events: List<PlayerEvent>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        events.forEach { event ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (event.isError) Danger else Aqua, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = event.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (event.isError) Danger else TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun VideoSource.accent(): Color = when (format) {
    "HLS" -> Aqua
    "DASH" -> Violet
    else -> Amber
}
