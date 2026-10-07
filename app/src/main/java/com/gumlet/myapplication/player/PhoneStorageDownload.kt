@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gumlet.myapplication.player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashUtil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.abs

/**
 * Writes the chosen rendition into the public Downloads/Gumlet folder as a single file
 * the user can see in the Files app — HLS becomes concatenated MPEG-TS, DASH concatenated
 * fragmented MP4.
 */
internal object PhoneStorageDownload {

    suspend fun save(
        context: Context,
        source: VideoSource,
        rendition: DownloadRendition,
        onProgress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val parts = if (source.format.equals("DASH", ignoreCase = true)) {
            dashParts(source.url, rendition.height)
        } else {
            hlsParts(source.url, rendition.height)
        }
        check(parts.isNotEmpty()) { "No media segments found" }

        val extension = if (source.format.equals("DASH", ignoreCase = true)) "mp4" else "ts"
        val mime = if (extension == "mp4") "video/mp4" else "video/mp2t"
        val filename = "${sanitize(source.title)}_${rendition.label}.$extension"
        val (uri, stream) = openDownloadsFile(context, filename, mime)
        try {
            stream.use { output ->
                copyParts(parts, output, onProgress)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            }
            uri
        } catch (error: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw error
        }
    }

    fun delete(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
        if (uri.scheme == "file") {
            uri.path?.let { File(it).delete() }
        }
    }

    private suspend fun copyParts(
        parts: List<DownloadPart>,
        output: OutputStream,
        onProgress: (Float) -> Unit,
    ) {
        var completed = 0L
        var lastReported = -1f
        val knownTotal = parts.mapNotNull { it.knownLength }.sum().takeIf { total ->
            total > 0 && parts.all { it.knownLength != null }
        }
        parts.forEachIndexed { index, part ->
            currentCoroutineContext().ensureActive()
            openConnection(part.url, part.range).inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    completed += read
                    val percent = when {
                        knownTotal != null && knownTotal > 0 ->
                            (completed.toFloat() / knownTotal) * 100f
                        else -> ((index + 1).toFloat() / parts.size) * 100f
                    }
                    if (percent - lastReported >= 0.4f || percent >= 99f) {
                        lastReported = percent
                        onProgress(percent.coerceIn(0f, 99.5f))
                    }
                }
            }
        }
        onProgress(100f)
    }

    private fun hlsParts(masterUrl: String, height: Int): List<DownloadPart> {
        val master = fetchText(masterUrl)
        val variant = pickHlsVariant(master, masterUrl, height)
        val media = fetchText(variant)
        val (init, segments) = parseHlsMedia(media, variant)
        return buildList {
            init?.let { add(DownloadPart(it)) }
            segments.forEach { add(DownloadPart(it)) }
        }
    }

    @OptIn(UnstableApi::class)
    private fun dashParts(manifestUrl: String, height: Int): List<DownloadPart> {
        val dataSource = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        val manifest = DashUtil.loadManifest(dataSource, manifestUrl.toUri())
        val period = manifest.getPeriod(0)
        val representation = period.adaptationSets
            .asSequence()
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { it.representations }
            .minByOrNull { abs(it.format.height - height) }
            ?: error("No video representation in the DASH manifest")
        val baseUrl = representation.baseUrls.first().url
        val parts = mutableListOf<DownloadPart>()
        representation.initializationUri?.let { ranged ->
            parts += rangedUriToPart(ranged, baseUrl)
        }
        val index = representation.index
            ?: error("This DASH stream has no segment index")
        val durationUs = manifest.getPeriodDurationUs(0)
        val count = index.getSegmentCount(durationUs)
        check(count > 0) { "Live or unbounded DASH cannot be saved to Downloads" }
        val first = index.firstSegmentNum
        for (offset in 0 until count) {
            parts += rangedUriToPart(index.getSegmentUrl(first + offset), baseUrl)
        }
        check(parts.isNotEmpty()) { "Couldn't resolve DASH segments" }
        return parts
    }

    private fun pickHlsVariant(master: String, masterUrl: String, height: Int): String {
        data class Variant(val height: Int, val bandwidth: Int, val uri: String)
        val variants = mutableListOf<Variant>()
        var pendingHeight = 0
        var pendingBandwidth = 0
        var haveInf = false
        master.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    pendingHeight = Regex("RESOLUTION=\\d+x(\\d+)").find(line)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    pendingBandwidth = Regex("BANDWIDTH=(\\d+)").find(line)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    haveInf = true
                }
                !line.startsWith("#") && haveInf -> {
                    variants += Variant(pendingHeight, pendingBandwidth, resolveUrl(masterUrl, line))
                    haveInf = false
                }
            }
        }
        if (variants.isEmpty()) return masterUrl
        return (
            variants.filter { it.height == height }.maxByOrNull { it.bandwidth }
                ?: variants.minByOrNull { abs(it.height - height) }
                ?: variants.first()
            ).uri
    }

    private fun parseHlsMedia(playlist: String, playlistUrl: String): Pair<String?, List<String>> {
        var init: String? = null
        val segments = mutableListOf<String>()
        playlist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-MAP") -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    if (uri != null) init = resolveUrl(playlistUrl, uri)
                }
                !line.startsWith("#") -> segments += resolveUrl(playlistUrl, line)
            }
        }
        return init to segments
    }

    private fun openDownloadsFile(
        context: Context,
        filename: String,
        mime: String,
    ): Pair<Uri, OutputStream> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Gumlet")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: error("Couldn't create a file in Downloads")
            val stream = context.contentResolver.openOutputStream(uri)
                ?: error("Couldn't open Downloads file")
            return uri to stream
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Gumlet",
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, filename)
        return file.toUri() to file.outputStream()
    }

    private fun fetchText(url: String): String {
        return openConnection(url).inputStream.bufferedReader().use { it.readText() }
    }

    private fun openConnection(url: String, range: LongRange? = null): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "GumletPlayer")
        if (range != null) {
            val end = if (range.last == Long.MAX_VALUE) "" else range.last.toString()
            connection.setRequestProperty("Range", "bytes=${range.first}-$end")
        }
        if (connection.responseCode !in 200..299) {
            error("Download failed (${connection.responseCode}) for $url")
        }
        return connection
    }

    private fun resolveUrl(base: String, reference: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference
        return URI(base).resolve(reference).toString()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "video" }

    private data class DownloadPart(val url: String, val range: LongRange? = null, val knownLength: Long? = null)

    private fun rangedUriToPart(
        ranged: androidx.media3.exoplayer.dash.manifest.RangedUri,
        baseUrl: String,
    ): DownloadPart {
        val uri = ranged.resolveUri(baseUrl).toString()
        val range = when {
            ranged.length == C.LENGTH_UNSET.toLong() && ranged.start == 0L -> null
            ranged.length == C.LENGTH_UNSET.toLong() -> ranged.start..Long.MAX_VALUE
            else -> ranged.start..(ranged.start + ranged.length - 1)
        }
        val known = ranged.length.takeIf { it > 0 }
        return DownloadPart(uri, range, known)
    }

    private const val DEFAULT_BUFFER = 64 * 1024
}
