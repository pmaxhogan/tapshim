package dev.maxhogan.tapshim.protocol

/**
 * Incremental framer for the RFCOMM byte stream. RFCOMM may split or coalesce
 * writes, so bytes are buffered until a whole frame (header, length, payload)
 * is available. Bytes before a header byte are discarded as garbage and
 * reported through [onGarbage].
 */
class TapFrameDecoder(private val onGarbage: (ByteArray) -> Unit = {}) {
    private var buffer = ByteArray(0)

    /** Feed bytes in; returns every complete frame now available, in order. */
    fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
        if (length <= 0) return emptyList()
        buffer += bytes.copyOfRange(0, length)
        val frames = ArrayList<ByteArray>()
        while (true) {
            resyncToHeader()
            if (buffer.size < 2) break
            val len = buffer[1].toInt() and 0xff
            val frameSize = 2 + len
            if (buffer.size < frameSize) break
            frames.add(buffer.copyOfRange(0, frameSize))
            buffer = buffer.copyOfRange(frameSize, buffer.size)
        }
        return frames
    }

    /** Bytes currently buffered but not yet forming a full frame. */
    val pending: Int get() = buffer.size

    private fun resyncToHeader() {
        val idx = buffer.indexOfFirst { it == TapPacketParser.HEADER }
        when {
            idx == 0 -> return
            idx < 0 -> {
                if (buffer.isNotEmpty()) onGarbage(buffer)
                buffer = ByteArray(0)
            }
            else -> {
                onGarbage(buffer.copyOfRange(0, idx))
                buffer = buffer.copyOfRange(idx, buffer.size)
            }
        }
    }
}
