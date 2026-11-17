// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.whisper

import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One dictation streamed to the server's /ws/live-transcribe: the current utterance as it
 * grows ([onPartial], about every second), each finished utterance ([onSegment]) and one
 * clean text of the whole recording at the end ([finish]).
 *
 * Audio goes out as [wanted]: "f32" (raw float32, 512 kbit/s), "s16" (raw 16-bit, 256 kbit/s)
 * or "opus" (about 24 kbit/s). A server that knows formats says so in its first message
 * ("ready"); to one that stays silent the audio is sent as f32, the only format it reads.
 */
class LiveSession(
    url: String, language: String?, apiKey: String, wanted: String,
    private val onSegment: (String) -> Unit,
    private val onPartial: (String) -> Unit,
) {
    @Volatile private var finalText: String? = null
    @Volatile var failed: String? = null
        private set
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val ws: WebSocket

    private val lock = Any()
    private var asked = wanted
    private var format: String? = null // decided by the server's "ready", or f32 without it
    private var encoder: OpusEncoder? = null
    private val waiting = ArrayList<ShortArray>() // audio recorded before the format is known

    // where the time goes, for the settings screen
    private val created = SystemClock.elapsedRealtime()
    @Volatile private var openedAt = 0L
    @Volatile private var firstTextAt = 0L
    @Volatile private var finalAt = 0L
    @Volatile private var serverWaitMs = -1
    @Volatile private var serverAsrMs = -1
    private var endAt = 0L
    private var bytes = 0L
    private var samples = 0L
    private var queuedMax = 0L
    private var queuedAtEnd = 0L

    init {
        if (asked == "opus") try { encoder = OpusEncoder(RATE) } catch (e: Exception) {
            android.util.Log.w(TAG, "no Opus encoder, sending 16-bit PCM", e)
            asked = "s16"
        }
        if (asked == "f32") format = "f32"
        val full = url + (if ('?' in url) "&" else "?") + "final=true&partial_ms=1000" +
                (if (language != null) "&language=$language" else "") +
                (if (asked != "f32") "&format=$asked" else "")
        val request = Request.Builder().url(full)
        if (apiKey.isNotEmpty()) request.header("Authorization", "Bearer $apiKey")
        ws = client.newWebSocket(request.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedAt = SystemClock.elapsedRealtime()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (_: Exception) { return }
                when (m.optString("type")) {
                    "ready" -> decide(m.optString("format", "f32"), fromServer = true)
                    "segment" -> m.optString("text").trim().takeIf { it.isNotEmpty() }?.let { firstText(); onSegment(it) }
                    "partial" -> m.optString("text").trim().takeIf { it.isNotEmpty() }?.let { firstText(); onPartial(it) }
                    "final" -> {
                        finalAt = SystemClock.elapsedRealtime()
                        serverWaitMs = m.optInt("wait_ms", -1)
                        serverAsrMs = m.optInt("asr_ms", -1)
                        finalText = m.optString("text").trim()
                    }
                    "done" -> done.countDown()
                    "error" -> { failed = m.optString("detail", "error"); ready.countDown(); done.countDown() }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failed = t.message ?: t.javaClass.simpleName
                ready.countDown()
                done.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
        })
    }

    private fun firstText() { if (firstTextAt == 0L) firstTextAt = SystemClock.elapsedRealtime() }

    private fun decide(fmt: String, fromServer: Boolean) = synchronized(lock) {
        if (fromServer) ready.countDown()
        if (format != null) {
            // "ready" came after f32 was already going out as the fallback: the server decodes
            // it as something else. Give the live text up; the recording is uploaded instead.
            if (fromServer && fmt != format) failed = "late ready ($fmt)"
            return@synchronized
        }
        format = fmt
        waiting.forEach { sendNow(it, it.size) }
        waiting.clear()
    }

    /** 16-bit PCM from the microphone, sent on in the agreed format. */
    fun send(pcm: ShortArray, n: Int) = synchronized(lock) {
        if (failed != null) return@synchronized
        samples += n
        if (format == null) {
            // no "ready" within READY_WAIT_MS of connecting: a server from before formats
            val opened = openedAt
            if (opened != 0L && SystemClock.elapsedRealtime() - opened > READY_WAIT_MS) decide("f32", fromServer = false)
            else { waiting.add(pcm.copyOf(n)); return@synchronized }
        }
        sendNow(pcm, n)
    }

    private fun sendNow(pcm: ShortArray, n: Int) {
        try {
            when (format) {
                "opus" -> (encoder ?: throw IllegalStateException("no encoder")).encode(pcm, n).forEach { put(it) }
                "s16" -> {
                    val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) b.putShort(pcm[i])
                    put(b.array())
                }
                else -> {
                    val b = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) b.putFloat(pcm[i] / 32768f)
                    put(b.array())
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "sending $format failed", e)
            failed = "$format: ${e.message}"
        }
    }

    private fun put(frame: ByteArray) {
        ws.send(frame.toByteString())
        bytes += frame.size
        queuedMax = maxOf(queuedMax, ws.queueSize())
    }

    /** End of recording: the final text, or null when the live connection failed. */
    fun finish(timeoutMs: Long): String? {
        if (failed == null) {
            // a short recording can end before the server said which format it reads
            val left = READY_WAIT_MS - (SystemClock.elapsedRealtime() - created)
            if (left > 0) ready.await(left, TimeUnit.MILLISECONDS)
            synchronized(lock) {
                if (format == null) decide("f32", fromServer = false)
                if (format == "opus" && failed == null) try { encoder?.finish()?.forEach { put(it) } } catch (_: Exception) {}
                queuedAtEnd = ws.queueSize()
                endAt = SystemClock.elapsedRealtime()
            }
        }
        if (failed == null) ws.send("END")
        val ok = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        ws.close(1000, null)
        encoder?.release()
        return if (ok && failed == null) finalText else null
    }

    fun abort() {
        ws.cancel()
        encoder?.release()
    }

    /** One line for the log and the settings screen: where the time of this dictation went. */
    fun summary(): String {
        fun s(ms: Long) = "%.1f s".format(ms / 1000f)
        val parts = ArrayList<String>()
        val audioMs = samples * 1000 / RATE
        parts += "voog ${format ?: asked}, ${s(audioMs)} heli, ${bytes / 1024} KB" +
                if (audioMs > 0) " (${bytes * 8 / audioMs} kbit/s)" else ""
        if (openedAt != 0L) parts += "ühendus ${openedAt - created} ms"
        if (firstTextAt != 0L) parts += "esimene tekst ${s(firstTextAt - created)}"
        if (queuedMax > 0) parts += "saatmise järjekord kuni ${queuedMax / 1024} KB, lõpus ${queuedAtEnd / 1024} KB"
        if (finalAt != 0L && endAt != 0L) {
            val total = finalAt - endAt
            var t = "lõpptekst ${s(total)} pärast rääkimise lõppu"
            if (serverAsrMs >= 0) t += " (server: järjekord ${serverWaitMs} ms, mudel ${serverAsrMs} ms; " +
                    "võrk ja saatmine ${maxOf(0, total - serverWaitMs - serverAsrMs)} ms)"
            parts += t
        }
        failed?.let { parts += "viga: $it" }
        return parts.joinToString("; ")
    }

    companion object {
        private const val TAG = "WhisperLive"
        private const val RATE = 16_000
        private const val READY_WAIT_MS = 2_000L
        val FORMATS = listOf("opus", "s16", "f32")

        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
        }

        /** wss://host/ws/live-transcribe from the transcription URL https://host/v1/audio/transcriptions */
        fun urlFor(transcriptionUrl: String): String {
            val u = java.net.URI(transcriptionUrl)
            val scheme = if (u.scheme == "http") "ws" else "wss"
            val port = if (u.port > 0) ":${u.port}" else ""
            return "$scheme://${u.host}$port/ws/live-transcribe"
        }
    }
}
