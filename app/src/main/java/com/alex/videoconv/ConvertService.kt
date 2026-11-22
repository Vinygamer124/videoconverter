package com.alex.videoconv

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import java.io.File
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaCodecInfo
import android.provider.OpenableColumns
import java.io.IOException

enum class St { WAIT, RUN, SAVE, DONE, ERR }

class Job(
    val uri: Uri, val name: String, val mime: String, val fast: Boolean,
    val outDir: Uri?, val deleteOriginal: Boolean
) {
    var state = St.WAIT
    var progress = 0
    var msg = ""
    var t0 = 0L            // elapsedRealtime lúc bắt đầu chạy
    var etaSec = -1.0      // ước tính còn lại (giây), -1 = đang đo
    var lastP = 0; var lastT = 0L; var ema = 0.0
}

fun fmtDur(sec: Double): String {
    val s = Math.max(0, Math.round(sec)).toInt()
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}p ${s % 60}s"
        else -> "${s / 3600}g ${(s % 3600) / 60}p"
    }
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
        super.onCreate()
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
        val eta = if (j.state == St.RUN && j.etaSec >= 0) " · còn ~${fmtDur(j.etaSec)}" else ""
        nm.notify(1, notif("${j.name}: ${j.progress}%$eta", j.progress))
        onChange?.invoke()
    }

    private fun next() {
        val j = jobs.firstOrNull { it.state == St.WAIT }
        if (j == null) { current = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        current = j; j.state = St.RUN; j.t0 = SystemClock.elapsedRealtime(); update(j)
        val tmp = File(cacheDir, "out_${System.nanoTime()}.mp4")
        val enc = DefaultEncoderFactory.Builder(this)
            .setEnableFallback(false)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(planBitrate(j))
                    .setBitrateMode(if (j.fast) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                                    else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build())
            .build()
        val t = Transformer.Builder(this)
            .setVideoMimeType(j.mime)
            .setEncoderFactory(enc)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) {
                    j.state = St.SAVE; j.progress = 100; update(j)
                    Thread {
                        try { save(tmp, j); j.state = St.DONE; j.progress = 100 }
                        catch (e: Exception) { j.state = St.ERR; j.msg = "Lỗi lưu: ${e.message}" }
                        tmp.delete(); main.post { update(j); finish() }
                    }.start()
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    j.state = St.ERR; j.msg = "Lỗi: ${e.errorCodeName}"; tmp.delete(); update(j); finish()
                }
            }).build()
        transformer = t
        t.start(MediaItem.fromUri(j.uri), tmp.absolutePath)
        poll(t, j)
    }


    // Bitrate tự động theo video gốc (giống planBitrate trong bản HTML)
    private fun planBitrate(j: Job): Int {
        val eff = mapOf(MimeTypes.VIDEO_H264 to 1.0, MimeTypes.VIDEO_H265 to 0.70, MimeTypes.VIDEO_AV1 to 0.60)
        try {
            var w = 0; var h = 0; var durMs = 0L
            MediaMetadataRetriever().also { r ->
                try {
                    r.setDataSource(this, j.uri)
                    w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                    h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                    durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                } finally { r.release() }
            }
            val size = contentResolver.query(j.uri, null, null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)) else 0L
            } ?: 0L
            var srcMime = MimeTypes.VIDEO_H264; var hasAudio = false
            MediaExtractor().also { e ->
                try {
                    e.setDataSource(this, j.uri, null)
                    for (i in 0 until e.trackCount) {
                        val m = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        if (m.startsWith("video/") && srcMime == MimeTypes.VIDEO_H264) srcMime = m
                        if (m.startsWith("audio/")) hasAudio = true
                    }
                } finally { e.release() }
            }
            if (durMs <= 0 || size <= 0) return 8_000_000
            val total = size * 8.0 / (durMs / 1000.0)
            val video = if (hasAudio) Math.max(total - 160_000, total * 0.8) else total
            val ratio = (eff[j.mime] ?: 1.0) / (eff[srcMime] ?: 1.0)
            val floor = w.toDouble() * h * 0.6 * (eff[j.mime] ?: 1.0)
            val target = Math.min(video, Math.max(floor, video * ratio * 1.15))
            return target.toInt().coerceIn(500_000, 80_000_000)
        } catch (_: Exception) { return 8_000_000 }
    }

    private fun poll(t: Transformer, j: Job) {
        if (j.state != St.RUN || transformer !== t) return
        val h = ProgressHolder()
        if (t.getProgress(h) != Transformer.PROGRESS_STATE_NOT_STARTED) {
            j.progress = h.progress; calcEta(j)
        }
        update(j)
        main.postDelayed({ poll(t, j) }, 700)
    }

    // ETA: trộn tốc độ trung bình toàn cục với tốc độ gần nhất, làm mượt để số không giật
    private fun calcEta(j: Job) {
        val now = SystemClock.elapsedRealtime()
        val el = (now - j.t0) / 1000.0
        val p = j.progress
        if (j.lastT != 0L && now - j.lastT >= 2000 && p > j.lastP) {
            val inst = (p - j.lastP) / ((now - j.lastT) / 1000.0)
            j.ema = if (j.ema == 0.0) inst else 0.7 * j.ema + 0.3 * inst
            j.lastP = p; j.lastT = now
        } else if (j.lastT == 0L) { j.lastP = p; j.lastT = now }
        if (el < 4 || p < 2) return
        val global = p / el
        val rate = if (j.ema > 0) 0.6 * global + 0.4 * j.ema else global
        if (rate <= 0) return
        val est = (100 - p) / rate
        j.etaSec = if (j.etaSec < 0) est else {
            val dec = Math.max(0.0, j.etaSec - 0.7)
            dec + 0.25 * (est - dec)
        }
    }

    private fun finish() { transformer = null; current = null; next() }

    private fun save(f: File, j: Job) {
        val base = j.name.substringBeforeLast('.')
        val tag = when (j.mime) { MimeTypes.VIDEO_H265 -> "h265"; MimeTypes.VIDEO_AV1 -> "av1"; else -> "h264" }
        val fileName = "${base}_$tag.mp4"
        var target: Uri? = null
        if (j.outDir != null) {
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(j.outDir, DocumentsContract.getTreeDocumentId(j.outDir))
                target = DocumentsContract.createDocument(contentResolver, parent, "video/mp4", fileName)
            } catch (_: Exception) {}
        }
        var note = ""
        if (target == null) {
            if (j.outDir != null) note = "Không ghi được vào thư mục đã chọn, đã lưu ở Movies/Converted. "
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Converted")
            }
            target = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("không tạo được file đầu ra")
        }
        val written = contentResolver.openOutputStream(target)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        if (written <= 0 || written != f.length()) throw IOException("file ghi ra không đầy đủ")
        note += "Đã lưu $fileName"
        if (j.deleteOriginal) {
            note += if (deleteOriginal(j.uri)) " · đã xoá file gốc" else " · không xoá được file gốc"
        }
        j.msg = note
    }

    private fun deleteOriginal(u: Uri): Boolean {
        try { if (DocumentsContract.deleteDocument(contentResolver, u)) return true } catch (_: Exception) {}
        return try { contentResolver.delete(u, null, null) > 0 } catch (_: Exception) { false }
    }

    override fun onDestroy() {
        transformer?.cancel(); wake?.takeIf { it.isHeld }?.release(); super.onDestroy()
    }
}
