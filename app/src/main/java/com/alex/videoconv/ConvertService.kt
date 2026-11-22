package com.alex.videoconv

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import java.io.File

class Job(val uri: Uri, val name: String, val mime: String, val bitrate: Int) {
    var state = "Chờ"; var progress = 0
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ConvertService : Service() {
    companion object {
        val jobs = mutableListOf<Job>()
        var onChange: (() -> Unit)? = null
        const val CH = "convert"
    }
    private val main = Handler(Looper.getMainLooper())
    private var current: Job? = null
    private var transformer: Transformer? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Chuyển đổi video", NotificationManager.IMPORTANCE_LOW))
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videoconv:wake").apply { acquire(6 * 60 * 60 * 1000L) }
        startForeground(1, notif("Đang chuẩn bị…", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (current == null) next()
        return START_STICKY
    }

    private fun notif(text: String, p: Int) = NotificationCompat.Builder(this, CH)
        .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Video Converter")
        .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
        .setProgress(100, p, false)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun update(j: Job) {
        nm.notify(1, notif("${j.name}: ${j.state} ${j.progress}%", j.progress))
        onChange?.invoke()
    }

    private fun next() {
        val j = jobs.firstOrNull { it.state == "Chờ" }
        if (j == null) { current = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        current = j; j.state = "Đang chạy"; update(j)
        val tmp = File(cacheDir, "out_${System.nanoTime()}.mp4")
        val enc = DefaultEncoderFactory.Builder(this)
            .setEnableFallback(false)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(j.bitrate).build())
            .build()
        val t = Transformer.Builder(this)
            .setVideoMimeType(j.mime)
            .setEncoderFactory(enc)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) {
                    j.state = "Đang lưu"; update(j)
                    Thread {
                        try { save(tmp, j); j.state = "Xong"; j.progress = 100 }
                        catch (e: Exception) { j.state = "Lỗi lưu: ${e.message}" }
                        tmp.delete(); main.post { update(j); finish() }
                    }.start()
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    j.state = "Lỗi: ${e.errorCodeName}"; tmp.delete(); update(j); finish()
                }
            }).build()
        transformer = t
        t.start(MediaItem.fromUri(j.uri), tmp.absolutePath)
        poll(t, j)
    }

    private fun poll(t: Transformer, j: Job) {
        if (j.state != "Đang chạy" || transformer !== t) return
        val h = ProgressHolder()
        if (t.getProgress(h) != Transformer.PROGRESS_STATE_NOT_STARTED) { j.progress = h.progress; update(j) }
        main.postDelayed({ poll(t, j) }, 700)
    }

    private fun finish() { transformer = null; current = null; next() }

    private fun save(f: File, j: Job) {
        val base = j.name.substringBeforeLast('.')
        val tag = when (j.mime) { MimeTypes.VIDEO_H265 -> "h265"; MimeTypes.VIDEO_AV1 -> "av1"; else -> "h264" }
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_$tag.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Converted")
        }
        val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
    }

    override fun onDestroy() {
        transformer?.cancel(); wake?.takeIf { it.isHeld }?.release(); super.onDestroy()
    }
}
