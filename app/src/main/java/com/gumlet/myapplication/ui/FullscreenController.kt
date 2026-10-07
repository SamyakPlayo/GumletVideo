package com.gumlet.myapplication.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Drives orientation and system-bar visibility for the in-player fullscreen toggle.
 *
 * Orientation is locked explicitly in both directions rather than left to the sensor: that keeps
 * the behaviour identical whether or not the user has auto-rotate enabled, and avoids the
 * rotate/exit/re-enter loop you get when fullscreen is derived from the current configuration.
 *
 * The Activity declares `configChanges` for orientation, so rotating does not recreate it and
 * playback continues uninterrupted across the toggle.
 */
@Composable
fun ApplyFullscreen(isFullscreen: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val view = LocalView.current

    // Applies the requested mode. It has NO cleanup on purpose: a DisposableEffect keyed on the
    // flag runs its onDispose on every toggle, so restoring SCREEN_ORIENTATION_UNSPECIFIED here
    // would hand the Activity back to the sensor for one frame on the way out of fullscreen —
    // the device rotates to whatever it is physically held at, then gets forced to portrait,
    // which reads as a flicker and a double rotation.
    LaunchedEffect(isFullscreen) {
        val insets = WindowCompat.getInsetsController(activity.window, view)

        // SENSOR_LANDSCAPE allows reverse landscape; PORTRAIT is a hard lock so a phone
        // still held sideways cannot sensor-rotate back into landscape after exit.
        activity.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        if (isFullscreen) {
            insets.hide(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Only runs when the screen genuinely leaves composition, so the app is never stranded in
    // landscape with hidden bars — without interfering with ordinary toggling.
    // Skip the reset across a configuration-change recreation: unlocking to UNSPECIFIED while
    // the device is still physically landscape lets the replacement Activity start in landscape
    // and then get forced back to portrait, which is the flicker+recreate the caller sees.
    DisposableEffect(Unit) {
        onDispose {
            WindowCompat.getInsetsController(activity.window, view)
                .show(WindowInsetsCompat.Type.systemBars())
            if (!activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
