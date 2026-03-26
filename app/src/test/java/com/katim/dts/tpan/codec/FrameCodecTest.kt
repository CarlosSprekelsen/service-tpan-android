package com.katim.dts.tpan.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class FrameCodecTest {

    // ── Round-trip ──────────────────────────────────────────────────────────

    @Test
    fun `DATA frame round-trip`() {
        val payload = byteArrayOf(0x45, 0x00, 0x00, 0x1C) // minimal IP header stub
        val original = Frame.data(payload)

        val wire = encode(original)
        val decoded = decode(wire)

        assertEquals(FrameType.DATA, decoded.type)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `KEEPALIVE frame round-trip`() {
        val original = Frame.keepalive()

        val wire = encode(original)
        val decoded = decode(wire)

        assertEquals(FrameType.KEEPALIVE, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `SHUTDOWN frame round-trip`() {
        val original = Frame.shutdown()

        val wire = encode(original)
        val decoded = decode(wire)

        assertEquals(FrameType.SHUTDOWN, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    // ── Wire format ────────────────────────────────────────────────────────

    @Test
    fun `wire format matches 3-byte header spec`() {
        val payload = ByteArray(300) { (it % 256).toByte() }
        val wire = encode(Frame.data(payload))

        // type
        assertEquals(0x00.toByte(), wire[0])
        // length big-endian: 300 = 0x012C
        assertEquals(0x01.toByte(), wire[1])
        assertEquals(0x2C.toByte(), wire[2])
        // payload
        assertArrayEquals(payload, wire.copyOfRange(3, wire.size))
    }

    @Test
    fun `KEEPALIVE wire is exactly 3 bytes`() {
        val wire = encode(Frame.keepalive())
        assertEquals(3, wire.size)
        assertEquals(0x01.toByte(), wire[0]) // KEEPALIVE type
        assertEquals(0x00.toByte(), wire[1]) // length high
        assertEquals(0x00.toByte(), wire[2]) // length low
    }

    @Test
    fun `SHUTDOWN wire is exactly 3 bytes`() {
        val wire = encode(Frame.shutdown())
        assertEquals(3, wire.size)
        assertEquals(0x02.toByte(), wire[0])
        assertEquals(0x00.toByte(), wire[1])
        assertEquals(0x00.toByte(), wire[2])
    }

    // ── Max MTU payload ─────────────────────────────────────────────────────

    @Test
    fun `DATA frame with 1400-byte MTU payload`() {
        val payload = ByteArray(1400) { (it % 251).toByte() }
        val original = Frame.data(payload)

        val wire = encode(original)
        assertEquals(3 + 1400, wire.size)

        val decoded = decode(wire)
        assertEquals(FrameType.DATA, decoded.type)
        assertArrayEquals(payload, decoded.payload)
    }

    // ── Partial read simulation ─────────────────────────────────────────────

    @Test
    fun `readFrame handles single-byte input stream`() {
        val payload = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val wire = encode(Frame.data(payload))

        // Feed one byte at a time to simulate partial reads
        val input = SingleByteInputStream(wire)
        val decoded = FrameCodec.readFrame(input)

        assertEquals(FrameType.DATA, decoded.type)
        assertArrayEquals(payload, decoded.payload)
    }

    // ── Multiple frames in sequence ─────────────────────────────────────────

    @Test
    fun `read multiple frames from one stream`() {
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, Frame.keepalive())
        FrameCodec.writeFrame(out, Frame.data(byteArrayOf(0xAA.toByte())))
        FrameCodec.writeFrame(out, Frame.shutdown())

        val input = ByteArrayInputStream(out.toByteArray())

        val f1 = FrameCodec.readFrame(input)
        assertEquals(FrameType.KEEPALIVE, f1.type)

        val f2 = FrameCodec.readFrame(input)
        assertEquals(FrameType.DATA, f2.type)
        assertArrayEquals(byteArrayOf(0xAA.toByte()), f2.payload)

        val f3 = FrameCodec.readFrame(input)
        assertEquals(FrameType.SHUTDOWN, f3.type)
    }

    // ── Error cases ─────────────────────────────────────────────────────────

    @Test(expected = EOFException::class)
    fun `readFrame throws on empty stream`() {
        FrameCodec.readFrame(ByteArrayInputStream(ByteArray(0)))
    }

    @Test(expected = EOFException::class)
    fun `readFrame throws on truncated header`() {
        // Only 2 of 3 header bytes
        FrameCodec.readFrame(ByteArrayInputStream(byteArrayOf(0x00, 0x00)))
    }

    @Test(expected = EOFException::class)
    fun `readFrame throws on truncated payload`() {
        // Header says 4 bytes of payload, but only 2 provided
        val wire = byteArrayOf(0x00, 0x00, 0x04, 0xAA.toByte(), 0xBB.toByte())
        FrameCodec.readFrame(ByteArrayInputStream(wire))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `readFrame throws on unknown frame type`() {
        val wire = byteArrayOf(0x03, 0x00, 0x00) // type 0x03 is invalid
        FrameCodec.readFrame(ByteArrayInputStream(wire))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `readFrame throws on 0xFF frame type`() {
        val wire = byteArrayOf(0xFF.toByte(), 0x00, 0x00)
        FrameCodec.readFrame(ByteArrayInputStream(wire))
    }

    // ── FrameType.fromWire ──────────────────────────────────────────────────

    @Test
    fun `fromWire returns correct types`() {
        assertEquals(FrameType.DATA, FrameType.fromWire(0x00))
        assertEquals(FrameType.KEEPALIVE, FrameType.fromWire(0x01))
        assertEquals(FrameType.SHUTDOWN, FrameType.fromWire(0x02))
    }

    @Test
    fun `fromWire returns null for unknown`() {
        assertNull(FrameType.fromWire(0x03))
        assertNull(FrameType.fromWire(0xFF.toByte()))
    }

    // ── Frame equality ──────────────────────────────────────────────────────

    @Test
    fun `Frame equals compares payload content`() {
        val a = Frame.data(byteArrayOf(1, 2, 3))
        val b = Frame.data(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun encode(frame: Frame): ByteArray {
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, frame)
        return out.toByteArray()
    }

    private fun decode(wire: ByteArray): Frame =
        FrameCodec.readFrame(ByteArrayInputStream(wire))

    /**
     * InputStream that returns exactly one byte per read() call,
     * simulating worst-case partial reads from a slow transport.
     */
    private class SingleByteInputStream(private val data: ByteArray) : java.io.InputStream() {
        private var pos = 0
        override fun read(): Int =
            if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            b[off] = data[pos++]
            return 1
        }
    }
}
