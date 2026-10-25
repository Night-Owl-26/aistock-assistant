package com.aistock.assistant.voice

import com.aistock.assistant.voice.cloud.Endpointer
import com.aistock.assistant.voice.cloud.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class SpeechAudioTest {
    private fun wav(rate: Int, channels: Int, bits: Int, data: ByteArray, extraChunk: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun s(x: String) = out.write(x.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) { for (k in 0 until 4) out.write((v shr (8 * k)) and 0xff) }
        fun i16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        s("RIFF"); i32(36 + data.size); s("WAVE")
        if (extraChunk) { s("LIST"); i32(3); out.write(byteArrayOf(1, 2, 3)); out.write(0) }
        s("fmt "); i32(16); i16(1); i16(channels); i32(rate); i32(rate * channels * bits / 8); i16(channels * bits / 8); i16(bits)
        s("data"); i32(data.size); out.write(data)
        return out.toByteArray()
    }

    @Test
    fun readsGoogleWavReplies() {
        val pcm = ByteArray(4800) { (it % 7).toByte() }
        val p = Wav.parse(wav(24000, 1, 16, pcm, extraChunk = true))!!
        assertEquals(24000, p.sampleRate)
        assertEquals(1, p.channels)
        assertEquals(pcm.toList(), p.data.toList())
        assertNull(Wav.parse(wav(24000, 1, 8, pcm)))
        assertNull(Wav.parse("not audio".toByteArray()))
        assertNull(Wav.parse(ByteArray(0)))
    }

    private fun frame(level: Int) = ShortArray(480) { if (it % 2 == 0) level.toShort() else (-level).toShort() }

    @Test
    fun stopsAfterAPauseOnceSomeoneHasSpoken() {
        val e = Endpointer()
        repeat(20) { assertEquals(Endpointer.State.WAITING, e.feed(frame(60), 480)) } // room noise
        repeat(2) { e.feed(frame(4000), 480) }
        assertEquals("a click is not speech", Endpointer.State.WAITING, e.feed(frame(60), 480))
        repeat(30) { e.feed(frame(4000), 480) }
        assertEquals(Endpointer.State.SPEAKING, e.state)
        repeat(20) { e.feed(frame(60), 480) } // a short breath inside the sentence
        assertEquals(Endpointer.State.SPEAKING, e.state)
        repeat(10) { e.feed(frame(3000), 480) }
        repeat(40) { e.feed(frame(60), 480) }
        assertEquals(Endpointer.State.DONE, e.state)
    }

    @Test
    fun givesUpWhenNobodySpeaksAndStopsAtTheTimeLimit() {
        val quiet = Endpointer()
        repeat(240) { quiet.feed(frame(80), 480) }
        assertEquals(Endpointer.State.NO_SPEECH, quiet.state)
        val long = Endpointer(maxMs = 3_000)
        repeat(200) { long.feed(frame(5000), 480) }
        assertEquals(Endpointer.State.DONE, long.state)
    }
}
