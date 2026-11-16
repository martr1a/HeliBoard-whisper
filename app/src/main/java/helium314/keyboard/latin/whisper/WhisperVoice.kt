// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.whisper

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Voice input without switching keyboards: the mic key records here and sends the
 * audio to an OpenAI-compatible Whisper endpoint (BuildConfig.WHISPER_URL).
 * Tap mic: start; tap again or stay silent: stop and transcribe. Each recording is saved as a
 * file before upload and deleted only after the text arrived, so a server outage loses nothing.
 * Switching to another voice IME loses the text field in some apps (HeliBoard #1772).
 */
object WhisperVoice {
    private const val TAG = "WhisperVoice"
    /** long-press code of the toolbar mic key: switch to the next voice language */
    const val CODE_NEXT_LANGUAGE = -10_233

    private const val RATE = 16_000
    private const val NO_SPEECH_MS = 8_000
    private const val MAX_MS = 180_000
    private const val SPEECH_RMS = 700.0

    val isEnabled get() = BuildConfig.WHISPER_URL.isNotEmpty()

    // user settings, changed in WhisperSettingsActivity
    const val PREFS = "whisper"
    const val KEY_URL = "url"
    const val KEY_SILENCE_MS = "silence_ms"
    const val KEY_LANGS = "languages"
    const val KEY_LANG_INDEX = "language_index"
    const val DEFAULT_SILENCE_MS = 3_000
    const val DEFAULT_LANGS = "auto, et, en"
    const val KEY_LIVE = "live"
    const val KEY_COMPRESS = "compress"
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun url(context: Context) = prefs(context).getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.WHISPER_URL

    /** voice languages in long-press order; "auto" = let Whisper detect */
    fun languages(context: Context): List<String> =
        prefs(context).getString(KEY_LANGS, DEFAULT_LANGS)!!.split(',', ' ', ';')
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    /** selected voice language; the keyboard language when no list is set */
    private fun selected(context: Context, keyboardLang: String): String {
        val langs = languages(context)
        if (langs.isEmpty()) return keyboardLang
        return langs[prefs(context).getInt(KEY_LANG_INDEX, 0).mod(langs.size)]
    }

    /** Mic key: tap records in the selected language, long-press selects the next language. */
    fun onKey(context: Context, code: Int, keyboardLang: String, root: View?, commit: (String) -> Unit) {
        if (code == CODE_NEXT_LANGUAGE) {
            val langs = languages(context)
            if (langs.size > 1) {
                val i = prefs(context).getInt(KEY_LANG_INDEX, 0)
                prefs(context).edit().putInt(KEY_LANG_INDEX, (i + 1).mod(langs.size)).apply()
            }
            root?.let { showLanguage(context, it, keyboardLang) }
            return
        }
        val lang = selected(context, keyboardLang)
        onMicKey(context, lang.takeIf { it != "auto" }, root, commit)
    }

    /** Write the selected language (ET, EN, AUTO) on the mic key. */
    fun showLanguage(context: Context, root: View, keyboardLang: String? = null) {
        val sel = selected(context, keyboardLang ?: Locale.getDefault().language)
        val lang = if (sel == "auto") "" else sel.uppercase(Locale.ROOT) // auto: plain mic
        forMicKeys(root) { v ->
            val d = v.drawable ?: return@forMicKeys
            val badge = d as? LanguageBadgeDrawable ?: LanguageBadgeDrawable(d).also { v.setImageDrawable(it) }
            badge.text = lang
            v.invalidate()
        }
    }

    // ---- state -------------------------------------------------------------------------

    private enum class State { IDLE, RECORDING, SENDING, FAILED }

    @Volatile private var recording = false
    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    @Volatile private var inputActive = true
    private val draining = AtomicBoolean(false)
    private val sender = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    // latest keyboard window and text sink; replaced on every key press
    @Volatile private var root: View? = null
    @Volatile private var commit: ((String) -> Unit)? = null

    private const val LIVE_PAUSE_MS = 700
    private const val LIVE_MIN_MS = 2_500
    private val RETRY_WAIT_MS = longArrayOf(0, 2_000, 5_000)

    private fun pendingDir(context: Context) = File(context.filesDir, "whisper_pending").apply { mkdirs() }
    fun pendingFiles(context: Context): List<File> =
        pendingDir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedBy { it.name } ?: emptyList()
    fun clearPending(context: Context) { pendingDir(context).listFiles()?.forEach { it.delete() } }

    private fun state(context: Context) = when {
        recording -> State.RECORDING
        draining.get() -> State.SENDING
        pendingFiles(context).isNotEmpty() -> State.FAILED
        else -> State.IDLE
    }

    /**
     * Mic key pressed. [language] is an ISO code ("et", "en") or null for auto-detect.
     * Tap while recording: stop. Tap when sending failed: send the kept recording again.
     */
    fun onMicKey(context: Context, language: String?, root: View?, commit: (String) -> Unit) {
        this.root = root
        this.commit = commit
        when (state(context)) {
            State.RECORDING -> { stopRequested = true; return }
            State.SENDING -> return
            State.FAILED -> { startSending(context); return }
            State.IDLE -> {}
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent(context, WhisperPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val p = prefs(context)
        val silenceMs = p.getInt(KEY_SILENCE_MS, DEFAULT_SILENCE_MS)
        val live = p.getBoolean(KEY_LIVE, false)
        val compress = p.getBoolean(KEY_COMPRESS, true)
        stopRequested = false
        cancelled = false
        recording = true
        updateColor(context)
        thread(name = "whisper-rec") {
            try {
                record(silenceMs, live) { pcm -> save(context, pcm, language, compress); startSending(context) }
            } catch (e: Exception) {
                Log.w(TAG, "recording failed", e)
                toast(context, "Whisper: ${e.message}")
            } finally {
                recording = false
                main.post { updateColor(context) }
            }
        }
    }

    /** Keyboard opened: show language and state again (the toolbar views are new). */
    fun onKeyboardShown(context: Context, root: View, keyboardLang: String) {
        inputActive = true
        this.root = root
        showLanguage(context, root, keyboardLang)
        updateColor(context)
    }

    /** Keyboard closed: drop an unfinished recording, never send it. */
    fun cancel() {
        inputActive = false
        if (recording) {
            cancelled = true
            stopRequested = true
        }
    }

    // ---- recording ---------------------------------------------------------------------

    /** Records until silence/tap; [segment] gets each finished piece (live: every pause). */
    @SuppressLint("MissingPermission") // checked in onMicKey
    private fun record(silenceMs: Int, live: Boolean, segment: (ByteArray) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        var out = ByteArrayOutputStream()
        val buf = ShortArray(RATE / 10) // 100 ms
        val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var heardAny = false
        var segSpeech = false
        var segMs = 0
        var quietMs = 0
        var totalMs = 0
        rec.startRecording()
        try {
            while (!stopRequested && totalMs < MAX_MS) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                bytes.clear()
                var sum = 0.0
                for (i in 0 until n) { bytes.putShort(buf[i]); sum += buf[i].toDouble() * buf[i] }
                out.write(bytes.array(), 0, n * 2)
                val ms = n * 1000 / RATE
                totalMs += ms
                segMs += ms
                if (sqrt(sum / n) > SPEECH_RMS) { heardAny = true; segSpeech = true; quietMs = 0 } else quietMs += ms
                if (heardAny && quietMs >= silenceMs) break
                if (!heardAny && totalMs >= NO_SPEECH_MS) return
                if (live && segSpeech && segMs >= LIVE_MIN_MS && quietMs >= LIVE_PAUSE_MS) {
                    segment(out.toByteArray())
                    out = ByteArrayOutputStream()
                    segSpeech = false
                    segMs = 0
                }
            }
        } finally {
            rec.stop()
            rec.release()
        }
        if (!cancelled && (segSpeech || (stopRequested && !live && heardAny))) segment(out.toByteArray())
    }

    /** Keep the piece as a file first, so a failed upload loses nothing. */
    private fun save(context: Context, pcm: ByteArray, language: String?, compress: Boolean) {
        val base = "${System.currentTimeMillis()}_${language ?: "auto"}"
        val dir = pendingDir(context)
        if (compress) {
            val tmp = File(dir, "$base.m4a.part")
            try {
                AacEncoder.encode(pcm, RATE, tmp)
                tmp.renameTo(File(dir, "$base.m4a"))
                return
            } catch (e: Exception) {
                Log.w(TAG, "AAC encoding failed, sending WAV", e)
                tmp.delete()
            }
        }
        val tmp = File(dir, "$base.wav.part")
        tmp.writeBytes(wav(pcm))
        tmp.renameTo(File(dir, "$base.wav"))
    }

    // ---- sending -----------------------------------------------------------------------

    private fun startSending(context: Context) {
        if (!draining.compareAndSet(false, true)) return
        main.post { updateColor(context) }
        sender.execute {
            var failed: String? = null
            try {
                val url = url(context)
                while (failed == null) {
                    val f = pendingFiles(context).firstOrNull() ?: break
                    val lang = f.name.substringAfter('_').substringBefore('.').takeIf { it != "auto" }
                    var text: String? = null
                    for (wait in RETRY_WAIT_MS) {
                        if (wait > 0) Thread.sleep(wait)
                        try { text = transcribe(url, f, lang); break }
                        catch (e: Exception) { Log.w(TAG, "upload failed", e); failed = e.message ?: e.javaClass.simpleName }
                    }
                    if (text == null) break
                    failed = null
                    f.delete()
                    deliver(context, text.trim())
                }
            } finally {
                draining.set(false)
                main.post { updateColor(context) }
                // a live piece may have been saved while the last upload finished
                if (failed == null && pendingFiles(context).isNotEmpty()) startSending(context)
            }
            failed?.let { toast(context, "Whisper: saatmine ebaõnnestus ($it). Salvestus on alles – vajuta mikrofoni uuesti.") }
        }
    }

    private fun deliver(context: Context, text: String) {
        if (text.isEmpty()) return
        main.post {
            val c = commit
            if (inputActive && c != null) c(text)
            else { // keyboard was closed meanwhile: do not lose the text
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Whisper", text))
                Toast.makeText(context, "Whisper: tekst on lõikelaual", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toast(context: Context, msg: String) =
        main.post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    private fun transcribe(url: String, audio: File, language: String?): String {
        val boundary = "----heliboard${System.nanoTime()}"
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) = body.write(
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        field("model", BuildConfig.WHISPER_MODEL)
        field("response_format", "json")
        if (!language.isNullOrEmpty()) field("language", language)
        val m4a = audio.name.endsWith(".m4a")
        body.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.${if (m4a) "m4a" else "wav"}\"\r\n" +
                "Content-Type: ${if (m4a) "audio/mp4" else "audio/wav"}\r\n\r\n").toByteArray())
        body.write(audio.readBytes())
        body.write("\r\n--$boundary--\r\n".toByteArray())

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (BuildConfig.WHISPER_KEY.isNotEmpty())
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.WHISPER_KEY}")
            conn.setFixedLengthStreamingMode(body.size())
            conn.outputStream.use { body.writeTo(it) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(200)
                throw RuntimeException("HTTP $code ${err ?: ""}")
            }
            return JSONObject(conn.inputStream.bufferedReader().readText()).optString("text")
        } finally {
            conn.disconnect()
        }
    }

    private fun updateColor(context: Context) {
        val color = when (state(context)) {
            State.IDLE -> null
            State.RECORDING -> Color.rgb(0xE5, 0x39, 0x35) // red
            State.SENDING -> Color.rgb(0xFB, 0x8C, 0x00)   // orange
            State.FAILED -> Color.rgb(0xAB, 0x47, 0xBC)    // purple: kept, tap to resend
        }
        root?.let { tintMicKeys(it, color) }
    }

    private fun tintMicKeys(root: View, color: Int?) = forMicKeys(root) { v ->
        if (color == null) v.clearColorFilter() else v.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun forMicKeys(v: View, action: (ImageView) -> Unit) {
        if (v.tag == ToolbarKey.VOICE && v is ImageView) action(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) forMicKeys(v.getChildAt(i), action)
    }
}
