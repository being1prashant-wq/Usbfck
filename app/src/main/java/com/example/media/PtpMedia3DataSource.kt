package com.example.media

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.usb.PtpConstants
import java.io.IOException

/**
 * Media3 DataSource implementation backed by [PtpPlaybackSession].
 * Streams audio/video chunk-by-chunk with random-access seek support for ExoPlayer,
 * enabling direct playback and decoding of AC3/EAC3/AAC/DTS/PCM streams over USB PTP/MTP.
 */
@UnstableApi
class PtpMedia3DataSource(
    private val session: PtpPlaybackSession
) : BaseDataSource(/* isNetwork = */ false) {

    private var currentDataSpec: DataSpec? = null
    private var bytesRemaining: Long = 0
    private var currentReadPosition: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentDataSpec = dataSpec
        currentReadPosition = dataSpec.position

        val totalSize = session.totalSizeBytes
        if (totalSize > 0 && dataSpec.position >= totalSize) {
            bytesRemaining = 0
            opened = true
            transferStarted(dataSpec)
            return 0
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            if (totalSize > 0) {
                totalSize - dataSpec.position
            } else {
                C.LENGTH_UNSET.toLong()
            }
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened || session.isClosed) return C.RESULT_END_OF_INPUT
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }

        if (bytesToRead <= 0) return C.RESULT_END_OF_INPUT

        return try {
            val bytesRead = session.readBytes(currentReadPosition, buffer, offset, bytesToRead)
            if (bytesRead < 0) {
                return C.RESULT_END_OF_INPUT
            }
            if (bytesRead == 0) return 0

            currentReadPosition += bytesRead
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= bytesRead
            }
            bytesTransferred(bytesRead)
            bytesRead
        } catch (e: Exception) {
            if (session.isClosed || !opened) {
                return C.RESULT_END_OF_INPUT
            }
            Log.w(PtpConstants.TAG, "PtpMedia3DataSource read exception at pos=$currentReadPosition: ${e.message}")
            throw IOException("Error reading from PTP session ${session.sessionId}", e)
        }
    }

    override fun getUri(): Uri? = currentDataSpec?.uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(
        private val session: PtpPlaybackSession
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = PtpMedia3DataSource(session)
    }
}
