package com.gumlet.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gumlet.myapplication.player.DownloadRendition
import com.gumlet.myapplication.player.VideoSource
import com.gumlet.myapplication.ui.theme.Danger
import com.gumlet.myapplication.ui.theme.Surface1
import com.gumlet.myapplication.ui.theme.TextSecondary
import com.gumlet.myapplication.ui.theme.Violet

private sealed interface ProbeUi {
    data object Loading : ProbeUi
    data class Ready(val options: List<DownloadRendition>) : ProbeUi
    data class Failed(val message: String) : ProbeUi
}

@Composable
fun DownloadQualityDialog(
    source: VideoSource,
    probe: suspend (VideoSource) -> List<DownloadRendition>,
    onPick: (DownloadRendition) -> Unit,
    onDismiss: () -> Unit,
) {
    var ui by remember(source.id) { mutableStateOf<ProbeUi>(ProbeUi.Loading) }

    LaunchedEffect(source.id) {
        ui = ProbeUi.Loading
        ui = runCatching { probe(source) }
            .fold(
                onSuccess = { options ->
                    if (options.isEmpty()) {
                        ProbeUi.Failed("No video renditions in this stream")
                    } else {
                        ProbeUi.Ready(options)
                    }
                },
                onFailure = { ProbeUi.Failed(it.message ?: "Couldn't read the stream") },
            )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = {
            Text("Download quality", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column {
                Text(
                    text = "Choose the rendition to save for ${source.title}. Lower rungs are smaller files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                when (val current = ui) {
                    ProbeUi.Loading -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                color = Violet,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Reading renditions…",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                    is ProbeUi.Failed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = Danger,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                current.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger,
                            )
                        }
                    }
                    is ProbeUi.Ready -> {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            current.options.forEachIndexed { index, rendition ->
                                RenditionRow(
                                    rendition = rendition,
                                    recommended = index == 0,
                                    onClick = { onPick(rendition) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

@Composable
private fun RenditionRow(
    rendition: DownloadRendition,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Violet.copy(alpha = if (recommended) 0.12f else 0.04f))
            .border(
                width = 1.dp,
                color = Violet.copy(alpha = if (recommended) 0.55f else 0.18f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = rendition.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val caption = buildString {
                if (recommended) append("Recommended")
                if (rendition.detail.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(rendition.detail)
                }
            }
            if (caption.isNotEmpty()) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        if (recommended) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = Violet,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
