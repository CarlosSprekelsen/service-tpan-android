package com.katim.dts.service.tpan.codec

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Encodes and decodes TPAN 3-byte framed protocol.
 *
 * Wire format (identical on Hub and EUD):
 * ```
 * +----------+----------------+----------------------------+
 * | type     | length         | payload                    |
 * | (1 byte) | (2 bytes, BE)  | (0 to 65535 bytes)         |
 * +----------+----------------+----------------------------+
 * ```
 *
 * Thread safety: [writeFrame] is synchronized so multiple writers (data path +
 * keepalive timer) can share a single output stream safely. [readFrame] is NOT
 * synchronized — it is expected to be called from a single reader loop.
 *
 * See svc-tpan-architecture.md §Framing Protocol.
 */
object FrameCodec {

    private const val HEADER_SIZE = 3

    /**
     * Read one frame from [input].
     *
     * Handles partial reads (loops until the full header and payload are received).
     *
     * @return the decoded [Frame]
     * @throws EOFException if the stream ends mid-frame or before any data
     * @throws IOException on underlying I/O error
     * @throws IllegalArgumentException if the frame type byte is unknown
     */
    fun readFrame(input: InputStream): Frame {
        val header = readExact(input, HEADER_SIZE)

        val type = FrameType.fromWire(header[0])
            ?: throw IllegalArgumentException(
                "Unknown frame type: 0x${(header[0].toInt() and 0xFF).toString(16)}"
            )

        val length = ((header[1].toInt() and 0xFF) shl 8) or
                (header[2].toInt() and 0xFF)

        val payload = if (length > 0) readExact(input, length) else ByteArray(0)
        return Frame(type, payload)
    }

    /**
     * Write one frame to [output].
     *
     * Synchronized so concurrent callers (data loop + keepalive timer) do not
     * interleave bytes on the wire.
     */
    @Synchronized
    fun writeFrame(output: OutputStream, frame: Frame) {
        val length = frame.payload.size
        val buf = ByteArray(HEADER_SIZE + length)
        buf[0] = frame.type.wire
        buf[1] = ((length shr 8) and 0xFF).toByte()
        buf[2] = (length and 0xFF).toByte()
        if (length > 0) {
            frame.payload.copyInto(buf, HEADER_SIZE)
        }
        output.write(buf)
        output.flush()
    }

    /**
     * Read exactly [count] bytes from [input], handling partial reads.
     *
     * @throws EOFException if the stream ends before [count] bytes are read
     */
    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n == -1) throw EOFException("Stream ended after $offset of $count bytes")
            offset += n
        }
        return buf
    }
}

