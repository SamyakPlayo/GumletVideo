package com.gumlet.myapplication

import android.app.Application
import com.gumlet.video.player.GumletDownloadManager

/**
 * Initialises the native [GumletDownloadManager] once so [GumletDownloadService] (merged from
 * the player AAR) can keep downloads running after the Activity is backgrounded.
 */
class GumletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GumletDownloadManager.initialize(this)
    }
}
