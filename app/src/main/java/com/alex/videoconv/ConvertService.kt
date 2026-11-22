package com.alex.videoconv

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import java.io.File
import java.io.IOException

enum class St { WAIT, RUN, SAVE, DONE, ERR }

class Job(
    val uri: Uri, val name: String, var mime: String, var fast: Boolean,
    var outDir: Uri?, var deleteOriginal: Boolean
) {
    var state = St.WAIT
    var progress = 0
    var msg = ""
    var t0 = 0L
    var durSec = 0.0
    var etaSec = -1.0
    var lastP = 0; var lastT = 0L; var ema = 0.0
    fun reset() { state = St.WAIT; progress = 0; msg = ""; etaSec = -1.0; lastP = 0; lastT = 0L; ema = 0.0 }
}

fun fmtDur(sec: Double): String {
    val s = Math.max(0, Math.round(sec)).toInt()
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}p ${s % 60}s"
        else -> "${s / 3600}g ${(s % 3600) / 60}p"
    }
}

private class StopEx : Exception()

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ConvertService : Service() {
    companion object {
        val jobs = mutableListOf<Job>()
        var onChange: (() -> Unit)? = null
        @Volatile var active = false
        const val CH = "convert"
        const val ACT_STOP = "com.alex.videoconv.STOP"
    }

    private val main = Handler(Looper.getMainLooper())
    private var current: Job? = null
    private var transformer: Transformer? = null
    private var tmpFile: File? = null
    private var pfd: ParcelFileDescriptor? = null
    private var target: Uri? = null          // file đích đang được ghi trực tiếp
    private var direct = false
    @Volatile private var runId = 0          // tăng mỗi lần Dừng để huỷ mọi việc đang dở
    private var wake: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cacheDir.listFiles()?.filter { it.name.startsWith("out_") }?.forEach { it.delete() }
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Chuyển đổi video", NotificationManager.IMPORTANCE_LOW))
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videoconv:wake").apply { acquire(6 * 60 * 60 * 1000L) }
        startForeground(1, notif("Đang chuẩn bị…", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACT_STOP) { stopAll(); return START_NOT_STICKY }
        if (current == null) next()
        return START_NOT_STICKY
    }

    private fun notif(text: String, p: Int, indeterminate: Boolean = false): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, ConvertService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Video Converter")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, p, indeterminate)
            .addAction(0, "Dừng", stop)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun update(j: Job) {
        val eta = if (j.state == St.RUN && j.etaSec >= 0) " · còn ~${fmtDur(j.etaSec)}" else ""
        nm.notify(1, notif("${j.name}: ${j.progress}%$eta", j.progress, j.state == St.RUN && j.progress == 0))
        onChange?.invoke()
    }

    // ---------- Dừng ----------
    private fun stopAll() {
        runId++
        transformer?.cancel(); transformer = null
        current?.let { if (it.state == St.RUN || it.state == St.SAVE) it.reset() }
        discardOutput()
        current = null; active = false
        onChange?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun endAll() {
        current = null; active = false
        val done = jobs.count { it.state == St.DONE }
        if (done > 0) {
            nm.notify(2, NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Video Converter").setContentText("Hoàn tất $done/${jobs.size} file")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .build())
        }
        onChange?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    // Bỏ file đích dở dang + file tạm
    private fun discardOutput() {
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        tmpFile?.delete(); tmpFile = null
        target?.let { deleteDoc(it) }; target = null
        direct = false
    }

    // ---------- Chạy từng file ----------
    private fun next() {
        val j = jobs.firstOrNull { it.state == St.WAIT }
        if (j == null) { endAll(); return }
        current = j
        j.state = St.RUN; j.progress = 0; j.msg = ""; j.etaSec = -1.0; j.lastP = 0; j.lastT = 0L; j.ema = 0.0
        j.t0 = SystemClock.elapsedRealtime(); update(j)
        val br = planBitrate(j)
        startEncode(j, br, true)
    }

    private fun spaceOk(path: String, need: Long): Boolean =
        try { StatFs(path).availableBytes >= need } catch (_: Exception) { true }

    private fun startEncode(j: Job, br: Int, tryDirect: Boolean) {
        val my = runId
        val need = (br * j.durSec / 8 * 1.05).toLong()
        var outPath = ""
        direct = false
        if (tryDirect) {
            try {
                if (j.outDir == null && j.durSec > 0 &&
                    !spaceOk(Environment.getExternalStorageDirectory().absolutePath, need))
                    throw IOException("Không đủ bộ nhớ: cần trống ~${need / 1048576} MB")
                val u = createTarget(j)
                target = u
                val p = contentResolver.openFileDescriptor(u, "rw") ?: throw IOException("không mở được file đích")
                pfd = p; direct = true
                outPath = "/proc/self/fd/${p.fd}"
            } catch (e: Exception) {
                val low = e.message?.startsWith("Không đủ bộ nhớ") == true
                discardOutput()
                if (low) { fail(j, e.message ?: "Không đủ bộ nhớ"); return }
            }
        }
        if (!direct) {
            if (j.durSec > 0 && !spaceOk(cacheDir.absolutePath, need)) {
                fail(j, "Không đủ bộ nhớ: cần trống ~${need / 1048576} MB cho file tạm, hiện còn ${StatFs(cacheDir.absolutePath).availableBytes / 1048576} MB")
                return
            }
            val tmp = File(cacheDir, "out_${System.nanoTime()}.mp4")
            tmpFile = tmp; outPath = tmp.absolutePath
        }

        val vb = VideoEncoderSettings.Builder().setBitrate(br)
            .setBitrateMode(if (j.fast) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                            else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        try { // không phải bản Media3 nào cũng có hàm này, nên gọi qua reflection
            vb.javaClass.getMethod("setEncoderPerformanceParameters", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(vb, Short.MAX_VALUE.toInt(), 1)
        } catch (_: Throwable) {}
        val enc = DefaultEncoderFactory.Builder(this)
            .setEnableFallback(false)
            .setRequestedVideoEncoderSettings(vb.build())
            .build()

        val wasDirect = direct
        val tmpUsed = tmpFile
        val t = Transformer.Builder(this)
            .setVideoMimeType(j.mime)
            .setEncoderFactory(enc)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) {
                    if (runId != my) return
                    if (wasDirect) completeDirect(j) else saveFromTmp(j, tmpUsed!!, my)
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    if (runId != my) return
                    transformer = null
                    val ioLike = e.errorCode == 1000 || e.errorCode in 2000..2999 || e.errorCode == 7001
                    discardOutput()
                    if (wasDirect && ioLike) {   // ghi trực tiếp không chạy được trên máy/thư mục này -> thử lại bằng file tạm
                        j.progress = 0; j.etaSec = -1.0; j.lastT = 0L; j.ema = 0.0; j.t0 = SystemClock.elapsedRealtime()
                        j.msg = "Máy không cho ghi trực tiếp, đã chuyển sang file tạm. "
                        startEncode(j, br, false)
                    } else fail(j, "Lỗi: ${e.errorCodeName}")
                }
            }).build()
        transformer = t
        t.start(MediaItem.fromUri(j.uri), outPath)
        poll(t, j, my)
    }

    private fun fail(j: Job, m: String) {
        j.state = St.ERR; j.msg = m
        discardOutput(); transformer = null
        update(j); current = null; next()
    }

    private fun poll(t: Transformer, j: Job, my: Int) {
        if (runId != my || transformer !== t || j.state != St.RUN) return
        val h = ProgressHolder()
        if (t.getProgress(h) != Transformer.PROGRESS_STATE_NOT_STARTED) { j.progress = h.progress; calcEta(j) }
        update(j)
        main.postDelayed({ poll(t, j, my) }, 500)
    }

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
            val dec = Math.max(0.0, j.etaSec - 0.5)
            dec + 0.25 * (est - dec)
        }
    }

    // ---------- Hoàn tất ----------
    private fun completeDirect(j: Job) {
        j.progress = 100
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        val u = target
        if (u == null || sizeOf(u) <= 0) { fail(j, "File đầu ra rỗng"); return }
        target = null; transformer = null
        finalizeOk(j, u)
        update(j); current = null; next()
    }

    // Dự phòng: ghi ra file tạm rồi chép sang đích (chỉ khi ghi trực tiếp không được)
    private fun saveFromTmp(j: Job, tmp: File, my: Int) {
        j.state = St.SAVE; j.progress = 100; update(j)
        Thread {
            var u: Uri? = null
            try {
                u = createTarget(j)
                contentResolver.openOutputStream(u, "w")!!.use { o ->
                    tmp.inputStream().use { ins ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            if (runId != my) throw StopEx()
                            val n = ins.read(buf); if (n < 0) break
                            o.write(buf, 0, n)
                        }
                    }
                }
                if (runId != my) throw StopEx()
                if (sizeOf(u) <= 0) throw IOException("file ghi ra rỗng")
                finalizeOk(j, u); j.state = St.DONE
            } catch (e: StopEx) {
                u?.let { deleteDoc(it) }; tmp.delete(); return@Thread
            } catch (e: Exception) {
                u?.let { deleteDoc(it) }
                j.state = St.ERR
                j.msg = if ((e.message ?: "").let { it.contains("ENOSPC") || it.contains("No space", true) })
                    "Hết bộ nhớ khi lưu. Hãy dọn thêm chỗ trống hoặc chọn thư mục ở thẻ nhớ."
                else "Lỗi lưu: ${e.message}"
            }
            tmp.delete()
            main.post { if (runId == my) { tmpFile = null; transformer = null; update(j); current = null; next() } }
        }.start()
    }

    private fun finalizeOk(j: Job, u: Uri) {
        if (u.authority == MediaStore.AUTHORITY) {
            try { contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) } catch (_: Exception) {}
        }
        j.state = St.DONE; j.progress = 100
        j.msg += "Đã lưu ${nameOfUri(u)}"
        if (j.deleteOriginal) {
            val od = outDurMs(u); val sd = (j.durSec * 1000).toLong()
            val ok = od > 0 && sd > 0 && Math.abs(od - sd) <= Math.max(1000L, sd / 50)
            j.msg += if (!ok) " · giữ file gốc (không xác minh được file mới)"
                     else if (deleteDoc(j.uri)) " · đã xoá file gốc" else " · không xoá được file gốc"
        }
    }

    // ---------- File đích ----------
    private fun createTarget(j: Job): Uri {
        val base = j.name.substringBeforeLast('.')
        val tag = when (j.mime) { MimeTypes.VIDEO_H265 -> "h265"; MimeTypes.VIDEO_AV1 -> "av1"; else -> "h264" }
        val fileName = "${base}_$tag.mp4"
        j.outDir?.let { dir ->
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(dir, DocumentsContract.getTreeDocumentId(dir))
                DocumentsContract.createDocument(contentResolver, parent, "video/mp4", fileName)?.let { return it }
            } catch (_: Exception) {}
            if (!j.msg.contains("Movies/Converted")) j.msg += "Không ghi được vào thư mục đã chọn, đã lưu ở Movies/Converted. "
        }
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Converted")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("không tạo được file đầu ra")
    }

    private fun sizeOf(u: Uri): Long = try {
        contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    } catch (_: Exception) { 0L }

    private fun nameOfUri(u: Uri): String = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "video"
    } catch (_: Exception) { "video" }

    private fun outDurMs(u: Uri): Long {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(this, u); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
        catch (_: Exception) { 0L } finally { r.release() }
    }

    private fun deleteDoc(u: Uri): Boolean {
        try { if (DocumentsContract.isDocumentUri(this, u) && DocumentsContract.deleteDocument(contentResolver, u)) return true } catch (_: Exception) {}
        return try { contentResolver.delete(u, null, null) > 0 } catch (_: Exception) { false }
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
            val size = sizeOf(j.uri)
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
            j.durSec = durMs / 1000.0
            if (durMs <= 0 || size <= 0) return 8_000_000
            val total = size * 8.0 / (durMs / 1000.0)
            val video = if (hasAudio) Math.max(total - 160_000, total * 0.8) else total
            val ratio = (eff[j.mime] ?: 1.0) / (eff[srcMime] ?: 1.0)
            val floor = w.toDouble() * h * 0.6 * (eff[j.mime] ?: 1.0)
            val target = Math.min(video, Math.max(floor, video * ratio * 1.15))
            return target.toInt().coerceIn(500_000, 80_000_000)
        } catch (_: Exception) { return 8_000_000 }
    }

    override fun onDestroy() {
        runId++
        transformer?.cancel(); discardOutput(); active = false
        wake?.takeIf { it.isHeld }?.release(); super.onDestroy()
    }
}
