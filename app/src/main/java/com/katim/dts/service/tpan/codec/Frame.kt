package com.katim.dts.service.tpan.codec

/**
 * A single TPAN frame (type + payload).
 *
 * Wire layout: `type(1) | length(2, BE) | payload(0..65535)`.
 */
data class Frame(
    val type: FrameType,
    val payload: ByteArray = EMPTY
) {
    companion object {
        private val EMPTY = ByteArray(0)

        /** Convenience factory for a DATA frame wrapping a raw Ethernet frame. */
        fun data(packet: ByteArray): Frame = Frame(FrameType.DATA, packet)

        /** Convenience factory for a KEEPALIVE frame (empty payload). */
        fun keepalive(): Frame = Frame(FrameType.KEEPALIVE)

        /** Convenience factory for a SHUTDOWN frame (empty payload). */
        fun shutdown(): Frame = Frame(FrameType.SHUTDOWN)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()

    override fun toString(): String = "Frame($type, ${payload.size} bytes)"
}

