package com.example.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MediaContextMenuHelper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ptpClientProvider: () -> PtpClient?,
    private val onRequestStoragePermission: ((onResult: (granted: Boolean) -> Unit) -> Unit)? = null,
    private val onCopyStart: (String) -> Unit,
    private val onCopyProgress: (writtenBytes: Long, totalBytes: Long) -> Unit,
    private val onCopyComplete: (success: Boolean, message: String) -> Unit
) {
    private var activeCopyJob: Job? = null
    @Volatile private var isCopyCancelled = false

    fun showContextMenu(item: PtpMediaItem) {
        val options = arrayOf(
            context.getString(R.string.play_with),
            context.getString(R.string.copy_to_tv)
        )

        AlertDialog.Builder(context)
            .setTitle(item.displayName)
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> handlePlayWith(item)
                    1 -> startCopyFlow(item)
                }
            }
            .setNegativeButton("CANCEL") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun cancelCopy() {
        isCopyCancelled = true
        activeCopyJob?.cancel()
        activeCopyJob = null
        onCopyComplete(false, "Copy cancelled by user")
    }

    private fun handlePlayWith(item: PtpMediaItem) {
        val client = ptpClientProvider()
        if (client == null || !client.isSessionOpen) {
            Toast.makeText(context, "PTP device not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val ext = item.filename.substringAfterLast('.', if (item.isVideo) "mp4" else if (item.isAudio) "mp3" else "jpg")
        val mimeType = if (item.isVideo) {
            "video/*"
        } else if (item.isAudio) {
            "audio/*"
        } else {
            "image/*"
        }

        val cacheFile = File(context.cacheDir, "open_with_temp.$ext")

        scope.launch {
            Toast.makeText(context, "Preparing file for external player...", Toast.LENGTH_SHORT).show()
            val success = withContext(Dispatchers.IO) {
                try {
                    FileOutputStream(cacheFile).use { fos ->
                        client.streamObject(item.handle, fos)
                    }
                } catch (e: Exception) {
                    Log.e(PtpConstants.TAG, "Failed streaming for Play With", e)
                    false
                }
            }

            if (success && cacheFile.exists() && cacheFile.length() > 0) {
                try {
                    val uri: Uri = try {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            cacheFile
                        )
                    } catch (_: Exception) {
                        Uri.fromFile(cacheFile)
                    }

                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    val chooser = Intent.createChooser(viewIntent, context.getString(R.string.play_with))
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    Log.e(PtpConstants.TAG, "Could not launch external player", e)
                    Toast.makeText(context, context.getString(R.string.no_apps_found), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Could not prepare media for external playback", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCopyFlow(item: PtpMediaItem) {
        val client = ptpClientProvider()
        if (client == null || !client.isSessionOpen) {
            Toast.makeText(context, "PTP device not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Check storage permission if needed on Android <= 28
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                if (onRequestStoragePermission != null) {
                    onRequestStoragePermission.invoke { granted ->
                        executeCopy(item, usePublicStorage = granted)
                    }
                    return
                }
            }
        }

        executeCopy(item, usePublicStorage = true)
    }

    private fun executeCopy(item: PtpMediaItem, usePublicStorage: Boolean) {
        val client = ptpClientProvider()
        if (client == null || !client.isSessionOpen) {
            Toast.makeText(context, "PTP device not ready", Toast.LENGTH_SHORT).show()
            return
        }

        isCopyCancelled = false

        // Determine destination directory
        val targetDir: File = if (usePublicStorage) {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloads, "DirectUSB").apply { mkdirs() }
        } else {
            val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            File(extDir, "DirectUSB").apply { mkdirs() }
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            val fallbackDir = context.getExternalFilesDir(null) ?: context.filesDir
            fallbackDir.mkdirs()
        }

        // Safe filename with collision avoidance
        val safeName = item.displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val destinationFile = getUniqueDestinationFile(targetDir, safeName)

        onCopyStart(destinationFile.name)

        activeCopyJob = scope.launch {
            var finalSuccess = false
            var errorMessage = ""

            try {
                finalSuccess = withContext(Dispatchers.IO) {
                    // Check disk space
                    val freeBytes = targetDir.freeSpace
                    if (item.sizeBytes > 0 && freeBytes < item.sizeBytes + (20 * 1024 * 1024L)) {
                        errorMessage = "Insufficient TV storage space"
                        Log.e(PtpConstants.TAG, "Copy aborted: insufficient space (free=$freeBytes, required=${item.sizeBytes})")
                        return@withContext false
                    }

                    FileOutputStream(destinationFile).use { fos ->
                        val boundedOutputStream = object : OutputStream() {
                            var written: Long = 0L
                            var lastReport: Long = 0L

                            override fun write(b: Int) {
                                if (isCopyCancelled || !isActive) return
                                fos.write(b)
                                written++
                                checkReport()
                            }

                            override fun write(b: ByteArray, off: Int, len: Int) {
                                if (isCopyCancelled || !isActive) return
                                fos.write(b, off, len)
                                written += len
                                checkReport()
                            }

                            private fun checkReport() {
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 250) {
                                    lastReport = now
                                    scope.launch(Dispatchers.Main) {
                                        onCopyProgress(written, item.sizeBytes)
                                    }
                                }
                            }

                            override fun flush() {
                                fos.flush()
                            }

                            override fun close() {
                                fos.close()
                            }
                        }

                        val success = client.streamObject(
                            handle = item.handle,
                            outputStream = boundedOutputStream,
                            isCancelled = { isCopyCancelled || !isActive }
                        )

                        success && !isCopyCancelled
                    }
                }
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "Error copying file to TV", e)
                errorMessage = e.message ?: "I/O error during copy"
                finalSuccess = false
            }

            if (finalSuccess && destinationFile.exists() && destinationFile.length() > 0 && !isCopyCancelled) {
                Log.i(PtpConstants.TAG, "File successfully copied to ${destinationFile.absolutePath}")
                onCopyComplete(true, destinationFile.absolutePath)
            } else {
                if (destinationFile.exists()) {
                    try {
                        destinationFile.delete()
                    } catch (_: Exception) {}
                }
                if (isCopyCancelled) {
                    onCopyComplete(false, "Copy cancelled")
                } else {
                    onCopyComplete(false, if (errorMessage.isNotBlank()) errorMessage else "Copy failed")
                }
            }
        }
    }

    private fun getUniqueDestinationFile(directory: File, baseName: String): File {
        val dotIndex = baseName.lastIndexOf('.')
        val namePart = if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName
        val extPart = if (dotIndex > 0) baseName.substring(dotIndex) else ""
        var candidate = File(directory, "$namePart$extPart")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$namePart ($counter)$extPart")
            counter++
        }
        return candidate
    }
}

