package com.example.media

import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import com.example.usb.PtpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoThumbnailLoader(
    private val scope: CoroutineScope,
    private val ptpClientProvider: () -> PtpClient?
) {
    // Keep max 25 thumbnails in RAM (~5MB total)
    private val memoryCache = object : LruCache<Int, Bitmap>(25) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            return 1
        }
    }

    private val loadingJobs = mutableMapOf<ImageView, Job>()

    fun loadThumbnail(
        handle: Int,
        targetImageView: ImageView,
        placeholderResId: Int? = null,
        onLoaded: ((Bitmap?) -> Unit)? = null
    ) {
        targetImageView.tag = handle

        // Check cache first
        val cached = memoryCache.get(handle)
        if (cached != null) {
            loadingJobs.remove(targetImageView)?.cancel()
            targetImageView.setImageBitmap(cached)
            onLoaded?.invoke(cached)
            return
        }

        placeholderResId?.let { targetImageView.setImageResource(it) }

        // Cancel any pending load for this recycled ImageView
        loadingJobs.remove(targetImageView)?.cancel()

        val job = scope.launch {
            val client = ptpClientProvider()
            val bitmap = if (client != null && client.isSessionOpen) {
                client.getThumb(handle, targetWidth = 320)
            } else {
                null
            }

            if (bitmap != null) {
                memoryCache.put(handle, bitmap)
            }

            withContext(Dispatchers.Main) {
                if (targetImageView.tag == handle) {
                    if (bitmap != null) {
                        targetImageView.setImageBitmap(bitmap)
                    } else if (placeholderResId != null) {
                        targetImageView.setImageResource(placeholderResId)
                    }
                    onLoaded?.invoke(bitmap)
                }
            }
        }

        loadingJobs[targetImageView] = job
    }

    fun clear() {
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        memoryCache.evictAll()
    }
}
