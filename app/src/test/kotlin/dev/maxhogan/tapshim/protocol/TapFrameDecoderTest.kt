package dev.maxhogan.tapshim.protocol

import dev.maxhogan.tapshim.protocol.TapPacketParserTest.Companion.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapFrameDecoderTest {

    @Test
    fun wholeFrameInOneRead() {
        val f = frame("a", "b", "c")
        val out = TapFrameDecoder().feed(f)
        assertEquals(1, out.size)
        assertEquals(f.toList(), out[0].toList())
    }

    @Test
    fun frameSplitAcrossReads() {
        val f = frame("client", "device", "maker")
        val d = TapFrameDecoder()
        assertTrue(d.feed(f.copyOfRange(0, 1)).isEmpty())
        assertTrue(d.feed(f.copyOfRange(1, 5)).isEmpty())
        val out = d.feed(f.copyOfRange(5, f.size))
        assertEquals(1, out.size)
        assertEquals(f.toList(), out[0].toList())
        assertEquals(0, d.pending)
    }

    @Test
    fun twoFramesCoalescedInOneRead() {
        val f1 = frame("1", "x", "y")
        val f2 = frame("2", "x", "y")
        val out = TapFrameDecoder().feed(f1 + f2)
        assertEquals(2, out.size)
        assertEquals(f1.toList(), out[0].toList())
        assertEquals(f2.toList(), out[1].toList())
    }

    @Test
    fun garbageBeforeHeaderIsDroppedAndReported() {
        val garbage = ArrayList<List<Byte>>()
        val d = TapFrameDecoder { garbage.add(it.toList()) }
        val f = frame("a", "b", "c")
        val out = d.feed(byteArrayOf(0x55, 0x66) + f)
        assertEquals(1, out.size)
        assertEquals(listOf(listOf<Byte>(0x55, 0x66)), garbage)
    }

    @Test
    fun onlyGarbageLeavesNothingPending() {
        val garbage = ArrayList<List<Byte>>()
        val d = TapFrameDecoder { garbage.add(it.toList()) }
        assertTrue(d.feed(byteArrayOf(0x7f, 0x7f)).isEmpty())
        assertEquals(0, d.pending)
        assertEquals(1, garbage.size)
    }

    @Test
    fun honoursLengthArgument() {
        val f = frame("a", "b", "c")
        val buf = f + ByteArray(50)
        val out = TapFrameDecoder().feed(buf, f.size)
        assertEquals(1, out.size)
    }

    @Test
    fun lengthByteIsUnsigned() {
        val payload = ByteArray(200) { 'z'.code.toByte() }
        val f = byteArrayOf(0x01, 200.toByte()) + payload
        val out = TapFrameDecoder().feed(f)
        assertEquals(1, out.size)
        assertEquals(202, out[0].size)
    }

    @Test
    fun zeroLengthReadIsIgnored() {
        val d = TapFrameDecoder()
        assertTrue(d.feed(ByteArray(10), 0).isEmpty())
        assertEquals(0, d.pending)
    }
}
