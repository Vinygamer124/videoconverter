package com.alex.videoconv

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MimeTypes

// Chọn nhiều video, xin quyền ghi để còn xoá được file gốc
class PickVideos : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

// Thanh tiến trình: chạy mượt giữa các lần cập nhật, gradient, có chế độ "đang khởi động"
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

// ScrollView có chiều cao tối đa (dùng cho bảng cài đặt)
class MaxScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
}

class MainActivity : ComponentActivity() {
    private val codecs = listOf("H.264" to MimeTypes.VIDEO_H264, "H.265" to MimeTypes.VIDEO_H265, "AV1" to MimeTypes.VIDEO_AV1)
    private val speeds = listOf("Chuẩn", "Nhanh")
    private val encs = listOf("Tự động", "Phần cứng", "Phần mềm", "libaom")

    private val night by lazy { (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
    private val cBg by lazy { if (night) 0xFF0B0D11.toInt() else 0xFFF3F4F8.toInt() }
    private val cSf by lazy { if (night) 0xFF161A21.toInt() else 0xFFFFFFFF.toInt() }
    private val cSv by lazy { if (night) 0xFF1F242D.toInt() else 0xFFE9ECF4.toInt() }
    private val cTrack by lazy { if (night) 0xFF2B313C.toInt() else 0xFFDDE1EB.toInt() }
    private val cTx by lazy { if (night) 0xFFECEFF4.toInt() else 0xFF14161A.toInt() }
    private val cMu by lazy { if (night) 0xFF8E97A6.toInt() else 0xFF6B7280.toInt() }
    private val cAc by lazy { if (night) 0xFF7B8FFF.toInt() else 0xFF4F6BFF.toInt() }
    private val cAc2 by lazy { if (night) 0xFFB79CFF.toInt() else 0xFF8B5CF6.toInt() }
    private val cOk by lazy { if (night) 0xFF4ADE80.toInt() else 0xFF16A34A.toInt() }
    private val cEr by lazy { if (night) 0xFFF87171.toInt() else 0xFFDC2626.toInt() }
    private val cRip by lazy { if (night) 0x33FFFFFF else 0x1F000000 }

    private lateinit var prefs: android.content.SharedPreferences
    private var codecIdx = 1
    private var speedIdx = 0
    private var levelIdx = 1
    private var encIdx = 0
    private var capMbps = 0.0
    private var fpsCap = 0.0
    private var battDlg: AlertDialog? = null
    private var report: CrashGuard.Verdict? = null
    private var delOrig = false
    private var keepTime = true
    private var outDir: Uri? = null

    private lateinit var chip: TextView
    private lateinit var sumCard: LinearLayout
    private lateinit var sumPhase: TextView
    private lateinit var sumPct: TextView
    private lateinit var sumEtaLbl: TextView
    private lateinit var sumEta: TextView
    private lateinit var sumBar: Bar
    private lateinit var sumSize: TextView
    private lateinit var sumStats: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var btnAdd: TextView
    private lateinit var btnMain: TextView
    private var hwTv: TextView? = null
    private var aomTv: TextView? = null
    private var swTv: TextView? = null
    private var folderTv: TextView? = null
    private val cards = LinkedHashMap<Job, Card>()
    private var btnKey = ""

    private val pick = registerForActivityResult(PickVideos()) { uris ->
        uris.forEach { u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            catch (_: Exception) { try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
            ConvertService.jobs.add(newJob(u))
        }
        refreshEstimates(); render()   // chỉ thêm vào hàng đợi, bấm "Bắt đầu" mới chạy
    }
    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            outDir = u; prefs.edit().putString("outDir", u.toString()).apply(); updateFolder(); updateChip()
        }
    }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // ---------- Tiện ích giao diện ----------
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun nameOf(u: Uri): String =
        contentResolver.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: "video"

    private fun shape(fill: Int, radius: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat() }
    private fun rip(fill: Int, radius: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(cRip), shape(fill, radius), shape(Color.WHITE, radius))
    private fun ripOval(fill: Int): Drawable = RippleDrawable(ColorStateList.valueOf(cRip),
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) },
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun bar(v: Float) = Bar(this, cTrack, cAc, cAc2).apply { set(v, false) }
    private fun fmtNum(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    private fun newJob(u: Uri) = Job(u, nameOf(u), codecs[codecIdx].second, speedIdx == 1, outDir, delOrig).also {
        it.enc = encIdx; it.keepTime = keepTime; it.level = levelIdx; it.capMbps = capMbps; it.fps = fpsCap
    }

    private fun seg(labels: List<String>, sel: Int, on: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { background = shape(cSv, 14); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val views = ArrayList<TextView>()
        fun paint(s: Int) = views.forEachIndexed { i, t ->
            t.background = if (i == s) shape(cAc, 11) else null
            t.setTextColor(if (i == s) Color.WHITE else cTx)
        }
        labels.forEachIndexed { i, l ->
            val t = tv(l, 13f, cTx, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(2), dp(10), dp(2), dp(10)); maxLines = 1
                setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); paint(i); on(i) }
            }
            views.add(t); box.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(sel); return box
    }

    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (android.widget.CompoundButton, Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(tv(title, 15f, cTx, true))
        txt.addView(tv(sub, 12f, cMu), lp(top = 1))
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { b, on -> onChange(b, on) } })
        return row
    }

    private fun section(text: String) = tv(text.uppercase(), 11f, cMu, true).apply { letterSpacing = 0.08f }

    // ---------- Màn hình chính ----------
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        CrashGuard.install(applicationContext)
        if (!ConvertService.active)
            cacheDir.listFiles()?.filter { it.name.startsWith("out_") }?.forEach { it.delete() }
        codecIdx = prefs.getInt("codec", 1); speedIdx = prefs.getInt("speed", 0); levelIdx = prefs.getInt("level", 1)
        encIdx = prefs.getInt("enc", 0); capMbps = prefs.getFloat("cap", 0f).toDouble()
        delOrig = prefs.getBoolean("del", false); keepTime = prefs.getBoolean("keep", true)
        outDir = prefs.getString("outDir", null)?.let { Uri.parse(it) }
        fpsCap = prefs.getFloat("fps", 0f).toDouble()
        // Lần trước app chết giữa lúc mã hoá? Nếu do AV1 crash thì tự chuyển về H.265
        if (!ConvertService.active) report = CrashGuard.check(this)?.also {
            if (it.mime == MimeTypes.VIDEO_AV1 && it.kind == 1) {
                CrashGuard.setAv1Bad(this, true)
                if (codecIdx == 2) { codecIdx = 1; prefs.edit().putInt("codec", 1).apply() }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(cBg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            col.setPadding(0, b.top, 0, b.bottom); ins
        }

        // Thanh tiêu đề
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        val appBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(12), dp(6)) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv("Video Converter", 26f, cTx, true))
        titles.addView(tv("Nén & đổi codec ngay trên máy · v$ver", 12f, cMu))
        appBar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        appBar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tune); setColorFilter(cTx); setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ripOval(cSv); contentDescription = "Cài đặt"
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); openSettings() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(appBar)

        // Dải tóm tắt cài đặt (bấm để mở bảng cài đặt)
        chip = tv("", 13f, cTx, true).apply {
            background = rip(cSv, 18); setPadding(dp(16), dp(11), dp(16), dp(11)); maxLines = 2
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); openSettings() }
        }
        col.addView(chip, lp(top = 6).apply { marginStart = dp(16); marginEnd = dp(16) })

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Thẻ tiến trình đang chạy
        sumCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(cSf, 26); setPadding(dp(20), dp(18), dp(20), dp(18))
            elevation = dp(2).toFloat(); visibility = View.GONE
        }
        sumPhase = tv("", 11f, cAc, true).apply { letterSpacing = 0.1f }
        val head = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        sumPct = tv("0%", 44f, cTx, true)
        val etaCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        sumEtaLbl = tv("", 11f, cMu, true).apply { gravity = Gravity.END; letterSpacing = 0.08f }
        sumEta = tv("", 16f, cTx, true).apply { gravity = Gravity.END }
        etaCol.addView(sumEtaLbl); etaCol.addView(sumEta)
        head.addView(sumPct, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(etaCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12); bottomMargin = dp(6) })
        sumBar = Bar(this, cTrack, cAc, cAc2)
        sumSize = tv("", 14f, cTx, true)
        sumStats = tv("", 12.5f, cMu).apply { setLineSpacing(0f, 1.25f) }
        sumCard.addView(sumPhase); sumCard.addView(head, lp(top = 2))
        sumCard.addView(sumBar, lp(h = dp(12), top = 8))
        sumCard.addView(sumSize, lp(top = 14)); sumCard.addView(sumStats, lp(top = 6))
        content.addView(sumCard, lp(bottom = 0))

        // Hàng đợi
        val qh = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        qh.addView(tv("Hàng đợi", 17f, cTx, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qh.addView(tv("Xoá mục đã xong", 13f, cAc, true).apply {
            background = rip(Color.TRANSPARENT, 12); setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { ConvertService.jobs.removeAll { it.state == St.DONE || it.state == St.ERR }; render() }
        })
        content.addView(qh, lp(top = 14))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutTransition = LayoutTransition().apply { setDuration(180) } }
        content.addView(listBox, lp(top = 4))
        emptyView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(48), 0, dp(48)) }
        emptyView.addView(tv("Chưa có video nào", 16f, cTx, true).apply { gravity = Gravity.CENTER })
        emptyView.addView(tv("Bấm “Thêm video”, chỉnh cài đặt rồi bấm “Bắt đầu”", 13f, cMu).apply { gravity = Gravity.CENTER }, lp(top = 4))
        content.addView(emptyView)

        // Hàng nút dưới cùng
        val bottom = LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(16), dp(14)) }
        btnAdd = tv("+  Thêm video", 15f, cAc, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(17), 0, dp(17)); elevation = dp(2).toFloat()
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); pick.launch(arrayOf("video/*")) }
        }
        btnMain = tv("", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(17), 0, dp(17)); visibility = View.GONE; elevation = dp(3).toFloat()
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onMainClick() }
        }
        bottom.addView(btnAdd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(btnMain, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply { marginStart = dp(10) })
        col.addView(bottom)

        setContentView(root)
        try {
            WindowCompat.getInsetsController(window, root).apply {
                isAppearanceLightStatusBars = !night; isAppearanceLightNavigationBars = !night
            }
        } catch (_: Exception) {}
        updateChip(); refreshEstimates()
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun updateChip() {
        val cap = (if (capMbps > 0) "≤ ${fmtNum(capMbps)} Mbps · " else "") + (if (fpsCap > 0) "≤ ${fmtNum(fpsCap)} fps · " else "")
        chip.text = "${codecs[codecIdx].first} · ${Levels.names[levelIdx]} · $cap${speeds[speedIdx]} · ${encs[encIdx]}\n" +
            (outDir?.let { folderName(it) } ?: "Movies/Converted")
    }

    private fun folderName(d: Uri): String {
        val id = try { android.provider.DocumentsContract.getTreeDocumentId(d) } catch (_: Exception) { d.lastPathSegment ?: "" }
        return id.substringAfter(':', id).ifEmpty { "Bộ nhớ trong" }
    }

    private fun updateFolder() { folderTv?.text = outDir?.let { folderName(it) } ?: "Movies/Converted (mặc định)" }

    private fun updateEnc() {
        val info = Enc.list(codecs[codecIdx].second)
        val cn = codecs[codecIdx].first
        fun line(t: TextView?, label: String, names: List<String>) {
            if (t == null) return
            if (names.isEmpty()) { t.text = "$label: ✗ không hỗ trợ $cn"; t.setTextColor(cEr) }
            else { t.text = "$label: ✓ ${names.first()}" + (if (names.size > 1) " (+${names.size - 1})" else ""); t.setTextColor(cOk) }
        }
        line(hwTv, "Phần cứng", info.hw); line(swTv, "Phần mềm", info.sw)
        aomTv?.let {
            if (codecs[codecIdx].second != MimeTypes.VIDEO_AV1) { it.text = "libaom: chỉ dùng cho AV1"; it.setTextColor(cMu) }
            else if (!AomNative.available() || android.os.Build.VERSION.SDK_INT < 34) { it.text = "libaom: ✗ cần arm64 + Android 14+"; it.setTextColor(cEr) }
            else { it.text = "libaom: ✓ AV1 bằng CPU (chậm hơn, nén tốt hơn)"; it.setTextColor(cOk) }
        }
    }

    // Tính lại dung lượng ước tính + ảnh xem trước cho các video đang chờ
    private fun refreshEstimates() {
        val mime = codecs[codecIdx].second; val lv = levelIdx; val cap = capMbps
        val waiting = ConvertService.jobs.filter { it.state == St.WAIT }
        if (waiting.isEmpty()) return
        Thread {
            waiting.forEach { j ->
                if (j.state != St.WAIT) return@forEach
                val p = Planner.plan(this, j.uri, mime, lv, cap)
                j.estBytes = p.estBytes; j.srcBytes = p.srcBytes
                j.srcFps = p.fps; j.srcW = p.w; j.srcH = p.h; j.srcVideoBps = p.srcVideoBps
                if (j.thumb == null) j.thumb = Planner.thumb(this, j.uri)
                runOnUiThread { render() }
            }
        }.start()
    }

    // ---------- Bảng cài đặt (bottom sheet) ----------
    private fun openSettings() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val r = dp(28).toFloat()
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(16))
            background = GradientDrawable().apply { setColor(cSf); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        sheet.addView(View(this).apply { background = shape(cTrack, 3) }, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        sheet.addView(tv("Cài đặt chuyển đổi", 20f, cTx, true), lp(top = 14))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        val sc = MaxScroll(this, (resources.displayMetrics.heightPixels * 0.70).toInt()).apply { isVerticalScrollBarEnabled = false; addView(body) }
        sheet.addView(sc, lp(top = 4))

        body.addView(section("Codec đầu ra"), lp(top = 14))
        body.addView(seg(codecs.map { it.first }, codecIdx) { codecIdx = it; prefs.edit().putInt("codec", it).apply(); updateEnc(); if (it == 2) av1Notice() }, lp(top = 8))

        body.addView(section("Mức nén"), lp(top = 18))
        body.addView(seg(Levels.names, levelIdx) { levelIdx = it; prefs.edit().putInt("level", it).apply() }, lp(top = 8))
        body.addView(tv("Càng nhỏ gọn file càng nhẹ, đổi lại có thể mất chi tiết. Dung lượng ước tính hiện ngay trên từng video.", 12f, cMu).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))

        body.addView(section("Giới hạn bitrate tối đa"), lp(top = 18))
        val capEt = EditText(this).apply {
            setText(if (capMbps > 0) fmtNum(capMbps) else ""); hint = "Không giới hạn"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(cTx); setHintTextColor(cMu); textSize = 15f; background = shape(cSv, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12)); maxLines = 1
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    capMbps = s.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0
                    prefs.edit().putFloat("cap", capMbps.toFloat()).apply()
                }
            })
        }
        val capRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        capRow.addView(capEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        capRow.addView(tv("Mbps", 14f, cMu, true).apply { setPadding(dp(12), 0, 0, 0) })
        body.addView(capRow, lp(top = 8))
        val quick = LinearLayout(this)
        listOf("6", "8", "12", "20", "Không").forEach { q ->
            quick.addView(tv(q, 13f, cTx, true).apply {
                gravity = Gravity.CENTER; background = rip(cSv, 12); setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { capEt.setText(if (q == "Không") "" else q); capEt.setSelection(capEt.text.length) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        body.addView(quick, lp(top = 8))
        val autoNote = tv("", 12f, cAc).apply { setLineSpacing(0f, 1.2f) }
        body.addView(tv("⚡  Tự chọn Mbps", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = rip(cAc, 14); setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val infos = ConvertService.jobs.filter { x -> x.srcW > 0 }
                val j = infos.firstOrNull { x -> x.state == St.WAIT } ?: infos.firstOrNull()
                val fps = effFps(j?.srcFps ?: 0.0)
                val rec = Planner.recommendMbps(j?.srcW ?: 0, j?.srcH ?: 0, fps, codecs[codecIdx].second, levelIdx, j?.srcVideoBps ?: 0.0)
                capEt.setText(fmtNum(rec)); capEt.setSelection(capEt.text.length)
                autoNote.text = if (j != null)
                    "Đề xuất ${fmtNum(rec)} Mbps cho ${j.srcW}×${j.srcH} · ${fmtFps(fps)} fps · ${codecs[codecIdx].first} · ${Levels.names[levelIdx]}" +
                        (if (infos.size > 1) ". Tính theo video đầu tiên; video nào có bitrate gốc thấp hơn vẫn tự hạ xuống." else "")
                else "Chưa có video nên tính cho 1080p · 30 fps · ${codecs[codecIdx].first}. Thêm video rồi bấm lại để chính xác hơn."
            }
        }, lp(top = 10))
        body.addView(autoNote, lp(top = 6))
        body.addView(tv("Để trống = tự tính theo từng video. Mức này chỉ là trần: bitrate tự tính theo video gốc rồi bị chặn ở đây (ví dụ 12 Mbps như CapCut).", 12f, cMu).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))

        // ----- FPS -----
        body.addView(section("Giới hạn FPS"), lp(top = 18))
        body.addView(tv(fpsInfoText(), 12.5f, cAc, true), lp(top = 8))
        val fpsEt = EditText(this).apply {
            setText(if (fpsCap > 0) fmtNum(fpsCap) else ""); hint = "Giữ FPS gốc"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(cTx); setHintTextColor(cMu); textSize = 15f; background = shape(cSv, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12)); maxLines = 1
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    fpsCap = s.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 1 } ?: 0.0
                    prefs.edit().putFloat("fps", fpsCap.toFloat()).apply()
                }
            })
        }
        val fpsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fpsRow.addView(fpsEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fpsRow.addView(tv("fps", 14f, cMu, true).apply { setPadding(dp(12), 0, 0, 0) })
        body.addView(fpsRow, lp(top = 8))
        val fpsQuick = LinearLayout(this)
        listOf("Gốc", "24", "30", "60").forEach { q ->
            fpsQuick.addView(tv(q, 13f, cTx, true).apply {
                gravity = Gravity.CENTER; background = rip(cSv, 12); setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { fpsEt.setText(if (q == "Gốc") "" else q); fpsEt.setSelection(fpsEt.text.length) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        body.addView(fpsQuick, lp(top = 8))
        body.addView(tv("Chỉ giảm được FPS (bỏ bớt khung hình), không tăng được. Giảm FPS làm file nhẹ hơn và giúp encoder AV1 / H.265 yếu chạy ổn định hơn.", 12f, cMu).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))

        body.addView(section("Chế độ"), lp(top = 18))
        body.addView(seg(speeds, speedIdx) { speedIdx = it; prefs.edit().putInt("speed", it).apply() }, lp(top = 8))
        body.addView(tv("Chuẩn: ưu tiên chất lượng · Nhanh: bitrate cố định, mã hoá nhanh hơn, dung lượng có thể nhỉnh hơn.", 12f, cMu).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))

        body.addView(section("Bộ mã hoá"), lp(top = 18))
        body.addView(seg(encs, encIdx) { encIdx = it; prefs.edit().putInt("enc", it).apply() }, lp(top = 8))
        hwTv = tv("", 12.5f, cMu); swTv = tv("", 12.5f, cMu); aomTv = tv("", 12.5f, cMu)
        body.addView(hwTv, lp(top = 8)); body.addView(swTv, lp(top = 2)); body.addView(aomTv, lp(top = 2))

        body.addView(section("Thư mục lưu"), lp(top = 18))
        val fRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = rip(cSv, 14); setPadding(dp(16), dp(13), dp(16), dp(13))
            setOnClickListener { pickDir.launch(null) }
        }
        folderTv = tv("", 14f, cTx).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        fRow.addView(folderTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fRow.addView(tv("Đổi", 13f, cAc, true).apply { setPadding(dp(10), 0, 0, 0) })
        body.addView(fRow, lp(top = 8))
        body.addView(tv("Dùng mặc định (Movies/Converted)", 12.5f, cAc, true).apply {
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { outDir = null; prefs.edit().remove("outDir").apply(); updateFolder(); updateChip() }
        })

        body.addView(switchRow("Tự xoá file gốc", "Chỉ xoá sau khi kiểm tra file mới đủ thời lượng", delOrig) { b, on ->
            if (on) {
                AlertDialog.Builder(this).setTitle("Bật tự xoá file gốc?")
                    .setMessage("Video gốc sẽ bị xoá vĩnh viễn sau khi chuyển đổi xong. Áp dụng khi bấm Bắt đầu.")
                    .setPositiveButton("Bật") { _, _ -> delOrig = true; prefs.edit().putBoolean("del", true).apply() }
                    .setNegativeButton("Huỷ") { _, _ -> b.isChecked = false }
                    .setOnCancelListener { b.isChecked = false }.show()
            } else { delOrig = false; prefs.edit().putBoolean("del", false).apply() }
        }, lp(top = 16))
        body.addView(switchRow("Giữ mốc thời gian gốc", "Ngày quay / ngày sửa của file mới trùng video gốc", keepTime) { _, on ->
            keepTime = on; prefs.edit().putBoolean("keep", on).apply()
        }, lp(top = 14))

        val done = tv("Xong", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = rip(cAc, 28); setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { d.dismiss() }
        }
        sheet.addView(done, lp(top = 8))

        d.setContentView(sheet)
        d.setOnDismissListener {
            hwTv = null; swTv = null; folderTv = null
            updateChip(); refreshEstimates()
        }
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        updateEnc(); updateFolder()
        d.show()
    }

    // ---------- Bắt đầu / Dừng ----------
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
                it.enc = encIdx; it.keepTime = keepTime; it.level = levelIdx; it.capMbps = capMbps; it.fps = fpsCap
            }
            ConvertService.active = true
            ContextCompat.startForegroundService(this, Intent(this, ConvertService::class.java))
            render()
        }
    }

    override fun onResume() { super.onResume(); ConvertService.onChange = { runOnUiThread { render() } }; render(); checkBattery() }

    // ---------- Bắt buộc tắt tối ưu pin để app không bị hệ thống kill khi đang chuyển đổi ----------
    private fun ignoringBattery(): Boolean =
        try { (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) } catch (_: Exception) { true }

    private fun checkBattery() {
        if (ignoringBattery()) { battDlg?.dismiss(); battDlg = null; showReport(); return }
        if (battDlg?.isShowing == true) return
        val d = AlertDialog.Builder(this).setTitle("Cho phép app chạy nền")
            .setMessage("Chuyển đổi video mất nhiều phút. Nếu bật tối ưu pin, hệ thống có thể tắt app giữa chừng.\n\n" +
                "Bấm “Mở cài đặt” rồi chọn “Cho phép” (hoặc Pin → Không hạn chế / Không tối ưu hoá). Cần làm bước này để dùng app.")
            .setCancelable(false)
            .setPositiveButton("Mở cài đặt", null)
            .setNegativeButton("Thoát") { _, _ -> finishAffinity() }
            .create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { requestBattery() } }
        battDlg = d; d.show()
    }

    private fun requestBattery() {
        val pkg = Uri.parse("package:$packageName")
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)); return } catch (_: Exception) {}
        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); return } catch (_: Exception) {}
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) } catch (_: Exception) {}
    }

    // ---------- Báo cáo khi lần trước app chết giữa lúc mã hoá ----------
    private fun showReport() {
        val r = report ?: return
        report = null
        val cn = codecs.firstOrNull { it.second == r.mime }?.first ?: "video"
        val msg = when (r.kind) {
            1 -> "Lần trước app dừng đột ngột (crash) khi đang mã hoá $cn." +
                (if (r.mime == MimeTypes.VIDEO_AV1) "\n\nMình đã chuyển về H.265. Muốn thử lại AV1: chọn bộ mã hoá “Phần mềm” hoặc giảm FPS trong Cài đặt." else "")
            2 -> "Lần trước app bị hệ thống tắt khi đang mã hoá $cn (thiếu RAM hoặc bị giới hạn nền), không phải lỗi codec.\n\nHãy giữ nguyên quyền chạy nền và khoá app trong màn hình đa nhiệm."
            else -> "Lần trước app dừng giữa lúc mã hoá $cn, chưa rõ nguyên nhân."
        } + (if (r.detail.isNotEmpty()) "\n\nChi tiết: ${r.detail}" else "")
        AlertDialog.Builder(this).setTitle("App đã dừng bất thường").setMessage(msg).setPositiveButton("Đã hiểu", null).show()
        updateChip()
    }

    private fun av1Notice() {
        val i = Enc.list(MimeTypes.VIDEO_AV1)
        val m = when {
            i.hw.isEmpty() && i.sw.isEmpty() -> "Máy không có encoder AV1. Hãy dùng H.265 hoặc H.264."
            CrashGuard.av1Bad(this) -> "AV1 từng làm app dừng đột ngột trên máy này. Nên dùng H.265, hoặc thử bộ mã hoá “Phần mềm”."
            else -> return
        }
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    // FPS dùng để tính gợi ý: FPS đã đặt nếu thấp hơn FPS gốc, ngược lại FPS gốc (mặc định 30)
    private fun effFps(src: Double): Double =
        if (fpsCap > 0 && (src <= 0 || fpsCap < src)) fpsCap else if (src > 0) src else 30.0

    private fun fpsInfoText(): String {
        val all = ConvertService.jobs
        if (all.isEmpty()) return "Thêm video để xem FPS hiện tại"
        val v = all.filter { it.srcFps > 0 }.map { fmtFps(it.srcFps) }.distinct()
        return if (v.isEmpty()) "Đang đọc FPS của video…" else "FPS hiện tại: ${v.joinToString(" · ")}" + (if (all.size > 1) "  (${all.size} video)" else "")
    }
    override fun onPause() { super.onPause(); ConvertService.onChange = null }

    // ---------- Thẻ video trong hàng đợi (cập nhật tại chỗ, không dựng lại) ----------
    private inner class Card(val j: Job) {
        val img = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(cSv); clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) }
            }
        }
        val title = tv(j.name, 14.5f, cTx, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val sub = tv("", 12.5f, cMu).apply { setLineSpacing(0f, 1.2f) }
        val pb = bar(0f)
        val x = tv("✕", 16f, cMu).apply {
            gravity = Gravity.CENTER; background = ripOval(Color.TRANSPARENT)
            setOnClickListener { ConvertService.jobs.remove(j); render() }
        }
        val root = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL; background = shape(cSf, 22); setPadding(dp(12), dp(12), dp(10), dp(12))
            elevation = if (night) 0f else dp(1).toFloat()
        }
        init {
            val txt = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(title); txt.addView(sub, lp(top = 2)); txt.addView(pb, lp(h = dp(5), top = 8))
            root.addView(img, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })
            root.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(x, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        fun bind(running: Boolean) {
            val t = j.thumb
            if (img.tag !== t) { img.setImageBitmap(t); img.tag = t }
            val saving = j.state == St.SAVE
            val pct = if (saving) j.savePct else j.progress
            when (j.state) {
                St.WAIT -> {
                    val est = if (j.estBytes > 0 && j.srcBytes > 0) "${humanSize(j.srcBytes)} → ≈ ${humanSize(j.estBytes)} (${deltaText(j.estBytes, j.srcBytes)})"
                              else if (j.srcBytes > 0) humanSize(j.srcBytes) else ""
                    sub.text = (if (running) "Trong hàng đợi" else "Sẵn sàng") + (if (est.isNotEmpty()) "\n$est" else "")
                    sub.setTextColor(cMu)
                }
                St.RUN -> {
                    val e = j.eta.text()
                    sub.text = "Đang chuyển đổi · $pct%" + (if (j.progress < 98 && j.eta.disp >= 0) " · còn ${e.t}" else "")
                    sub.setTextColor(cAc)
                }
                St.SAVE -> {
                    sub.text = "Đang lưu · $pct%" + (if (j.saveSpeed > 0) " · %.0f MB/s".format(j.saveSpeed) else "") +
                        (if (j.saveEta >= 0) " · còn ${fmtDur(j.saveEta)}" else "")
                    sub.setTextColor(cAc)
                }
                St.DONE -> {
                    sub.text = "✓ ${humanSize(j.outBytes)}" + (if (j.srcBytes > 0) " (${deltaText(j.outBytes, j.srcBytes)})" else "") +
                        (if (j.msg.isNotEmpty()) "\n${j.msg}" else "")
                    sub.setTextColor(cOk)
                }
                St.ERR -> { sub.text = j.msg; sub.setTextColor(cEr) }
            }
            val busy = j.state == St.RUN || saving
            pb.visibility = if (busy) View.VISIBLE else View.GONE
            if (busy) pb.set(pct / 100f, false)
            x.visibility = if (busy) View.GONE else View.VISIBLE
        }
    }

    private fun render() {
        val jobs = ConvertService.jobs.toList()
        val running = ConvertService.active
        val waiting = jobs.count { it.state == St.WAIT }

        // Nút chính
        fun pill(t: TextView, fill: Int, text: String, fg: Int) { t.background = rip(fill, 28); t.text = text; t.setTextColor(fg) }
        val key = if (running) "run" else if (waiting > 0) "wait$waiting" else "idle"
        if (key != btnKey) {
            btnKey = key
            if (running) {
                btnMain.visibility = View.VISIBLE; pill(btnMain, cEr, "■  Dừng", Color.WHITE)
                pill(btnAdd, cSf, "+  Thêm video", cAc)
            } else if (waiting > 0) {
                btnMain.visibility = View.VISIBLE; pill(btnMain, cAc, "▶  Bắt đầu ($waiting)", Color.WHITE)
                pill(btnAdd, cSf, "+  Thêm video", cAc)
            } else {
                btnMain.visibility = View.GONE; pill(btnAdd, cAc, "+  Thêm video", Color.WHITE)
            }
        }

        // Thẻ tiến trình
        val cur = jobs.firstOrNull { it.state == St.RUN || it.state == St.SAVE }
        if (cur == null) { sumCard.visibility = View.GONE; sumBar.indeterminate = false } else {
            sumCard.visibility = View.VISIBLE
            val saving = cur.state == St.SAVE
            val pct = if (saving) cur.savePct else cur.progress
            sumPhase.text = if (saving) "ĐANG LƯU FILE" else "ĐANG CHUYỂN ĐỔI"
            sumPct.text = "$pct%"
            sumBar.indeterminate = cur.state == St.RUN && cur.progress == 0
            sumBar.set(pct / 100f)
            val e = cur.eta.text()
            val plain = !saving && (cur.progress >= 98 || cur.eta.disp < 0)
            sumEtaLbl.text = if (plain) "" else "CÒN LẠI"
            sumEta.text = if (saving) (if (cur.saveEta >= 0) fmtDur(cur.saveEta) else "…") else e.t

            val est = cur.szp.est()
            if (est == null || saving) {
                sumSize.visibility = View.GONE
            } else {
                sumSize.visibility = View.VISIBLE
                sumSize.text = "Dung lượng dự đoán ≈ ${humanSize(est[1])}  (${humanSize(est[0])} – ${humanSize(est[2])})" +
                    (if (cur.srcBytes > 0) "\n${deltaText(est[1], cur.srcBytes)} so với gốc ${humanSize(cur.srcBytes)}" else "")
            }

            val el = (SystemClock.elapsedRealtime() - cur.t0) / 1000.0
            val parts = ArrayList<String>()
            parts.add("Đã chạy ${fmtDur(el)}")
            if (!saving && e.atMs > 0) { parts.add("xong lúc ${clockText(e.atMs)}"); if (e.conf != "–") parts.add("tin cậy ${e.conf.lowercase()}") }
            if (saving) parts.add("%.0f MB/s".format(cur.saveSpeed))
            else {
                if (el > 3 && cur.progress > 0 && cur.durSec > 0) parts.add("%.2f× tốc độ thật".format(cur.progress / 100.0 * cur.durSec / el))
                if (cur.szp.now > 0) parts.add("đã ghi ${humanSize(cur.szp.now)}")
            }
            sumStats.text = "File ${jobs.indexOf(cur) + 1}/${jobs.size} · ${cur.name}\n" + parts.joinToString(" · ") +
                (if (waiting > 0) "\n$waiting file đang chờ" else "")
        }

        // Danh sách thẻ
        cards.keys.filter { it !in jobs }.forEach { k -> cards.remove(k)?.let { listBox.removeView(it.root) } }
        jobs.forEachIndexed { i, j ->
            val c = cards.getOrPut(j) {
                Card(j).also { listBox.addView(it.root, Math.min(i, listBox.childCount), lp(top = 8)) }
            }
            c.bind(running)
        }
        emptyView.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
    }
}
