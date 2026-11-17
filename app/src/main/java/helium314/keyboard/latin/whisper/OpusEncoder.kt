// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.whisper

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * 16-bit mono PCM to Opus packets with the platform encoder (Android 10+; the constructor
 * throws where there is none). One returned array = one Opus packet = one WebSocket frame.
 */
class OpusEncoder(private val rate: Int, bitrate: Int = 24_000) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun encode(pcm: ShortArray, n: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var off = 0
        while (off < n) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val b = codec.getInputBuffer(i)!!
                b.clear()
                val count = minOf(n - off, b.remaining() / 2)
                b.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off, count)
                codec.queueInputBuffer(i, 0, count * 2, ptsUs, 0)
                ptsUs += count * 1_000_000L / rate
                off += count
            }
            drain(out, 0)
        }
        return out
    }

    /** The packets still inside the encoder; the encoder is closed afterwards. */
    fun finish(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        try {
            val i = codec.dequeueInputBuffer(100_000)
            if (i >= 0) {
                codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                var tries = 0
                while (!drain(out, 20_000) && tries++ < 25) { /* until end of stream, at most 0.5 s */ }
            }
        } catch (_: Exception) {
        } finally {
            release()
        }
        return out
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }

    /** true at end of stream */
    private fun drain(out: MutableList<ByteArray>, timeoutUs: Long): Boolean {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, timeoutUs)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (o < 0) return false
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!config && info.size > 0) {
                val b = codec.getOutputBuffer(o)!!
                val packet = ByteArray(info.size)
                b.position(info.offset)
                b.get(packet)
                out.add(packet)
            }
            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(o, false)
            if (end) return true
        }
    }
}
