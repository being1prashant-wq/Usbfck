package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.media.PtpMediaItem
import com.example.media.PtpPlaybackSession
import com.example.media.PtpVideoDataSource
import com.example.media.VideoPlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PtpVideoDataSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testScope: CoroutineScope
    private lateinit var testDir: File

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        testDir = tempFolder.newFolder("cache")
    }

    @Test
    fun testPtpVideoDataSource_reportsFullSize() {
        val totalSize = 3_500_000_000L // 3.5 GB large video file
        val item = PtpMediaItem(
            handle = 42,
            isVideo = true,
            filename = "large_video.mp4",
            sizeBytes = totalSize,
            format = 0x300C
        )

        val session = PtpPlaybackSession(
            sessionId = 101L,
            item = item,
            totalSizeBytes = totalSize,
            client = null,
            cacheDir = testDir,
            scope = testScope
        )

        val dataSource = PtpVideoDataSource(session)
        assertEquals(totalSize, dataSource.size)

        // Read past end of file returns -1 (genuine EOF)
        val buffer = ByteArray(1024)
        val readPastEof = dataSource.readAt(totalSize + 100L, buffer, 0, buffer.size)
        assertEquals(-1, readPastEof)

        session.close()
    }

    @Test
    fun testPtpVideoDataSource_sessionClosedReturnsMinusOne() {
        val totalSize = 10_000_000L
        val item = PtpMediaItem(
            handle = 1,
            isVideo = true,
            filename = "sample.mp4",
            sizeBytes = totalSize,
            format = 0x300C
        )

        val session = PtpPlaybackSession(
            sessionId = 102L,
            item = item,
            totalSizeBytes = totalSize,
            client = null,
            cacheDir = testDir,
            scope = testScope
        )

        val dataSource = PtpVideoDataSource(session)
        session.close()

        val buffer = ByteArray(512)
        val result = dataSource.readAt(0L, buffer, 0, buffer.size)
        assertEquals(-1, result)
    }

    @Test
    fun testVideoPlaybackManager_sessionIsolation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = VideoPlaybackManager(context, testScope)

        val itemA = PtpMediaItem(
            handle = 10,
            isVideo = true,
            filename = "videoA.mp4",
            sizeBytes = 50_000_000L,
            format = 0x300C
        )

        val itemB = PtpMediaItem(
            handle = 20,
            isVideo = true,
            filename = "videoB.mp4",
            sizeBytes = 80_000_000L,
            format = 0x300C
        )

        // Open Video A with session 1
        val sessionA = manager.createSession(null, itemA, 1L)
        assertTrue(manager.isSessionActive(1L))
        assertFalse(sessionA.isClosed)

        // Opening Video B with session 2 must close session A immediately
        val sessionB = manager.createSession(null, itemB, 2L)
        assertTrue(sessionA.isClosed)
        assertFalse(sessionB.isClosed)
        assertFalse(manager.isSessionActive(1L))
        assertTrue(manager.isSessionActive(2L))

        manager.closeCurrentSession()
        assertTrue(sessionB.isClosed)
        assertFalse(manager.isSessionActive(2L))
    }
}
