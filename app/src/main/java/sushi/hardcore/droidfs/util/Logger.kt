package sushi.hardcore.droidfs.util

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi

/**
 * Everything the app writes to the system log goes through here, so that it can be switched off.
 *
 * The app's own lines carry no file names or paths, but that is not the whole risk: what appears
 * around them still describes activity inside an open volume -- a codec starting says a video was
 * viewed, a viewer opening says something was opened. Most of those lines come from the framework
 * running in this process and cannot be silenced from here. What can be silenced is the app itself
 * and the media libraries it pulls in, which is what this does.
 *
 * Crashes are written by the runtime, not through this, so they are reported either way.
 */
object Logger {
    private val enabledDelegate = AndroidUtils.LiveBooleanPreference("logging", true) { applyToLibraries(it) }
    private val enabled by enabledDelegate

    fun init(context: Context) {
        enabledDelegate.init(context)
        applyToLibraries(enabled)
    }

    /**
     * ExoPlayer logs playback state and errors on its own. Warnings are kept while logging is on,
     * because a failed playback is worth diagnosing; the chattier levels are not.
     */
    @OptIn(UnstableApi::class)
    private fun applyToLibraries(enabled: Boolean) {
        androidx.media3.common.util.Log.setLogLevel(
            if (enabled) {
                androidx.media3.common.util.Log.LOG_LEVEL_WARNING
            } else {
                androidx.media3.common.util.Log.LOG_LEVEL_OFF
            }
        )
    }

    fun i(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (enabled) Log.w(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        if (enabled) Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String) {
        if (enabled) Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (enabled) Log.e(tag, message, throwable)
    }
}
