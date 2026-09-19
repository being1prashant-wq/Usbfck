package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.usb.PtpConstants
import com.example.usb.PtpPacket
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("DirectUSB", appName)
  }

  @Test
  fun `verify PTP command packet structure`() {
    val packet = PtpPacket.buildCommand(
      operationCode = PtpConstants.OPERATION_OPEN_SESSION,
      transactionId = 1,
      1
    )

    assertEquals(16, packet.size)
    val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(16, bb.getInt())
    assertEquals(PtpConstants.CONTAINER_TYPE_COMMAND, bb.getShort())
    assertEquals(PtpConstants.OPERATION_OPEN_SESSION.toShort(), bb.getShort())
    assertEquals(1, bb.getInt())
    assertEquals(1, bb.getInt())
  }

  @Test
  fun `verify PtpPacket parseUInt16Array`() {
    // 4-byte count = 3, followed by 3 UINT16 values (0x1001, 0x1002, 0x1003)
    val bb = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
    bb.putInt(3)
    bb.putShort(0x1001.toShort())
    bb.putShort(0x1002.toShort())
    bb.putShort(0x1003.toShort())
    bb.flip()

    val result = PtpPacket.parseUInt16Array(bb)
    assertEquals(3, result.size)
    assertEquals(0x1001, result[0])
    assertEquals(0x1002, result[1])
    assertEquals(0x1003, result[2])
  }

  @Test
  fun `verify PtpPacket parseUInt32Array`() {
    // 4-byte count = 2, followed by 2 UINT32 values (0x00010001, 0x00020001)
    val bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
    bb.putInt(2)
    bb.putInt(0x00010001)
    bb.putInt(0x00020001)
    bb.flip()

    val result = PtpPacket.parseUInt32Array(bb)
    assertEquals(2, result.size)
    assertEquals(0x00010001, result[0])
    assertEquals(0x00020001, result[1])
  }

  @Test
  fun `verify PtpPacket parseString UTF16LE`() {
    // "TV" in UTF-16LE: numChars = 3 ('T', 'V', '\0')
    val bb = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
    bb.put(3.toByte()) // 3 chars
    bb.putShort('T'.code.toShort())
    bb.putShort('V'.code.toShort())
    bb.putShort(0) // null terminator
    bb.flip()

    val str = PtpPacket.parseString(bb)
    assertEquals("TV", str)
  }

  @Test
  fun `verify PtpPacket handles truncated buffer safely`() {
    val bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
    bb.putShort(1)
    bb.flip()

    // parseHeader needs at least 12 bytes; should safely return null without throwing
    val header = PtpPacket.parseHeader(bb)
    assertEquals(null, header)

    // parseUInt16Array needs at least 4 bytes; should return empty array
    val arr16 = PtpPacket.parseUInt16Array(bb)
    assertEquals(0, arr16.size)

    // parseUInt32Array needs at least 4 bytes; should return empty array
    val arr32 = PtpPacket.parseUInt32Array(bb)
    assertEquals(0, arr32.size)
  }
}
