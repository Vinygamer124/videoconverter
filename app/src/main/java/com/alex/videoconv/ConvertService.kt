package com.alex.videoconv

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.transformer.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class St { WAIT, RUN, SAVE, DONE, ERR }

class Job(
    val uri: Uri, val name: String, var mime: String, var fast: Boolean,
    var outDir: Uri?, var deleteOriginal: Boolean
) {
    var state = St.WAIT
    var progress = 0
    var msg = ""
    var t0 = 0L; var wallStart = 0L
    var durSec = 0.0
    var srcBytes = 0L; var estBytes = 0L; var outBytes = 0L
    var enc = 0                 // 0 tự động, 1 phần cứng, 2 phần mềm
    var level = 1               // mức nén
    var capMbps = 0.0           // giới hạn tối đa, 0 = không giới hạn
    var fps = 0.0               // giới hạn FPS, 0 = giữ FPS gốc
    var srcFps = 0.0; var srcW = 0; var srcH = 0; var srcVideoBps = 0.0   // thông tin video gốc
    var keepTime = true
    var tCreate = 0L; var tMod = 0L
    var savePct = 0; var saveEta = -1.0; var saveSpeed = 0.0
    val eta = Eta()
    val szp = SizePred()
    @Volatile var thumb: Bitmap? = null
    fun reset() {
        state = St.WAIT; progress = 0; msg = ""
        savePct = 0; saveEta = -1.0; saveSpeed = 0.0
        eta.reset(SystemClock.elapsedRealtime()); szp.reset(estBytes)
    }
}

fun clockText(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private class StopEx : Exception()

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ConvertService : Service() {
    companion object {
        val jobs = mutableListOf<Job>()
        var onChange: (() -> Unit)? = null
        @Volatile var active = false
        const val CH = "convert"
        const val CH_DONE = "done"
        const val ACT_STOP = "com.alex.videoconv.STOP"
        const val ACCENT = 0xFF4F6BFF.toInt()
    }

    private val main = Handler(Looper.getMainLooper())
    private var current: Job? = null
    private var transformer: Transformer? = null
    private var tmpFile: File? = null
    private var pfd: ParcelFileDescriptor? = null
    private var target: Uri? = null
    private var direct = false
    private var curBr = 0
    private var curChk: Enc.Check? = null
    private val session = ArrayList<Job>()
    @Volatile private var runId = 0
    private var wake: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(applicationContext)
        cacheDir.listFiles()?.filter { it.name.startsWith("out_") }?.forEach { it.delete() }
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Đang chuyển đổi", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Tiến độ chuyển đổi video"; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Hoàn tất", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Báo kết quả khi chuyển đổi xong"
        })
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videoconv:wake").apply { acquire(6 * 60 * 60 * 1000L) }
        startForeground(1, notifFor(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACT_STOP) { stopAll(); return START_NOT_STICKY }
        if (current == null) next()
        return START_NOT_STICKY
    }

    // ---------- Thông báo ----------
    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    private fun stopPi() = PendingIntent.getService(this, 1, Intent(this, ConvertService::class.java).setAction(ACT_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun codecLabel(m: String) = when (m) { MimeTypes.VIDEO_H265 -> "H.265"; MimeTypes.VIDEO_AV1 -> "AV1"; else -> "H.264" }

    private fun notifFor(j: Job?): Notification {
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat).setColor(ACCENT)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp()).addAction(0, "Dừng", stopPi())
        if (j == null) return b.setContentTitle("Video Converter").setContentText("Đang chuẩn bị…").setProgress(0, 0, true).build()
        b.setSubText("File ${jobs.indexOf(j) + 1}/${jobs.size} · ${codecLabel(j.mime)}")
        b.setLargeIcon(j.thumb)
        if (j.wallStart > 0) b.setUsesChronometer(true).setWhen(j.wallStart).setShowWhen(true)   // đồng hồ đã chạy
        if (j.state == St.SAVE) {
            val sp = "%.0f MB/s".format(j.saveSpeed) + (if (j.saveEta >= 0) " · còn ${fmtDur(j.saveEta)}" else "")
            b.setContentTitle("Đang lưu · ${j.savePct}%").setContentText(j.name).setProgress(100, j.savePct, false)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${j.name}\n$sp"))
        } else {
            val e = j.eta.text()
            val etaPart = when { j.progress >= 98 -> "sắp xong"; j.eta.disp < 0 -> "đang đo tốc độ…"; else -> "còn ${e.t}" }
            val sz = j.szp.est()
            val szLine = if (sz != null) "≈ ${humanSize(sz[1])}" + (if (j.srcBytes > 0) " (${deltaText(sz[1], j.srcBytes)})" else "") else ""
            val big = buildString {
                append(j.name); append("\n$etaPart")
                if (e.atMs > 0) append(" · xong lúc ${clockText(e.atMs)}")
                if (szLine.isNotEmpty()) append("\nDung lượng dự đoán $szLine")
            }
            b.setContentTitle("${j.progress}% · $etaPart")
                .setContentText(if (szLine.isNotEmpty()) "${j.name} · $szLine" else j.name)
                .setProgress(100, j.progress, j.progress == 0)
                .setStyle(NotificationCompat.BigTextStyle().bigText(big))
        }
        return b.build()
    }

    private fun update(j: Job) {
        if (j.state == St.RUN || j.state == St.SAVE) nm.notify(1, notifFor(j))
        onChange?.invoke()
    }

    // ---------- Dừng / kết thúc ----------
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
        val done = session.filter { it.state == St.DONE }
        val err = session.count { it.state == St.ERR }
        if (done.isNotEmpty() || err > 0) {
            val src = done.sumOf { it.srcBytes }; val out = done.sumOf { it.outBytes }
            val saved = when {
                src <= 0 || out <= 0 -> ""
                out <= src -> "Nhẹ đi ${humanSize(src - out)} (${deltaText(out, src)})"
                else -> "Nặng hơn ${humanSize(out - src)} (${deltaText(out, src)})"
            }
            val title = if (err == 0) "Hoàn tất ${done.size} video" else "Xong ${done.size}/${session.size} video · $err lỗi"
            val lines = session.take(6).joinToString("\n") {
                if (it.state == St.DONE) "✓ ${it.name} · ${humanSize(it.outBytes)}" + (if (it.srcBytes > 0) " (${deltaText(it.outBytes, it.srcBytes)})" else "")
                else "✕ ${it.name} · ${it.msg.take(60)}"
            } + (if (session.size > 6) "\n… và ${session.size - 6} video khác" else "")
            nm.notify(2, NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat).setColor(ACCENT)
                .setContentTitle(title).setContentText(saved.ifEmpty { "Bấm để xem kết quả" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(if (saved.isNotEmpty()) "$saved\n$lines" else lines))
                .setCategory(NotificationCompat.CATEGORY_STATUS).setAutoCancel(true).setContentIntent(openApp())
                .build())
        }
        onChange?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun discardOutput() {
        CrashGuard.clear(this)
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
        val now = SystemClock.elapsedRealtime()
        j.state = St.RUN; j.progress = 0; j.msg = ""; j.t0 = now; j.wallStart = System.currentTimeMillis()
        j.eta.reset(now)
        if (!session.contains(j)) session.add(j)
        update(j)
        readOrigTimes(j)
        if (j.thumb == null) j.thumb = Planner.thumb(this, j.uri)
        val info = Enc.list(j.mime)
        val cn = codecLabel(j.mime)
        if (j.enc == 3) {
            if (j.mime != MimeTypes.VIDEO_AV1) { fail(j, "libaom chỉ mã hoá AV1. Hãy chọn codec AV1 hoặc đổi bộ mã hoá."); return }
            if (!AomNative.available()) { fail(j, "Không nạp được libaom trên máy này (cần chip arm64)."); return }
            if (Build.VERSION.SDK_INT < 34) { fail(j, "Ghi AV1 vào MP4 cần Android 14 trở lên."); return }
        }
        if (j.enc == 1 && info.hw.isEmpty()) { fail(j, "Máy không có encoder phần cứng cho $cn. Hãy đổi sang Tự động hoặc Phần mềm."); return }
        if (j.enc == 2 && info.sw.isEmpty()) { fail(j, "Máy không có encoder phần mềm cho $cn. Hãy đổi sang Tự động hoặc Phần cứng."); return }
        val plan = Planner.plan(this, j.uri, j.mime, j.level, j.capMbps)
        j.durSec = plan.durSec; j.srcBytes = plan.srcBytes; j.estBytes = plan.estBytes
        j.srcFps = plan.fps; j.srcW = plan.w; j.srcH = plan.h; j.srcVideoBps = plan.srcVideoBps
        j.szp.reset(plan.estBytes)
        if (plan.srcMime == MimeTypes.VIDEO_AV1 && !Enc.canDecode(MimeTypes.VIDEO_AV1)) {
            fail(j, "Video gốc là AV1 nhưng máy không có bộ giải mã AV1 (cần decoder phần mềm như dav1d). Hãy thử video gốc H.264/H.265."); return
        }
        if (j.fps > 0 && plan.fps > 0 && j.fps >= plan.fps - 0.5) j.msg += "Giữ FPS gốc (${fmtFps(plan.fps)}) vì mức đặt không thấp hơn. "
        val effFps = if (j.fps > 0 && (plan.fps <= 0 || j.fps < plan.fps - 0.5)) j.fps else plan.fps
        // Kiểm tra encoder trước khi chạy để tránh crash / lỗi khó hiểu (nhất là AV1)
        val chk = if (j.enc == 3) Enc.Check(true, "", plan.bitrate, "libaom")
                  else Enc.preflight(j.mime, j.enc, plan.w, plan.h, effFps, plan.bitrate, j.fast)
        if (!chk.ok) { fail(j, chk.msg); return }
        curChk = chk
        update(j)
        startEncode(j, chk.bitrate, true)
    }

    private fun spaceOk(path: String, need: Long): Boolean =
        try { StatFs(path).availableBytes >= need } catch (_: Exception) { true }

    private fun startEncode(j: Job, br: Int, tryDirect: Boolean) {
        val my = runId
        curBr = br
        val need = j.estBytes
        var outPath = ""
        direct = false
        if (tryDirect) {
            try {
                if (j.outDir == null && need > 0 && !spaceOk(Environment.getExternalStorageDirectory().absolutePath, need))
                    throw IOException("Không đủ bộ nhớ: cần trống ~${humanSize(need)}")
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
            if (need > 0 && !spaceOk(cacheDir.absolutePath, need)) {
                fail(j, "Không đủ bộ nhớ: cần trống ~${humanSize(need)} cho file tạm, hiện còn ${humanSize(StatFs(cacheDir.absolutePath).availableBytes)}")
                return
            }
            val tmp = File(cacheDir, "out_${System.nanoTime()}.mp4")
            tmpFile = tmp; outPath = tmp.absolutePath
        }

        val chk = curChk
        val vb = VideoEncoderSettings.Builder().setBitrate(br)
            .setBitrateMode(chk?.bitrateMode ?: if (j.fast) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                            else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        // AV1: không ép operating-rate/priority vì một số encoder AV1 xử lý sai và làm app dừng đột ngột
        if (j.mime != MimeTypes.VIDEO_AV1) {
            try {
                vb.javaClass.getMethod("setEncoderPerformanceParameters", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(vb, Short.MAX_VALUE.toInt(), 1)
            } catch (_: Throwable) {}
        }
        val defEnc = DefaultEncoderFactory.Builder(this)
            .setEnableFallback(false)
            .setVideoEncoderSelector(Enc.selector(if (j.enc == 3) 0 else j.enc, if (j.enc == 3) null else chk?.name))
            .setRequestedVideoEncoderSettings(vb.build())
            .build()
        val outFps = if (j.fps > 0 && (j.srcFps <= 0 || j.fps < j.srcFps - 0.5)) j.fps else j.srcFps
        val enc: Codec.EncoderFactory = if (j.enc == 3) AomEncoderFactory(defEnc, br, outFps, j.fast) else defEnc

        val wasDirect = direct
        val tmpUsed = tmpFile
        val t = Transformer.Builder(this)
            .setVideoMimeType(j.mime)
            .setEncoderFactory(enc)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) {
                    if (runId != my) return
                    CrashGuard.clear(this@ConvertService)
                    if (wasDirect) completeDirect(j) else saveFromTmp(j, tmpUsed!!, my)
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    if (runId != my) return
                    transformer = null
                    val ioLike = e.errorCode == 1000 || e.errorCode in 2000..2999 || e.errorCode == 7001
                    discardOutput()
                    if (wasDirect && ioLike) {
                        restartClock(j)
                        j.msg = "Máy không cho ghi trực tiếp, đã chuyển sang file tạm. "
                        startEncode(j, br, false)
                    } else fail(j, "Lỗi: ${e.errorCodeName}")
                }
            }).build()
        transformer = t
        // Giảm FPS bằng cách bỏ bớt khung hình (chỉ giảm được, không tăng)
        val dropFps = if (j.fps > 0 && (j.srcFps <= 0 || j.fps < j.srcFps - 0.5)) j.fps else 0.0
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(j.uri)).apply {
            if (dropFps > 0) setEffects(Effects(emptyList(), listOf(FrameDropEffect.createDefaultFrameDropEffect(dropFps.toFloat()))))
        }.build()
        try {
            CrashGuard.mark(this, j.mime)      // nếu app chết ngay trong encoder, lần sau sẽ báo đúng nguyên nhân
            t.start(edited, outPath)
        } catch (e: Throwable) {
            CrashGuard.clear(this)
            fail(j, "Không khởi động được bộ mã hoá ${codecLabel(j.mime)}: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        poll(t, j, my)
    }

    private fun restartClock(j: Job) {
        val now = SystemClock.elapsedRealtime()
        j.progress = 0; j.t0 = now; j.wallStart = System.currentTimeMillis()
        j.eta.reset(now); j.szp.reset(j.estBytes)
    }

    private fun fail(j: Job, m: String) {
        j.state = St.ERR; j.msg = m
        discardOutput(); transformer = null
        update(j); current = null; next()
    }

    private fun poll(t: Transformer, j: Job, my: Int) {
        if (runId != my || transformer !== t || j.state != St.RUN) return
        val h = ProgressHolder()
        val now = SystemClock.elapsedRealtime()
        if (t.getProgress(h) != Transformer.PROGRESS_STATE_NOT_STARTED) {
            j.progress = h.progress
            j.eta.push((now - j.t0) / 1000.0, h.progress / 100.0)
            val sz = if (direct) (try { pfd?.statSize ?: 0L } catch (_: Exception) { 0L }) else (tmpFile?.length() ?: 0L)
            j.szp.push(h.progress / 100.0, sz)
        }
        j.eta.tick(now)
        update(j)
        main.postDelayed({ poll(t, j, my) }, 500)
    }

    // ---------- Hoàn tất ----------
    private fun completeDirect(j: Job) {
        j.progress = 100
        val sz = try { pfd?.statSize ?: 0L } catch (_: Exception) { 0L }
        if (j.keepTime && j.tCreate > 0 && sz > 0) pfd?.let { patchFd(it, j.tCreate) }
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        val u = target
        if (u == null || sz <= 0) {
            discardOutput(); transformer = null
            restartClock(j)
            j.msg = "Máy không cho ghi trực tiếp, đã chuyển sang file tạm. "
            startEncode(j, curBr, false); return
        }
        target = null; transformer = null
        j.outBytes = sz
        finalizeOk(j, u)
        update(j); current = null; next()
    }

    private fun saveFromTmp(j: Job, tmp: File, my: Int) {
        j.state = St.SAVE; j.progress = 100; update(j)
        Thread {
            var u: Uri? = null
            try {
                if (j.keepTime && j.tCreate > 0) patchFile(tmp, j.tCreate)
                u = createTarget(j)
                var copied = 0L
                val total = tmp.length(); val ts = SystemClock.elapsedRealtime(); var lastUi = 0L
                j.savePct = 0; j.saveEta = -1.0; j.saveSpeed = 0.0
                contentResolver.openOutputStream(u, "w")!!.use { o ->
                    tmp.inputStream().use { ins ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            if (runId != my) throw StopEx()
                            val n = ins.read(buf); if (n < 0) break
                            o.write(buf, 0, n); copied += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUi >= 300) {
                                lastUi = now
                                val el = (now - ts) / 1000.0
                                val sp = if (el > 0.5) copied / el else 0.0
                                j.savePct = if (total > 0) (copied * 100 / total).toInt() else 0
                                j.saveSpeed = sp / 1048576.0
                                j.saveEta = if (sp > 0) (total - copied) / sp else -1.0
                                update(j)
                            }
                        }
                    }
                }
                if (runId != my) throw StopEx()
                j.savePct = 100
                if (copied <= 0 || copied != tmp.length()) throw IOException("file ghi ra không đầy đủ")
                j.outBytes = copied
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
        val keep = j.keepTime && j.tMod > 0
        if (keep) setMtime(u, j.tMod)
        if (u.authority == MediaStore.AUTHORITY) {
            val ok = try {
                contentResolver.update(u, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    if (keep) {
                        put(MediaStore.Video.Media.DATE_TAKEN, j.tCreate)
                        put(MediaStore.Video.Media.DATE_MODIFIED, j.tMod / 1000)
                        put(MediaStore.Video.Media.DATE_ADDED, j.tMod / 1000)
                    }
                }, null, null) > 0
            } catch (_: Exception) { false }
            if (!ok) {
                try { contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) } catch (_: Exception) {}
                if (keep) setDates(u, j.tCreate, j.tMod)
            }
            if (keep) { val c = j.tCreate; val m = j.tMod; main.postDelayed({ setDates(u, c, m) }, 4000) }
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

    // ---------- Mốc thời gian gốc ----------
    private fun readOrigTimes(j: Job) {
        var mod = 0L
        try {
            contentResolver.query(j.uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) mod = it.getLong(0)
            }
        } catch (_: Exception) {}
        var create = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, j.uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { create = parseMetaDate(it) }
        } catch (_: Exception) {} finally { r.release() }
        val min = 631152000000L
        if (mod < min) mod = 0L
        if (create < min) create = 0L
        if (create == 0L) create = mod
        if (mod == 0L) mod = create
        j.tCreate = create; j.tMod = mod
    }

    private fun parseMetaDate(s: String): Long = try {
        val f = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.parse(s.substring(0, 15))?.time ?: 0L
    } catch (_: Exception) { 0L }

    private fun setMtime(u: Uri, ms: Long) {
        try { contentResolver.openFileDescriptor(u, "rw")?.use { p -> File("/proc/self/fd/${p.fd}").setLastModified(ms) } } catch (_: Exception) {}
    }

    private fun setDates(u: Uri, create: Long, mod: Long) {
        try {
            contentResolver.update(u, ContentValues().apply {
                put(MediaStore.Video.Media.DATE_TAKEN, create)
                put(MediaStore.Video.Media.DATE_MODIFIED, mod / 1000)
            }, null, null)
        } catch (_: Exception) {}
    }

    private fun patchFd(p: ParcelFileDescriptor, ms: Long) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(p.dup()).use { ins ->
                ParcelFileDescriptor.AutoCloseOutputStream(p.dup()).use { outs -> patchMp4(ins.channel, outs.channel, ms) }
            }
        } catch (_: Exception) {}
    }

    private fun patchFile(f: File, ms: Long) {
        try { RandomAccessFile(f, "rw").use { patchMp4(it.channel, it.channel, ms) } } catch (_: Exception) {}
    }

    private fun patchMp4(rd: FileChannel, wr: FileChannel, unixMs: Long) {
        val t = unixMs / 1000 + 2082844800L
        val size = rd.size()
        var pos = 0L
        val hdr = ByteBuffer.allocate(16)
        while (pos + 8 <= size) {
            hdr.clear()
            val n = rd.read(hdr, pos); if (n < 8) return
            var boxSize = hdr.getInt(0).toLong() and 0xFFFFFFFFL
            val type = hdr.getInt(4)
            var headerLen = 8
            if (boxSize == 1L) { if (n < 16) return; boxSize = hdr.getLong(8); headerLen = 16 }
            else if (boxSize == 0L) boxSize = size - pos
            if (boxSize < headerLen) return
            if (type == 0x6D6F6F76) {
                if (boxSize > 64L * 1024 * 1024) return
                val len = boxSize.toInt()
                val arr = ByteArray(len)
                val bb = ByteBuffer.wrap(arr)
                while (bb.hasRemaining()) { if (rd.read(bb, pos + bb.position()) < 0) return }
                patchBoxes(arr, headerLen, len, t)
                val out = ByteBuffer.wrap(arr)
                var wp = pos
                while (out.hasRemaining()) wp += wr.write(out, wp)
                try { wr.force(true) } catch (_: Exception) {}
                return
            }
            pos += boxSize
        }
    }

    private fun i32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)

    private fun patchBoxes(b: ByteArray, start: Int, end: Int, t: Long) {
        var o = start
        while (o + 8 <= end) {
            val sz = i32(b, o)
            if (sz < 8 || o + sz > end) return
            when (i32(b, o + 4)) {
                0x6D766864, 0x746B6864, 0x6D646864 -> setTimes(b, o + 8, t)
                0x7472616B, 0x6D646961 -> patchBoxes(b, o + 8, o + sz, t)
            }
            o += sz
        }
    }

    private fun put(b: ByteArray, o: Int, v: Long, n: Int) { for (i in 0 until n) b[o + i] = (v shr (8 * (n - 1 - i))).toByte() }

    private fun setTimes(b: ByteArray, p: Int, t: Long) {
        if (b[p].toInt() == 1) { put(b, p + 4, t, 8); put(b, p + 12, t, 8) }
        else { val v = t and 0xFFFFFFFFL; put(b, p + 4, v, 4); put(b, p + 8, v, 4) }
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

    override fun onDestroy() {
        runId++
        transformer?.cancel(); discardOutput(); active = false
        wake?.takeIf { it.isHeld }?.release(); super.onDestroy()
    }
}
