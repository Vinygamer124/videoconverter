package com.alex.videoconv

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.transformer.EncoderSelector
import com.google.common.collect.ImmutableList

// Liệt kê và chọn bộ mã hoá phần cứng / phần mềm
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object Enc {
    class Info(val hw: List<String>, val sw: List<String>)

    private fun encoders(mime: String): List<MediaCodecInfo> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { c ->
            c.isEncoder && c.supportedTypes.any { it.equals(mime, true) }
        }
    } catch (_: Exception) { emptyList() }

    fun list(mime: String): Info {
        val all = encoders(mime)
        return Info(
            all.filter { it.isHardwareAccelerated && !it.isSoftwareOnly }.map { it.name },
            all.filter { it.isSoftwareOnly }.map { it.name }
        )
    }

    // mode: 0 = tự động, 1 = phần cứng, 2 = phần mềm
    fun selector(mode: Int): EncoderSelector = when (mode) {
        1 -> EncoderSelector { mime -> ImmutableList.copyOf(encoders(mime).filter { it.isHardwareAccelerated && !it.isSoftwareOnly }) }
        2 -> EncoderSelector { mime -> ImmutableList.copyOf(encoders(mime).filter { it.isSoftwareOnly }) }
        else -> EncoderSelector.DEFAULT
    }
}
