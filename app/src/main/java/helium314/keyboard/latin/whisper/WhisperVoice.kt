// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.whisper

import android.Manifest
import android.annotation.SuppressLint
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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Voice input without switching keyboards: the mic key records here and sends the
 * audio to an OpenAI-compatible Whisper endpoint (BuildConfig.WHISPER_URL).
 * Tap mic: start; tap again or stay silent: stop and transcribe.
 * Switching to another voice IME loses the text field in some apps (HeliBoard #1772).
 */
object WhisperVoice {
    private const val TAG = "WhisperVoice"
    /** long-press code of the toolbar mic key: record in the other language */
    const val CODE_OTHER_LANGUAGE = -10_233

    private const val RATE = 16_000
    private const val SILENCE_MS = 1_500
    private const val NO_SPEECH_MS = 8_000
    private const val MAX_MS = 180_000
    private const val SPEECH_RMS = 700.0

    private enum class State { IDLE, RECORDING, SENDING }

    @Volatile private var state = State.IDLE
    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    private val main = Handler(Looper.getMainLooper())

    val isEnabled get() = BuildConfig.WHISPER_URL.isNotEmpty()

    /**
     * Mic key pressed. [language] is an ISO code ("et", "en") or null for auto-detect.
     * [root] is the keyboard window, used to colour the mic key.
     * [commit] inserts the transcript; called on the main thread.
     */
    fun onMicKey(context: Context, language: String?, root: View?, commit: (String) -> Unit) {
        when (state) {
            State.RECORDING -> { stopRequested = true; return }
            State.SENDING -> return
            State.IDLE -> {}
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent(context, WhisperPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        stopRequested = false
        cancelled = false
        setState(State.RECORDING, root)
        thread(name = "whisper") {
            val text = try {
                val pcm = record()
                if (pcm == null || cancelled) null
                else {
                    main.post { setState(State.SENDING, root) }
                    transcribe(wav(pcm), language)
                }
            } catch (e: Exception) {
                Log.w(TAG, "voice input failed", e)
                main.post { Toast.makeText(context, "Whisper: ${e.message}", Toast.LENGTH_LONG).show() }
                null
            }
            main.post {
                setState(State.IDLE, root)
                if (!text.isNullOrBlank() && !cancelled) commit(text.trim())
            }
        }
    }

    /** Keyboard closed: drop an unfinished recording, never send it. */
    fun cancel() {
        if (state == State.RECORDING) {
            cancelled = true
            stopRequested = true
        }
    }

    @SuppressLint("MissingPermission") // checked in onMicKey
    private fun record(): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        val out = ByteArrayOutputStream()
        val buf = ShortArray(RATE / 10) // 100 ms
        val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var heardSpeech = false
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
                if (sqrt(sum / n) > SPEECH_RMS) { heardSpeech = true; quietMs = 0 } else quietMs += ms
                if (heardSpeech && quietMs >= SILENCE_MS) break
                if (!heardSpeech && totalMs >= NO_SPEECH_MS) return null
            }
        } finally {
            rec.stop()
            rec.release()
        }
        return if (heardSpeech || stopRequested) out.toByteArray() else null
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    private fun transcribe(wav: ByteArray, language: String?): String {
        val boundary = "----heliboard${System.nanoTime()}"
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) = body.write(
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        field("model", BuildConfig.WHISPER_MODEL)
        field("response_format", "json")
        if (!language.isNullOrEmpty()) field("language", language)
        body.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray())
        body.write(wav)
        body.write("\r\n--$boundary--\r\n".toByteArray())

        val conn = URL(BuildConfig.WHISPER_URL).openConnection() as HttpURLConnection
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

    private fun setState(s: State, root: View?) {
        state = s
        val color = when (s) {
            State.IDLE -> null
            State.RECORDING -> Color.rgb(0xE5, 0x39, 0x35)
            State.SENDING -> Color.rgb(0xFB, 0x8C, 0x00)
        }
        root?.let { tintMicKeys(it, color) }
    }

    private fun tintMicKeys(v: View, color: Int?) {
        if (v.tag == ToolbarKey.VOICE && v is ImageView) {
            if (color == null) v.clearColorFilter() else v.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) tintMicKeys(v.getChildAt(i), color)
    }
}
