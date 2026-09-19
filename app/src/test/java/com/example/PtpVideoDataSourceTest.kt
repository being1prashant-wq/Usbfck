package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.media.PtpMediaItem
import com.example.media.PtpPlaybackSession
import com.example.media.PtpVideoDataSource
import com.example.media.VideoPlaybackManager
import com.example.usb.PtpClient
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
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PtpVideoDataSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeClient: FakePtpClient
    private lateinit var testScope: CoroutineScope
    private lateinit var testDir: File

    class FakePtpClient : PtpClient() {
        override fun supportsPartialObject(): Boolean = true

        override suspend fun getPartialObjectRange(
            handle: Int,
            offset: Long,
            maxBytes: Int,
            isCancelled: (() -> Boolean)?
        ): ByteArray? {
            if (isCancelled?.invoke() == true) return null
            val len = minOf(maxBytes, 64 * 1024)
            return ByteArray(len) { idx -> ((offset + idx) % 256).toByte() }
        }
    }

    @Before
    fun setUp() {
        fakeClient = FakePtpClient()
        testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        testDir = tempFolder.newFolder("video_cache")
    }

    @Test
    fun testPtpVideoDataSource_reportsFullSize() {
        val totalSize = 3_500_000_000L // 3.5 GB large video file
        val item = PtpMediaItem(
            handle = 42,
            isVideo = true,
            isAudio = false,
            filename = "large_video.mp4",
            sizeBytes = totalSize,
            format = 0x300C
        )

        val session = PtpPlaybackSession(
            sessionId = 101L,
            item = item,
            totalSizeBytes = totalSize,
            client = fakeClient,
            cacheDir = testDir,
            scope = testScope
        )

        val dataSource = PtpVideoDataSource(session)
        assertEquals(totalSize, dataSource.size)

        // Reading past end of file returns -1 (genuine EOF)
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
            isAudio = false,
            filename = "sample.mp4",
            sizeBytes = totalSize,
            format = 0x300C
        )

        val session = PtpPlaybackSession(
            sessionId = 102L,
            item = item,
            totalSizeBytes = totalSize,
            client = fakeClient,
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
            isAudio = false,
            filename = "videoA.mp4",
            sizeBytes = 50_000_000L,
            format = 0x300C
        )

        val itemB = PtpMediaItem(
            handle = 20,
            isVideo = true,
            isAudio = false,
            filename = "videoB.mp4",
            sizeBytes = 80_000_000L,
            format = 0x300C
        )

        // Open Video A with session 1
        val sessionA = manager.createSession(fakeClient, itemA, 1L)
        assertTrue(manager.isSessionActive(1L))
        assertFalse(sessionA.isClosed)

        // Opening Video B with session 2 must close session A immediately
        val sessionB = manager.createSession(fakeClient, itemB, 2L)
        assertTrue(sessionA.isClosed)
        assertFalse(sessionB.isClosed)
        assertFalse(manager.isSessionActive(1L))
        assertTrue(manager.isSessionActive(2L))

        manager.closeCurrentSession()
        assertTrue(sessionB.isClosed)
        assertFalse(manager.isSessionActive(2L))
    }
}
