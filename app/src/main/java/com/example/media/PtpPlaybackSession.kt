package com.example.media

import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Encapsulates an isolated video playback session.
 * Manages chunk-based disk caching, priority on-demand fetching, ahead-of-playback prefetching,
 * and cancellation-aware read-through data delivery for MediaPlayer.
 */
class PtpPlaybackSession(
    val sessionId: Long,
    val item: PtpMediaItem,
    val totalSizeBytes: Long,
    private val client: PtpClient,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val onBufferingUpdate: ((isBuffering: Boolean, bufferedBytes: Long, totalBytes: Long) -> Unit)? = null
) {
    companion object {
        const val CHUNK_SIZE = 1024 * 1024 // 1 MB per chunk
        const val PREFETCH_AHEAD_CHUNKS = 8 // Prefetch up to 8 MB ahead
        const val MAX_FETCH_ATTEMPTS = 3
    }

    @Volatile
    var isClosed = false
        private set

    private val cacheFile = File(cacheDir, "ptp_video_session_${sessionId}_${item.handle}.cache")
    private var raf: RandomAccessFile? = null

    // Track cached chunks: chunkIndex -> bytesWritten
    private val cachedChunks = ConcurrentHashMap<Long, Int>()
    // In-flight chunks being downloaded right now
    private val inFlightChunks = HashSet<Long>()
    private val lock = Object()

    @Volatile
    private var highestReadChunkIndex = 0L

    @Volatile
    private var prefetchJob: Job? = null

    @Volatile
    private var fallbackStreamJob: Job? = null

    @Volatile
    private var isSequentialFallback = false

    @Volatile
    private var sequentialBytesDownloaded = 0L

    @Volatile
    private var isDemandFetching = false

    val dataSource: PtpVideoDataSource by lazy {
        PtpVideoDataSource(this)
    }

    init {
        try {
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            raf = RandomAccessFile(cacheFile, "rw")

            if (!client.supportsPartialObject()) {
                Log.w(PtpConstants.TAG, "Device reports no partial object support; starting sequential stream fallback for session $sessionId")
                startSequentialFallbackStream()
            } else {
                startPrefetchWorker()
            }
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to initialize PtpPlaybackSession $sessionId", e)
        }
    }

    /**
     * Pre-warm initial chunks (e.g. chunk 0) so metadata/initial headers are instantly ready.
     */
    suspend fun prewarmInitialChunk(): Boolean {
        if (isClosed) return false
        if (isSequentialFallback) {
            return waitForSequentialBytes(minOf(CHUNK_SIZE.toLong(), totalSizeBytes))
        }
        return fetchChunk(0L)
    }

    /**
     * Read bytes at specific position for MediaPlayer.
     * Blocks if the data is not yet in cache until it is fetched or session is cancelled.
     */
    fun readBytes(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (isClosed) throw IOException("Session $sessionId is closed")
        if (position >= totalSizeBytes) return -1
        if (size <= 0) return 0

        val chunkIndex = position / CHUNK_SIZE
        val offsetInChunk = (position % CHUNK_SIZE).toInt()
        val availableInChunk = CHUNK_SIZE - offsetInChunk
        val toRead = minOf(size.toLong(), availableInChunk.toLong(), totalSizeBytes - position).toInt()

        highestReadChunkIndex = chunkIndex

        // Ensure the chunk covering this range is available
        ensureDataAvailable(chunkIndex, position + toRead)

        if (isClosed) throw IOException("Session $sessionId was closed during read")

        synchronized(lock) {
            val file = raf ?: throw IOException("Cache file is closed")
            file.seek(position)
            val bytesRead = file.read(buffer, offset, toRead)
            if (bytesRead <= 0) {
                throw IOException("Cache read failed at pos $position, toRead $toRead")
            }
            return bytesRead
        }
    }

    private fun ensureDataAvailable(chunkIndex: Long, targetBytePos: Long) {
        if (isClosed) throw IOException("Session $sessionId is closed")

        if (isSequentialFallback) {
            waitForSequentialPosition(targetBytePos)
            return
        }

        if (cachedChunks.containsKey(chunkIndex)) {
            return
        }

        // Notify buffering indicator
        onBufferingUpdate?.invoke(true, getBufferedBytes(), totalSizeBytes)

        val fetched = fetchChunk(chunkIndex)
        if (!fetched) {
            if (isClosed) throw IOException("Session $sessionId closed")

            // If partial object failed, attempt falling back to sequential stream
            if (!isSequentialFallback) {
                Log.w(PtpConstants.TAG, "Partial object fetch failed; falling back to sequential stream for session $sessionId")
                startSequentialFallbackStream()
                waitForSequentialPosition(targetBytePos)
                return
            }
            throw IOException("Failed to load chunk $chunkIndex for session $sessionId")
        }

        onBufferingUpdate?.invoke(false, getBufferedBytes(), totalSizeBytes)
    }

    /**
     * Fetch a specific chunk on-demand with priority.
     */
    private fun fetchChunk(chunkIndex: Long): Boolean {
        synchronized(lock) {
            if (cachedChunks.containsKey(chunkIndex)) return true

            while (inFlightChunks.contains(chunkIndex) && !isClosed) {
                try {
                    lock.wait(100)
                } catch (e: InterruptedException) {
                    throw IOException("Interrupted waiting for chunk $chunkIndex")
                }
                if (cachedChunks.containsKey(chunkIndex)) return true
            }

            if (isClosed) return false
            if (cachedChunks.containsKey(chunkIndex)) return true

            inFlightChunks.add(chunkIndex)
        }

        isDemandFetching = true
        try {
            var attempt = 0
            while (attempt < MAX_FETCH_ATTEMPTS && !isClosed) {
                attempt++
                val chunkOffset = chunkIndex * CHUNK_SIZE
                val remaining = totalSizeBytes - chunkOffset
                if (remaining <= 0) return true
                val chunkSize = minOf(CHUNK_SIZE.toLong(), remaining).toInt()

                val data = runBlocking(Dispatchers.IO) {
                    client.getPartialObjectRange(
                        handle = item.handle,
                        offset = chunkOffset,
                        maxBytes = chunkSize,
                        isCancelled = { isClosed }
                    )
                }

                if (data != null && data.isNotEmpty()) {
                    writeChunkToCache(chunkIndex, chunkOffset, data)
                    return true
                }
            }
            return false
        } finally {
            isDemandFetching = false
            synchronized(lock) {
                inFlightChunks.remove(chunkIndex)
                lock.notifyAll()
            }
        }
    }

    private fun writeChunkToCache(chunkIndex: Long, offset: Long, data: ByteArray) {
        synchronized(lock) {
            if (isClosed) return
            try {
                raf?.seek(offset)
                raf?.write(data)
                cachedChunks[chunkIndex] = data.size
                lock.notifyAll()
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "Error writing chunk $chunkIndex to cache for session $sessionId", e)
            }
        }
        onBufferingUpdate?.invoke(false, getBufferedBytes(), totalSizeBytes)
    }

    /**
     * Background worker that continuously prefetches future chunks ahead of the current playback position.
     */
    private fun startPrefetchWorker() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            while (isActive && !isClosed && !isSequentialFallback) {
                if (isDemandFetching) {
                    delay(40)
                    continue
                }
                val currentChunk = highestReadChunkIndex
                val totalChunks = (totalSizeBytes + CHUNK_SIZE - 1) / CHUNK_SIZE

                var didDownload = false
                for (ahead in 1..PREFETCH_AHEAD_CHUNKS) {
                    val targetChunk = currentChunk + ahead
                    if (targetChunk >= totalChunks || !isActive || isClosed || isSequentialFallback) break

                    val needsDownload: Boolean
                    synchronized(lock) {
                        needsDownload = !cachedChunks.containsKey(targetChunk) && !inFlightChunks.contains(targetChunk)
                        if (needsDownload) {
                            inFlightChunks.add(targetChunk)
                        }
                    }

                    if (needsDownload) {
                        try {
                            val chunkOffset = targetChunk * CHUNK_SIZE
                            val remaining = totalSizeBytes - chunkOffset
                            val chunkSize = minOf(CHUNK_SIZE.toLong(), remaining).toInt()

                            val data = client.getPartialObjectRange(
                                handle = item.handle,
                                offset = chunkOffset,
                                maxBytes = chunkSize,
                                isCancelled = { isClosed || !isActive || isSequentialFallback }
                            )

                            if (data != null && data.isNotEmpty()) {
                                writeChunkToCache(targetChunk, chunkOffset, data)
                                didDownload = true
                            }
                        } catch (e: Exception) {
                            Log.w(PtpConstants.TAG, "Prefetch error on chunk $targetChunk", e)
                        } finally {
                            synchronized(lock) {
                                inFlightChunks.remove(targetChunk)
                                lock.notifyAll()
                            }
                        }
                    }
                }

                if (!didDownload) {
                    delay(150)
                }
            }
        }
    }

    private fun startSequentialFallbackStream() {
        if (isSequentialFallback) return
        isSequentialFallback = true
        prefetchJob?.cancel()

        fallbackStreamJob = scope.launch(Dispatchers.IO) {
            try {
                val out = object : OutputStream() {
                    override fun write(b: Int) {
                        if (isClosed) throw IOException("Session closed")
                        synchronized(lock) {
                            raf?.seek(sequentialBytesDownloaded)
                            raf?.write(b)
                            sequentialBytesDownloaded++
                            lock.notifyAll()
                        }
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (isClosed) throw IOException("Session closed")
                        synchronized(lock) {
                            raf?.seek(sequentialBytesDownloaded)
                            raf?.write(b, off, len)
                            sequentialBytesDownloaded += len
                            lock.notifyAll()
                        }
                    }
                }
                client.streamObject(item.handle, out, isCancelled = { isClosed })
            } catch (e: Exception) {
                Log.w(PtpConstants.TAG, "Sequential fallback stream ended for session $sessionId: ${e.message}")
            }
        }
    }

    private fun waitForSequentialBytes(targetBytes: Long): Boolean {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + 10000
            while (sequentialBytesDownloaded < targetBytes && !isClosed && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(100)
                } catch (e: InterruptedException) {
                    return false
                }
            }
            return sequentialBytesDownloaded >= targetBytes && !isClosed
        }
    }

    private fun waitForSequentialPosition(targetPos: Long) {
        synchronized(lock) {
            while (sequentialBytesDownloaded < targetPos && !isClosed) {
                try {
                    lock.wait(200)
                } catch (e: InterruptedException) {
                    throw IOException("Interrupted waiting for position $targetPos")
                }
            }
            if (isClosed) throw IOException("Session $sessionId closed")
        }
    }

    fun getBufferedBytes(): Long {
        if (isSequentialFallback) return sequentialBytesDownloaded
        return cachedChunks.values.sumOf { it.toLong() }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        prefetchJob?.cancel()
        fallbackStreamJob?.cancel()

        synchronized(lock) {
            inFlightChunks.clear()
            lock.notifyAll()
            try {
                raf?.close()
            } catch (_: Exception) {}
            raf = null
        }

        try {
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        } catch (_: Exception) {}
    }
}
