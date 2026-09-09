package dev.maxhogan.tapshim.protocol

class TapProtocolException(message: String, val raw: ByteArray) : Exception(message)

object TapPacketParser {
    const val HEADER: Byte = 0x01
    const val FIELD_COUNT = 3

    /** The largest possible frame: header + length byte + 255 payload bytes. */
    const val MAX_FRAME = 2 + 255

    /**
     * Parse one complete frame. The frame must be exactly header + len + payload;
     * trailing bytes are a protocol error (use [TapFrameDecoder] for streams).
     */
    fun parse(frame: ByteArray): TapCommand {
        if (frame.isEmpty()) throw TapProtocolException("empty frame", frame)
        if (frame[0] != HEADER) throw TapProtocolException("bad header 0x%02x".format(frame[0]), frame)
        if (frame.size < 2) throw TapProtocolException("missing length byte", frame)
        val len = frame[1].toInt() and 0xff
        if (frame.size != 2 + len) {
            throw TapProtocolException("length byte says $len payload bytes, frame has ${frame.size - 2}", frame)
        }
        val payload = frame.copyOfRange(2, 2 + len)
        val fields = splitFields(payload, frame)
        if (fields.size != FIELD_COUNT) {
            throw TapProtocolException("expected $FIELD_COUNT fields, got ${fields.size}", frame)
        }
        return TapCommand(fields[0], fields[1], fields[2], frame)
    }

    private fun splitFields(payload: ByteArray, frame: ByteArray): List<String> {
        if (payload.isEmpty()) return emptyList()
        val fields = ArrayList<String>(FIELD_COUNT)
        val sb = StringBuilder()
        for (b in payload) {
            if (b == 0.toByte()) {
                fields.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append((b.toInt() and 0xff).toChar())
            }
        }
        if (sb.isNotEmpty()) throw TapProtocolException("last field is not NUL-terminated", frame)
        return fields
    }
}
