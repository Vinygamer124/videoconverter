package com.alex.videoconv

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MimeTypes
import java.text.SimpleDateFormat
import java.util.*

// Chọn nhiều video, xin quyền ghi để còn xoá được file gốc
class PickVideos : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

// Thanh tiến trình: chạy mượt giữa các lần cập nhật, đổ màu gradient, có chế độ "đang khởi động"
class Bar(ctx: Context, private val track: Int, private val c1: Int, private val c2: Int) : View(ctx) {
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private var shim: ValueAnimator? = null
    private var phase = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    var indeterminate = false
        set(v) {
            if (field == v) return
            field = v
            shim?.cancel()
            if (v) shim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start()
            }
            invalidate()
        }

    fun set(v: Float, animate: Boolean = true) {
        val t = v.coerceIn(0f, 1f)
        anim?.cancel()
        if (!animate || width == 0 || t < shown) { shown = t; invalidate(); return }
        anim = ValueAnimator.ofFloat(shown, t).apply {
            duration = 500; interpolator = LinearInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        p.shader = null; p.color = track
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        if (indeterminate) {
            clip.reset(); clip.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            val seg = w * 0.35f; val x = (w + seg) * phase - seg
            p.shader = LinearGradient(x, 0f, x + seg, 0f, c1, c2, Shader.TileMode.CLAMP)
            c.drawRoundRect(x, 0f, x + seg, h, r, r, p)
            c.restore()
        } else if (shown > 0f) {
            val fw = Math.max(h, w * shown)
            p.shader = LinearGradient(0f, 0f, fw, 0f, c1, c2, Shader.TileMode.CLAMP)
            c.drawRoundRect(0f, 0f, fw, h, r, r, p)
        }
        p.shader = null
    }

    override fun onDetachedFromWindow() { anim?.cancel(); shim?.cancel(); super.onDetachedFromWindow() }
}

class MainActivity : ComponentActivity() {
    private val codecs = listOf("H.264" to MimeTypes.VIDEO_H264, "H.265" to MimeTypes.VIDEO_H265, "AV1" to MimeTypes.VIDEO_AV1)
    private val speeds = listOf("Chuẩn", "Nhanh")

    private val night by lazy { (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
    private val cBg by lazy { if (night) 0xFF0E1013.toInt() else 0xFFF5F6F8.toInt() }
    private val cSf by lazy { if (night) 0xFF171A1F.toInt() else 0xFFFFFFFF.toInt() }
    private val cTx by lazy { if (night) 0xFFECEFF4.toInt() else 0xFF14161A.toInt() }
    private val cMu by lazy { if (night) 0xFF8E97A6.toInt() else 0xFF7A8190.toInt() }
    private val cBd by lazy { if (night) 0xFF272C35.toInt() else 0xFFE4E7EC.toInt() }
    private val cAc by lazy { if (night) 0xFF7B8FFF.toInt() else 0xFF4F6BFF.toInt() }
    private val cAc2 by lazy { if (night) 0xFFB79CFF.toInt() else 0xFF8B5CF6.toInt() }
    private val cOk by lazy { if (night) 0xFF4ADE80.toInt() else 0xFF16A34A.toInt() }
    private val cEr by lazy { if (night) 0xFFF87171.toInt() else 0xFFDC2626.toInt() }

    private lateinit var prefs: android.content.SharedPreferences
    private var codecIdx = 1
    private var speedIdx = 0
    private var delOrig = false
    private var outDir: Uri? = null

    private lateinit var folderTv: TextView
    private lateinit var sumCard: LinearLayout
    private lateinit var sumPct: TextView
    private lateinit var sumEta: TextView
    private lateinit var sumBar: Bar
    private lateinit var sumStats: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var btnAdd: TextView
    private lateinit var btnMain: TextView

    private val pick = registerForActivityResult(PickVideos()) { uris ->
        uris.forEach { u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            catch (_: Exception) { try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
            ConvertService.jobs.add(Job(u, nameOf(u), codecs[codecIdx].second, speedIdx == 1, outDir, delOrig))
        }
        render()   // chỉ thêm vào hàng đợi, bấm "Bắt đầu" mới chạy
    }
    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            outDir = u; prefs.edit().putString("outDir", u.toString()).apply(); updateFolder()
        }
    }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun nameOf(u: Uri): String =
        contentResolver.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: "video"

    private fun shape(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = shape(cSf, 14, cBd); setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun seg(labels: List<String>, sel: Int, on: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { background = shape(cBg, 10, cBd); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val views = ArrayList<TextView>()
        fun paint(s: Int) = views.forEachIndexed { i, t ->
            t.background = if (i == s) shape(cAc, 8) else null
            t.setTextColor(if (i == s) Color.WHITE else cTx)
        }
        labels.forEachIndexed { i, l ->
            val t = tv(l, 13f, cTx, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { paint(i); on(i) }
            }
            views.add(t); box.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(sel); return box
    }

    private fun bar(v: Float) = Bar(this, cBd, cAc, cAc2).apply { set(v, false) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!ConvertService.active)
            cacheDir.listFiles()?.filter { it.name.startsWith("out_") }?.forEach { it.delete() }
        codecIdx = prefs.getInt("codec", 1); speedIdx = prefs.getInt("speed", 0); delOrig = prefs.getBoolean("del", false)
        outDir = prefs.getString("outDir", null)?.let { Uri.parse(it) }
        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16), b.top + dp(14), dp(16), b.bottom + dp(14)); ins
        }

        root.addView(tv("Video Converter", 26f, cTx, true))
        root.addView(tv("Nén & đổi codec video ngay trên máy", 13f, cMu), lp(top = 2))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }
        root.addView(scroll, lp(h = 0, top = 12).apply { weight = 1f })

        // Thẻ cài đặt
        val set = card()
        set.addView(tv("Codec đầu ra", 12f, cMu, true))
        set.addView(seg(codecs.map { it.first }, codecIdx) { codecIdx = it; prefs.edit().putInt("codec", it).apply() }, lp(top = 6))
        set.addView(tv("Chế độ", 12f, cMu, true), lp(top = 14))
        set.addView(seg(speeds, speedIdx) { speedIdx = it; prefs.edit().putInt("speed", it).apply() }, lp(top = 6))
        set.addView(tv("Chuẩn: ưu tiên chất lượng · Nhanh: mã hoá nhanh hơn, dung lượng có thể nhỉnh hơn. Bitrate tự tính theo video gốc.", 12f, cMu).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))
        set.addView(tv("Thư mục lưu", 12f, cMu, true), lp(top = 14))
        val fRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = shape(cBg, 10, cBd); setPadding(dp(12), dp(11), dp(12), dp(11))
            setOnClickListener { pickDir.launch(null) }
        }
        folderTv = tv("", 14f, cTx).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        fRow.addView(folderTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fRow.addView(tv("Đổi", 13f, cAc, true).apply { setPadding(dp(10), 0, dp(4), 0) })
        set.addView(fRow, lp(top = 6))
        set.addView(tv("Dùng mặc định (Movies/Converted)", 12f, cMu).apply {
            setPadding(0, dp(8), 0, dp(2))
            setOnClickListener { outDir = null; prefs.edit().remove("outDir").apply(); updateFolder() }
        })
        val dRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val dTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dTxt.addView(tv("Tự xoá file gốc", 14f, cTx, true))
        dTxt.addView(tv("Chỉ xoá sau khi kiểm tra file mới đủ thời lượng", 12f, cMu))
        dRow.addView(dTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply { isChecked = delOrig }
        sw.setOnCheckedChangeListener { b, on ->
            if (on) {
                AlertDialog.Builder(this).setTitle("Bật tự xoá file gốc?")
                    .setMessage("Video gốc sẽ bị xoá vĩnh viễn sau khi chuyển đổi xong. Áp dụng khi bấm Bắt đầu.")
                    .setPositiveButton("Bật") { _, _ -> delOrig = true; prefs.edit().putBoolean("del", true).apply() }
                    .setNegativeButton("Huỷ") { _, _ -> b.isChecked = false }
                    .setOnCancelListener { b.isChecked = false }.show()
            } else { delOrig = false; prefs.edit().putBoolean("del", false).apply() }
        }
        dRow.addView(sw)
        set.addView(dRow, lp(top = 12))
        content.addView(set)

        // Thẻ tiến trình
        sumCard = card().apply { visibility = View.GONE }
        val head = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        sumPct = tv("0%", 34f, cTx, true)
        sumEta = tv("", 15f, cAc, true).apply { gravity = Gravity.END; setPadding(0, 0, 0, dp(6)) }
        head.addView(sumPct, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(sumEta)
        sumBar = bar(0f)
        sumStats = tv("", 13f, cMu).apply { setLineSpacing(0f, 1.25f) }
        sumCard.addView(head)
        sumCard.addView(sumBar, lp(h = dp(10), top = 6))
        sumCard.addView(sumStats, lp(top = 10))
        content.addView(sumCard, lp(top = 12))

        // Hàng đợi
        val qh = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        qh.addView(tv("Hàng đợi", 15f, cTx, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qh.addView(tv("Xoá mục đã xong", 13f, cAc, true).apply {
            setPadding(dp(8), dp(6), 0, dp(6))
            setOnClickListener { ConvertService.jobs.removeAll { it.state == St.DONE || it.state == St.ERR }; render() }
        })
        content.addView(qh, lp(top = 16))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listBox, lp(top = 6))

        // Hàng nút: Thêm video + Bắt đầu / Dừng
        val bottom = LinearLayout(this)
        btnAdd = tv("+  Thêm video", 15f, cAc, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(15), 0, dp(15))
            setOnClickListener { pick.launch(arrayOf("video/*")) }
        }
        btnMain = tv("", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(15), 0, dp(15)); visibility = View.GONE
            setOnClickListener { onMainClick() }
        }
        bottom.addView(btnAdd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(btnMain, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply { marginStart = dp(10) })
        root.addView(bottom, lp(top = 12))

        setContentView(root)
        try {
            WindowCompat.getInsetsController(window, root).apply {
                isAppearanceLightStatusBars = !night; isAppearanceLightNavigationBars = !night
            }
        } catch (_: Exception) {}
        updateFolder()
    }

    private fun onMainClick() {
        if (ConvertService.active) {
            AlertDialog.Builder(this).setTitle("Dừng chuyển đổi?")
                .setMessage("File đang chạy sẽ bị huỷ và quay về hàng đợi. File đã xong được giữ nguyên.")
                .setPositiveButton("Dừng") { _, _ ->
                    startService(Intent(this, ConvertService::class.java).setAction(ConvertService.ACT_STOP))
                }
                .setNegativeButton("Tiếp tục", null).show()
        } else {
            ConvertService.jobs.filter { it.state == St.WAIT }.forEach {   // áp cài đặt hiện tại cho các file đang chờ
                it.mime = codecs[codecIdx].second; it.fast = speedIdx == 1; it.outDir = outDir; it.deleteOriginal = delOrig
            }
            ConvertService.active = true
            ContextCompat.startForegroundService(this, Intent(this, ConvertService::class.java))
            render()
        }
    }

    private fun updateFolder() {
        val d = outDir
        folderTv.text = if (d == null) "Movies/Converted (mặc định)" else {
            val id = try { android.provider.DocumentsContract.getTreeDocumentId(d) } catch (_: Exception) { d.lastPathSegment ?: "" }
            id.substringAfter(':', id).ifEmpty { "Bộ nhớ trong" }
        }
    }

    override fun onResume() { super.onResume(); ConvertService.onChange = { runOnUiThread { render() } }; render() }
    override fun onPause() { super.onPause(); ConvertService.onChange = null }

    private fun render() {
        val jobs = ConvertService.jobs.toList()
        val running = ConvertService.active
        val waiting = jobs.count { it.state == St.WAIT }

        // Nút chính
        if (running) {
            btnMain.visibility = View.VISIBLE; btnMain.text = "■  Dừng"; btnMain.background = shape(cEr, 14)
            btnAdd.setTextColor(cAc); btnAdd.background = shape(cSf, 14, cBd)
        } else if (waiting > 0) {
            btnMain.visibility = View.VISIBLE; btnMain.text = "▶  Bắt đầu ($waiting)"; btnMain.background = shape(cAc, 14)
            btnAdd.setTextColor(cAc); btnAdd.background = shape(cSf, 14, cBd)
        } else {
            btnMain.visibility = View.GONE
            btnAdd.setTextColor(Color.WHITE); btnAdd.background = shape(cAc, 14)
        }

        // Tổng quan job đang chạy
        val cur = jobs.firstOrNull { it.state == St.RUN || it.state == St.SAVE }
        if (cur == null) { sumCard.visibility = View.GONE; sumBar.indeterminate = false } else {
            sumCard.visibility = View.VISIBLE
            sumPct.text = "${cur.progress}%"
            sumBar.indeterminate = cur.state == St.RUN && cur.progress == 0
            sumBar.set(cur.progress / 100f)
            val el = (SystemClock.elapsedRealtime() - cur.t0) / 1000.0
            sumEta.text = when {
                cur.state == St.SAVE -> "đang lưu…"
                cur.progress >= 98 -> "sắp xong"
                cur.etaSec < 0 -> "đang đo tốc độ…"
                else -> "còn ~${fmtDur(cur.etaSec)}"
            }
            val fin = if (cur.state == St.RUN && cur.etaSec >= 0 && cur.progress < 98)
                " · xong lúc " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (cur.etaSec * 1000).toLong())) else ""
            val spd = if (el > 3 && cur.progress > 0 && cur.durSec > 0) " · %.2f× tốc độ thật".format(cur.progress / 100.0 * cur.durSec / el) else ""
            val w = jobs.count { it.state == St.WAIT }
            sumStats.text = "File ${jobs.indexOf(cur) + 1}/${jobs.size} · ${cur.name}\nĐã chạy ${fmtDur(el)}$fin$spd" +
                if (w > 0) "\n$w file đang chờ" else ""
        }

        // Danh sách
        listBox.removeAllViews()
        if (jobs.isEmpty()) {
            listBox.addView(tv("Chưa có video nào.\nBấm “Thêm video”, rồi bấm “Bắt đầu” khi sẵn sàng.", 13f, cMu).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(28), 0, dp(28)) })
            return
        }
        jobs.forEach { j ->
            val c = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(tv(j.name, 14f, cTx, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (j.state != St.RUN && j.state != St.SAVE) {
                top.addView(tv("✕", 16f, cMu).apply {
                    setPadding(dp(10), dp(2), dp(2), dp(2))
                    setOnClickListener { ConvertService.jobs.remove(j); render() }
                })
            }
            c.addView(top)
            val (txt, col) = when (j.state) {
                St.WAIT -> (if (running) "Trong hàng đợi" else "Sẵn sàng · bấm Bắt đầu") to cMu
                St.RUN -> "Đang chuyển đổi · ${j.progress}%" + (if (j.etaSec >= 0) " · còn ~${fmtDur(j.etaSec)}" else "") to cAc
                St.SAVE -> "Đang lưu…" to cAc
                St.DONE -> "Xong ✓ · ${j.msg}" to cOk
                St.ERR -> j.msg to cEr
            }
            c.addView(tv(txt, 12f, col).apply { setLineSpacing(0f, 1.2f) }, lp(top = 3))
            if (j.state == St.RUN || j.state == St.SAVE) c.addView(bar(j.progress / 100f), lp(h = dp(5), top = 8))
            listBox.addView(c, lp(top = 6))
        }
    }
}
