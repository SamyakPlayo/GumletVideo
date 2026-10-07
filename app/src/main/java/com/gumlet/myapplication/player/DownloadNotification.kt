package com.gumlet.myapplication.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gumlet.myapplication.R
import com.gumlet.myapplication.VideoPlayerActivity

/**
 * A heads-up / shade notification with a large progress bar so download status is visible
 * outside the app (status bar + notification tray), not only on the source card.
 */
internal class DownloadNotification(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video downloads",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Progress while saving videos to Downloads"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun update(source: VideoSource, quality: String, percent: Float, indeterminate: Boolean = false) {
        val clamped = percent.coerceIn(0f, 100f)
        val views = RemoteViews(context.packageName, R.layout.notification_download).apply {
            setTextViewText(R.id.download_title, source.title)
            setTextViewText(
                R.id.download_subtitle,
                if (indeterminate) "Starting $quality…"
                else "Saving $quality to Downloads · ${formatPercent(clamped)}",
            )
            setProgressBar(
                R.id.download_progress,
                1000,
                (clamped * 10).toInt(),
                indeterminate,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setProgress(1000, (clamped * 10).toInt(), indeterminate)
            .build()
        runCatching { manager.notify(idFor(source.id), notification) }
    }

    fun complete(source: VideoSource, quality: String) {
        val views = RemoteViews(context.packageName, R.layout.notification_download).apply {
            setTextViewText(R.id.download_title, source.title)
            setTextViewText(R.id.download_subtitle, "Saved to Downloads · $quality")
            setProgressBar(R.id.download_progress, 1000, 1000, false)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp())
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .build()
        runCatching { manager.notify(idFor(source.id), notification) }
    }

    fun cancel(sourceId: String) {
        manager.cancel(idFor(sourceId))
    }

    fun fail(source: VideoSource, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(source.title)
            .setContentText(message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { manager.notify(idFor(source.id), notification) }
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(context, VideoPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun idFor(sourceId: String): Int = sourceId.hashCode()

    private fun formatPercent(percent: Float): String =
        if (percent < 10f) "%.1f%%".format(percent) else "${percent.toInt()}%"

    private companion object {
        const val CHANNEL_ID = "gumlet_downloads"
    }
}
