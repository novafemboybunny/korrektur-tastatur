package de.example.korrektur

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class KorrekturKeyboard : InputMethodService() {

    private class Lang(val code: String, val lt: String, val rows: List<String>)

    private val langs = listOf(
        Lang("DE", "de-DE", listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnmß")),
        Lang("EN", "en-US", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
        Lang("FR", "fr", listOf("azertyuiop", "qsdfghjklm", "wxcvbnéèç")),
        Lang("ES", "es", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")),
        Lang("IT", "it", listOf("qwertyuiop", "asdfghjklè", "zxcvbnmàò")),
        Lang("NL", "nl", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
        Lang("PT", "pt-PT", listOf("qwertyuiop", "asdfghjklç", "zxcvbnmã"))
    )

    private val palette = listOf(
        0xFF202124.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFCFD8DC.toInt(),
        0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFFC2185B.toInt(), 0xFF6A1B9A.toInt(),
        0xFFEF6C00.toInt(), 0xFF3C4043.toInt()
    )
    private val accentColor = 0xFF1E88E5.toInt()

    private val prefs by lazy { getSharedPreferences("kb", MODE_PRIVATE) }
    private var langIdx: Int
        get() = prefs.getInt("lang", 0).coerceIn(0, langs.size - 1)
        set(v) { prefs.edit().putInt("lang", v).apply() }
    private val bgColor: Int get() = prefs.getInt("bg", 0xFF202124.toInt())
    private val keyColor: Int get() = prefs.getInt("key", 0xFF3C4043.toInt())
    private var radius: Int
        get() = prefs.getInt("radius", 10)
        set(v) { prefs.edit().putInt("radius", v).apply() }
    private var keyH: Int
        get() = prefs.getInt("keyH", 46)
        set(v) { prefs.edit().putInt("keyH", v).apply() }

    private var shift = false
    private var settingsOpen = false
    private val ui = Handler(Looper.getMainLooper())
    private val letters = mutableListOf<Button>()

    override fun onCreateInputView(): View = buildView()

    private fun rebuild() { setInputView(buildView()) }

    private fun buildView(): View {
        letters.clear()
        shift = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        if (settingsOpen) buildSettings(root) else buildKeys(root)
        return root
    }

    // ---------- Tastatur ----------
    private fun buildKeys(root: LinearLayout) {
        root.addView(row(
            key("✓ Korrigieren", 3f, true) { korrigiere() },
            key(",") { type(",") }, key(".") { type(".") },
            key("?") { type("?") }, key("!") { type("!") },
            key(langs[langIdx].code, 1.2f) { langIdx = (langIdx + 1) % langs.size; rebuild() },
            key("⚙") { settingsOpen = true; rebuild() }
        ))
        root.addView(row(*"1234567890".map { c -> key("$c") { type("$c") } }.toTypedArray()))
        for (r in langs[langIdx].rows) {
            root.addView(row(*r.map { c ->
                key("$c") { type(cased("$c")); setShift(false) }.also { letters += it }
            }.toTypedArray()))
        }
        root.addView(row(
            key("⇧", 1.5f) { setShift(!shift) },
            key("Leerzeichen", 5f) { type(" ") },
            key("⌫", 1.5f) { backspace() },
            key("⏎", 1.5f) { enter() }
        ))
    }

    // ---------- Einstellungen ----------
    private fun buildSettings(root: LinearLayout) {
        root.addView(row(
            label("Einstellungen", 3f),
            key("✓ Fertig", 1.5f, true) { settingsOpen = false; rebuild() }
        ))
        root.addView(label("Sprache"))
        root.addView(row(*langs.mapIndexed { i, l ->
            key(l.code, 1f, i == langIdx) { langIdx = i; rebuild() }
        }.toTypedArray()))
        root.addView(label("Hintergrund"))
        root.addView(swatchRow("bg", bgColor))
        root.addView(label("Tasten"))
        root.addView(swatchRow("key", keyColor))
        root.addView(label("Tastenform"))
        root.addView(row(
            key("eckig", 1f, radius == 0) { radius = 0; rebuild() },
            key("rund", 1f, radius == 10) { radius = 10; rebuild() },
            key("sehr rund", 1f, radius == 24) { radius = 24; rebuild() }
        ))
        root.addView(label("Tastengröße"))
        root.addView(row(
            key("−") { keyH = (keyH - 6).coerceAtLeast(34); rebuild() },
            key("+") { keyH = (keyH + 6).coerceAtMost(70); rebuild() }
        ))
    }

    private fun swatchRow(pref: String, current: Int) = row(*palette.map { col ->
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            background = GradientDrawable().apply {
                setColor(col)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(if (col == current) 3 else 1), if (col == current) accentColor else 0x66888888)
            }
            setOnClickListener { prefs.edit().putInt(pref, col).apply(); rebuild() }
        }
    }.toTypedArray())

    // ---------- UI-Helfer ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun onColor(c: Int) = if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun label(t: String, weight: Float = -1f) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(onColor(bgColor))
        setPadding(dp(6), dp(6), 0, dp(2))
        if (weight > 0) layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
    }

    private fun key(label: String, weight: Float = 1f, highlight: Boolean = false, onClick: () -> Unit): Button {
        val fill = if (highlight) accentColor else keyColor
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = keyH / 3f
            setTextColor(onColor(fill))
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat() }
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(keyH), weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun cased(s: String) = if (shift && s != "ß") s.uppercase() else s

    private fun setShift(on: Boolean) {
        shift = on
        letters.forEach {
            val t = it.text.toString()
            it.text = if (on && t != "ß") t.uppercase() else t.lowercase()
        }
    }

    // ---------- Eingabe ----------
    private fun type(s: String) { currentInputConnection?.commitText(s, 1) }
    private fun backspace() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }
    private fun enter() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    // ---------- Korrektur ----------
    private fun korrigiere() {
        val text = currentInputConnection
            ?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        if (text.isNullOrBlank()) return
        val lt = langs[langIdx].lt
        Thread {
            val result = runCatching { LanguageTool.fix(text, lt) }
            ui.post {
                val ic = currentInputConnection ?: return@post
                result.onSuccess { fixed ->
                    if (fixed != text) {
                        ic.beginBatchEdit()
                        ic.setSelection(0, text.length)
                        ic.commitText(fixed, 1)
                        ic.endBatchEdit()
                    } else Toast.makeText(this, "Keine Fehler gefunden", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Prüfung fehlgeschlagen (Internet?)", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}

object LanguageTool {
    private const val URL_CHECK = "https://api.languagetool.org/v2/check"

    fun fix(text: String, lang: String): String {
        val body = "language=" + lang + "&text=" + URLEncoder.encode(text, "UTF-8")
        val con = (URL(URL_CHECK).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        con.outputStream.use { it.write(body.toByteArray()) }
        val json = JSONObject(con.inputStream.bufferedReader().readText())
        val matches = json.getJSONArray("matches")

        data class Fix(val start: Int, val end: Int, val with: String)
        val fixes = (0 until matches.length()).mapNotNull { i ->
            val m = matches.getJSONObject(i)
            val reps = m.getJSONArray("replacements")
            if (reps.length() == 0) null
            else Fix(m.getInt("offset"), m.getInt("offset") + m.getInt("length"),
                reps.getJSONObject(0).getString("value"))
        }.sortedByDescending { it.start }

        val sb = StringBuilder(text)
        var limit = Int.MAX_VALUE
        for (f in fixes) {
            if (f.end > limit) continue
            sb.replace(f.start, f.end, f.with)
            limit = f.start
        }
        return sb.toString()
    }
}
