package com.vpsconsole.app.core

import android.content.Context
import android.util.Log

/**
 * Captures uncaught crashes without ADB so the stack trace can be shown in the
 * app on the next launch. This is diagnostic only; normal errors are handled
 * where they occur.
 */
object CrashReport {
    private const val PREFS = "vps_console_crash"
    private const val LAST = "last_stack_trace"

    @Volatile
    var last: String? = null
        private set

    fun install(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        last = prefs.getString(LAST, null)?.takeIf { it.isNotBlank() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                prefs.edit().putString(LAST, Log.getStackTraceString(error)).apply()
            }
            val fallback = previous ?: Thread.UncaughtExceptionHandler { _, _ ->
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(1)
            }
            fallback.uncaughtException(thread, error)
        }
    }

    fun clear(context: Context) {
        last = null
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(LAST).apply()
    }
}
