package com.tinyggrok.app.data.repository

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why the app stopped running last time.
 *
 * Reported as "app sometimes quits" — something nobody can act on without knowing what
 * kind of quit it was. A crash is the app's fault and leaves a stack trace; being killed
 * for memory is Android reclaiming a backgrounded app and is a reason to hold less; the
 * user swiping it away is neither. Android keeps its own record of the last few exits and
 * will tell an app about its own, so the app can simply ask rather than the two of us
 * guessing. Saved conversations make the answer survivable either way; this makes it
 * diagnosable.
 *
 * The record exists from Android 11. Below that this reports nothing, which is honest.
 */
@Singleton
class AppExitRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** A sentence about the most recent previous exit, or null if nothing is recorded. */
    fun lastExit(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        } catch (_: Throwable) {
            null
        } ?: return null
        val when_ = formatter.format(Date(info.timestamp))
        val detail = info.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "${describe(info.reason)} on $when_$detail"
    }

    private fun describe(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Crashed (native)"
        ApplicationExitInfo.REASON_ANR -> "Stopped responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android to free memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed for using too much memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "Closed by you"
        ApplicationExitInfo.REASON_USER_STOPPED -> "Stopped from Android settings"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "A service it depended on died"
        ApplicationExitInfo.REASON_OTHER -> "Closed by Android"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Restarted after a permission change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Restarted after an app update"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
        ApplicationExitInfo.REASON_SIGNALED -> "Ended by a signal"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Closed itself"
        else -> "Ended for reason $reason"
    }
}
