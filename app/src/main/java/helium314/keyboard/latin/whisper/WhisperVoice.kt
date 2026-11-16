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
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
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
    // speech = louder than SPEECH_FACTOR x the noise floor measured in the first 300 ms
    private const val MIN_SPEECH_RMS = 120.0
    private const val SPEECH_FACTOR = 2.5

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

    /** one line for the settings list: pause and languages */
    fun summary(context: Context): String {
        val pause = prefs(context).getInt(KEY_SILENCE_MS, DEFAULT_SILENCE_MS) / 1000f
        val langs = languages(context).joinToString(", ").ifEmpty { "klaviatuuri keel" }
        return "Paus %.1f s · %s".format(pause, langs)
    }

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

    private const val LIVE_PAUSE_MS = 400
    private const val LIVE_MIN_MS = 1_500
    private val RETRY_WAIT_MS = longArrayOf(0, 2_000, 5_000)

    private fun pendingDir(context: Context) = File(context.filesDir, "whisper_pending").apply { mkdirs() }
    private fun queuedFiles(context: Context): List<File> =
        pendingDir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedBy { it.name } ?: emptyList()
    private fun File.isPreview() = name.substringBefore('.').endsWith("_p")
    /** recordings whose text has not reached the field yet */
    fun pendingFiles(context: Context): List<File> = queuedFiles(context).filter { !it.isPreview() }
    fun clearPending(context: Context) { pendingDir(context).listFiles()?.forEach { it.delete() } }

    @Volatile private var finishing = false // waiting for the live final text
    @Volatile private var liveSession: LiveSession? = null

    private fun state(context: Context) = when {
        recording -> State.RECORDING
        finishing || draining.get() -> State.SENDING
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
        queuedFiles(context).filter { it.isPreview() }.forEach { it.delete() }
        previewText.setLength(0)
        updateColor(context)
        val url = url(context)
        thread(name = "whisper-rec") {
            // live: stream to the server, pieces appear on the toolbar row while talking
            val session = if (live) try { LiveSession(LiveSession.urlFor(url), language) { addPreview(it) } }
                catch (e: Exception) { android.util.Log.w(TAG, "live session failed", e); null } else null
            liveSession = session
            try {
                record(silenceMs, live && session == null) { pcm, final ->
                    if (!final) { save(context, pcm, language, compress, false); startSending(context); return@record }
                    if (session != null) {
                        finishing = true
                        recording = false
                        main.post { updateColor(context) }
                        val text = session.finish(60_000)
                        finishing = false
                        if (text != null) {
                            android.util.Log.i(TAG, "live final: ${text.length} chars")
                            deliver(context, text)
                            return@record
                        }
                        android.util.Log.w(TAG, "live failed (${session.failed}), uploading the recording")
                    }
                    save(context, pcm, language, compress, true)
                    startSending(context)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "recording failed", e)
                toast(context, "Whisper: ${e.message}")
            } finally {
                session?.abort() // no-op after finish; closes it when nothing was said
                liveSession = null
                finishing = false
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
            liveSession?.abort()
        }
        root?.let { hidePreview(it) }
    }

    // ---- recording ---------------------------------------------------------------------

    /** Records until silence/tap. Live: [segment] gets a preview piece at every pause; always the whole recording at the end. */
    @SuppressLint("MissingPermission") // checked in onMicKey
    private fun record(silenceMs: Int, live: Boolean, segment: (ByteArray, Boolean) -> Unit) {
        val session = liveSession
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        var out = ByteArrayOutputStream()
        val all = ByteArrayOutputStream()
        val buf = ShortArray(RATE / 10) // 100 ms
        val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var heardAny = false
        var segSpeech = false
        var segMs = 0
        var quietMs = 0
        var totalMs = 0
        var noise = 0.0
        var maxRms = 0.0
        var threshold = Double.MAX_VALUE
        rec.startRecording()
        try {
            while (!stopRequested && totalMs < MAX_MS) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                bytes.clear()
                var sum = 0.0
                for (i in 0 until n) { bytes.putShort(buf[i]); sum += buf[i].toDouble() * buf[i] }
                out.write(bytes.array(), 0, n * 2)
                all.write(bytes.array(), 0, n * 2)
                session?.send(buf, n)
                val ms = n * 1000 / RATE
                totalMs += ms
                segMs += ms
                val rms = sqrt(sum / n)
                maxRms = maxOf(maxRms, rms)
                if (totalMs <= 300) { // noise floor; the user rarely starts talking this fast
                    noise = maxOf(noise, rms)
                    threshold = maxOf(MIN_SPEECH_RMS, noise * SPEECH_FACTOR)
                    continue
                }
                if (rms > threshold) { heardAny = true; segSpeech = true; quietMs = 0 } else quietMs += ms
                if (heardAny && quietMs >= silenceMs) break
                if (!heardAny && totalMs >= NO_SPEECH_MS) break
                if (live && segSpeech && segMs >= LIVE_MIN_MS && quietMs >= LIVE_PAUSE_MS) {
                    android.util.Log.i(TAG, "live piece at $totalMs ms ($segMs ms)")
                    segment(out.toByteArray(), false)
                    out = ByteArrayOutputStream()
                    segSpeech = false
                    segMs = 0
                }
            }
        } finally {
            rec.stop()
            rec.release()
            android.util.Log.i(TAG, "recorded ${totalMs} ms, live ${if (session != null) "server" else live}, noise %.0f, threshold %.0f, max %.0f, speech $heardAny, stopped by tap $stopRequested".format(noise, threshold, maxRms))
        }
        // a tap always sends what was recorded; silence-stop sends only when speech was heard
        val tapped = stopRequested && !cancelled
        if (!cancelled && (heardAny || (tapped && totalMs >= 500))) segment(all.toByteArray(), true)
    }

    /** Keep the piece as a file first, so a failed upload loses nothing. */
    private fun save(context: Context, pcm: ByteArray, language: String?, compress: Boolean, final: Boolean) {
        val base = "${System.currentTimeMillis()}_${language ?: "auto"}_${if (final) "f" else "p"}"
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
                    val f = queuedFiles(context).firstOrNull() ?: break
                    val lang = f.name.split('_').getOrNull(1)?.substringBefore('.')?.takeIf { it != "auto" }
                    if (f.isPreview()) { // one try; the final text covers it anyway
                        // recording already finished: skip the preview, the final text matters now
                        if (queuedFiles(context).any { !it.isPreview() }) { f.delete(); continue }
                        val t = try { transcribe(url, f, lang) } catch (e: Exception) { null }
                        f.delete()
                        if (!t.isNullOrBlank()) addPreview(t.trim())
                        continue
                    }
                    var text: String? = null
                    for (wait in RETRY_WAIT_MS) {
                        if (wait > 0) Thread.sleep(wait)
                        try { text = transcribe(url, f, lang); break }
                        catch (e: Exception) { android.util.Log.w(TAG, "upload failed", e); failed = e.message ?: e.javaClass.simpleName }
                    }
                    if (text == null) break
                    failed = null
                    android.util.Log.i(TAG, "sent ${f.name} (${f.length() / 1024} KB): ${text.length} chars")
                    f.delete()
                    deliver(context, text.trim())
                }
            } finally {
                draining.set(false)
                main.post { updateColor(context) }
                // a live piece may have been saved while the last upload finished
                if (failed == null && queuedFiles(context).isNotEmpty()) startSending(context)
            }
            if (failed != null) main.post { root?.let { hidePreview(it) } }
            failed?.let { toast(context, "Whisper: saatmine ebaõnnestus ($it). Salvestus on alles – vajuta mikrofoni uuesti.") }
        }
    }

    private fun deliver(context: Context, text: String) {
        if (text.isEmpty()) return
        main.post {
            root?.let { hidePreview(it) }
            val c = commit
            if (inputActive && c != null) c(text)
            else { // keyboard was closed meanwhile: do not lose the text
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Whisper", text))
                Toast.makeText(context, "Whisper: tekst on lõikelaual", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- live preview on the toolbar row ------------------------------------------------

    private val previewText = StringBuilder()
    private var previewView: TextView? = null
    private val hidden = mutableMapOf<View, Int>()

    private fun addPreview(piece: String) = main.post {
        if (previewText.isNotEmpty()) previewText.append(' ')
        previewText.append(piece)
        root?.let { showPreview(it, previewText.toString()) }
    }

    private fun showPreview(root: View, text: String) {
        val wrapper = root.findViewById<LinearLayout>(R.id.suggestions_strip_wrapper) ?: return
        val tv = previewView?.takeIf { it.parent === wrapper } ?: TextView(root.context).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START // newest words stay visible
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            setTextColor(try { Settings.getValues().mColors.get(ColorType.KEY_TEXT) } catch (_: Exception) { Color.WHITE })
            val pinned = wrapper.findViewById<View>(R.id.pinned_keys)
            val at = wrapper.indexOfChild(pinned).let { if (it < 0) wrapper.childCount else it }
            for (id in intArrayOf(R.id.suggestions_strip, R.id.toolbar_container)) {
                wrapper.findViewById<View>(id)?.let { v -> hidden[v] = v.visibility; v.visibility = View.GONE }
            }
            wrapper.addView(this, at, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            previewView = this
        }
        tv.text = text
    }

    private fun hidePreview(root: View) {
        previewView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        previewView = null
        hidden.forEach { (v, vis) -> v.visibility = vis }
        hidden.clear()
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
