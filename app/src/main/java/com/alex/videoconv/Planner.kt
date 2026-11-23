package com.alex.videoconv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.media3.common.MimeTypes
import java.util.Locale

// ---------- Định dạng ----------
fun fmtDur(sec: Double): String {
    val s = Math.max(0L, Math.round(sec))
    val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return if (h > 0) "${h}g ${m}p ${x}s" else if (m > 0) "${m}p ${x}s" else "${x}s"
}

fun humanSize(b: Long): String {
    val v = b.toDouble()
    return when {
        v >= 1073741824.0 -> String.format(Locale.US, "%.2f GB", v / 1073741824.0)
        v >= 1048576.0 -> String.format(Locale.US, "%.0f MB", v / 1048576.0)
        else -> String.format(Locale.US, "%.0f KB", v / 1024.0)
    }
}

fun deltaText(now: Long, src: Long): String {
    if (src <= 0) return ""
    val d = Math.round(now * 100.0 / src - 100.0).toInt()
    return if (d <= 0) "−${-d}%" else "+$d%"
}

// ---------- Mức nén ----------
object Levels {
    val names = listOf("Giữ chất lượng", "Cân bằng", "Nhỏ gọn")
    val factor = doubleArrayOf(1.15, 0.85, 0.60)
    val floorF = doubleArrayOf(1.0, 0.75, 0.5)
}

class Plan(val bitrate: Int, val durSec: Double, val srcBytes: Long, val estBytes: Long,
           val w: Int = 0, val h: Int = 0, val fps: Double = 0.0, val srcVideoBps: Double = 0.0,
           val srcMime: String = MimeTypes.VIDEO_H264)

fun fmtFps(f: Double): String =
    if (Math.abs(f - Math.round(f)) < 0.05) Math.round(f).toString() else String.format(Locale.US, "%.2f", f)

object Planner {
    private val eff = mapOf(MimeTypes.VIDEO_H264 to 1.0, MimeTypes.VIDEO_H265 to 0.70, MimeTypes.VIDEO_AV1 to 0.60)

    // Bitrate tự động theo video gốc (như planBitrate của bản HTML) + mức nén + giới hạn tối đa
    fun plan(ctx: Context, uri: Uri, mime: String, level: Int, capMbps: Double): Plan {
        var w = 0; var h = 0; var durMs = 0L; var size = 0L
        var srcMime = MimeTypes.VIDEO_H264; var hasAudio = false
        var frames = 0L; var fpsFmt = 0.0; var videoBps = 0.0
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
            } finally { r.release() }
        } catch (_: Exception) {}
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) size = it.getLong(0)
            }
        } catch (_: Exception) {}
        try {
            val e = MediaExtractor()
            try {
                e.setDataSource(ctx, uri, null)
                var gotVideo = false
                for (i in 0 until e.trackCount) {
                    val f = e.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/") && !gotVideo) {
                        srcMime = m; gotVideo = true
                        if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) fpsFmt =
                            try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                            catch (_: Exception) { try { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() } catch (_: Exception) { 0.0 } }
                    }
                    if (m.startsWith("audio/")) hasAudio = true
                }
            } finally { e.release() }
        } catch (_: Exception) {}

        val lv = level.coerceIn(0, 2)
        val effT = eff[mime] ?: 1.0
        var br: Double
        if (durMs <= 0 || size <= 0) br = 8_000_000.0
        else {
            val total = size * 8.0 / (durMs / 1000.0)
            val video = if (hasAudio) Math.max(total - 160_000, total * 0.8) else total
            videoBps = video
            val ratio = effT / (eff[srcMime] ?: 1.0)
            val floor = w.toDouble() * h * 0.6 * effT * Levels.floorF[lv]
            br = Math.min(video, Math.max(floor, video * ratio * Levels.factor[lv]))
        }
        if (capMbps > 0) br = Math.min(br, capMbps * 1_000_000)
        val bi = br.toInt().coerceIn(500_000, 80_000_000)
        val sec = durMs / 1000.0
        val est = if (durMs > 0) (bi * sec / 8 + (if (hasAudio) 160_000 * sec / 8 else 0.0)).toLong() else 0L
        // FPS thật: ưu tiên số khung hình / thời lượng (chính xác với 29.97, 59.94), không có thì lấy từ metadata
        val measured = if (frames > 0 && durMs > 0) frames * 1000.0 / durMs else 0.0
        val fps = if (measured in 1.0..240.0) measured else fpsFmt
        return Plan(bi, sec, size, est, w, h, fps, videoBps, srcMime)
    }

    // Gợi ý Mbps theo độ phân giải, FPS, codec và mức nén; không vượt bitrate video gốc
    fun recommendMbps(w: Int, h: Int, fps: Double, mime: String, level: Int, srcVideoBps: Double): Double {
        val ww = if (w > 0) w else 1920; val hh = if (h > 0) h else 1080; val ff = if (fps > 0) fps else 30.0
        val bpp = when (mime) { MimeTypes.VIDEO_H265 -> 0.105; MimeTypes.VIDEO_AV1 -> 0.09; else -> 0.15 }   // bit / điểm ảnh / khung
        var m = ww.toDouble() * hh * ff * bpp * Levels.factor[level.coerceIn(0, 2)] / 0.85 / 1e6
        if (srcVideoBps > 0) m = Math.min(m, srcVideoBps / 1e6)
        m = m.coerceIn(1.0, 80.0)
        return if (m >= 10) Math.round(m).toDouble() else Math.round(m * 2) / 2.0
    }

    fun thumb(ctx: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val bm = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val s = Math.min(bm.width, bm.height)
            val sq = Bitmap.createBitmap(bm, (bm.width - s) / 2, (bm.height - s) / 2, s, s)
            val out = Bitmap.createScaledBitmap(sq, 192, 192, true)
            if (sq !== bm) bm.recycle()
            if (out !== sq) sq.recycle()
            out
        } catch (_: Throwable) { null } finally { try { r.release() } catch (_: Exception) {} }
    }
}

// ---------- ETA hai mốc A – B (port từ bản HTML) ----------
class EtaText(val t: String, val atMs: Long, val conf: String)

class Eta {
    private class S(val t: Double, val r: Double)
    private val s = ArrayList<S>()
    private val inst = ArrayList<Double>()
    private var est = -1.0
    private var estAt = 0L
    var disp = -1.0; private set
    private var last = 0L
    var p = 0.0; private set
    var f = 0.6; private set

    fun reset(nowMs: Long) { s.clear(); inst.clear(); est = -1.0; estAt = 0L; disp = -1.0; last = nowMs; p = 0.0; f = 0.6 }

    // t = giây đã chạy, r = tiến độ 0..1
    fun push(t: Double, r: Double) {
        p = r
        val l = s.lastOrNull()
        if (r <= 0 || (l != null && (r <= l.r || t - l.t < 0.5))) return
        if (l != null && t - l.t >= 1) { inst.add((r - l.r) / (t - l.t)); if (inst.size > 24) inst.removeAt(0) }
        s.add(S(t, r))
        if (s.size > 800) repeat(100) { s.removeAt(1) }
        compute()
    }

    private fun compute() {
        val i0 = s.indexOfFirst { it.r >= 0.01 || it.t >= 3 }
        if (i0 < 0 || i0 >= s.size - 1) return
        val a = s[i0]; val z = s[s.size - 1]
        if (z.t - a.t < 4) return                       // chưa đủ dữ liệu
        val ro = (z.r - a.r) / (z.t - a.t)
        val w0 = Math.max(10.0, (z.t - a.t) * 0.2)
        val j = s.indexOfFirst { it.t >= z.t - w0 && it.t >= a.t }
        val b = s[Math.min(if (j < 0) i0 else j, s.size - 2)]
        val rr = (z.r - b.r) / (z.t - b.t)
        val w = Math.min(0.7, z.r * 3)
        val rate = ro * (1 - w) + rr * w
        if (!(rate > 0)) return
        est = (1 - z.r) / rate; estAt = SystemClock.elapsedRealtime()
        var cv = 0.4; val n = inst.size
        if (n >= 3) {
            val m = inst.sum() / n
            var v = 0.0; for (x in inst) v += (x - m) * (x - m)
            cv = Math.sqrt(v / n) / m / Math.sqrt(n.toDouble())
        }
        f = Math.min(0.6, Math.max(0.03, cv + 0.3 * Math.max(0.0, 1 - z.r / 0.1)))
    }

    fun tick(now: Long) {
        val dt = (now - last) / 1000.0; last = now
        if (est < 0) return
        val cur = Math.max(0.0, est - (now - estAt) / 1000.0)
        if (disp < 0) disp = cur
        else {
            disp = Math.max(0.0, disp - dt)
            val k = 0.05 + 0.3 * Math.min(1.0, p / 0.3)   // càng về sau càng tin ước tính mới
            disp += Math.min(1.0, k * dt) * (cur - disp)
        }
    }

    fun text(): EtaText {
        if (p >= 0.985) return EtaText("sắp xong", 0L, "Cao")
        val d = disp
        if (d < 0) return EtaText("đang đo tốc độ…", 0L, "–")
        val q = if (d > 3600) 300 else if (d > 600) 60 else if (d > 120) 15 else if (d > 30) 5 else 1
        fun rq(x: Double): String = fmtDur(Math.max(q.toDouble(), Math.round(x / q) * q.toDouble()))
        val t = if (f > 0.12) "${rq(d * (1 - f))} – ${rq(d * (1 + f))}" else "~${rq(d)}"
        val at = System.currentTimeMillis() + (d * 1000).toLong()
        return EtaText(t, at, if (f > 0.25) "Thấp" else if (f > 0.1) "Trung bình" else "Cao")
    }
}

// ---------- Dự đoán dung lượng file đầu ra theo tiến độ hiện tại ----------
class SizePred {
    private val rs = ArrayList<Double>()
    private val bs = ArrayList<Long>()
    var prior = 0L
    var now = 0L; private set

    fun reset(prior: Long) { rs.clear(); bs.clear(); this.prior = prior; now = 0L }

    fun push(r: Double, bytes: Long) {
        now = bytes
        if (r <= 0 || bytes <= 0) return
        if (rs.isNotEmpty() && r <= rs[rs.size - 1]) return
        rs.add(r); bs.add(bytes)
        if (rs.size > 600) { rs.removeAt(1); bs.removeAt(1) }
    }

    // [thấp, giữa, cao] hoặc null nếu chưa có gì để dự đoán
    fun est(): LongArray? {
        val n = rs.size
        if (prior <= 0 && n < 2) return null
        val r = if (n > 0) rs[n - 1] else 0.0
        val b = if (n > 0) bs[n - 1].toDouble() else 0.0
        var meas = -1.0
        if (n >= 2 && r >= 0.01 && b > 0) {
            val global = b / r
            val span = Math.max(0.03, r * 0.2)
            var j = n - 1
            while (j > 0 && rs[j] > r - span) j--
            val dr = r - rs[j]; val db = b - bs[j]
            val recent = if (dr > 0 && db > 0) db / dr else global
            val w = Math.min(0.7, r * 3)
            meas = b + (1 - r) * (global * (1 - w) + recent * w)
        }
        val m = Math.min(1.0, r / 0.1)             // đầu job tin vào dự tính theo bitrate, về sau tin số đo thật
        val mid = if (meas > 0 && prior > 0) prior * (1 - m) + meas * m else if (meas > 0) meas else prior.toDouble()
        val g = Math.max(0.03, 0.35 * (1 - r)) + (if (meas < 0) 0.15 else 0.0)
        val low = Math.max(mid * (1 - g), b)
        return longArrayOf(low.toLong(), Math.max(mid, b).toLong(), (mid * (1 + g)).toLong())
    }
}
