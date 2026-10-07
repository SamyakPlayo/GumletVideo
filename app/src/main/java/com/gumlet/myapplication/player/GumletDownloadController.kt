package com.gumlet.myapplication.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import com.gumlet.video.player.GumletDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.os.Looper
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads the chosen rendition into public Downloads/Gumlet and posts a large
 * status-bar notification for progress. Quality probing still uses media3's
 * [DownloadHelper] so the picker matches the live stream ladder.
 */
@Stable
class GumletDownloadController(private val appContext: Context) {

    var states by mutableStateOf<Map<String, GumletDownloadState>>(emptyMap())
        private set

    var qualityLabels by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var localUris by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var banner by mutableStateOf<DownloadBannerState?>(null)
        private set

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val notification = DownloadNotification(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val queued = mutableMapOf<String, Pair<VideoSource, DownloadRendition>>()

    fun start() {
        qualityLabels = prefs.all.mapNotNull { (key, value) ->
            val id = key.removePrefix(QUALITY_PREFIX).takeIf { key.startsWith(QUALITY_PREFIX) }
            val label = value as? String
            if (id != null && label != null) id to label else null
        }.toMap()
        localUris = prefs.all.mapNotNull { (key, value) ->
            val id = key.removePrefix(URI_PREFIX).takeIf { key.startsWith(URI_PREFIX) }
            val uri = value as? String
            if (id != null && uri != null) {
                id to uri
            } else {
                null
            }
        }.toMap()
        states = localUris.keys.associateWith { GumletDownloadState.Completed }
    }

    fun stop() = Unit

    fun stateOf(id: String): GumletDownloadState =
        states[id] ?: GumletDownloadState.NotDownloaded

    fun qualityLabel(id: String): String? = qualityLabels[id]

    fun localPlaybackUrl(id: String): String? = localUris[id]

    fun isCompleted(id: String): Boolean = stateOf(id) is GumletDownloadState.Completed

    @OptIn(UnstableApi::class)
    suspend fun probeQualities(source: VideoSource): List<DownloadRendition> =
        withPreparedHelper(source) { helper -> helper.videoRenditions() }

    fun download(source: VideoSource, rendition: DownloadRendition) {
        jobs[source.id]?.cancel()
        queued[source.id] = source to rendition
        rememberQuality(source.id, rendition.label)
        setState(source.id, GumletDownloadState.Queued)
        notification.update(source, rendition.label, 0f, indeterminate = true)
        publishBanner(source, rendition.label, 0f)
        jobs[source.id] = scope.launch(Dispatchers.IO) {
            setState(source.id, GumletDownloadState.Downloading(0f))
            runCatching {
                PhoneStorageDownload.save(appContext, source, rendition) { percent ->
                    setState(source.id, GumletDownloadState.Downloading(percent))
                    notification.update(source, rendition.label, percent)
                    publishBanner(source, rendition.label, percent)
                }
            }.onSuccess { uri ->
                rememberLocalUri(source.id, uri.toString())
                setState(source.id, GumletDownloadState.Completed)
                notification.complete(source, rendition.label)
                clearBanner(source.id)
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) {
                    setState(source.id, GumletDownloadState.Stopped)
                    notification.cancel(source.id)
                    clearBanner(source.id)
                    throw error
                }
                setState(source.id, GumletDownloadState.Failed(error.message ?: "Download failed"))
                notification.fail(source, error.message ?: "Download failed")
                clearBanner(source.id)
            }
        }
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        setState(id, GumletDownloadState.Stopped)
        notification.cancel(id)
        clearBanner(id)
    }

    fun resume(id: String) {
        val (source, rendition) = queued[id] ?: return
        download(source, rendition)
    }

    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        queued.remove(id)
        localUris[id]?.let { PhoneStorageDownload.delete(appContext, Uri.parse(it)) }
        rememberLocalUri(id, null)
        rememberQuality(id, null)
        setState(id, GumletDownloadState.NotDownloaded)
        notification.cancel(id)
        clearBanner(id)
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun setState(id: String, state: GumletDownloadState) {
        onMain { states = states + (id to state) }
    }

    private fun publishBanner(source: VideoSource, quality: String, percent: Float) {
        onMain { banner = DownloadBannerState(source.id, source.title, quality, percent) }
    }

    private fun clearBanner(id: String) {
        onMain { if (banner?.id == id) banner = null }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else scope.launch(Dispatchers.Main.immediate) { block() }
    }

    private fun rememberQuality(id: String, label: String?) {
        onMain {
            if (label == null) {
                prefs.edit().remove(QUALITY_PREFIX + id).apply()
                qualityLabels = qualityLabels - id
            } else {
                prefs.edit().putString(QUALITY_PREFIX + id, label).apply()
                qualityLabels = qualityLabels + (id to label)
            }
        }
    }

    private fun rememberLocalUri(id: String, uri: String?) {
        onMain {
            if (uri == null) {
                prefs.edit().remove(URI_PREFIX + id).apply()
                localUris = localUris - id
            } else {
                prefs.edit().putString(URI_PREFIX + id, uri).apply()
                localUris = localUris + (id to uri)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun <T> withPreparedHelper(
        source: VideoSource,
        block: (DownloadHelper) -> T,
    ): T = suspendCancellableCoroutine { cont ->
        val helper = createHelper(source)
        cont.invokeOnCancellation { helper.release() }
        helper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(helper: DownloadHelper) {
                try {
                    val result = block(helper)
                    helper.release()
                    if (cont.isActive) cont.resume(result)
                } catch (error: Exception) {
                    helper.release()
                    if (cont.isActive) cont.resumeWithException(error)
                }
            }

            override fun onPrepareError(helper: DownloadHelper, error: IOException) {
                helper.release()
                if (cont.isActive) cont.resumeWithException(error)
            }
        })
    }

    @OptIn(UnstableApi::class)
    private fun createHelper(source: VideoSource): DownloadHelper {
        val mediaItem = MediaItem.fromUri(source.url)
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("GumletPlayer")
            .setAllowCrossProtocolRedirects(true)
        return DownloadHelper.forMediaItem(
            appContext,
            mediaItem,
            DefaultRenderersFactory(appContext),
            dataSource,
        )
    }

    private companion object {
        const val PREFS = "gumlet_downloads"
        const val QUALITY_PREFIX = "quality_"
        const val URI_PREFIX = "uri_"
    }
}

data class DownloadBannerState(
    val id: String,
    val title: String,
    val quality: String,
    val percent: Float,
)

internal val GumletDownloadState.isInProgress: Boolean
    get() = this is GumletDownloadState.Queued ||
        this is GumletDownloadState.Downloading ||
        this is GumletDownloadState.Removing

@OptIn(UnstableApi::class)
private fun DownloadHelper.videoRenditions(): List<DownloadRendition> {
    val bestByHeight = LinkedHashMap<Int, DownloadRendition>()
    for (period in 0 until periodCount) {
        val mapped = getMappedTrackInfo(period)
        for (renderer in 0 until mapped.rendererCount) {
            if (mapped.getRendererType(renderer) != C.TRACK_TYPE_VIDEO) continue
            val groups = mapped.getTrackGroups(renderer)
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                for (track in 0 until group.length) {
                    val format = group.getFormat(track)
                    if (format.height <= 0) continue
                    val current = bestByHeight[format.height]
                    if (current == null || format.bitrate > current.bitrate) {
                        bestByHeight[format.height] = DownloadRendition(
                            label = "${format.height}p",
                            detail = bitrateLabel(format.bitrate),
                            height = format.height,
                            bitrate = format.bitrate,
                        )
                    }
                }
            }
        }
    }
    return bestByHeight.values.sortedByDescending { it.height }
}

private fun bitrateLabel(bitrate: Int): String = when {
    bitrate <= 0 -> ""
    bitrate >= 1_000_000 -> "%.1f Mbps".format(bitrate / 1_000_000f)
    else -> "${bitrate / 1000} kbps"
}

@Composable
fun rememberGumletDownloadController(): GumletDownloadController {
    val context = LocalContext.current.applicationContext
    val controller = remember { GumletDownloadController(context) }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }
    return controller
}
