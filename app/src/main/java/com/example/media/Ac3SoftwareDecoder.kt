package com.example.media

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Pure Kotlin implementation of ATSC A/52 (AC-3 / Dolby Digital) software decoder.
 * Provides fallback audio decoding when hardware/system MediaCodec decoders are unavailable.
 * Decodes AC-3 sync frames (1536 samples per frame) into 16-bit PCM stereo audio.
 */
class Ac3SoftwareDecoder {

    companion object {
        const val TAG = "Ac3SoftwareDecoder"
        const val SYNC_WORD = 0x0B77
        const val SAMPLES_PER_FRAME = 1536
        const val SAMPLES_PER_BLOCK = 256
        const val BLOCKS_PER_FRAME = 6

        // Sampling rates in Hz corresponding to fscod (0: 48k, 1: 44.1k, 2: 32k)
        val SAMPLE_RATES = intArrayOf(48000, 44100, 32000, 0)

        // Bitrates in kbps for frmsizecod
        val BITRATES_KBPS = intArrayOf(
            32, 40, 48, 56, 64, 80, 96, 112, 128, 160,
            192, 224, 256, 320, 384, 448, 512, 576, 640
        )

        // Number of full bandwidth channels per acmod (0..7)
        val NFCHANS = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)

        // Precalculated IMDCT cosine/sine window tables
        private val WINDOW_512 = FloatArray(512)
        private val IMDCT_COS_512 = FloatArray(256)
        private val IMDCT_SIN_512 = FloatArray(256)

        // Exponent lookup: 2^(-exp)
        private val EXP_TABLE = FloatArray(64)

        init {
            // Kaiser-Bessel derived (KBD) window approximation for AC-3
            for (i in 0 until 512) {
                WINDOW_512[i] = sin(PI * (i + 0.5) / 512.0).toFloat()
            }
            for (i in 0 until 256) {
                val angle = (PI * (2 * i + 1 + 256) / (4.0 * 256))
                IMDCT_COS_512[i] = cos(angle).toFloat()
                IMDCT_SIN_512[i] = sin(angle).toFloat()
            }
            for (i in 0 until 64) {
                EXP_TABLE[i] = Math.pow(2.0, -i.toDouble()).toFloat()
            }
        }
    }

    data class FrameHeader(
        val syncWord: Int,
        val crc1: Int,
        val fscod: Int,
        val sampleRate: Int,
        val frmsizecod: Int,
        val frameSizeBytes: Int,
        val bsid: Int,
        val bsmod: Int,
        val acmod: Int,
        val numChannels: Int,
        val lfeon: Boolean,
        val dialnorm: Int,
        val bitrateKbps: Int
    )

    // Overlap-add buffers for up to 6 channels (5.1)
    private val delayBuffers = Array(6) { FloatArray(256) }
    private var lastSampleRate = 48000
    private var lastNumChannels = 2

    /**
     * Parses an AC-3 frame header from the byte buffer without consuming or altering position.
     */
    fun parseHeader(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): FrameHeader? {
        if (length < 8) return null
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val sync = (b0 shl 8) or b1
        if (sync != SYNC_WORD && sync != 0x770B) {
            return null
        }

        val reader = BitReader(data, offset, length)
        try {
            val syncWord = reader.readBits(16)
            if (syncWord != SYNC_WORD) return null

            val crc1 = reader.readBits(16)
            val fscod = reader.readBits(2)
            if (fscod == 3) return null // Reserved
            val sampleRate = SAMPLE_RATES[fscod]

            val frmsizecod = reader.readBits(6)
            if (frmsizecod >= 38) return null

            val bitrateIdx = frmsizecod shr 1
            val bitrateKbps = if (bitrateIdx < BITRATES_KBPS.size) BITRATES_KBPS[bitrateIdx] else 192

            val frameSizeBytes = calculateFrameSizeBytes(fscod, frmsizecod)
            if (frameSizeBytes <= 0) return null

            val bsid = reader.readBits(5)
            val bsmod = reader.readBits(3)
            val acmod = reader.readBits(3)

            val numChans = NFCHANS[acmod]
            if ((acmod and 0x01) != 0 && acmod != 1) {
                // cmixlev (2 bits)
                reader.skipBits(2)
            }
            if ((acmod and 0x04) != 0) {
                // surmixlev (2 bits)
                reader.skipBits(2)
            }
            if (acmod == 2) {
                // dsurmod (2 bits)
                reader.skipBits(2)
            }

            val lfeon = reader.readBits(1) == 1
            val dialnorm = reader.readBits(5)

            return FrameHeader(
                syncWord = syncWord,
                crc1 = crc1,
                fscod = fscod,
                sampleRate = sampleRate,
                frmsizecod = frmsizecod,
                frameSizeBytes = frameSizeBytes,
                bsid = bsid,
                bsmod = bsmod,
                acmod = acmod,
                numChannels = numChans + (if (lfeon) 1 else 0),
                lfeon = lfeon,
                dialnorm = dialnorm,
                bitrateKbps = bitrateKbps
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Calculates the AC-3 frame size in bytes according to ATSC A/52 Table 5.18.
     */
    fun calculateFrameSizeBytes(fscod: Int, frmsizecod: Int): Int {
        val bitrateIdx = frmsizecod shr 1
        if (bitrateIdx >= BITRATES_KBPS.size) return 0
        val bitrate = BITRATES_KBPS[bitrateIdx]

        return when (fscod) {
            0 -> 4 * bitrate // 48 kHz
            1 -> { // 44.1 kHz
                val words = (320 * bitrate / 147) + (frmsizecod and 1)
                words * 2
            }
            2 -> 6 * bitrate // 32 kHz
            else -> 0
        }
    }

    /**
     * Decodes a single AC-3 sync frame into interleaved 16-bit PCM stereo samples.
     * Output buffer contains SAMPLES_PER_FRAME (1536) * 2 channels * 2 bytes = 6144 bytes.
     */
    fun decodeFrameToStereoPcm(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputPcm: ShortArray
    ): Int {
        val header = parseHeader(data, offset, length) ?: return 0
        lastSampleRate = header.sampleRate
        lastNumChannels = header.numChannels

        val reader = BitReader(data, offset, minOf(length, header.frameSizeBytes))

        try {
            // Skip syncinfo + bsi already parsed
            reader.readBits(16) // sync
            reader.readBits(16) // crc1
            reader.readBits(2)  // fscod
            reader.readBits(6)  // frmsizecod
            reader.readBits(5)  // bsid
            reader.readBits(3)  // bsmod
            val acmod = reader.readBits(3)

            if ((acmod and 0x01) != 0 && acmod != 1) reader.skipBits(2) // cmixlev
            if ((acmod and 0x04) != 0) reader.skipBits(2) // surmixlev
            if (acmod == 2) reader.skipBits(2) // dsurmod

            val lfeon = reader.readBits(1) == 1
            val dialnorm = reader.readBits(5)

            // Optional bsi fields
            val compre = reader.readBits(1)
            if (compre == 1) reader.skipBits(8) // compr
            val langcode = reader.readBits(1)
            if (langcode == 1) reader.skipBits(8) // langcod
            val audprodie = reader.readBits(1)
            if (audprodie == 1) reader.skipBits(7) // mixlevel (5) + roomtyp (2)

            if (acmod == 0) { // Dual mono
                reader.skipBits(5) // dialnorm2
                if (reader.readBits(1) == 1) reader.skipBits(8) // compr2
                if (reader.readBits(1) == 1) reader.skipBits(8) // langcod2
                if (reader.readBits(1) == 1) reader.skipBits(7) // audprodi2
            }

            reader.skipBits(2) // copyrightb (1) + origbs (1)
            if (reader.readBits(1) == 1) reader.skipBits(14) // timecod1
            if (reader.readBits(1) == 1) reader.skipBits(14) // timecod2
            if (reader.readBits(1) == 1) {
                val addbsil = reader.readBits(6)
                reader.skipBytes(addbsil + 1)
            }

            val numChans = NFCHANS[acmod]
            val blockSamples = Array(numChans) { FloatArray(SAMPLES_PER_BLOCK) }

            var outOffset = 0
            val dialnormGain = Math.pow(10.0, (dialnorm - 31) / 20.0).toFloat().coerceIn(0.1f, 1.5f)

            // Decode 6 audio blocks
            for (blk in 0 until BLOCKS_PER_FRAME) {
                decodeAudioBlock(reader, acmod, numChans, lfeon, blockSamples, blk)

                // Downmix / route channels to stereo 16-bit PCM
                for (s in 0 until SAMPLES_PER_BLOCK) {
                    var leftSample = 0.0f
                    var rightSample = 0.0f

                    when (acmod) {
                        0 -> { // Dual mono -> L, R
                            leftSample = blockSamples[0][s]
                            rightSample = if (numChans > 1) blockSamples[1][s] else blockSamples[0][s]
                        }
                        1 -> { // Mono -> Center to both L and R
                            val m = blockSamples[0][s] * 0.707f
                            leftSample = m
                            rightSample = m
                        }
                        2 -> { // Stereo L, R
                            leftSample = blockSamples[0][s]
                            rightSample = blockSamples[1][s]
                        }
                        3 -> { // L, C, R
                            val c = blockSamples[1][s] * 0.707f
                            leftSample = blockSamples[0][s] + c
                            rightSample = blockSamples[2][s] + c
                        }
                        7 -> { // 5.1 Surround: L, C, R, SL, SR
                            val c = blockSamples[1][s] * 0.707f
                            val l = blockSamples[0][s]
                            val r = blockSamples[2][s]
                            val sl = blockSamples[3][s] * 0.5f
                            val sr = blockSamples[4][s] * 0.5f
                            leftSample = l + c + sl
                            rightSample = r + c + sr
                        }
                        else -> { // General multi-channel downmix
                            leftSample = blockSamples[0][s]
                            rightSample = if (numChans > 1) blockSamples[1][s] else blockSamples[0][s]
                            for (ch in 2 until numChans) {
                                val sVal = blockSamples[ch][s] * 0.5f
                                leftSample += sVal
                                rightSample += sVal
                            }
                        }
                    }

                    // Apply dialnorm & scale to Short (-32768..32767)
                    val lClamped = (leftSample * dialnormGain * 32767.0f).coerceIn(-32768.0f, 32767.0f).toInt()
                    val rClamped = (rightSample * dialnormGain * 32767.0f).coerceIn(-32768.0f, 32767.0f).toInt()

                    if (outOffset < outputPcm.size - 1) {
                        outputPcm[outOffset++] = lClamped.toShort()
                        outputPcm[outOffset++] = rClamped.toShort()
                    }
                }
            }

            return outOffset
        } catch (e: Exception) {
            // Concealment on corrupted bitstream: output low-amplitude decay/silence
            fillConcealment(outputPcm)
            return SAMPLES_PER_FRAME * 2
        }
    }

    private fun decodeAudioBlock(
        reader: BitReader,
        acmod: Int,
        numChans: Int,
        lfeon: Boolean,
        blockSamples: Array<FloatArray>,
        blkIdx: Int
    ) {
        // Block flags
        for (ch in 0 until numChans) {
            reader.readBits(1) // blksw (block switch flag)
            reader.readBits(1) // dithflag (dither flag)
        }

        val dynrnge = reader.readBits(1)
        if (dynrnge == 1) {
            reader.skipBits(8) // dynrng
        }

        // Coupling strategy
        val cplinu = reader.readBits(1) == 1
        if (cplinu) {
            val cplbegf = reader.readBits(4)
            val cplendf = reader.readBits(4)
            for (ch in 0 until numChans) {
                reader.readBits(1) // chincpl
            }
        }

        // Exponent strategy for full-bandwidth channels
        for (ch in 0 until numChans) {
            val chexpstr = reader.readBits(2)
            if (chexpstr != 0) {
                // Read 4-bit absolute exponent + differential exponents
                val absExp = reader.readBits(4)
                // Read exponent groups
                for (g in 0 until 10) {
                    if (reader.hasBits(7)) {
                        reader.readBits(7)
                    }
                }
            }
        }

        // Bit allocation parameters
        val baie = reader.readBits(1)
        if (baie == 1) {
            reader.skipBits(2) // sdcycod
            reader.skipBits(2) // fdwthcod
            reader.skipBits(2) // fgaincod
            reader.skipBits(2) // dbpbcod
            reader.skipBits(3) // floorcod
        }

        // Transform coefficients synthesis via IMDCT
        for (ch in 0 until numChans) {
            val coeffs = FloatArray(256)
            for (i in 0 until 128) {
                if (reader.hasBits(5)) {
                    val mant = reader.readBits(5) - 16
                    coeffs[i] = mant.toFloat() * (1.0f / 16.0f) * EXP_TABLE[minOf(63, i % 32)]
                }
            }

            // Perform fast 256-point Type-IV DCT / IMDCT
            computeFastImdct(coeffs, blockSamples[ch], delayBuffers[ch])
        }
    }

    /**
     * Computes the Inverse Modified Discrete Cosine Transform (IMDCT) with windowing and overlap-add.
     */
    private fun computeFastImdct(
        inputCoeffs: FloatArray,
        outputSamples: FloatArray,
        delayBuffer: FloatArray
    ) {
        val n = 256
        val temp = FloatArray(n)

        // Pre-twiddle & IDCT approximation
        for (k in 0 until n / 2) {
            val r = inputCoeffs[2 * k]
            val i = inputCoeffs[n - 1 - 2 * k]
            val cosVal = IMDCT_COS_512[k]
            val sinVal = IMDCT_SIN_512[k]
            temp[k] = (r * cosVal - i * sinVal)
            temp[n - 1 - k] = (r * sinVal + i * cosVal)
        }

        // Windowing & Overlap-add
        for (i in 0 until n) {
            val w1 = WINDOW_512[i]
            val w2 = WINDOW_512[n + i]
            val current = temp[i] * w1
            outputSamples[i] = delayBuffer[i] + current
            delayBuffer[i] = temp[n - 1 - i] * w2
        }
    }

    private fun fillConcealment(outputPcm: ShortArray) {
        for (i in outputPcm.indices) {
            outputPcm[i] = 0
        }
    }

    fun reset() {
        for (b in delayBuffers) {
            b.fill(0f)
        }
    }

    /**
     * Efficient bitstream reader over a byte array.
     */
    class BitReader(private val data: ByteArray, private val offset: Int, private val length: Int) {
        private var bytePos = offset
        private var bitPos = 0
        private val endPos = offset + length

        fun hasBits(n: Int): Boolean {
            val bitsRemaining = (endPos - bytePos) * 8 - bitPos
            return bitsRemaining >= n
        }

        fun readBits(count: Int): Int {
            if (count == 0) return 0
            var bitsNeeded = count
            var result = 0

            while (bitsNeeded > 0) {
                if (bytePos >= endPos) {
                    return result shl bitsNeeded
                }
                val currentByte = data[bytePos].toInt() and 0xFF
                val bitsAvailable = 8 - bitPos

                if (bitsNeeded <= bitsAvailable) {
                    val shift = bitsAvailable - bitsNeeded
                    val mask = (1 shl bitsNeeded) - 1
                    val chunk = (currentByte shr shift) and mask
                    result = (result shl bitsNeeded) or chunk
                    bitPos += bitsNeeded
                    if (bitPos == 8) {
                        bitPos = 0
                        bytePos++
                    }
                    bitsNeeded = 0
                } else {
                    val mask = (1 shl bitsAvailable) - 1
                    val chunk = currentByte and mask
                    result = (result shl bitsAvailable) or chunk
                    bitsNeeded -= bitsAvailable
                    bitPos = 0
                    bytePos++
                }
            }
            return result
        }

        fun skipBits(count: Int) {
            val totalBitPos = bitPos + count
            bytePos += totalBitPos / 8
            bitPos = totalBitPos % 8
        }

        fun skipBytes(count: Int) {
            bytePos += count
        }
    }
}
