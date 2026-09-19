package com.example.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class PtpHeader(
    val length: Int,
    val type: Short,
    val code: Int,
    val transactionId: Int
)

data class PtpResponse(
    val responseCode: Int,
    val transactionId: Int,
    val params: IntArray
) {
    val isOk: Boolean get() = responseCode == PtpConstants.RESPONSE_OK

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PtpResponse
        if (responseCode != other.responseCode) return false
        if (transactionId != other.transactionId) return false
        return params.contentEquals(other.params)
    }

    override fun hashCode(): Int {
        var result = responseCode
        result = 31 * result + transactionId
        result = 31 * result + params.contentHashCode()
        return result
    }
}

object PtpPacket {

    fun buildCommand(operationCode: Int, transactionId: Int, vararg params: Int): ByteArray {
        val totalLength = PtpConstants.HEADER_SIZE + (params.size * 4)
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalLength)
        buffer.putShort(PtpConstants.CONTAINER_TYPE_COMMAND)
        buffer.putShort(operationCode.toShort())
        buffer.putInt(transactionId)
        for (param in params) {
            buffer.putInt(param)
        }
        return buffer.array()
    }

    fun parseHeader(buffer: ByteBuffer): PtpHeader? {
        if (buffer.remaining() < PtpConstants.HEADER_SIZE) return null
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        return try {
            val length = buffer.getInt()
            val type = buffer.getShort()
            val code = buffer.getShort().toInt() and 0xFFFF
            val transactionId = buffer.getInt()
            PtpHeader(length, type, code, transactionId)
        } catch (e: Exception) {
            null
        }
    }

    fun parseResponse(buffer: ByteBuffer): PtpResponse {
        val header = parseHeader(buffer) ?: return PtpResponse(PtpConstants.RESPONSE_GENERAL_ERROR, 0, IntArray(0))
        val remainingBytes = buffer.remaining()
        val paramCount = (remainingBytes / 4).coerceAtLeast(0)
        val params = IntArray(paramCount)
        for (i in 0 until paramCount) {
            params[i] = buffer.getInt()
        }
        return PtpResponse(header.code, header.transactionId, params)
    }

    /**
     * Parse PTP String according to ISO 15740:
     * - 1 byte character count (numChars, including null terminator)
     * - followed by (numChars * 2) bytes of UTF-16LE characters
     */
    fun parseString(buffer: ByteBuffer): String {
        if (!buffer.hasRemaining()) return ""
        val numChars = try {
            buffer.get().toInt() and 0xFF
        } catch (e: Exception) {
            return ""
        }
        if (numChars == 0) return ""

        val totalBytes = numChars * 2
        if (buffer.remaining() < totalBytes) {
            // Not enough bytes remaining in buffer, consume safely without throwing
            val skip = buffer.remaining()
            buffer.position(buffer.position() + skip)
            return ""
        }

        return try {
            val stringBytes = ByteArray((numChars - 1) * 2)
            if (stringBytes.isNotEmpty()) {
                buffer.get(stringBytes)
            }
            // Discard 2-byte null terminator
            buffer.getShort()
            String(stringBytes, StandardCharsets.UTF_16LE)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Parse array of 16-bit unsigned integers (used in DeviceInfo for operations, events, formats)
     * Format: 4-byte count (UINT32), followed by count * UINT16 elements.
     */
    fun parseUInt16Array(buffer: ByteBuffer): IntArray {
        if (buffer.remaining() < 4) return IntArray(0)
        return try {
            val count = buffer.getInt()
            if (count <= 0 || count > 5000 || buffer.remaining() < count * 2) {
                return IntArray(0)
            }
            val result = IntArray(count)
            for (i in 0 until count) {
                result[i] = buffer.getShort().toInt() and 0xFFFF
            }
            result
        } catch (e: Exception) {
            IntArray(0)
        }
    }

    /**
     * Parse array of 32-bit unsigned integers (used for StorageIDs, ObjectHandles)
     * Format: 4-byte count (UINT32), followed by count * UINT32 elements.
     */
    fun parseUInt32Array(buffer: ByteBuffer): IntArray {
        if (buffer.remaining() < 4) return IntArray(0)
        return try {
            val countRaw = buffer.getInt()
            if (countRaw <= 0) return IntArray(0)
            val available = buffer.remaining() / 4
            val count = countRaw.coerceAtMost(available)
            if (count <= 0) return IntArray(0)
            val result = IntArray(count)
            for (i in 0 until count) {
                result[i] = buffer.getInt()
            }
            result
        } catch (e: Exception) {
            IntArray(0)
        }
    }
}
