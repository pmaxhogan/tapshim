package dev.maxhogan.tapshim.protocol

import dev.maxhogan.tapshim.protocol.TapPacketParserTest.Companion.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class TapStreamReaderTest {

    @Test
    fun emitsEachCommandThenReturnsOnEof() {
        val bytes = frame("a", "b", "c") + frame("d", "e", "f")
        val seen = ArrayList<TapCommand>()
        TapStreamReader(ByteArrayInputStream(bytes), { seen.add(it) }).run()
        assertEquals(listOf("a", "d"), seen.map { it.clientId })
    }

    @Test
    fun skipsMalformedFrameAndKeepsReading() {
        val bad = byteArrayOf(0x01, 0x02, 'x'.code.toByte(), 'y'.code.toByte()) // payload has no NULs
        val bytes = bad + frame("ok", "b", "c")
        val seen = ArrayList<TapCommand>()
        val errors = ArrayList<TapProtocolException>()
        TapStreamReader(ByteArrayInputStream(bytes), { seen.add(it) }, { errors.add(it) }).run()
        assertEquals(listOf("ok"), seen.map { it.clientId })
        assertEquals(1, errors.size)
    }

    @Test
    fun handlesOneByteAtATimeStream() {
        val bytes = frame("slow", "b", "c")
        val trickle = object : InputStream() {
            var i = 0
            override fun read(): Int = if (i < bytes.size) bytes[i++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= bytes.size) return -1
                b[off] = bytes[i++]
                return 1
            }
        }
        val seen = ArrayList<TapCommand>()
        TapStreamReader(trickle, { seen.add(it) }).run()
        assertEquals(listOf("slow"), seen.map { it.clientId })
    }

    @Test
    fun propagatesIoException() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("socket closed")
        }
        assertThrows(IOException::class.java) { TapStreamReader(broken, {}).run() }
    }

    @Test
    fun stopEndsLoopAfterCurrentRead() {
        val endless = object : InputStream() {
            var reads = 0
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return 0
            }
        }
        val reader = TapStreamReader(endless, {})
        val t = Thread { reader.run() }
        t.start()
        reader.stop()
        t.join(2000)
        assertEquals(false, t.isAlive)
    }
}
