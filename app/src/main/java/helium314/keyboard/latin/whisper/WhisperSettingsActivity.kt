// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.whisper

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import helium314.keyboard.latin.BuildConfig

/** Settings of the Whisper mic key. Changes apply to the next recording. */
class WhisperSettingsActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var languages: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText

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

        fun switch(text: String, key: String, default: Boolean, help: String) {
            col.addView(Switch(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(18), 0, dp(4))
                isChecked = prefs.getBoolean(key, default)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
            })
            col.addView(label(help, 13f))
        }
        switch("Tekst osade kaupa", WhisperVoice.KEY_LIVE, false,
            "Iga lühikese pausi järel saadetakse valmis osa kohe ära ja tekst ilmub rääkimise ajal. " +
                    "Salvestus lõpeb ikka ülal valitud pausiga või mikrofoni vajutusega.")
        // audio format of the live stream
        col.addView(label("Voo helivorming (tekst osade kaupa)"))
        val formats = listOf("opus" to "Opus – umbes 24 kbit/s", "s16" to "16-bitine – 256 kbit/s", "f32" to "Pakkimata – 512 kbit/s")
        val currentFormat = prefs.getString(WhisperVoice.KEY_STREAM_FORMAT, WhisperVoice.DEFAULT_STREAM_FORMAT)
        col.addView(RadioGroup(this).apply {
            formats.forEachIndexed { i, (value, text) ->
                addView(RadioButton(this@WhisperSettingsActivity).apply {
                    id = i + 1
                    this.text = text
                    isChecked = value == currentFormat
                })
            }
            setOnCheckedChangeListener { _, id ->
                prefs.edit().putString(WhisperVoice.KEY_STREAM_FORMAT, formats[id - 1].first).apply()
            }
        })
        col.addView(label("Aeglase mobiilse ühendusega jääb pakkimata voog jutust maha ja tekst hilineb. " +
                "Server, mis vorminguid ei tunne, saab alati pakkimata voo.", 13f))

        switch("Pakitud heli", WhisperVoice.KEY_COMPRESS, true,
            "Saadab AAC-faili (umbes 0,25 MB minutis) WAV-i (2 MB minutis) asemel.")

        // recordings that could not be sent yet
        pending = label("", 16f)
        val clear = Button(this).apply {
            text = "Kustuta ootel salvestused"
            setOnClickListener { WhisperVoice.clearPending(this@WhisperSettingsActivity); showPending() }
        }
        col.addView(pending)
        col.addView(clear)
        col.addView(label("Kui server ei vasta, jääb salvestus alles ja mikrofon on lilla. " +
                "Mikrofoni vajutus saadab selle uuesti.", 13f))

        col.addView(label("Viimaste salvestuste ajakulu"))
        timing = label("", 13f).apply { setTextIsSelectable(true) }
        col.addView(timing)

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
        col.addView(label("OpenAI-ühilduv aadress, nt https://server/v1/audio/transcriptions." +
                if (BuildConfig.WHISPER_URL.isEmpty()) " Ilma selleta mikrofon ei tööta." else " Tühi = ${BuildConfig.WHISPER_URL}", 13f))

        col.addView(label("API võti"))
        apiKey = EditText(this).apply {
            setText(prefs.getString(WhisperVoice.KEY_API_KEY, ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        col.addView(apiKey)
        col.addView(label("Tasuliste teenuste (OpenAI, Groq jt) jaoks; saadetakse päises „Authorization: Bearer“. " +
                "Oma serveri puhul jäta tühjaks.", 13f))

        col.addView(label("Mudel"))
        model = EditText(this).apply {
            setText(prefs.getString(WhisperVoice.KEY_MODEL, ""))
            hint = BuildConfig.WHISPER_MODEL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        col.addView(model)
        col.addView(label("Tühi = ${BuildConfig.WHISPER_MODEL}. Näiteks Groq: whisper-large-v3-turbo.", 13f))

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

    private lateinit var pending: TextView
    private lateinit var timing: TextView

    private fun showPending() {
        val n = WhisperVoice.pendingFiles(this).size
        pending.text = if (n == 0) "Ootel salvestusi pole" else "Ootel salvestusi: $n"
        timing.text = WhisperVoice.prefs(this).getString(WhisperVoice.KEY_LAST_TIMING, "")!!.ifBlank { "Veel pole mõõdetud." }
            .lines().joinToString("\n\n")
    }

    override fun onResume() {
        super.onResume()
        showPending()
    }

    override fun onPause() {
        super.onPause()
        WhisperVoice.prefs(this).edit()
            .putString(WhisperVoice.KEY_URL, url.text.toString().trim())
            .putString(WhisperVoice.KEY_LANGS, languages.text.toString().trim().lowercase())
            .putString(WhisperVoice.KEY_API_KEY, apiKey.text.toString().trim())
            .putString(WhisperVoice.KEY_MODEL, model.text.toString().trim())
            .apply()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
