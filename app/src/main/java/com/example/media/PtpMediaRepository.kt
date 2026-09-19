package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class PtpMediaRepository(private val context: Context) {

    private var activeClient: PtpClient? = null
    private var reportedDeviceName: String = "PTP Device"

    val photoItems = mutableListOf<PtpMediaItem>()
    val videoItems = mutableListOf<PtpMediaItem>()
    val audioItems = mutableListOf<PtpMediaItem>()

    val isSessionReady: Boolean
        get() = activeClient?.isSessionOpen == true

    val deviceName: String
        get() = reportedDeviceName

    val client: PtpClient?
        get() = activeClient

    suspend fun initialize(ptpClient: PtpClient, fallbackName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            activeClient = ptpClient
            photoItems.clear()
            videoItems.clear()
            audioItems.clear()

            Log.i(PtpConstants.TAG, "Step 1: OpenSession...")
            if (!ptpClient.openSession()) {
                Log.e(PtpConstants.TAG, "OpenSession failed")
                return@withContext false
            }

            // 1. Get Device Info
            Log.i(PtpConstants.TAG, "Step 2: GetDeviceInfo...")
            val devInfo = ptpClient.getDeviceInfo()
            reportedDeviceName = if (devInfo != null && (devInfo.manufacturer.isNotBlank() || devInfo.model.isNotBlank())) {
                "${devInfo.manufacturer} ${devInfo.model}".trim()
            } else {
                fallbackName
            }
            Log.i(PtpConstants.TAG, "PTP session opened. Device name: $reportedDeviceName")

            // 2. Get Storage IDs
            Log.i(PtpConstants.TAG, "Step 3: GetStorageIDs...")
            val storageIds = ptpClient.getStorageIds()
            Log.i(PtpConstants.TAG, "Retrieved ${storageIds.size} storage IDs")

            // 3. Complete media discovery across all storages
            Log.i(PtpConstants.TAG, "Step 4: Complete media discovery across all storages...")
            discoverAllMedia(ptpClient, storageIds)

            Log.i(PtpConstants.TAG, "PTP Ready: ${photoItems.size} photos, ${videoItems.size} videos, ${audioItems.size} audio tracks")
            true
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "PTP initialization failed with exception", e)
            false
        }
    }

    private suspend fun discoverAllMedia(ptpClient: PtpClient, storageIds: IntArray) {
        val targetStorageIds = mutableListOf<Int>()
        if (storageIds.isNotEmpty()) {
            for (id in storageIds) {
                targetStorageIds.add(id)
            }
        } else {
            targetStorageIds.add(PtpConstants.STORAGE_ALL)
        }

        val visitedHandles = mutableSetOf<Int>()
        val pendingHandles = ArrayDeque<Int>()
        val visitedFolders = mutableSetOf<Int>()

        // 1. Initial handles collection across all storages
        for (sId in targetStorageIds) {
            Log.i(PtpConstants.TAG, "Querying initial handles for storage 0x${sId.toString(16)}...")
            val storageRootHandles = mutableSetOf<Int>()

            // Query root objects (parent = 0x00000000)
            val hRoot = ptpClient.getObjectHandles(
                storageId = sId,
                formatCode = PtpConstants.FORMAT_ALL,
                parentHandle = PtpConstants.PARENT_ROOT
            )
            for (h in hRoot) storageRootHandles.add(h)

            // Query all objects (parent = 0xFFFFFFFF / -1)
            val hAll = ptpClient.getObjectHandles(
                storageId = sId,
                formatCode = PtpConstants.FORMAT_ALL,
                parentHandle = PtpConstants.PARENT_ALL
            )
            for (h in hAll) storageRootHandles.add(h)

            // Fallback: If 0 handles were returned for FORMAT_ALL on this storage,
            // query format-specific codes
            if (storageRootHandles.isEmpty()) {
                Log.w(PtpConstants.TAG, "FORMAT_ALL returned no handles on storage 0x${sId.toString(16)}, trying format fallbacks")
                val fallbackFormats = intArrayOf(
                    PtpConstants.FORMAT_EXIF_JPEG,
                    PtpConstants.FORMAT_PNG,
                    PtpConstants.FORMAT_MP4,
                    PtpConstants.FORMAT_3GP,
                    PtpConstants.FORMAT_MOV,
                    PtpConstants.FORMAT_AVI,
                    PtpConstants.FORMAT_MKV,
                    PtpConstants.FORMAT_WEBM,
                    PtpConstants.FORMAT_HEIF,
                    PtpConstants.FORMAT_WEBP,
                    PtpConstants.FORMAT_MP3,
                    PtpConstants.FORMAT_WAV,
                    PtpConstants.FORMAT_AAC,
                    PtpConstants.FORMAT_FLAC,
                    PtpConstants.FORMAT_M4A,
                    PtpConstants.FORMAT_OGG,
                    PtpConstants.FORMAT_WMA,
                    PtpConstants.FORMAT_UNDEFINED_AUDIO,
                    PtpConstants.FORMAT_ASSOCIATION,
                    PtpConstants.FORMAT_UNDEFINED
                )
                for (fmt in fallbackFormats) {
                    val hList0 = ptpClient.getObjectHandles(sId, fmt, PtpConstants.PARENT_ROOT)
                    for (h in hList0) storageRootHandles.add(h)
                    val hListAll = ptpClient.getObjectHandles(sId, fmt, PtpConstants.PARENT_ALL)
                    for (h in hListAll) storageRootHandles.add(h)
                }
            }

            Log.i(PtpConstants.TAG, "Storage 0x${sId.toString(16)} yielded ${storageRootHandles.size} initial handles")
            for (h in storageRootHandles) {
                if (h !in visitedHandles) {
                    pendingHandles.add(h)
                }
            }
        }

        // If specific storage IDs yielded no handles at all, try STORAGE_ALL (-1)
        if (pendingHandles.isEmpty() && !targetStorageIds.contains(PtpConstants.STORAGE_ALL)) {
            Log.i(PtpConstants.TAG, "No handles found on specific storages, attempting STORAGE_ALL query...")
            val allRoot = ptpClient.getObjectHandles(
                storageId = PtpConstants.STORAGE_ALL,
                formatCode = PtpConstants.FORMAT_ALL,
                parentHandle = PtpConstants.PARENT_ROOT
            )
            val allAll = ptpClient.getObjectHandles(
                storageId = PtpConstants.STORAGE_ALL,
                formatCode = PtpConstants.FORMAT_ALL,
                parentHandle = PtpConstants.PARENT_ALL
            )
            for (h in allRoot) {
                if (h !in visitedHandles) pendingHandles.add(h)
            }
            for (h in allAll) {
                if (h !in visitedHandles) pendingHandles.add(h)
            }
        }

        Log.i(PtpConstants.TAG, "Total initial handles to process: ${pendingHandles.size}")

        // 2. Breadth-first traversal of all handles and folders
        while (pendingHandles.isNotEmpty()) {
            val handle = pendingHandles.removeFirst()
            if (handle in visitedHandles) continue
            visitedHandles.add(handle)

            val info = try {
                ptpClient.getObjectInfo(handle)
            } catch (e: Exception) {
                Log.w(PtpConstants.TAG, "Exception getting ObjectInfo for handle $handle", e)
                null
            }

            if (info == null) {
                Log.w(PtpConstants.TAG, "Null ObjectInfo for handle $handle, continuing")
                continue
            }

            // If it's a directory / folder association, traverse into it
            if (info.isFolder) {
                if (handle !in visitedFolders) {
                    visitedFolders.add(handle)
                    Log.d(PtpConstants.TAG, "Descending into folder '${info.filename}' (handle $handle, storage 0x${info.storageId.toString(16)})")
                    val childHandles = ptpClient.getObjectHandles(
                        storageId = if (info.storageId != 0) info.storageId else PtpConstants.STORAGE_ALL,
                        formatCode = PtpConstants.FORMAT_ALL,
                        parentHandle = handle
                    )
                    Log.d(PtpConstants.TAG, "Folder '${info.filename}' contains ${childHandles.size} child handles")
                    for (ch in childHandles) {
                        if (ch !in visitedHandles) {
                            pendingHandles.add(ch)
                        }
                    }
                }
                continue
            }

            // Identify audio, video or photo
            if (info.isAudio) {
                audioItems.add(
                    PtpMediaItem(
                        handle = handle,
                        isVideo = false,
                        isAudio = true,
                        filename = info.filename,
                        sizeBytes = info.compressedSize,
                        format = info.format,
                        isMetadataLoaded = true
                    )
                )
                Log.d(PtpConstants.TAG, "Discovered AUDIO: '${info.filename}' (handle $handle, size ${info.compressedSize} B, format 0x${info.format.toString(16)})")
            } else if (info.isVideo) {
                videoItems.add(
                    PtpMediaItem(
                        handle = handle,
                        isVideo = true,
                        isAudio = false,
                        filename = info.filename,
                        sizeBytes = info.compressedSize,
                        format = info.format,
                        isMetadataLoaded = true
                    )
                )
                Log.d(PtpConstants.TAG, "Discovered VIDEO: '${info.filename}' (handle $handle, size ${info.compressedSize} B, format 0x${info.format.toString(16)})")
            } else if (info.isImage) {
                photoItems.add(
                    PtpMediaItem(
                        handle = handle,
                        isVideo = false,
                        isAudio = false,
                        filename = info.filename,
                        sizeBytes = info.compressedSize,
                        format = info.format,
                        isMetadataLoaded = true
                    )
                )
                Log.d(PtpConstants.TAG, "Discovered PHOTO: '${info.filename}' (handle $handle, format 0x${info.format.toString(16)})")
            } else {
                Log.d(PtpConstants.TAG, "Ignored non-media object '${info.filename}' (handle $handle, format 0x${info.format.toString(16)})")
            }
        }

        Log.i(PtpConstants.TAG, "Media discovery complete: ${photoItems.size} photos, ${videoItems.size} videos, ${audioItems.size} audio tracks across ${visitedFolders.size} folders (${visitedHandles.size} total objects evaluated)")
    }

    suspend fun fetchMetadataIfNeeded(item: PtpMediaItem): PtpMediaItem = withContext(Dispatchers.IO) {
        if (item.isMetadataLoaded) return@withContext item
        val client = activeClient ?: return@withContext item

        val info = client.getObjectInfo(item.handle)
        if (info != null) {
            item.filename = info.filename
            item.sizeBytes = info.compressedSize
            item.format = info.format
            item.isMetadataLoaded = true
        }
        item
    }

    suspend fun loadFullPhoto(handle: Int, maxDim: Int = 1920): Bitmap? = withContext(Dispatchers.IO) {
        val client = activeClient ?: return@withContext null
        val tempFile = File(context.cacheDir, "full_photo_temp.jpg")
        if (tempFile.exists()) tempFile.delete()

        try {
            FileOutputStream(tempFile).use { fos ->
                val success = client.streamObject(handle, fos)
                if (!success) return@withContext null
            }

            // Decode dimensions
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, boundsOpts)

            val sampleSize = PtpClient.calculateInSampleSize(boundsOpts, maxDim, maxDim)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOpts)
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.e(PtpConstants.TAG, "OOM loading full photo $handle", e)
            null
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error loading full photo $handle", e)
            null
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {}
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            try {
                activeClient?.closeSession()
            } catch (_: Exception) {}
            activeClient = null
            photoItems.clear()
            videoItems.clear()
            audioItems.clear()
        }
    }
}
