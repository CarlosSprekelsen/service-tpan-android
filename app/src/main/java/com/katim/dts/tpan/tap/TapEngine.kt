package com.katim.dts.tpan.tap

import android.os.ParcelFileDescriptor
import android.util.Log
import com.katim.dts.tpan.codec.Frame
import com.katim.dts.tpan.codec.FrameCodec
import com.katim.dts.tpan.codec.FrameType
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * TAP Engine — manages a root-created TAP (Layer 2) network interface.
 *
 * Creates a TAP device via the TUNSETIFF ioctl using a JNI helper
 * (requires root — KATIM platform build runs this service as root).
 * Assigns the EUD's stable IP, adds a route for `192.168.101.0/24`
 * and `224.0.0.0/4` (multicast), and relays raw Ethernet frames
 * between the TAP fd and the Frame Codec.
 *
 * TAP operates at Layer 2 and is preferred over TUN: it supports native
 * ARP, L2 broadcast, and multicast without any route injection workarounds.
 * SITAWARE/STC COP distribution requires multicast.
 *
 * Bearer-independent: identical code regardless of which transport
 * carries the framed traffic.
 *
 * See svc-tpan-architecture.md §Level 2 service-tpan-android,
 * AD-TPAN-01, AD-TPAN-02.
 */
class TapEngine {

    companion object {
        private const val TAG = "TapEngine"
        private const val TPAN_SUBNET = "192.168.101.0"
        private const val TPAN_PREFIX = 24
        private const val MTU = 1400
        private const val TAP_DEVICE_NAME = "tpan0"
        private const val TAP_READ_BUF_SIZE = 1514 // max Ethernet frame (14 + 1500)

        init {
            System.loadLibrary("tpan_tap")
        }
    }

    /** Native JNI method: opens /dev/net/tun, ioctls IFF_TAP | IFF_NO_PI. */
    private external fun nativeCreateTap(deviceName: String): Int

    /** TAP file descriptor, non-null while TAP is active. */
    @Volatile
    private var tapFd: ParcelFileDescriptor? = null

    /** TAP → transport reader thread. */
    private var tapReadThread: Thread? = null

    /** Transport → TAP writer thread. */
    private var transportReadThread: Thread? = null

    /** True while data-path loops should run. */
    @Volatile
    private var running = false

    val isActive: Boolean get() = tapFd != null

    // ── TAP lifecycle ───────────────────────────────────────────────────────

    /**
     * Activate the TAP interface with the given EUD IP.
     *
     * Creates a TAP device via JNI (TUNSETIFF ioctl, requires root),
     * assigns the IP address and subnet route, and brings the interface up.
     *
     * @param eudIp stable EUD address — `192.168.101.10` (TT) or `.20` (TWT)
     * @return true if TAP was activated successfully
     */
    fun activateTap(eudIp: String): Boolean {
        if (tapFd != null) {
            Log.w(TAG, "TAP already active")
            return true
        }

        val rawFd = nativeCreateTap(TAP_DEVICE_NAME)
        if (rawFd < 0) {
            Log.e(TAG, "Failed to create TAP device (root required)")
            return false
        }

        val fd = ParcelFileDescriptor.adoptFd(rawFd)
        tapFd = fd

        // Configure the TAP interface via shell commands (running as root)
        if (!exec("ip addr add $eudIp/24 dev $TAP_DEVICE_NAME")) {
            Log.e(TAG, "Failed to assign IP $eudIp to $TAP_DEVICE_NAME")
            deactivateTap()
            return false
        }
        if (!exec("ip link set $TAP_DEVICE_NAME up mtu $MTU")) {
            Log.e(TAG, "Failed to bring up $TAP_DEVICE_NAME")
            deactivateTap()
            return false
        }
        // Route for Hub Internal Zone
        exec("ip route add $TPAN_SUBNET/$TPAN_PREFIX dev $TAP_DEVICE_NAME")
        // Route for multicast — required for SITAWARE/STC COP distribution
        exec("ip route add 224.0.0.0/4 dev $TAP_DEVICE_NAME")

        Log.i(TAG, "TAP activated — $TAP_DEVICE_NAME EUD IP $eudIp, MTU $MTU")
        return true
    }

    /**
     * Deactivate the TAP. Closes fd and removes the interface.
     *
     * Apps revert to USB routing when TAP is deactivated.
     */
    fun deactivateTap() {
        stopDataPath()
        tapFd?.let { fd ->
            try {
                fd.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error closing TAP fd", e)
            }
        }
        tapFd = null
        // Kernel removes the TAP device automatically when fd is closed
        Log.i(TAG, "TAP deactivated")
    }

    // ── Data path ───────────────────────────────────────────────────────────

    /**
     * Start the bidirectional data path: TAP ↔ Frame Codec ↔ transport.
     *
     * @param transportInput  input stream from active transport (e.g. BluetoothSocket)
     * @param transportOutput output stream to active transport
     */
    fun startDataPath(transportInput: InputStream, transportOutput: OutputStream) {
        val fd = tapFd ?: run {
            Log.e(TAG, "Cannot start data path — TAP not active")
            return
        }
        if (running) {
            Log.w(TAG, "Data path already running")
            return
        }

        running = true

        // TAP → transport: read Ethernet frames from TAP, frame and send
        tapReadThread = Thread({
            val tapIn = FileInputStream(fd.fileDescriptor)
            val buf = ByteBuffer.allocate(TAP_READ_BUF_SIZE)
            try {
                while (running) {
                    buf.clear()
                    val len = tapIn.read(buf.array())
                    if (len <= 0) continue
                    val frame = ByteArray(len)
                    System.arraycopy(buf.array(), 0, frame, 0, len)
                    FrameCodec.writeFrame(transportOutput, Frame.data(frame))
                }
            } catch (e: IOException) {
                if (running) Log.e(TAG, "TAP read loop error", e)
            }
            Log.d(TAG, "TAP read loop exited")
        }, "tpan-tap-read").also { it.start() }

        // Transport → TAP: read frames from transport, write Ethernet frames to TAP
        transportReadThread = Thread({
            val tapOut = FileOutputStream(fd.fileDescriptor)
            try {
                while (running) {
                    val frame = FrameCodec.readFrame(transportInput)
                    when (frame.type) {
                        FrameType.DATA -> {
                            tapOut.write(frame.payload)
                            tapOut.flush()
                        }
                        FrameType.KEEPALIVE -> {
                            onKeepaliveReceived?.invoke()
                        }
                        FrameType.SHUTDOWN -> {
                            Log.i(TAG, "Received SHUTDOWN from peer")
                            onShutdownReceived?.invoke()
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Transport read loop error", e)
            }
            Log.d(TAG, "Transport read loop exited")
        }, "tpan-transport-read").also { it.start() }

        Log.i(TAG, "Data path started")
    }

    /**
     * Stop the data-path loops. TAP fd remains open for reconnection.
     */
    fun stopDataPath() {
        if (!running) return
        running = false

        tapReadThread?.interrupt()
        transportReadThread?.interrupt()

        tapReadThread?.join(1000)
        transportReadThread?.join(1000)

        tapReadThread = null
        transportReadThread = null
        Log.i(TAG, "Data path stopped")
    }

    // ── Callbacks ───────────────────────────────────────────────────────────

    /** Called when a KEEPALIVE frame is received from the peer. */
    var onKeepaliveReceived: (() -> Unit)? = null

    /** Called when a SHUTDOWN frame is received from the peer. */
    var onShutdownReceived: (() -> Unit)? = null

    // ── Shell helper ────────────────────────────────────────────────────────

    private fun exec(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.w(TAG, "Command failed (rc=$exitCode): $command")
            }
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Command exception: $command", e)
            false
        }
    }
}
