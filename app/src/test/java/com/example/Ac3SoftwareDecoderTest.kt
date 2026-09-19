package com.example

import com.example.media.Ac3SoftwareDecoder
import com.example.media.AudioTrackInfo
import com.example.media.AudioProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ac3SoftwareDecoderTest {

    @Test
    fun testIsAc3Codec_recognizesAc3Variants() {
        assertTrue(Ac3SoftwareDecoder.isAc3Codec("ac3"))
        assertTrue(Ac3SoftwareDecoder.isAc3Codec("AC3"))
        assertTrue(Ac3SoftwareDecoder.isAc3Codec("ac-3"))
        assertTrue(Ac3SoftwareDecoder.isAc3Codec("eac3"))
        assertTrue(Ac3SoftwareDecoder.isAc3Codec("E-AC-3"))
        assertTrue(Ac3SoftwareDecoder.isAc3Codec("dolby"))
        assertTrue(Ac3SoftwareDecoder.isAc3Codec("a52"))
        assertTrue(Ac3SoftwareDecoder.isAc3Codec("dca"))
        assertTrue(Ac3SoftwareDecoder.isAc3Codec("dts"))

        assertFalse(Ac3SoftwareDecoder.isAc3Codec("aac"))
        assertFalse(Ac3SoftwareDecoder.isAc3Codec("mp3"))
        assertFalse(Ac3SoftwareDecoder.isAc3Codec("opus"))
        assertFalse(Ac3SoftwareDecoder.isAc3Codec("vorbis"))
        assertFalse(Ac3SoftwareDecoder.isAc3Codec("flac"))
        assertFalse(Ac3SoftwareDecoder.isAc3Codec(null))
    }

    @Test
    fun testAudioProbeResult_detectsAc3Track() {
        val tracks = listOf(
            AudioTrackInfo(
                index = 0,
                streamIndex = 1,
                codec = "aac",
                channels = 2,
                sampleRate = 44100,
                language = "eng",
                isAc3 = false
            ),
            AudioTrackInfo(
                index = 1,
                streamIndex = 2,
                codec = "ac3",
                channels = 6,
                sampleRate = 48000,
                language = "spa",
                isAc3 = true
            )
        )

        val probeResult = AudioProbeResult(
            hasAc3 = tracks.any { it.isAc3 },
            audioTracks = tracks,
            videoCodec = "h264",
            format = "matroska,webm",
            durationMs = 120_000L
        )

        assertTrue(probeResult.hasAc3)
        assertEquals(2, probeResult.audioTracks.size)
        assertTrue(probeResult.audioTracks[1].isAc3)
        assertEquals(6, probeResult.audioTracks[1].channels)
        assertEquals("h264", probeResult.videoCodec)
    }
}
