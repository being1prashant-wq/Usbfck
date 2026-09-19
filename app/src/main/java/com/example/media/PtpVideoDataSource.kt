package com.example.media

import android.media.MediaDataSource
import android.util.Log
import com.example.usb.PtpConstants
import java.io.IOException

/**
 * Android MediaDataSource implementation backed by PTP range-based chunk reader.
 * Allows Android MediaPlayer native extractor to read and seek through remote video
 * files of arbitrary size (including 3-4 GB files) without downloading the full video first
 * and without premature EOF.
 */
class PtpVideoDataSource(
    val session: PtpPlaybackSession
) : MediaDataSource() {

    @Volatile
    private var isClosed = false

    override fun getSize(): Long = session.totalSizeBytes

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (isClosed || session.isClosed) return -1
        if (size == 0) return 0
        if (position >= session.totalSizeBytes) return -1 // Genuine EOF

        try {
            val bytesRead = session.readBytes(position, buffer, offset, size)
            if (bytesRead < 0) {
                return -1
            }
            return bytesRead
        } catch (e: Exception) {
            if (isClosed || session.isClosed) return -1
            Log.w(PtpConstants.TAG, "PtpVideoDataSource readAt error pos=$position size=$size session=${session.sessionId}: ${e.message}")
            throw IOException("Failed to read at position $position for session ${session.sessionId}", e)
        }
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            session.close()
        }
    }
}
