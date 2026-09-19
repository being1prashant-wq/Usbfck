package com.example.usb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

data class PtpDeviceInfo(
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
    val operationsSupported: Set<Int>
)

data class PtpObjectInfo(
    val handle: Int,
    val storageId: Int,
    val format: Int,
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbCompressedSize: Int,
    val thumbPixWidth: Int,
    val thumbPixHeight: Int,
    val imagePixWidth: Int,
    val imagePixHeight: Int,
    val parentObject: Int = 0,
    val associationType: Int = 0,
    val filename: String,
    val dateModified: String
) {
    val isFolder: Boolean
        get() = format == PtpConstants.FORMAT_ASSOCIATION || associationType != 0

    val isImage: Boolean
        get() = !isFolder && PtpConstants.isPhoto(format, filename)

    val isVideo: Boolean
        get() = !isFolder && PtpConstants.isVideo(format, filename)

    val isAudio: Boolean
        get() = !isFolder && PtpConstants.isAudio(format, filename)
}

class PtpClient(
    private val connection: UsbDeviceConnection?,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint
) {
    private val transactionCounter = AtomicInteger(1)
    private val mutex = Mutex()
    private val usbTimeout = 5000 // 5s timeout
    private var sessionId: Int = 1
    var isSessionOpen = false
        internal set

    var deviceInfo: PtpDeviceInfo? = null
        private set

    private fun nextTransactionId(): Int {
        val id = transactionCounter.getAndIncrement()
        if (id >= 0x7FFFFFFF) {
            transactionCounter.set(1)
        }
        return id
    }

    private fun safeBulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        return try {
            val conn = connection ?: return -1
            conn.bulkTransfer(endpoint, buffer, length, timeout)
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "bulkTransfer exception", e)
            -1
        }
    }

    /**
     * Drain any residual bytes in bulkIn from a previously aborted transfer.
     * Bounded to [maxReads] to prevent blocking or hanging.
     */
    fun drainResidualBulkIn(maxReads: Int = 3) {
        try {
            val dummy = ByteArray(16384)
            var count = 0
            while (count < maxReads && safeBulkTransfer(bulkIn, dummy, dummy.size, 15) > 0) {
                count++
            }
        } catch (_: Exception) {}
    }

    /**
     * Clear ENDPOINT_HALT feature on a USB endpoint via standard control transfer.
     */
    private fun clearEndpointHalt(endpoint: UsbEndpoint?) {
        if (endpoint == null || connection == null) return
        try {
            // Standard USB CLEAR_FEATURE request for ENDPOINT_HALT (0x0000)
            // requestType: 0x02 (USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_ENDPOINT)
            // request: 0x01 (CLEAR_FEATURE)
            // value: 0x00 (ENDPOINT_HALT)
            // index: endpoint.address
            connection?.controlTransfer(0x02, 0x01, 0x00, endpoint.address, null, 0, 100)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Failed to clear halt on endpoint ${endpoint.address}", e)
        }
    }

    /**
     * Reset and clear endpoints following a cancelled or aborted transaction.
     */
    fun cancelTransaction(tid: Int = 0) {
        try {
            clearEndpointHalt(bulkIn)
            clearEndpointHalt(bulkOut)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Error clearing halts on endpoints", e)
        }
        drainResidualBulkIn(maxReads = 2)
    }

    /**
     * Send command to Bulk OUT
     */
    private fun sendCommand(opCode: Int, transactionId: Int, vararg params: Int): Boolean {
        drainResidualBulkIn(maxReads = 2)
        return try {
            val cmd = PtpPacket.buildCommand(opCode, transactionId, *params)
            var transferred = safeBulkTransfer(bulkOut, cmd, cmd.size, usbTimeout)
            if (transferred < 0) {
                // Endpoint might be halted from previous aborted transfer, clear halt and retry once
                clearEndpointHalt(bulkOut)
                clearEndpointHalt(bulkIn)
                transferred = safeBulkTransfer(bulkOut, cmd, cmd.size, usbTimeout)
                if (transferred < 0) {
                    Log.e(PtpConstants.TAG, "bulkTransfer OUT failed for op: 0x${opCode.toString(16)}")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "sendCommand exception", e)
            false
        }
    }

    /**
     * Execute a command that does not return a Data container, only a Response container.
     */
    suspend fun executeSimpleCommand(opCode: Int, vararg params: Int): PtpResponse = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tid = nextTransactionId()
            try {
                if (!sendCommand(opCode, tid, *params)) {
                    return@withContext PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0))
                }
                readResponse(tid)
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "executeSimpleCommand exception op=0x${opCode.toString(16)}", e)
                PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0))
            }
        }
    }

    /**
     * Read a response packet from bulkIn
     */
    private fun readResponse(expectedTid: Int): PtpResponse {
        val buf = ByteArray(1024)
        val read = safeBulkTransfer(bulkIn, buf, buf.size, usbTimeout)
        if (read < PtpConstants.HEADER_SIZE) {
            Log.e(PtpConstants.TAG, "readResponse: read $read bytes, expected >= ${PtpConstants.HEADER_SIZE}")
            return PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, expectedTid, IntArray(0))
        }
        return try {
            val byteBuffer = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
            val header = PtpPacket.parseHeader(byteBuffer)
                ?: return PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, expectedTid, IntArray(0))
            if (header.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                val paramCount = ((read - PtpConstants.HEADER_SIZE) / 4).coerceAtMost(byteBuffer.remaining() / 4)
                val params = IntArray(paramCount)
                for (i in 0 until paramCount) {
                    params[i] = byteBuffer.getInt()
                }
                PtpResponse(header.code, header.transactionId, params)
            } else {
                Log.w(PtpConstants.TAG, "Unexpected container type in readResponse: ${header.type}")
                PtpResponse(header.code, header.transactionId, IntArray(0))
            }
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Exception parsing response", e)
            PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, expectedTid, IntArray(0))
        }
    }

    /**
     * Execute a command that returns a Data container, then a Response container.
     * Safely handles devices where the Data container and Response container arrive in the same USB packet.
     */
    suspend fun executeDataCommand(opCode: Int, vararg params: Int): Pair<PtpResponse, ByteArray?> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tid = nextTransactionId()
            try {
                if (!sendCommand(opCode, tid, *params)) {
                    return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )
                }

                // Read initial chunk
                val initialBuffer = ByteArray(32768)
                val firstRead = safeBulkTransfer(bulkIn, initialBuffer, initialBuffer.size, usbTimeout)
                if (firstRead < PtpConstants.HEADER_SIZE) {
                    Log.e(PtpConstants.TAG, "executeDataCommand: first read too small ($firstRead bytes)")
                    return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )
                }

                val bb = ByteBuffer.wrap(initialBuffer, 0, firstRead).order(ByteOrder.LITTLE_ENDIAN)
                val header = PtpPacket.parseHeader(bb)
                    ?: return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )

                // If the device sent a Response container right away (e.g. error)
                if (header.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                    val paramCount = ((firstRead - PtpConstants.HEADER_SIZE) / 4).coerceAtMost(bb.remaining() / 4)
                    val respParams = IntArray(paramCount)
                    for (i in 0 until paramCount) {
                        respParams[i] = bb.getInt()
                    }
                    return@withContext Pair(PtpResponse(header.code, header.transactionId, respParams), null)
                }

                if (header.type != PtpConstants.CONTAINER_TYPE_DATA) {
                    Log.e(PtpConstants.TAG, "Unexpected container type: ${header.type}")
                    return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )
                }

                val totalContainerLengthLong = header.length.toLong() and 0xFFFFFFFFL
                val isIndeterminate = (totalContainerLengthLong == 0xFFFFFFFFL)

                if (!isIndeterminate && totalContainerLengthLong < PtpConstants.HEADER_SIZE) {
                    Log.e(PtpConstants.TAG, "Invalid totalContainerLength: $totalContainerLengthLong")
                    return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )
                }

                val payloadLength = if (isIndeterminate) -1 else (totalContainerLengthLong - PtpConstants.HEADER_SIZE).toInt()
                // Cap in-memory data payload to 16MB to prevent TV OOM
                if (payloadLength > 16 * 1024 * 1024) {
                    Log.e(PtpConstants.TAG, "Payload exceeds cap: $payloadLength")
                    return@withContext Pair(
                        PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)),
                        null
                    )
                }

                val payload: ByteArray
                var responseFound: PtpResponse? = null

                if (isIndeterminate) {
                    val stream = java.io.ByteArrayOutputStream()
                    val initialBytes = firstRead - PtpConstants.HEADER_SIZE
                    if (initialBytes > 0) {
                        stream.write(initialBuffer, PtpConstants.HEADER_SIZE, initialBytes)
                    }
                    val chunk = ByteArray(16384)
                    while (stream.size() < 16 * 1024 * 1024) {
                        val r = safeBulkTransfer(bulkIn, chunk, chunk.size, usbTimeout)
                        if (r <= 0) break
                        val chunkBb = ByteBuffer.wrap(chunk, 0, r).order(ByteOrder.LITTLE_ENDIAN)
                        val chunkHdr = PtpPacket.parseHeader(chunkBb)
                        if (chunkHdr != null && chunkHdr.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                            val pCount = ((r - PtpConstants.HEADER_SIZE) / 4).coerceAtMost(chunkBb.remaining() / 4)
                            val respParams = IntArray(pCount)
                            for (i in 0 until pCount) {
                                respParams[i] = chunkBb.getInt()
                            }
                            responseFound = PtpResponse(chunkHdr.code, chunkHdr.transactionId, respParams)
                            break
                        }
                        stream.write(chunk, 0, r)
                        if (r < chunk.size) break
                    }
                    payload = stream.toByteArray()
                } else {
                    payload = ByteArray(payloadLength)
                    val availablePayloadInFirstRead = (firstRead - PtpConstants.HEADER_SIZE).coerceAtLeast(0)
                    val initialBytesToCopy = availablePayloadInFirstRead.coerceAtMost(payloadLength)
                    if (initialBytesToCopy > 0) {
                        System.arraycopy(initialBuffer, PtpConstants.HEADER_SIZE, payload, 0, initialBytesToCopy)
                    }

                    var bytesRead = initialBytesToCopy
                    val chunk = ByteArray(16384)
                    while (bytesRead < payloadLength) {
                        val readChunk = safeBulkTransfer(bulkIn, chunk, chunk.size, usbTimeout)
                        if (readChunk <= 0) {
                            Log.e(PtpConstants.TAG, "executeDataCommand: timeout reading payload ($bytesRead / $payloadLength)")
                            break
                        }
                        val toCopy = readChunk.coerceAtMost(payloadLength - bytesRead)
                        System.arraycopy(chunk, 0, payload, bytesRead, toCopy)
                        bytesRead += toCopy

                        if (readChunk > toCopy) {
                            val leftover = readChunk - toCopy
                            if (leftover >= PtpConstants.HEADER_SIZE) {
                                val respBb = ByteBuffer.wrap(chunk, toCopy, leftover).order(ByteOrder.LITTLE_ENDIAN)
                                val respHeader = PtpPacket.parseHeader(respBb)
                                if (respHeader != null && respHeader.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                                    val pCount = ((leftover - PtpConstants.HEADER_SIZE) / 4).coerceAtMost(respBb.remaining() / 4)
                                    val respParams = IntArray(pCount)
                                    for (i in 0 until pCount) {
                                        respParams[i] = respBb.getInt()
                                    }
                                    responseFound = PtpResponse(respHeader.code, respHeader.transactionId, respParams)
                                }
                            }
                        }
                    }

                    // Check if the response container was already received in initialBuffer
                    if (responseFound == null) {
                        val leftoverInFirstRead = firstRead - totalContainerLengthLong.toInt()
                        if (leftoverInFirstRead >= PtpConstants.HEADER_SIZE) {
                            val respBb = ByteBuffer.wrap(initialBuffer, totalContainerLengthLong.toInt(), leftoverInFirstRead)
                                .order(ByteOrder.LITTLE_ENDIAN)
                            val respHeader = PtpPacket.parseHeader(respBb)
                            if (respHeader != null && respHeader.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                                val pCount = ((leftoverInFirstRead - PtpConstants.HEADER_SIZE) / 4)
                                    .coerceAtMost(respBb.remaining() / 4)
                                val respParams = IntArray(pCount)
                                for (i in 0 until pCount) {
                                    respParams[i] = respBb.getInt()
                                }
                                responseFound = PtpResponse(respHeader.code, respHeader.transactionId, respParams)
                            }
                        }
                    }
                }

                val finalResp = responseFound ?: readResponse(tid)
                Pair(finalResp, payload)
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "Exception in executeDataCommand op=0x${opCode.toString(16)}", e)
                Pair(PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, tid, IntArray(0)), null)
            }
        }
    }

    /**
     * Stream an object directly to an OutputStream (e.g. temporary cache file).
     * Avoids loading large files into RAM. Handles files > 2GB using unsigned length.
     * Supports immediate cancellation.
     */
    suspend fun streamObject(
        handle: Int,
        outputStream: OutputStream,
        isCancelled: (() -> Boolean)? = null
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tid = nextTransactionId()
            try {
                if (!sendCommand(PtpConstants.OPERATION_GET_OBJECT, tid, handle)) {
                    return@withContext false
                }

                val buffer = ByteArray(16384)
                val firstRead = safeBulkTransfer(bulkIn, buffer, buffer.size, usbTimeout)
                if (firstRead < PtpConstants.HEADER_SIZE) return@withContext false

                val bb = ByteBuffer.wrap(buffer, 0, firstRead).order(ByteOrder.LITTLE_ENDIAN)
                val header = PtpPacket.parseHeader(bb) ?: return@withContext false

                if (header.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                    Log.e(PtpConstants.TAG, "streamObject error response code: 0x${header.code.toString(16)}")
                    return@withContext false
                }
                if (header.type != PtpConstants.CONTAINER_TYPE_DATA) return@withContext false

                val totalContainerLength = header.length.toLong() and 0xFFFFFFFFL
                val isIndeterminate = (totalContainerLength == 0xFFFFFFFFL)
                val totalPayload = if (isIndeterminate) -1L else totalContainerLength - PtpConstants.HEADER_SIZE
                if (!isIndeterminate && totalPayload < 0) return@withContext false

                var written: Long = 0L
                val initialBytes = firstRead - PtpConstants.HEADER_SIZE
                if (initialBytes > 0) {
                    val toWrite = if (isIndeterminate) initialBytes.toLong() else initialBytes.toLong().coerceAtMost(totalPayload)
                    outputStream.write(buffer, PtpConstants.HEADER_SIZE, toWrite.toInt())
                    written += toWrite
                }

                while (isIndeterminate || written < totalPayload) {
                    if (!isActive || isCancelled?.invoke() == true) {
                        Log.i(PtpConstants.TAG, "streamObject aborted/cancelled for handle $handle at $written bytes")
                        cancelTransaction(tid)
                        return@withContext false
                    }

                    val r = safeBulkTransfer(bulkIn, buffer, buffer.size, usbTimeout)
                    if (r <= 0) {
                        if (!isIndeterminate) {
                            Log.e(PtpConstants.TAG, "streamObject: transfer halted at $written / $totalPayload bytes")
                            cancelTransaction(tid)
                            return@withContext false
                        }
                        break
                    }

                    if (!isActive || isCancelled?.invoke() == true) {
                        Log.i(PtpConstants.TAG, "streamObject aborted/cancelled for handle $handle at $written bytes")
                        cancelTransaction(tid)
                        return@withContext false
                    }

                    val toWrite = if (isIndeterminate) r.toLong() else r.toLong().coerceAtMost(totalPayload - written)
                    try {
                        outputStream.write(buffer, 0, toWrite.toInt())
                    } catch (e: Exception) {
                        Log.i(PtpConstants.TAG, "streamObject write aborted for handle $handle: ${e.message}")
                        cancelTransaction(tid)
                        return@withContext false
                    }
                    written += toWrite
                    if (isIndeterminate && r < buffer.size) {
                        break
                    }
                }

                outputStream.flush()

                // If response container was in the first read after data
                if (!isIndeterminate) {
                    val leftover = firstRead - totalContainerLength.toInt()
                    if (leftover >= PtpConstants.HEADER_SIZE) {
                        val respBb = ByteBuffer.wrap(buffer, totalContainerLength.toInt(), leftover).order(ByteOrder.LITTLE_ENDIAN)
                        val respHeader = PtpPacket.parseHeader(respBb)
                        if (respHeader != null && respHeader.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                            return@withContext (respHeader.code == PtpConstants.RESPONSE_OK)
                        }
                    }
                }

                val resp = readResponse(tid)
                resp.isOk
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "Exception streaming object $handle", e)
                drainResidualBulkIn()
                false
            }
        }
    }

    /**
     * Open PTP Session
     */
    suspend fun openSession(): Boolean {
        return try {
            sessionId = 1
            val resp = executeSimpleCommand(PtpConstants.OPERATION_OPEN_SESSION, sessionId)
            if (resp.isOk || resp.responseCode == PtpConstants.RESPONSE_SESSION_ALREADY_OPEN) {
                isSessionOpen = true
                Log.i(PtpConstants.TAG, "PTP session opened successfully")
                true
            } else {
                Log.e(PtpConstants.TAG, "OpenSession failed: 0x${resp.responseCode.toString(16)}")
                false
            }
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "openSession exception", e)
            false
        }
    }

    /**
     * Close PTP Session
     */
    suspend fun closeSession(): Boolean {
        if (!isSessionOpen) return true
        return try {
            val resp = executeSimpleCommand(PtpConstants.OPERATION_CLOSE_SESSION)
            isSessionOpen = false
            Log.i(PtpConstants.TAG, "PTP session closed")
            resp.isOk
        } catch (e: Exception) {
            isSessionOpen = false
            false
        }
    }

    /**
     * Get Device Info
     */
    suspend fun getDeviceInfo(): PtpDeviceInfo? {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_DEVICE_INFO)
        if (!resp.isOk || payload == null || payload.isEmpty()) {
            Log.w(PtpConstants.TAG, "GetDeviceInfo failed: 0x${resp.responseCode.toString(16)}")
            return null
        }
        return try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.remaining() < 8) return null
            bb.getShort() // StandardVersion
            bb.getInt() // VendorExtensionId
            bb.getShort() // VendorExtensionVersion
            PtpPacket.parseString(bb) // VendorExtensionDesc
            if (bb.remaining() < 2) return null
            bb.getShort() // FunctionalMode

            // Operations, Events, DeviceProperties, CaptureFormats, and PlaybackFormats are UINT16 arrays in PTP ISO 15740
            val ops = PtpPacket.parseUInt16Array(bb).toSet()
            PtpPacket.parseUInt16Array(bb) // EventsSupported
            PtpPacket.parseUInt16Array(bb) // DevicePropertiesSupported
            PtpPacket.parseUInt16Array(bb) // CaptureFormats
            PtpPacket.parseUInt16Array(bb) // PlaybackFormats

            val manufacturer = PtpPacket.parseString(bb)
            val model = PtpPacket.parseString(bb)
            val deviceVersion = PtpPacket.parseString(bb)
            val serialNumber = PtpPacket.parseString(bb)

            val info = PtpDeviceInfo(manufacturer, model, deviceVersion, serialNumber, ops)
            deviceInfo = info
            Log.i(PtpConstants.TAG, "PTP DeviceInfo: manufacturer='$manufacturer', model='$model'")
            info
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to parse DeviceInfo", e)
            null
        }
    }

    /**
     * Get Storage IDs
     */
    suspend fun getStorageIds(): IntArray {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_STORAGE_IDS)
        if (!resp.isOk || payload == null || payload.isEmpty()) return IntArray(0)
        return try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val ids = PtpPacket.parseUInt32Array(bb)
            Log.i(PtpConstants.TAG, "Storage IDs: ${ids.joinToString { "0x" + it.toString(16) }}")
            ids
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to parse Storage IDs", e)
            IntArray(0)
        }
    }

    /**
     * Get Object Handles with optional format filter.
     */
    suspend fun getObjectHandles(
        storageId: Int = PtpConstants.STORAGE_ALL,
        formatCode: Int = PtpConstants.FORMAT_ALL,
        parentHandle: Int = PtpConstants.PARENT_ALL
    ): IntArray {
        val (resp, payload) = executeDataCommand(
            PtpConstants.OPERATION_GET_OBJECT_HANDLES,
            storageId,
            formatCode,
            parentHandle
        )
        if (!resp.isOk || payload == null || payload.isEmpty()) {
            Log.w(PtpConstants.TAG, "GetObjectHandles failed: 0x${resp.responseCode.toString(16)}")
            return IntArray(0)
        }
        return try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            PtpPacket.parseUInt32Array(bb)
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to parse ObjectHandles", e)
            IntArray(0)
        }
    }

    /**
     * Get Object Info for a specific object handle.
     */
    suspend fun getObjectInfo(handle: Int): PtpObjectInfo? {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_OBJECT_INFO, handle)
        if (!resp.isOk || payload == null || payload.isEmpty()) return null
        return try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.remaining() < 52) return null
            val storageId = bb.getInt()
            val format = bb.getShort().toInt() and 0xFFFF
            bb.getShort() // protectionStatus
            val compressedSize = bb.getInt().toLong() and 0xFFFFFFFFL
            val thumbFormat = bb.getShort().toInt() and 0xFFFF
            val thumbCompressedSize = bb.getInt()
            val thumbPixWidth = bb.getInt()
            val thumbPixHeight = bb.getInt()
            val imagePixWidth = bb.getInt()
            val imagePixHeight = bb.getInt()
            bb.getInt() // imageBitDepth
            val parentObject = bb.getInt()
            val associationType = bb.getShort().toInt() and 0xFFFF
            bb.getInt() // associationDesc
            bb.getInt() // sequenceNumber

            val filename = PtpPacket.parseString(bb)
            PtpPacket.parseString(bb) // dateCreated
            val dateModified = PtpPacket.parseString(bb)

            PtpObjectInfo(
                handle = handle,
                storageId = storageId,
                format = format,
                compressedSize = compressedSize,
                thumbFormat = thumbFormat,
                thumbCompressedSize = thumbCompressedSize,
                thumbPixWidth = thumbPixWidth,
                thumbPixHeight = thumbPixHeight,
                imagePixWidth = imagePixWidth,
                imagePixHeight = imagePixHeight,
                parentObject = parentObject,
                associationType = associationType,
                filename = filename,
                dateModified = dateModified
            )
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Error parsing ObjectInfo for handle $handle", e)
            null
        }
    }

    /**
     * Get Thumbnail for an image.
     * Prefers GetThumb operation, decoding as RGB_565 with bounds.
     */
    suspend fun getThumb(handle: Int, targetWidth: Int = 320): Bitmap? {
        val (resp, payload) = executeDataCommand(PtpConstants.OPERATION_GET_THUMB, handle)
        if (!resp.isOk || payload == null || payload.isEmpty()) return null

        return withContext(Dispatchers.Default) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(payload, 0, payload.size, opts)

                val sampleSize = calculateInSampleSize(opts, targetWidth, targetWidth)
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(payload, 0, payload.size, decodeOpts)
            } catch (e: OutOfMemoryError) {
                Log.e(PtpConstants.TAG, "OOM decoding thumbnail for handle $handle", e)
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Checks if the connected PTP/MTP device supports partial object operations.
     */
    fun supportsPartialObject(): Boolean {
        val ops = deviceInfo?.operationsSupported ?: return true
        return ops.contains(PtpConstants.OPERATION_GET_PARTIAL_OBJECT) ||
               ops.contains(PtpConstants.OPERATION_GET_PARTIAL_OBJECT_64)
    }

    /**
     * Get Partial Object data for a range with 64-bit offset and cancellation support.
     */
    suspend fun getPartialObjectRange(
        handle: Int,
        offset: Long,
        maxBytes: Int,
        isCancelled: (() -> Boolean)? = null
    ): ByteArray? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isActive || isCancelled?.invoke() == true) return@withContext null
            val tid = nextTransactionId()
            try {
                val use64 = offset > 0xFFFFFFFFL || (offset + maxBytes > 0xFFFFFFFFL) ||
                    (deviceInfo?.operationsSupported?.contains(PtpConstants.OPERATION_GET_PARTIAL_OBJECT_64) == true &&
                     deviceInfo?.operationsSupported?.contains(PtpConstants.OPERATION_GET_PARTIAL_OBJECT) != true)

                val sent = if (use64) {
                    val offsetLow = (offset and 0xFFFFFFFFL).toInt()
                    val offsetHigh = ((offset ushr 32) and 0xFFFFFFFFL).toInt()
                    sendCommand(PtpConstants.OPERATION_GET_PARTIAL_OBJECT_64, tid, handle, offsetLow, offsetHigh, maxBytes, 0)
                } else {
                    val offsetInt = (offset and 0xFFFFFFFFL).toInt()
                    sendCommand(PtpConstants.OPERATION_GET_PARTIAL_OBJECT, tid, handle, offsetInt, maxBytes)
                }

                if (!sent) {
                    return@withContext null
                }

                val initialBuffer = ByteArray(32768)
                val firstRead = safeBulkTransfer(bulkIn, initialBuffer, initialBuffer.size, usbTimeout)
                if (firstRead < PtpConstants.HEADER_SIZE) {
                    cancelTransaction(tid)
                    return@withContext null
                }

                val bb = ByteBuffer.wrap(initialBuffer, 0, firstRead).order(ByteOrder.LITTLE_ENDIAN)
                val header = PtpPacket.parseHeader(bb)
                if (header == null) {
                    cancelTransaction(tid)
                    return@withContext null
                }

                if (header.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                    Log.w(PtpConstants.TAG, "getPartialObjectRange response error: 0x${header.code.toString(16)}")
                    return@withContext null
                }

                if (header.type != PtpConstants.CONTAINER_TYPE_DATA) {
                    cancelTransaction(tid)
                    return@withContext null
                }

                val totalContainerLengthLong = header.length.toLong() and 0xFFFFFFFFL
                val isIndeterminate = (totalContainerLengthLong == 0xFFFFFFFFL)
                val payloadLength = if (isIndeterminate) -1 else (totalContainerLengthLong - PtpConstants.HEADER_SIZE).toInt()

                if (!isIndeterminate && (payloadLength < 0 || payloadLength > 16 * 1024 * 1024)) {
                    cancelTransaction(tid)
                    return@withContext null
                }

                val payload: ByteArray
                var responseFound: PtpResponse? = null

                if (isIndeterminate) {
                    val stream = java.io.ByteArrayOutputStream()
                    val initialBytes = firstRead - PtpConstants.HEADER_SIZE
                    if (initialBytes > 0) {
                        stream.write(initialBuffer, PtpConstants.HEADER_SIZE, initialBytes)
                    }
                    val chunk = ByteArray(16384)
                    while (stream.size() < maxBytes) {
                        if (!isActive || isCancelled?.invoke() == true) {
                            cancelTransaction(tid)
                            return@withContext null
                        }
                        val r = safeBulkTransfer(bulkIn, chunk, chunk.size, usbTimeout)
                        if (r <= 0) break
                        val chunkBb = ByteBuffer.wrap(chunk, 0, r).order(ByteOrder.LITTLE_ENDIAN)
                        val chunkHdr = PtpPacket.parseHeader(chunkBb)
                        if (chunkHdr != null && chunkHdr.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                            val pCount = ((r - PtpConstants.HEADER_SIZE) / 4).coerceAtMost(chunkBb.remaining() / 4)
                            val respParams = IntArray(pCount)
                            for (i in 0 until pCount) {
                                respParams[i] = chunkBb.getInt()
                            }
                            responseFound = PtpResponse(chunkHdr.code, chunkHdr.transactionId, respParams)
                            break
                        }
                        stream.write(chunk, 0, r)
                        if (r < chunk.size) break
                    }
                    payload = stream.toByteArray()
                } else {
                    payload = ByteArray(payloadLength)
                    val availablePayloadInFirstRead = (firstRead - PtpConstants.HEADER_SIZE).coerceAtLeast(0)
                    val initialBytesToCopy = availablePayloadInFirstRead.coerceAtMost(payloadLength)
                    if (initialBytesToCopy > 0) {
                        System.arraycopy(initialBuffer, PtpConstants.HEADER_SIZE, payload, 0, initialBytesToCopy)
                    }

                    var bytesRead = initialBytesToCopy
                    val chunk = ByteArray(16384)
                    while (bytesRead < payloadLength) {
                        if (!isActive || isCancelled?.invoke() == true) {
                            cancelTransaction(tid)
                            return@withContext null
                        }
                        val readChunk = safeBulkTransfer(bulkIn, chunk, chunk.size, usbTimeout)
                        if (readChunk <= 0) {
                            cancelTransaction(tid)
                            return@withContext null
                        }
                        val toCopy = readChunk.coerceAtMost(payloadLength - bytesRead)
                        System.arraycopy(chunk, 0, payload, bytesRead, toCopy)
                        bytesRead += toCopy

                        if (readChunk > toCopy) {
                            val leftover = readChunk - toCopy
                            if (leftover >= PtpConstants.HEADER_SIZE) {
                                val respBb = ByteBuffer.wrap(chunk, toCopy, leftover).order(ByteOrder.LITTLE_ENDIAN)
                                val respHeader = PtpPacket.parseHeader(respBb)
                                if (respHeader != null && respHeader.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                                    val pCount = ((leftover - PtpConstants.HEADER_SIZE) / 4).coerceAtMost(respBb.remaining() / 4)
                                    val respParams = IntArray(pCount)
                                    for (i in 0 until pCount) {
                                        respParams[i] = respBb.getInt()
                                    }
                                    responseFound = PtpResponse(respHeader.code, respHeader.transactionId, respParams)
                                }
                            }
                        }
                    }

                    if (responseFound == null) {
                        val leftoverInFirstRead = firstRead - totalContainerLengthLong.toInt()
                        if (leftoverInFirstRead >= PtpConstants.HEADER_SIZE) {
                            val respBb = ByteBuffer.wrap(initialBuffer, totalContainerLengthLong.toInt(), leftoverInFirstRead)
                                .order(ByteOrder.LITTLE_ENDIAN)
                            val respHeader = PtpPacket.parseHeader(respBb)
                            if (respHeader != null && respHeader.type == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                                val pCount = ((leftoverInFirstRead - PtpConstants.HEADER_SIZE) / 4).coerceAtMost(respBb.remaining() / 4)
                                val respParams = IntArray(pCount)
                                for (i in 0 until pCount) {
                                    respParams[i] = respBb.getInt()
                                }
                                responseFound = PtpResponse(respHeader.code, respHeader.transactionId, respParams)
                            }
                        }
                    }
                }

                val finalResp = responseFound ?: readResponse(tid)
                if (finalResp.isOk) payload else null
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "getPartialObjectRange exception for handle $handle at $offset", e)
                cancelTransaction(tid)
                null
            }
        }
    }

    /**
     * Get Partial Object data (e.g. for reading header or preview).
     */
    suspend fun getPartialObject(handle: Int, offset: Int, maxBytes: Int): ByteArray? {
        return getPartialObjectRange(handle, offset.toLong(), maxBytes)
    }

    companion object {
        fun calculateInSampleSize(
            options: BitmapFactory.Options,
            reqWidth: Int,
            reqHeight: Int
        ): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }
    }
}
