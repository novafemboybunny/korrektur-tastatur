package de.example.korrektur

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class KorrekturKeyboard : InputMethodService() {

    private var shift = false
    private val ui = Handler(Looper.getMainLooper())
    private val letters = mutableListOf<Button>()

    override fun onCreateInputView(): View {
        letters.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF202124.toInt())
            setPadding(4, 4, 4, 4)
        }

        root.addView(row(
            key("✓ Korrigieren", 3f) { korrigiere() },
            key(",") { type(",") }, key(".") { type(".") },
            key("?") { type("?") }, key("!") { type("!") },
            key(":") { type(":") }, key(";") { type(";") }
        ))
        root.addView(row(*"1234567890".map { c -> key("$c") { type("$c") } }.toTypedArray()))
        for (r in listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnmß")) {
            val keys = r.map { c ->
                key("$c").also { b ->
                    b.setOnClickListener { type(if (shift) "$c".uppercase() else "$c"); setShift(false) }
                    letters += b
                }
            }
            root.addView(row(*keys.toTypedArray()))
        }
        root.addView(row(
            key("⇧", 1.5f) { setShift(!shift) },
            key("Leerzeichen", 5f) { type(" ") },
            key("⌫", 1.5f) { backspace() },
            key("⏎", 1.5f) { enter() }
        ))
        return root
    }

    private fun row(vararg keys: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        keys.forEach { addView(it) }
    }

    private fun key(label: String, weight: Float = 1f, onClick: (() -> Unit)? = null) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF3C4043.toInt())
            layoutParams = LinearLayout.LayoutParams(0, 130, weight).apply { setMargins(3, 3, 3, 3) }
            onClick?.let { cb -> setOnClickListener { cb() } }
        }

    private fun setShift(on: Boolean) {
        shift = on
        letters.forEach { it.text = if (on) it.text.toString().uppercase() else it.text.toString().lowercase() }
    }

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

    private fun korrigiere() {
        val text = currentInputConnection
            ?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        if (text.isNullOrBlank()) return
        Thread {
            val result = runCatching { LanguageTool.fix(text) }
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

    fun fix(text: String): String {
        val body = "language=de-DE&text=" + URLEncoder.encode(text, "UTF-8")
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
