// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.whisper

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
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
 */
class LiveSession(
    url: String, language: String?,
    private val onSegment: (String) -> Unit,
    private val onPartial: (String) -> Unit,
) {
    @Volatile private var finalText: String? = null
    @Volatile var failed: String? = null
        private set
    private val done = CountDownLatch(1)
    private val ws: WebSocket

    init {
        val full = url + (if ('?' in url) "&" else "?") + "final=true&partial_ms=1000" +
                (if (language != null) "&language=$language" else "")
        ws = client.newWebSocket(Request.Builder().url(full).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (_: Exception) { return }
                when (m.optString("type")) {
                    "segment" -> m.optString("text").trim().takeIf { it.isNotEmpty() }?.let(onSegment)
                    "partial" -> m.optString("text").trim().takeIf { it.isNotEmpty() }?.let(onPartial)
                    "final" -> finalText = m.optString("text").trim()
                    "done" -> done.countDown()
                    "error" -> { failed = m.optString("detail", "error"); done.countDown() }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failed = t.message ?: t.javaClass.simpleName
                done.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
        })
    }

    /** 16-bit PCM in, float32 little-endian out (what the server expects). */
    fun send(pcm: ShortArray, n: Int) {
        if (failed != null) return
        val b = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putFloat(pcm[i] / 32768f)
        ws.send(b.array().toByteString())
    }

    /** End of recording: the final text, or null when the live connection failed. */
    fun finish(timeoutMs: Long): String? {
        if (failed == null) ws.send("END")
        val ok = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        ws.close(1000, null)
        return if (ok && failed == null) finalText else null
    }

    fun abort() { ws.cancel() }

    companion object {
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
