// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.whisper

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import helium314.keyboard.latin.BuildConfig

/** Settings of the Whisper mic key. Changes apply to the next recording. */
class WhisperSettingsActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var languages: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Whisper"
        val prefs = WhisperVoice.prefs(this)
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setPadding(0, dp(18), 0, dp(4))
        }

        col.addView(TextView(this).apply {
            text = "Whisper"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        })

        // pause before sending
        val pauseLabel = label("")
        fun showPause(ms: Int) { pauseLabel.text = "Paus enne saatmist: %.1f s".format(ms / 1000f) }
        val steps = 19 // 0.5 s … 10 s in 0.5 s steps
        val current = prefs.getInt(WhisperVoice.KEY_SILENCE_MS, WhisperVoice.DEFAULT_SILENCE_MS)
        showPause(current)
        val seek = SeekBar(this).apply {
            max = steps
            progress = (current / 500 - 1).coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val ms = (p + 1) * 500
                    showPause(ms)
                    prefs.edit().putInt(WhisperVoice.KEY_SILENCE_MS, ms).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        col.addView(pauseLabel)
        col.addView(seek)
        col.addView(label("Nii kaua vaikust pärast rääkimist, siis saadetakse heli automaatselt. " +
                "Mikrofoni uuesti vajutades saadetakse kohe.", 13f))

        col.addView(label("Keeled"))
        languages = EditText(this).apply {
            setText(prefs.getString(WhisperVoice.KEY_LANGS, WhisperVoice.DEFAULT_LANGS))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        col.addView(languages)
        col.addView(label("Komadega eraldatud, nt „auto, et, en“. Mikrofoni pikk vajutus vahetab keelt, valitud keel " +
                "on kirjas mikrofoni nupul. „auto“ = Whisper tuvastab keele ise, nupul siis silti pole. " +
                "Tühi = klaviatuuri paigutuse keel.", 13f))

        col.addView(label("Serveri aadress"))
        url = EditText(this).apply {
            setText(WhisperVoice.url(this@WhisperSettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        col.addView(url)
        col.addView(label("OpenAI-ühilduv /v1/audio/transcriptions. Tühi = ${BuildConfig.WHISPER_URL}", 13f))

        val scroll = ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // content is drawn edge to edge: keep it clear of the status bar, navigation bar and keyboard
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onPause() {
        super.onPause()
        WhisperVoice.prefs(this).edit()
            .putString(WhisperVoice.KEY_URL, url.text.toString().trim())
            .putString(WhisperVoice.KEY_LANGS, languages.text.toString().trim().lowercase())
            .apply()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
