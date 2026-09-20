package com.example.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * High-performance Media3 [DataSource] implementation backed by [PtpPlaybackSession].
 * Seamlessly provides seekable, range-based streaming from PTP USB camera/phone storage
 * directly into ExoPlayer's extractors and decoders.
 */
class PtpExoDataSource(
    private val session: PtpPlaybackSession
) : BaseDataSource(/* isNetwork = */ false) {

    private var dataSpec: DataSpec? = null
    private var bytesRemaining: Long = 0
    private var currentPosition: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        currentPosition = dataSpec.position
        val totalSize = session.totalSizeBytes

        if (totalSize > 0 && currentPosition >= totalSize) {
            throw IOException("Position $currentPosition is beyond total size $totalSize")
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            if (totalSize > 0) {
                totalSize - currentPosition
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
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (session.isClosed) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), bytesRemaining).toInt()
        } else {
            length
        }

        val bytesRead: Int
        try {
            bytesRead = session.readBytes(currentPosition, buffer, offset, bytesToRead)
        } catch (e: Exception) {
            if (session.isClosed) return C.RESULT_END_OF_INPUT
            throw IOException("PtpExoDataSource read failed at position $currentPosition", e)
        }

        if (bytesRead <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        currentPosition += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val session: PtpPlaybackSession) : DataSource.Factory {
        override fun createDataSource(): DataSource = PtpExoDataSource(session)
    }
}
