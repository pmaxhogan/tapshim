package dev.maxhogan.tapshim.protocol

import java.io.IOException
import java.io.InputStream

/**
 * Blocking read loop over an RFCOMM input stream. Returns normally on EOF,
 * throws [IOException] if the socket errors. Malformed frames are reported to
 * [onError] and skipped; the loop keeps reading.
 */
class TapStreamReader(
    private val input: InputStream,
    private val onCommand: (TapCommand) -> Unit,
    private val onError: (TapProtocolException) -> Unit = {},
    private val onGarbage: (ByteArray) -> Unit = {},
) {
    @Volatile
    private var stopped = false

    fun stop() {
        stopped = true
    }

    @Throws(IOException::class)
    fun run() {
        val decoder = TapFrameDecoder(onGarbage)
        val buf = ByteArray(TapPacketParser.MAX_FRAME)
        while (!stopped) {
            val n = input.read(buf, 0, buf.size)
            if (n < 0) return
            for (frame in decoder.feed(buf, n)) {
                try {
                    onCommand(TapPacketParser.parse(frame))
                } catch (e: TapProtocolException) {
                    onError(e)
                }
            }
        }
    }
}
