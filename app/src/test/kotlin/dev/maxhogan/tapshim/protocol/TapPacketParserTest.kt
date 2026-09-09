package dev.maxhogan.tapshim.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TapPacketParserTest {

    @Test
    fun parsesWellFormedFrame() {
        val frame = frame("bose-client", "QC Ultra", "Bose")
        val cmd = TapPacketParser.parse(frame)
        assertEquals("bose-client", cmd.clientId)
        assertEquals("QC Ultra", cmd.deviceName)
        assertEquals("Bose", cmd.manufacturer)
        assertEquals(frame.toList(), cmd.raw.toList())
    }

    @Test
    fun allowsEmptyFields() {
        val cmd = TapPacketParser.parse(frame("", "", ""))
        assertEquals("", cmd.clientId)
        assertEquals("", cmd.deviceName)
        assertEquals("", cmd.manufacturer)
    }

    @Test
    fun acceptsPayloadLongerThan127() {
        // Spotify treats the length byte as signed and rejects >127; we accept
        // the full unsigned range so a long device name does not break us.
        val longName = "N".repeat(200)
        val f = frame("c", longName, "m")
        assertEquals(2 + 1 + 1 + 200 + 1 + 1 + 1, f.size)
        val cmd = TapPacketParser.parse(f)
        assertEquals(longName, cmd.deviceName)
    }

    @Test
    fun rejectsEmptyFrame() {
        assertThrows(TapProtocolException::class.java) { TapPacketParser.parse(byteArrayOf()) }
    }

    @Test
    fun rejectsBadHeader() {
        assertThrows(TapProtocolException::class.java) { TapPacketParser.parse(byteArrayOf(0x02, 0x00)) }
    }

    @Test
    fun rejectsMissingLength() {
        assertThrows(TapProtocolException::class.java) { TapPacketParser.parse(byteArrayOf(0x01)) }
    }

    @Test
    fun rejectsTruncatedPayload() {
        val f = frame("a", "b", "c")
        val truncated = f.copyOfRange(0, f.size - 1)
        assertThrows(TapProtocolException::class.java) { TapPacketParser.parse(truncated) }
    }

    @Test
    fun rejectsTrailingBytes() {
        val f = frame("a", "b", "c") + byteArrayOf(0x00)
        assertThrows(TapProtocolException::class.java) { TapPacketParser.parse(f) }
    }

    @Test
    fun rejectsUnterminatedLastField() {
        val payload = "a\u0000b\u0000c".toByteArray(Charsets.US_ASCII)
        val f = byteArrayOf(0x01, payload.size.toByte()) + payload
        val e = assertThrows(TapProtocolException::class.java) { TapPacketParser.parse(f) }
        assertEquals("last field is not NUL-terminated", e.message)
    }

    @Test
    fun rejectsWrongFieldCount() {
        val two = "a\u0000b\u0000".toByteArray(Charsets.US_ASCII)
        val f2 = byteArrayOf(0x01, two.size.toByte()) + two
        assertThrows(TapProtocolException::class.java) { TapPacketParser.parse(f2) }
        val four = "a\u0000b\u0000c\u0000d\u0000".toByteArray(Charsets.US_ASCII)
        val f4 = byteArrayOf(0x01, four.size.toByte()) + four
        assertThrows(TapProtocolException::class.java) { TapPacketParser.parse(f4) }
    }

    @Test
    fun zeroLengthPayloadIsWrongFieldCount() {
        assertThrows(TapProtocolException::class.java) { TapPacketParser.parse(byteArrayOf(0x01, 0x00)) }
    }

    @Test
    fun hexRendering() {
        assertEquals("01 02 ff", byteArrayOf(0x01, 0x02, 0xff.toByte()).toHex())
    }

    companion object {
        /** Build a frame exactly as the headphones would send it. */
        fun frame(clientId: String, deviceName: String, manufacturer: String): ByteArray {
            val payload = "$clientId\u0000$deviceName\u0000$manufacturer\u0000".toByteArray(Charsets.US_ASCII)
            require(payload.size <= 255)
            return byteArrayOf(TapPacketParser.HEADER, payload.size.toByte()) + payload
        }
    }
}
