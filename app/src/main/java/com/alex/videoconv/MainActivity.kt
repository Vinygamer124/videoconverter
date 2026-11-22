package com.alex.videoconv

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes

class MainActivity : ComponentActivity() {
    private val codecs = listOf("H.264" to MimeTypes.VIDEO_H264, "H.265 (HEVC)" to MimeTypes.VIDEO_H265, "AV1" to MimeTypes.VIDEO_AV1)
    private val rates = listOf("2 Mbps" to 2_000_000, "4 Mbps" to 4_000_000, "8 Mbps" to 8_000_000, "16 Mbps" to 16_000_000)
    private lateinit var list: TextView
    private lateinit var codecSp: Spinner
    private lateinit var rateSp: Spinner

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val c = codecs[codecSp.selectedItemPosition]; val r = rates[rateSp.selectedItemPosition].second
        uris.forEach { u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            ConvertService.jobs.add(Job(u, nameOf(u), c.second, r))
        }
        if (uris.isNotEmpty()) {
            ContextCompat.startForegroundService(this, Intent(this, ConvertService::class.java))
            render()
        }
    }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun nameOf(u: Uri): String =
        contentResolver.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: "video"

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 100, 40, 40) }
        root.addView(TextView(this).apply { text = "Video Converter"; textSize = 24f })
        root.addView(TextView(this).apply { text = "Codec đầu ra" })
        codecSp = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, codecs.map { it.first }) }
        root.addView(codecSp)
        root.addView(TextView(this).apply { text = "Bitrate" })
        rateSp = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, rates.map { it.first }); setSelection(2) }
        root.addView(rateSp)
        root.addView(Button(this).apply { text = "Chọn video & thêm vào hàng đợi"; setOnClickListener { pick.launch(arrayOf("video/*")) } })
        root.addView(Button(this).apply { text = "Xoá các mục đã xong/lỗi"; setOnClickListener {
            ConvertService.jobs.removeAll { it.state == "Xong" || it.state.startsWith("Lỗi") }; render() } })
        list = TextView(this).apply { textSize = 15f }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); ConvertService.onChange = { runOnUiThread { render() } }; render() }
    override fun onPause() { super.onPause(); ConvertService.onChange = null }

    private fun render() {
        val names = mapOf(MimeTypes.VIDEO_H264 to "H.264", MimeTypes.VIDEO_H265 to "H.265", MimeTypes.VIDEO_AV1 to "AV1")
        list.text = ConvertService.jobs.joinToString("\n\n") {
            "${it.name} → ${names[it.mime]}\n${it.state}" + if (it.state == "Đang chạy") " ${it.progress}%" else ""
        }.ifEmpty { "Hàng đợi trống. Lưu tại Movies/Converted." }
    }
}
