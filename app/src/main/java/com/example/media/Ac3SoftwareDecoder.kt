package com.example.media

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Audio track information extracted by FFprobe.
 */
data class AudioTrackInfo(
    val index: Int,
    val streamIndex: Int,
    val codec: String,
    val channels: Int,
    val sampleRate: Int,
    val language: String?,
    val isAc3: Boolean
)

/**
 * Result of probing media streams with FFprobe.
 */
data class AudioProbeResult(
    val hasAc3: Boolean,
    val audioTracks: List<AudioTrackInfo>,
    val videoCodec: String?,
    val format: String?,
    val durationMs: Long
)

/**
 * Result of FFmpeg software decoding / transcoding operation.
 */
data class DecodeResult(
    val success: Boolean,
    val outputFile: File?,
    val errorMessage: String? = null
)

/**
 * Robust cross-platform software decoder leveraging FFmpegKit
 * for detecting and decoding AC-3 (Dolby Digital) and E-AC-3 audio streams.
 */
class Ac3SoftwareDecoder(private val context: Context) {

    companion object {
        private const val TAG = "Ac3SoftwareDecoder"

        /**
         * Check if a codec string identifies AC-3 or E-AC-3 audio.
         */
        fun isAc3Codec(codec: String?): Boolean {
            if (codec == null) return false
            val lower = codec.lowercase().trim()
            return lower == "ac3" ||
                    lower == "ac-3" ||
                    lower == "eac3" ||
                    lower == "e-ac-3" ||
                    lower == "a52" ||
                    lower == "dolby" ||
                    lower == "dts" ||
                    lower == "dca"
        }
    }

    private val decoderOutputDir = File(context.cacheDir, "ac3_decoder_output").apply {
        if (!exists()) mkdirs()
    }

    @Volatile
    private var activeSessionId: Long? = null

    /**
     * Probes media file to identify audio codecs, channels, and specifically AC3 streams.
     */
    suspend fun probeMedia(filePath: String): AudioProbeResult = withContext(Dispatchers.IO) {
        try {
            val session = FFprobeKit.getMediaInformation(filePath)
            val info = session.mediaInformation
            if (info == null) {
                Log.w(TAG, "FFprobe returned null MediaInformation for $filePath")
                return@withContext AudioProbeResult(
                    hasAc3 = false,
                    audioTracks = emptyList(),
                    videoCodec = null,
                    format = null,
                    durationMs = 0L
                )
            }

            var videoCodec: String? = null
            val audioTracks = mutableListOf<AudioTrackInfo>()
            var audioCounter = 0

            val streams = info.streams ?: emptyList()
            for (stream in streams) {
                val type = stream.type?.lowercase()
                val codec = stream.codec?.lowercase() ?: "unknown"

                if (type == "video" && videoCodec == null) {
                    videoCodec = codec
                } else if (type == "audio") {
                    val isAc3 = isAc3Codec(codec)
                    val lang = stream.getStringProperty("tags/language")
                        ?: stream.getStringProperty("language")
                        ?: "Track ${audioCounter + 1}"
                    val channels = stream.getNumberProperty("channels")?.toInt()
                        ?: stream.getStringProperty("channels")?.toIntOrNull()
                        ?: 2
                    val sampleRate = stream.sampleRate?.toIntOrNull() ?: 48000

                    audioTracks.add(
                        AudioTrackInfo(
                            index = audioCounter,
                            streamIndex = stream.index?.toInt() ?: audioCounter,
                            codec = codec,
                            channels = channels,
                            sampleRate = sampleRate,
                            language = lang,
                            isAc3 = isAc3
                        )
                    )
                    audioCounter++
                }
            }

            val hasAc3 = audioTracks.any { it.isAc3 }
            val durationMs = try {
                info.duration?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
            } catch (_: Exception) {
                0L
            }

            AudioProbeResult(
                hasAc3 = hasAc3,
                audioTracks = audioTracks,
                videoCodec = videoCodec,
                format = info.format,
                durationMs = durationMs
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to probe media with FFprobe: ${t.message}")
            AudioProbeResult(
                hasAc3 = false,
                audioTracks = emptyList(),
                videoCodec = null,
                format = null,
                durationMs = 0L
            )
        }
    }

    /**
     * Decode and transcode the video's AC-3 audio stream into AAC while keeping
     * the video stream lossless (stream-copied with `-c:v copy`).
     * This provides a fast, cross-platform software-decoded stream that plays
     * smoothly in Android MediaPlayer and DirectVideoView without AV desync.
     */
    suspend fun decodeAc3StreamForPlayback(
        inputPath: String,
        sessionId: Long,
        audioTrackIndex: Int = 0,
        onProgress: ((progressPercent: Int) -> Unit)? = null
    ): DecodeResult = withContext(Dispatchers.IO) {
        val outputFile = File(decoderOutputDir, "decoded_ac3_session_${sessionId}.mp4")
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val cmd = "-y -i \"$inputPath\" -map 0:v? -c:v copy -map 0:a:$audioTrackIndex? -c:a aac -b:a 256k -movflags +faststart \"${outputFile.absolutePath}\""
        Log.i(TAG, "Executing FFmpegKit AC3 software decode: $cmd")

        try {
            val session = FFmpegKit.execute(cmd)
            activeSessionId = session.sessionId

            val state = session.state
            val returnCode = session.returnCode

            if (ReturnCode.isSuccess(returnCode) && outputFile.exists() && outputFile.length() > 0) {
                Log.i(TAG, "FFmpegKit AC3 software decode successful: size=${outputFile.length()} bytes")
                DecodeResult(success = true, outputFile = outputFile)
            } else if (ReturnCode.isCancel(returnCode)) {
                Log.i(TAG, "FFmpegKit AC3 software decode cancelled for session $sessionId")
                DecodeResult(success = false, outputFile = null, errorMessage = "Cancelled")
            } else {
                val errorMsg = session.failStackTrace ?: session.logsAsString ?: "Unknown FFmpeg error"
                Log.e(TAG, "FFmpegKit decode failed with returnCode=$returnCode: $errorMsg")
                DecodeResult(success = false, outputFile = null, errorMessage = errorMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during FFmpegKit AC3 decode: ${e.message}", e)
            DecodeResult(success = false, outputFile = null, errorMessage = e.message)
        } finally {
            activeSessionId = null
        }
    }

    /**
     * Decode AC-3 audio track directly to raw PCM or WAV format for audio rendering.
     */
    suspend fun decodeAc3ToWav(
        inputPath: String,
        sessionId: Long,
        audioTrackIndex: Int = 0
    ): DecodeResult = withContext(Dispatchers.IO) {
        val outputFile = File(decoderOutputDir, "decoded_ac3_audio_${sessionId}.wav")
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val cmd = "-y -i \"$inputPath\" -map 0:a:$audioTrackIndex? -c:a pcm_s16le -ar 48000 -ac 2 \"${outputFile.absolutePath}\""
        try {
            val session = FFmpegKit.execute(cmd)
            activeSessionId = session.sessionId
            val returnCode = session.returnCode

            if (ReturnCode.isSuccess(returnCode) && outputFile.exists() && outputFile.length() > 0) {
                DecodeResult(success = true, outputFile = outputFile)
            } else {
                DecodeResult(success = false, outputFile = null, errorMessage = session.failStackTrace)
            }
        } catch (t: Throwable) {
            DecodeResult(success = false, outputFile = null, errorMessage = t.message)
        } finally {
            activeSessionId = null
        }
    }

    /**
     * Cancel any currently active FFmpeg decoding session.
     */
    fun cancelActiveDecode() {
        try {
            activeSessionId?.let { id ->
                FFmpegKit.cancel(id)
            } ?: FFmpegKit.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Error cancelling FFmpegKit decode: ${t.message}")
        }
        activeSessionId = null
    }

    /**
     * Clean up all temporary decoded AC3 files.
     */
    fun cleanupTempFiles() {
        try {
            decoderOutputDir.listFiles()?.forEach { file ->
                try { file.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
