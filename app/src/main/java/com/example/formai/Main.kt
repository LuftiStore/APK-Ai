package com.example.formai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Layar pengaturan: simpan API key + buka pengaturan Aksesibilitas. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val info = TextView(this).apply {
            text = "1. Tempel API key (Gemini gratis dari aistudio.google.com, atau Anthropic) lalu Simpan.\n" +
                "2. Buka Aksesibilitas, aktifkan 'Form AI Mengambang'.\n" +
                "3. Buka Google Form di Chrome, tekan tombol AI yang mengambang."
            setPadding(0, 0, 0, 32)
        }
        val keyInput = EditText(this).apply {
            hint = "API key (AIza... atau sk-ant-...)"
            setText(prefs.getString("key", ""))
        }
        val modelInput = EditText(this).apply {
            hint = "Model Gemini (default gemini-2.5-flash)"
            setText(prefs.getString("model", "gemini-2.5-flash"))
        }
        val save = Button(this).apply {
            text = "Simpan"
            setOnClickListener {
                prefs.edit().putString("key", keyInput.text.toString().trim())
                    .putString("model", modelInput.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Tersimpan", Toast.LENGTH_SHORT).show()
            }
        }
        val acc = Button(this).apply {
            text = "Buka Pengaturan Aksesibilitas"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(info); addView(keyInput); addView(modelInput); addView(save); addView(acc)
        })
    }
}

/** Tombol AI mengambang + panel jawaban, tampil di atas aplikasi lain (Chrome). */
class FloatingService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var blp: WindowManager.LayoutParams
    private var menu: LinearLayout? = null
    private var autoMode = false
    private var busy = false
    private var lastHash = 0
    private val browsers = setOf(
        "com.android.chrome", "com.chrome.beta", "org.mozilla.firefox",
        "com.sec.android.app.sbrowser", "com.microsoft.emmx", "com.brave.browser", "com.opera.browser"
    )
    private val autoTick = Runnable { if (autoMode && !busy) run(true, true) }
    private var panel: LinearLayout? = null
    private var out: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val http = OkHttpClient()

    override fun onServiceConnected() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (56 * resources.displayMetrics.density).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = screenW - size; y = 400 }

        bubble = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = 12f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#7C4DFF"))
                setStroke(4, Color.WHITE)
            }
        }
        blp = lp
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        bubble.setOnTouchListener { v: View, e: MotionEvent ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    hideMenu()
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                    v.alpha = 0.8f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = sx + dx; lp.y = sy + dy
                    wm.updateViewLayout(bubble, lp)
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    if (moved) {
                        // tempel ke sisi layar terdekat seperti chat head
                        lp.x = if (lp.x + size / 2 < screenW / 2) 0 else screenW - size
                        wm.updateViewLayout(bubble, lp)
                    } else toggleMenu()
                }
            }
            true
        }
        wm.addView(bubble, lp)
    }

    private fun showPanel(msg: String) {
        if (panel == null) {
            val tv = TextView(this).apply {
                setTextColor(Color.BLACK); textSize = 15f; setPadding(32, 32, 32, 32)
            }
            out = tv
            val close = Button(this).apply {
                text = "Tutup"
                setOnClickListener { panel?.let { wm.removeView(it) }; panel = null }
            }
            val p = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F2FFFFFF"))
                addView(ScrollView(context).apply { addView(tv) }, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(close)
            }
            panel = p
            val h = (resources.displayMetrics.heightPixels * 0.5).toInt()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM }
            wm.addView(p, lp)
        }
        out?.text = msg
    }

    private fun collect(n: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (n == null || sb.length > 15000) return
        val t = n.text ?: n.contentDescription
        if (!t.isNullOrBlank()) sb.append(t).append('\n')
        for (i in 0 until n.childCount) collect(n.getChild(i), sb)
    }

    private fun toggleMenu() {
        if (menu != null) { hideMenu(); return }
        val d = resources.displayMetrics.density
        val w = (190 * d).toInt()
        val sw = resources.displayMetrics.widthPixels
        fun item(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { hideMenu(); action() }
        }
        val m = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2FFFFFF"))
            setPadding(8, 8, 8, 8)
            addView(item("Tampilkan jawaban") { run(false) })
            addView(item("Jawab otomatis") { run(true) })
            addView(item(if (autoMode) "Mode otomatis: NYALA ✓" else "Mode otomatis: MATI") { setAuto(!autoMode) })
            addView(item("Tutup menu") { })
        }
        val mx = if (blp.x < sw / 2) blp.x + blp.width + 8 else blp.x - w - 8
        val lp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = mx; y = blp.y }
        wm.addView(m, lp)
        menu = m
    }

    private fun hideMenu() { menu?.let { wm.removeView(it) }; menu = null }

    private fun say(msg: String, quiet: Boolean) {
        if (quiet) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() else showPanel(msg)
    }

    private fun setAuto(on: Boolean) {
        autoMode = on
        (bubble.background as? GradientDrawable)?.setColor(Color.parseColor(if (on) "#00A86B" else "#7C4DFF"))
        ui.removeCallbacks(autoTick)
        if (on) {
            lastHash = 0
            Toast.makeText(this, "Mode otomatis NYALA: soal di layar dijawab otomatis", Toast.LENGTH_SHORT).show()
            ui.postDelayed(autoTick, 500)
        } else {
            Toast.makeText(this, "Mode otomatis MATI", Toast.LENGTH_SHORT).show()
        }
    }

    /** auto=false: tampilkan jawaban di panel. auto=true: isi form otomatis (tidak menekan Kirim). */
    private fun run(auto: Boolean, silent: Boolean = false) {
        if (busy) return
        val key = getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isBlank()) {
            say("Isi API key dulu di aplikasi Form AI.", silent)
            if (silent) setAuto(false)
            return
        }
        val sb = StringBuilder()
        collect(rootInActiveWindow, sb)
        val screen = sb.toString()
        if (silent && (screen.length < 40 || screen.hashCode() == lastHash)) return
        lastHash = screen.hashCode()
        busy = true
        val prompt = if (auto) {
            "Berikut teks layar Google Form. Balas HANYA JSON array tanpa teks lain, satu objek per soal: " +
                "[{\"q\":\"40 karakter pertama teks soal persis\",\"type\":\"choice\" atau \"text\"," +
                "\"answer\":[\"teks pilihan persis seperti di layar\"]}]. " +
                "Untuk type text, answer berisi 1 jawaban singkat. " +
                "Analisis setiap soal dengan teliti dan pilih jawaban yang paling benar.\n\n$screen"
        } else {
            "Berikut teks yang terbaca dari layar Google Form. Temukan setiap pertanyaan " +
                "beserta pilihannya, lalu beri jawaban terbaik yang singkat. " +
                "Analisis dengan teliti dan pilih jawaban yang paling benar. " +
                "Format: nomor. jawaban (untuk pilihan, tulis persis teks pilihannya).\n\n$screen"
        }
        say("Memproses…", silent)
        Thread {
            val r = try { chat(key, prompt) } catch (e: Exception) { "Error: ${e.message}" }
            ui.post { if (auto) applyAnswers(r, silent) else { busy = false; showPanel(r) } }
        }.start()
    }

    private fun flatten(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) flatten(n.getChild(i), out)
    }

    private fun label(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription)?.toString()?.trim() ?: ""

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c == null) return false
        if (c.isChecked || n.isChecked) return true
        return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun applyAnswers(raw: String, quiet: Boolean) {
        val items = try {
            JSONArray(raw.replace("```json", "").replace("```", "").trim())
        } catch (e: Exception) {
            busy = false
            say(if (quiet) "Gagal membaca jawaban AI" else "Gagal membaca jawaban AI:\n$raw", quiet); return
        }
        val nodes = ArrayList<AccessibilityNodeInfo>()
        flatten(rootInActiveWindow, nodes)
        var ok = 0
        val failed = StringBuilder()
        for (i in 0 until items.length()) {
            val obj = items.getJSONObject(i)
            val q = obj.optString("q").take(40)
            val ans = obj.optJSONArray("answer") ?: JSONArray()
            val qIdx = if (q.isBlank()) -1 else nodes.indexOfFirst { label(it).contains(q, true) }
            var done = false
            if (obj.optString("type") == "text") {
                val field = nodes.withIndex().firstOrNull { (idx, nd) ->
                    idx > qIdx && nd.className == "android.widget.EditText"
                }?.value
                if (field != null && ans.length() > 0) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ans.getString(0))
                    }
                    done = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            } else {
                for (j in 0 until ans.length()) {
                    val a = ans.getString(j).trim()
                    val target = nodes.withIndex().firstOrNull { (idx, nd) ->
                        idx > qIdx && label(nd).equals(a, true)
                    }?.value
                    if (target != null && click(target)) done = true
                }
            }
            if (done) ok++ else failed.append("• ").append(q).append('\n')
        }
        say(
            "Terisi $ok dari ${items.length()} soal.\n" +
                (if (failed.isNotEmpty() && !quiet) "Belum terisi (scroll lalu coba lagi, atau isi manual):\n$failed" else "") +
                (if (!quiet) "\nPeriksa dulu jawabannya sebelum menekan Kirim." else ""),
            quiet
        )
        // catat kondisi layar setelah diisi agar perubahan akibat klik tidak memicu ulang
        ui.postDelayed({
            val sb2 = StringBuilder()
            collect(rootInActiveWindow, sb2)
            lastHash = sb2.toString().hashCode()
            busy = false
        }, 2000)
    }

    /** Kunci diawali sk-ant -> Claude (berbayar). Selain itu -> Gemini (ada tier gratis). */
    private fun chat(key: String, prompt: String): String =
        if (key.startsWith("sk-ant")) chatClaude(key, prompt) else chatGemini(key, prompt)

    private fun chatGemini(key: String, prompt: String): String {
        val model = getSharedPreferences("cfg", MODE_PRIVATE).getString("model", "")
            ?.trim().takeUnless { it.isNullOrEmpty() } ?: "gemini-2.5-flash"
        val body = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        ).toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val json = JSONObject(res.body!!.string())
            if (json.has("error")) return "Error: " + json.getJSONObject("error").optString("message")
            return json.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        }
    }

    private fun chatClaude(key: String, prompt: String): String {
        val body = JSONObject()
            .put("model", "claude-sonnet-4-6")
            .put("max_tokens", 1500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .toString()
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val json = JSONObject(res.body!!.string())
            if (json.has("error")) return "Error: " + json.getJSONObject("error").optString("message")
            return json.getJSONArray("content").getJSONObject(0).getString("text")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!autoMode || busy || event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in browsers) return
        ui.removeCallbacks(autoTick)
        ui.postDelayed(autoTick, 1500) // tunggu layar berhenti bergerak
    }
    override fun onInterrupt() {}
}
