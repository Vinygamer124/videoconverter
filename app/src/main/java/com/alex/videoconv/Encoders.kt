package com.alex.videoconv

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.EncoderSelector
import com.google.common.collect.ImmutableList

// Liệt kê, kiểm tra trước và chọn bộ mã hoá phần cứng / phần mềm
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object Enc {
    class Info(val hw: List<String>, val sw: List<String>)
    class Check(val ok: Boolean, val msg: String, val bitrate: Int, val name: String? = null,
                val bitrateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

    private fun codecs(mime: String, encoder: Boolean): List<MediaCodecInfo> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { c ->
            c.isEncoder == encoder && c.supportedTypes.any { it.equals(mime, true) }
        }
    } catch (_: Exception) { emptyList() }

    private fun isHw(c: MediaCodecInfo) = c.isHardwareAccelerated && !c.isSoftwareOnly

    // mode: 0 = tự động (ưu tiên phần cứng), 1 = phần cứng, 2 = phần mềm
    private fun pool(mime: String, mode: Int): List<MediaCodecInfo> {
        val all = codecs(mime, true)
        return when (mode) {
            1 -> all.filter { isHw(it) }
            2 -> all.filter { it.isSoftwareOnly }
            else -> all.sortedByDescending { isHw(it) }
        }
    }

    fun list(mime: String): Info {
        val all = codecs(mime, true)
        return Info(all.filter { isHw(it) }.map { it.name }, all.filter { it.isSoftwareOnly }.map { it.name })
    }

    // Máy có bộ giải mã cho định dạng của video gốc không (vd. AV1 trên máy đời cũ)
    fun canDecode(mime: String): Boolean = try { codecs(mime, false).isNotEmpty() } catch (_: Throwable) { true }

    private fun label(mime: String) = when (mime) { MimeTypes.VIDEO_H265 -> "H.265"; MimeTypes.VIDEO_AV1 -> "AV1"; else -> "H.264" }

    // Kiểm tra encoder có làm được độ phân giải / fps / bitrate này không TRƯỚC khi chạy,
    // để báo lỗi dễ hiểu thay vì để codec lỗi hoặc làm app dừng đột ngột.
    fun preflight(mime: String, mode: Int, w: Int, h: Int, fps: Double, br: Int, wantCbr: Boolean): Check {
        val pool = pool(mime, mode)
        if (pool.isEmpty()) return Check(false, "Máy không có encoder ${label(mime)} theo chế độ đã chọn.", br)
        val strict = mime == MimeTypes.VIDEO_AV1          // AV1 là codec dễ lỗi nhất nên kiểm tra kỹ hơn
        val cbrM = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        val vbrM = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        var why = ""
        for (ci in pool) {
            try {
                val caps = ci.getCapabilitiesForType(mime)
                val vc = caps.videoCapabilities ?: continue
                if (w > 0 && h > 0) {
                    if (!(vc.isSizeSupported(w, h) || vc.isSizeSupported(h, w))) {
                        why = "${ci.name} không hỗ trợ ${w}×${h} (tối đa ${vc.supportedWidths.upper}×${vc.supportedHeights.upper}). Thử bộ mã hoá hoặc codec khác."
                        continue
                    }
                    if (strict && fps > 0 && !(vc.areSizeAndRateSupported(w, h, fps) || vc.areSizeAndRateSupported(h, w, fps))) {
                        why = "${ci.name} không đủ sức mã hoá ${w}×${h} ở ${fmtFps(fps)} fps. Hãy giảm FPS trong Cài đặt."
                        continue
                    }
                }
                val ec = caps.encoderCapabilities
                val cbr = ec.isBitrateModeSupported(cbrM); val vbr = ec.isBitrateModeSupported(vbrM)
                val bm = when {
                    wantCbr && cbr -> cbrM
                    vbr -> vbrM
                    cbr -> cbrM
                    else -> { why = "${ci.name} không hỗ trợ chế độ bitrate CBR/VBR."; continue }
                }
                val b = br.coerceIn(vc.bitrateRange.lower, vc.bitrateRange.upper)
                return Check(true, "", b, ci.name, bm)
            } catch (e: Throwable) { why = "${ci.name}: ${e.message}" }
        }
        return Check(false, why.ifEmpty { "Không có encoder ${label(mime)} phù hợp." }, br)
    }

    // pinned: ép dùng đúng encoder đã kiểm tra ở preflight
    fun selector(mode: Int, pinned: String? = null): EncoderSelector {
        if (pinned == null && mode == 0) return EncoderSelector.DEFAULT
        return EncoderSelector { mime ->
            val l = pool(mime, mode)
            ImmutableList.copyOf(if (pinned != null) l.filter { it.name == pinned }.ifEmpty { l } else l)
        }
    }
}
