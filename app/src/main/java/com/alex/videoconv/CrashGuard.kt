package com.alex.videoconv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

// Ghi dấu "đang mã hoá" trước khi chạy encoder. Nếu app chết giữa chừng (crash native của codec,
// bị hệ thống tắt...), lần mở sau sẽ biết và báo đúng nguyên nhân thay vì chỉ thấy app biến mất.
object CrashGuard {
    class Verdict(val mime: String, val kind: Int, val detail: String)   // kind: 0 chưa rõ, 1 crash, 2 bị hệ thống tắt

    private var installed = false
    private fun p(c: Context) = c.getSharedPreferences("guard", Context.MODE_PRIVATE)

    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { p(app).edit().putString("lastCrash", "${e.javaClass.simpleName}: ${e.message}".take(300)).commit() } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun mark(c: Context, mime: String) { try { p(c).edit().putString("pending", "$mime|${System.currentTimeMillis()}").commit() } catch (_: Throwable) {} }
    fun clear(c: Context) { try { p(c).edit().remove("pending").commit() } catch (_: Throwable) {} }

    fun av1Bad(c: Context): Boolean = p(c).getBoolean("av1Bad", false)
    fun setAv1Bad(c: Context, v: Boolean) { p(c).edit().putBoolean("av1Bad", v).apply() }

    // Đọc và xoá dấu; trả về null nếu lần trước thoát bình thường
    fun check(c: Context): Verdict? {
        val s = p(c).getString("pending", null) ?: return null
        val last = p(c).getString("lastCrash", "") ?: ""
        p(c).edit().remove("pending").remove("lastCrash").commit()
        val mime = s.substringBefore('|')
        val at = s.substringAfter('|', "0").toLongOrNull() ?: 0L
        var kind = 0; var detail = last
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val e = am.getHistoricalProcessExitReasons(c.packageName, 0, 5).firstOrNull { it.timestamp >= at }
                if (e != null) {
                    kind = when (e.reason) {
                        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> 1
                        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
                        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_USER_REQUESTED,
                        ApplicationExitInfo.REASON_OTHER -> 2
                        else -> 0
                    }
                    if (detail.isEmpty()) detail = e.description ?: ""
                }
            } catch (_: Throwable) {}
        } else if (last.isNotEmpty()) kind = 1
        return Verdict(mime, kind, detail)
    }
}
