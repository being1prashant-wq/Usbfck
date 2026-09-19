package com.example.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Custom Media3 [DataSource] that directly streams byte ranges from [PtpPlaybackSession].
 * Supports seeking, arbitrary byte ranges, and AC-3/E-AC-3/DTS/AAC audio/video bitstreams.
 */
class PtpMedia3DataSource(
    private val session: PtpPlaybackSession
) : DataSource {

    private var uri: Uri? = null
    private var opened = false
    private var currentPosition = 0L
    private var bytesRemaining = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        // No-op
    }

    override fun open(dataSpec: DataSpec): Long {
        this.uri = dataSpec.uri
        this.currentPosition = dataSpec.position
        val totalSize = session.totalSizeBytes

        if (totalSize > 0 && dataSpec.position > totalSize) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (totalSize > 0) {
            totalSize - dataSpec.position
        } else {
            C.LENGTH_UNSET.toLong()
        }

        opened = true
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), bytesRemaining).toInt()
        } else {
            length
        }

        if (session.isClosed) return C.RESULT_END_OF_INPUT

        val bytesRead = try {
            session.readBytes(currentPosition, buffer, offset, toRead)
        } catch (e: Exception) {
            if (session.isClosed) return C.RESULT_END_OF_INPUT
            throw IOException("PtpMedia3DataSource read error at position $currentPosition", e)
        }

        if (bytesRead <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        currentPosition += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        opened = false
    }

    class Factory(private val session: PtpPlaybackSession) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return PtpMedia3DataSource(session)
        }
    }
}
