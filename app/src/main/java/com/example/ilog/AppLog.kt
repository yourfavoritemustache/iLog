package com.example.ilog

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val LOG_FILE_NAME = "ilog_debug_logs.txt"
    private val scope = CoroutineScope(Dispatchers.IO)

    fun d(context: Context, tag: String, message: String) {
        log(context.applicationContext, "DEBUG", tag, message)
    }

    fun e(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
        log(context.applicationContext, "ERROR", tag, fullMessage)
    }

    private fun log(context: Context, level: String, tag: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $level/$tag: $message\n"
        
        // Also log to Logcat
        if (level == "ERROR") {
            android.util.Log.e(tag, message)
        } else {
            android.util.Log.d(tag, message)
        }

        scope.launch {
            try {
                val logFile = File(context.filesDir, LOG_FILE_NAME)
                logFile.appendText(logEntry)
            } catch (e: Exception) {
                android.util.Log.e("AppLog", "Failed to write to log file", e)
            }
        }
    }

    fun getLogs(context: Context): String {
        return try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile.exists()) logFile.readText() else "No logs found."
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        scope.launch {
            try {
                val logFile = File(context.filesDir, LOG_FILE_NAME)
                if (logFile.exists()) logFile.delete()
            } catch (e: Exception) {
                android.util.Log.e("AppLog", "Failed to clear logs", e)
            }
        }
    }
}
