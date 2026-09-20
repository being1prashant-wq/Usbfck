package com.example.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.example.usb.PtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Metadata descriptor for an audio track inside a video container.
 */
data class AudioTrackDescriptor(
    val trackIndex: Int,
    val mimeType: String,
    val language: String,
    val channelCount: Int,
    val sampleRate: Int,
    val isAc3: Boolean,
    val codecDisplayName: String,
    val userDisplayName: String
)

/**
 * Dedicated AC-3 audio playback and synchronization engine for Android TV.
 * Detects AC-3 tracks, enables track selection in the UI, decodes via MediaCodec/Passthrough/Software,
 * and outputs synchronized PCM audio to the TV audio output.
 */
class Ac3AudioEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val TAG = "Ac3AudioEngine"
        const val MIME_AC3 = "audio/ac3"
        const val MIME_EAC3 = "audio/eac3"
        const val MIME_DOLBY = "audio/vnd.dolby.dd"
    }

    private var audioTrack: AudioTrack? = null
    private var mediaCodec: MediaCodec? = null
    private var extractor: MediaExtractor? = null
    private var playbackJob: Job? = null

    private val softwareDecoder = Ac3SoftwareDecoder()

    @Volatile
    private var isPlaying = false

    @Volatile
    private var isMuted = false

    @Volatile
    private var activeTrackIndex: Int = -1

    @Volatile
    private var currentPositionProvider: (() -> Long)? = null

    @Volatile
    private var isVideoPlayingProvider: (() -> Boolean)? = null

    private var cachedTracks: List<AudioTrackDescriptor> = emptyList()

    /**
     * Inspects the given MediaDataSource and extracts all audio track descriptors.
     */
    fun inspectTracks(dataSource: MediaDataSource): List<AudioTrackDescriptor> {
        val tracks = mutableListOf<AudioTrackDescriptor>()
        val ext = MediaExtractor()
        try {
            ext.setDataSource(dataSource)
            val numTracks = ext.trackCount
            var audioIdx = 1

            for (i in 0 until numTracks) {
                val format = ext.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""

                if (mime.startsWith("audio/")) {
                    val lang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                        format.getString(MediaFormat.KEY_LANGUAGE) ?: "und"
                    } else "und"

                    val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else 2

                    val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else 48000

                    val isAc3 = mime.equals(MIME_AC3, ignoreCase = true) ||
                            mime.equals(MIME_EAC3, ignoreCase = true) ||
                            mime.equals(MIME_DOLBY, ignoreCase = true) ||
                            mime.contains("ac3", ignoreCase = true) ||
                            mime.contains("dolby", ignoreCase = true)

                    val codecName = when {
                        mime.equals(MIME_AC3, ignoreCase = true) -> "Dolby Digital (AC-3)"
                        mime.equals(MIME_EAC3, ignoreCase = true) -> "Dolby Digital Plus (E-AC-3)"
                        mime.contains("mp4a-latm", ignoreCase = true) -> "AAC"
                        mime.contains("mpeg", ignoreCase = true) -> "MP3"
                        mime.contains("flac", ignoreCase = true) -> "FLAC"
                        mime.contains("opus", ignoreCase = true) -> "Opus"
                        mime.contains("vorbis", ignoreCase = true) -> "Vorbis"
                        else -> mime.substringAfter("audio/").uppercase()
                    }

                    val chStr = when (channels) {
                        6 -> "5.1"
                        1 -> "Mono"
                        else -> "Stereo"
                    }

                    val langStr = if (lang != "und" && lang.isNotBlank()) lang.uppercase() else "Track $audioIdx"
                    val displayName = "$langStr • $codecName $chStr"

                    tracks.add(
                        AudioTrackDescriptor(
                            trackIndex = i,
                            mimeType = mime,
                            language = lang,
                            channelCount = channels,
                            sampleRate = sampleRate,
                            isAc3 = isAc3,
                            codecDisplayName = codecName,
                            userDisplayName = displayName
                        )
                    )
                    audioIdx++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract track descriptors: ${e.message}")
        } finally {
            try { ext.release() } catch (_: Exception) {}
        }

        cachedTracks = tracks
        return tracks
    }

    fun getDetectedTracks(): List<AudioTrackDescriptor> = cachedTracks

    /**
     * Prepares and starts AC-3 audio playback for the designated AC-3 track index.
     */
    fun startAc3Playback(
        dataSource: MediaDataSource,
        trackIndex: Int,
        positionProvider: () -> Long,
        isPlayingProvider: () -> Boolean,
        onPlaybackError: ((String) -> Unit)? = null
    ) {
        stopPlayback()

        activeTrackIndex = trackIndex
        currentPositionProvider = positionProvider
        isVideoPlayingProvider = isPlayingProvider

        playbackJob = scope.launch(Dispatchers.IO) {
            var mediaExtractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            var track: AudioTrack? = null

            try {
                mediaExtractor = MediaExtractor()
                mediaExtractor.setDataSource(dataSource)
                mediaExtractor.selectTrack(trackIndex)
                extractor = mediaExtractor

                val format = mediaExtractor.getTrackFormat(trackIndex)
                val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 48000
                val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else 2

                // Setup AudioTrack for stereo 16-bit PCM output
                val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(16384)

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()

                track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                track.play()
                isPlaying = true

                // Try Hardware/Platform MediaCodec first
                val decoderName = findAc3DecoderName(format.getString(MediaFormat.KEY_MIME) ?: MIME_AC3)
                var useSoftwareFallback = (decoderName == null)

                if (decoderName != null) {
                    try {
                        decoder = MediaCodec.createByCodecName(decoderName)
                        decoder.configure(format, null, null, 0)
                        decoder.start()
                        mediaCodec = decoder
                        Log.i(TAG, "Using MediaCodec AC3 decoder: $decoderName")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to start MediaCodec AC3 decoder, switching to software decoder: ${e.message}")
                        useSoftwareFallback = true
                        try { decoder?.release() } catch (_: Exception) {}
                        decoder = null
                    }
                }

                if (useSoftwareFallback) {
                    Log.i(TAG, "Using internal pure Kotlin ATSC A/52 software AC3 decoder")
                    runSoftwareDecodingLoop(mediaExtractor, track, sampleRate)
                } else {
                    runHardwareDecodingLoop(mediaExtractor, decoder!!, track)
                }

            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "AC3 playback loop terminated: ${e.message}")
                    onPlaybackError?.invoke("AC-3 Audio stream issue: ${e.message}")
                }
            } finally {
                try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
                try { track?.stop(); track?.release() } catch (_: Exception) {}
                try { mediaExtractor?.release() } catch (_: Exception) {}
                if (audioTrack == track) audioTrack = null
                if (mediaCodec == decoder) mediaCodec = null
                if (extractor == mediaExtractor) extractor = null
                isPlaying = false
            }
        }
    }

    private suspend fun runSoftwareDecodingLoop(
        extractor: MediaExtractor,
        audioTrack: AudioTrack,
        sampleRate: Int
    ) {
        val inputBuffer = ByteBuffer.allocate(64 * 1024)
        val pcmOut = ShortArray(Ac3SoftwareDecoder.SAMPLES_PER_FRAME * 2)
        val rawBytes = ByteArray(pcmOut.size * 2)

        while (scope.isActive && isPlaying) {
            val isVidPlaying = isVideoPlayingProvider?.invoke() ?: true
            if (!isVidPlaying) {
                if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.pause()
                }
                delay(20)
                continue
            } else if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.play()
            }

            inputBuffer.clear()
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize < 0) {
                break // EOF
            }

            val ptsUs = extractor.sampleTime
            val videoPosMs = currentPositionProvider?.invoke() ?: 0L
            val audioPosMs = ptsUs / 1000L

            // Re-sync if seek or drift occurred (> 350ms difference)
            if (kotlin.math.abs(audioPosMs - videoPosMs) > 350) {
                extractor.seekTo(videoPosMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                audioTrack.flush()
                softwareDecoder.reset()
                extractor.advance()
                continue
            }

            val decodedSamples = softwareDecoder.decodeFrameToStereoPcm(
                inputBuffer.array(),
                0,
                sampleSize,
                pcmOut
            )

            if (decodedSamples > 0) {
                // Convert ShortArray to little-endian ByteArray
                var byteIdx = 0
                for (i in 0 until decodedSamples) {
                    val s = pcmOut[i].toInt()
                    rawBytes[byteIdx++] = (s and 0xFF).toByte()
                    rawBytes[byteIdx++] = ((s shr 8) and 0xFF).toByte()
                }
                audioTrack.write(rawBytes, 0, byteIdx)
            }

            extractor.advance()
        }
    }

    private suspend fun runHardwareDecodingLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        audioTrack: AudioTrack
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var isExtractorEos = false

        while (scope.isActive && isPlaying) {
            val isVidPlaying = isVideoPlayingProvider?.invoke() ?: true
            if (!isVidPlaying) {
                if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.pause()
                }
                delay(20)
                continue
            } else if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.play()
            }

            // Sync with video timeline
            val videoPosMs = currentPositionProvider?.invoke() ?: 0L
            val currentSampleTimeMs = extractor.sampleTime / 1000L
            if (kotlin.math.abs(currentSampleTimeMs - videoPosMs) > 400) {
                extractor.seekTo(videoPosMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                audioTrack.flush()
            }

            // Feed input
            if (!isExtractorEos) {
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isExtractorEos = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            // Drain output
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.get(chunk)
                    audioTrack.write(chunk, 0, chunk.size)
                }
                codec.releaseOutputBuffer(outputIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    private fun findAc3DecoderName(mime: String): String? {
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(mime, ignoreCase = true) ||
                        type.equals(MIME_AC3, ignoreCase = true) ||
                        type.equals(MIME_EAC3, ignoreCase = true)
                    ) {
                        return info.name
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying MediaCodecList: ${e.message}")
        }
        return null
    }

    fun seekTo(positionMs: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                extractor?.seekTo(positionMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                audioTrack?.flush()
                mediaCodec?.flush()
                softwareDecoder.reset()
            } catch (_: Exception) {}
        }
    }

    fun pause() {
        try {
            audioTrack?.pause()
        } catch (_: Exception) {}
    }

    fun resume() {
        try {
            if (isPlaying) {
                audioTrack?.play()
            }
        } catch (_: Exception) {}
    }

    fun stopPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        try {
            extractor?.release()
        } catch (_: Exception) {}
        extractor = null

        activeTrackIndex = -1
    }

    val isAc3Active: Boolean
        get() = isPlaying && activeTrackIndex >= 0

    val currentActiveTrackIndex: Int
        get() = activeTrackIndex
}
