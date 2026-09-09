package dev.maxhogan.tapshim.protocol

/**
 * One tap event as sent by the headphones over the RFCOMM socket.
 *
 * Wire format (reverse-engineered from the Spotify Android app, Sept 2026):
 *   0x01, <len>, then <len> bytes holding three NUL-terminated ASCII strings:
 *   clientId, deviceName, manufacturer.
 */
data class TapCommand(
    val clientId: String,
    val deviceName: String,
    val manufacturer: String,
    val raw: ByteArray,
) {
    val rawHex: String get() = raw.toHex()

    override fun equals(other: Any?): Boolean =
        other is TapCommand &&
            clientId == other.clientId &&
            deviceName == other.deviceName &&
            manufacturer == other.manufacturer &&
            raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String =
        "TapCommand(clientId=$clientId, deviceName=$deviceName, manufacturer=$manufacturer, raw=$rawHex)"
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
