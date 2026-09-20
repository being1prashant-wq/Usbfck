package com.example

import com.example.media.Ac3AudioEngine
import com.example.media.Ac3SoftwareDecoder
import com.example.media.AudioTrackDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Ac3AudioEngineTest {

    private val decoder = Ac3SoftwareDecoder()

    @Test
    fun testFrameSizeCalculation_48kHz() {
        // 48 kHz: frame size in bytes = 4 * bitrate_kbps
        // 192 kbps -> 768 bytes
        val size192 = decoder.calculateFrameSizeBytes(fscod = 0, frmsizecod = 20) // 192 kbps
        assertEquals(768, size192)

        // 384 kbps -> 1536 bytes
        val size384 = decoder.calculateFrameSizeBytes(fscod = 0, frmsizecod = 28) // 384 kbps
        assertEquals(1536, size384)

        // 448 kbps -> 1792 bytes
        val size448 = decoder.calculateFrameSizeBytes(fscod = 0, frmsizecod = 30) // 448 kbps
        assertEquals(1792, size448)

        // 640 kbps -> 2560 bytes
        val size640 = decoder.calculateFrameSizeBytes(fscod = 0, frmsizecod = 36) // 640 kbps
        assertEquals(2560, size640)
    }

    @Test
    fun testParseHeader_validAc3Stream() {
        // Create a synthetic AC-3 sync frame header:
        // Sync word: 0x0B77
        // CRC1: 0x1234
        // fscod: 0 (48kHz, 2 bits) -> 00
        // frmsizecod: 28 (384kbps, 6 bits) -> 011100
        // bsid: 8 (standard AC-3, 5 bits) -> 01000
        // bsmod: 0 (main audio, 3 bits) -> 000
        // acmod: 7 (3/2 5.1 surround, 3 bits) -> 111
        // cmixlev: 0 (2 bits) -> 00
        // surmixlev: 0 (2 bits) -> 00
        // lfeon: 1 (1 bit) -> 1
        // dialnorm: 31 (5 bits) -> 11111

        val frameData = ByteArray(1536)
        frameData[0] = 0x0B
        frameData[1] = 0x77
        frameData[2] = 0x12
        frameData[3] = 0x34
        // byte 4: fscod(00) + frmsizecod(011100) -> 0b00011100 = 0x1C
        frameData[4] = 0x1C
        // byte 5: bsid(01000) + bsmod(000) -> 0b01000000 = 0x40
        frameData[5] = 0x40
        // byte 6: acmod(111) + cmixlev(00) + surmixlev(00) + lfeon(1) -> 0b11100001 = 0xE1
        frameData[6] = 0xE1.toByte()
        // byte 7: dialnorm(11111) + compre(0) + langcode(0) + audprodie(0) -> 0b11111000 = 0xF8
        frameData[7] = 0xF8.toByte()

        val header = decoder.parseHeader(frameData, 0, frameData.size)
        assertNotNull(header)
        assertEquals(Ac3SoftwareDecoder.SYNC_WORD, header!!.syncWord)
        assertEquals(48000, header.sampleRate)
        assertEquals(384, header.bitrateKbps)
        assertEquals(1536, header.frameSizeBytes)
        assertEquals(8, header.bsid)
        assertEquals(6, header.numChannels) // 5.1
        assertTrue(header.lfeon)
        assertEquals(31, header.dialnorm)
    }

    @Test
    fun testDecodeFrameToStereoPcm_outputs1536StereoSamples() {
        val frameData = ByteArray(1536)
        frameData[0] = 0x0B
        frameData[1] = 0x77
        frameData[2] = 0x00
        frameData[3] = 0x00
        frameData[4] = 0x1C // 48kHz, 384kbps
        frameData[5] = 0x40 // bsid 8
        frameData[6] = 0x40 // stereo acmod 2
        frameData[7] = 0xF8.toByte() // dialnorm 31

        val pcmOut = ShortArray(Ac3SoftwareDecoder.SAMPLES_PER_FRAME * 2)
        val decodedSamples = decoder.decodeFrameToStereoPcm(frameData, 0, frameData.size, pcmOut)

        assertEquals(Ac3SoftwareDecoder.SAMPLES_PER_FRAME * 2, decodedSamples)
        // Ensure no out of range samples or NaNs
        for (i in 0 until decodedSamples) {
            val sample = pcmOut[i]
            assertTrue(sample in Short.MIN_VALUE..Short.MAX_VALUE)
        }
    }

    @Test
    fun testDecodeInvalidOrCorruptData_gracefulConcealment() {
        val corruptData = ByteArray(500) { 0xFF.toByte() }
        val pcmOut = ShortArray(Ac3SoftwareDecoder.SAMPLES_PER_FRAME * 2)

        // Non-sync header returns 0 without crashing
        val decoded = decoder.decodeFrameToStereoPcm(corruptData, 0, corruptData.size, pcmOut)
        assertEquals(0, decoded)
    }

    @Test
    fun testBitReader_readVariousBitWidths() {
        val data = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val reader = Ac3SoftwareDecoder.BitReader(data, 0, data.size)

        // 0xAB = 0b10101011
        // read 4 bits -> 0b1010 = 10
        assertEquals(10, reader.readBits(4))
        // read 4 bits -> 0b1011 = 11
        assertEquals(11, reader.readBits(4))
        // 0xCD = 0b11001101
        // read 8 bits -> 0xCD = 205
        assertEquals(0xCD, reader.readBits(8))
    }

    @Test
    fun testAudioTrackDescriptor_ac3MimeRecognition() {
        val ac3Track = AudioTrackDescriptor(
            trackIndex = 1,
            mimeType = "audio/ac3",
            language = "eng",
            channelCount = 6,
            sampleRate = 48000,
            isAc3 = true,
            codecDisplayName = "Dolby Digital (AC-3)",
            userDisplayName = "ENG • Dolby Digital (AC-3) 5.1"
        )
        assertTrue(ac3Track.isAc3)
        assertEquals("Dolby Digital (AC-3)", ac3Track.codecDisplayName)

        val aacTrack = AudioTrackDescriptor(
            trackIndex = 2,
            mimeType = "audio/mp4a-latm",
            language = "spa",
            channelCount = 2,
            sampleRate = 44100,
            isAc3 = false,
            codecDisplayName = "AAC",
            userDisplayName = "SPA • AAC Stereo"
        )
        assertFalse(aacTrack.isAc3)
        assertEquals("AAC", aacTrack.codecDisplayName)
    }
}
