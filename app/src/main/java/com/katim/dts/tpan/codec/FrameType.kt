package com.katim.dts.tpan.codec

/**
 * TPAN frame types.
 *
 * Wire format: single unsigned byte.
 * See svc-tpan-architecture.md §Framing Protocol.
 */
enum class FrameType(val wire: Byte) {
    /** Raw IPv4 packet. */
    DATA(0x00),
    /** Link health probe — empty payload, sent every 1 s. */
    KEEPALIVE(0x01),
    /** Graceful tunnel teardown — empty payload. */
    SHUTDOWN(0x02);

    companion object {
        /** Decode a wire byte to [FrameType], or `null` if unknown. */
        fun fromWire(b: Byte): FrameType? = entries.firstOrNull { it.wire == b }
    }
}
