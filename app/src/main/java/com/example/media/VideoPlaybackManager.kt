package com.example.media

import android.content.Context
import com.example.usb.PtpClient
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * High-level manager responsible for creating and isolating video playback sessions.
 * Guarantees that opening a new video immediately invalidates and terminates any old video session.
 */
class VideoPlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    @Volatile
    private var currentSession: PtpPlaybackSession? = null

    @Volatile
    private var activeSessionId: Long = 0L

    /**
     * Create a new isolated playback session for the given media item.
     * Automatically invalidates and cleans up any existing active session.
     */
    fun createSession(
        client: PtpClient,
        item: PtpMediaItem,
        sessionId: Long,
        onBufferingUpdate: ((isBuffering: Boolean, bufferedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): PtpPlaybackSession {
        closeCurrentSession()

        activeSessionId = sessionId
        val session = PtpPlaybackSession(
            sessionId = sessionId,
            item = item,
            totalSizeBytes = item.sizeBytes,
            client = client,
            cacheDir = context.cacheDir,
            scope = scope,
            onBufferingUpdate = onBufferingUpdate
        )
        currentSession = session
        return session
    }

    fun getCurrentSession(): PtpPlaybackSession? = currentSession

    fun getActiveSessionId(): Long = activeSessionId

    fun isSessionActive(sessionId: Long): Boolean {
        return (activeSessionId == sessionId) && (currentSession?.isClosed == false)
    }

    /**
     * Terminate the currently active session, cancel all its pending reads/jobs,
     * and delete its temporary cache file.
     */
    fun closeCurrentSession() {
        activeSessionId = -1L
        currentSession?.close()
        currentSession = null
        cleanupStaleCacheFiles()
    }

    private fun cleanupStaleCacheFiles() {
        try {
            val files = context.cacheDir.listFiles { _, name ->
                name.startsWith("ptp_video_session_") && name.endsWith(".cache")
            }
            files?.forEach { file ->
                try { file.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
