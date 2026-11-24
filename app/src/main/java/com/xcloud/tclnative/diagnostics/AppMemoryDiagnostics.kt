package com.xcloud.tclnative.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock

/** Local, on-demand data. Android's process records do not report the cause of a device reboot. */
internal object AppMemoryDiagnostics {
    fun collect(context: Context): String {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return "MEMORY/UNAVAILABLE"
        val device = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val process = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()) / MIB
        return buildString {
            appendLine("DEVICE_MEMORY/AVAILABLE_MIB=${device.availMem / MIB}; TOTAL_MIB=${device.totalMem / MIB}; LOW=${device.lowMemory}")
            appendLine("APP_MEMORY/PSS_MIB=${process.totalPss / 1024}; HEAP_MIB=$usedHeap; HEAP_LIMIT_MIB=${manager.memoryClass}")
            appendLine("DEVICE_UPTIME/SECONDS=${SystemClock.elapsedRealtime() / 1000}")
            append(if (Build.VERSION.SDK_INT >= 30) exitReason(manager) else "LAST_APP_EXIT/UNAVAILABLE_ANDROID_${Build.VERSION.SDK_INT}")
        }
    }

    @androidx.annotation.RequiresApi(30)
    private fun exitReason(manager: ActivityManager): String = runCatching {
        val record = manager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
            ?: return "LAST_APP_EXIT/NO_RECORD"
        val reason = when (record.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            else -> record.reason.toString()
        }
        "LAST_APP_EXIT/$reason; STATUS=${record.status}; PSS_MIB=${record.pss / 1024}; " +
            "AGE_SECONDS=${(System.currentTimeMillis() - record.timestamp).coerceAtLeast(0) / 1000}"
    }.getOrDefault("LAST_APP_EXIT/UNAVAILABLE")

    private const val MIB = 1024L * 1024L
}
