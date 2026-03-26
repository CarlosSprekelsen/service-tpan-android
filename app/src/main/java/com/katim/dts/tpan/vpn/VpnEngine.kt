package com.katim.dts.tpan.vpn

import android.net.VpnService
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
import java.net.DatagramSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * VPN Engine — manages the Android VpnService TUN interface.
 *
 * Creates a TUN device with the EUD's stable IP, adds a route for
 * `192.168.101.0/24`, and relays raw IP packets between the TUN fd
 * and the Frame Codec.
 *
 * Bearer-independent: identical code regardless of which transport
 * carries the framed traffic.
 *
 * See svc-tpan-architecture.md §Level 2 service-tpan-android (lines 382–443),
 * AD-TPAN-01 (lines 1089–1095).
 */
class VpnEngine(private val service: VpnService) {

    companion object {
        private const val TAG = "VpnEngine"
        private const val TPAN_SUBNET = "192.168.101.0"
        private const val TPAN_PREFIX = 24
        private const val MTU = 1400
        private const val TUN_READ_BUF_SIZE = 1500 // max IP packet we'll read from TUN
    }

    /** TUN file descriptor, non-null while VPN is active. */
    @Volatile
    private var tunFd: ParcelFileDescriptor? = null

    /** TUN → transport reader thread. */
    private var vpnReadThread: Thread? = null

    /** Transport → TUN writer thread. */
    private var transportReadThread: Thread? = null

    /** True while data-path loops should run. */
    @Volatile
    private var running = false

    val isActive: Boolean get() = tunFd != null

    // ── VPN lifecycle ───────────────────────────────────────────────────────

    /**
     * Activate the VPN TUN interface with the given EUD IP.
     *
     * @param eudIp stable EUD address — `192.168.101.10` (TT) or `.20` (TWT)
     * @return true if VPN was activated successfully
     */
    fun activateVpn(eudIp: String): Boolean {
        if (tunFd != null) {
            Log.w(TAG, "VPN already active")
            return true
        }

        val fd = try {
            service.Builder()
                .addAddress(eudIp, 32)
                .addRoute(TPAN_SUBNET, TPAN_PREFIX)
                .setMtu(MTU)
                .setBlocking(true)
                .setSession("TPAN")
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN", e)
            return false
        }

        if (fd == null) {
            Log.e(TAG, "VPN consent not granted")
            return false
        }

        tunFd = fd
        Log.i(TAG, "VPN activated — EUD IP $eudIp, MTU $MTU")
        return true
    }

    /**
     * Deactivate the VPN. Closes TUN fd and stops data-path loops.
     *
     * Apps revert to USB routing when VPN is deactivated.
     */
    fun deactivateVpn() {
        stopDataPath()
        tunFd?.let { fd ->
            try {
                fd.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error closing TUN fd", e)
            }
        }
        tunFd = null
        Log.i(TAG, "VPN deactivated")
    }

    // ── Data path ───────────────────────────────────────────────────────────

    /**
     * Start the bidirectional data path: TUN ↔ Frame Codec ↔ transport.
     *
     * @param transportInput  input stream from active transport (e.g. BluetoothSocket)
     * @param transportOutput output stream to active transport
     */
    fun startDataPath(transportInput: InputStream, transportOutput: OutputStream) {
        val fd = tunFd ?: run {
            Log.e(TAG, "Cannot start data path — VPN not active")
            return
        }
        if (running) {
            Log.w(TAG, "Data path already running")
            return
        }

        running = true

        // TUN → transport: read IP packets from TUN, frame and send
        vpnReadThread = Thread({
            val tunIn = FileInputStream(fd.fileDescriptor)
            val buf = ByteBuffer.allocate(TUN_READ_BUF_SIZE)
            try {
                while (running) {
                    buf.clear()
                    val len = tunIn.read(buf.array())
                    if (len <= 0) continue
                    val packet = ByteArray(len)
                    System.arraycopy(buf.array(), 0, packet, 0, len)
                    FrameCodec.writeFrame(transportOutput, Frame.data(packet))
                }
            } catch (e: IOException) {
                if (running) Log.e(TAG, "VPN read loop error", e)
            }
            Log.d(TAG, "VPN read loop exited")
        }, "tpan-vpn-read").also { it.start() }

        // Transport → TUN: read frames from transport, write IP packets to TUN
        transportReadThread = Thread({
            val tunOut = FileOutputStream(fd.fileDescriptor)
            try {
                while (running) {
                    val frame = FrameCodec.readFrame(transportInput)
                    when (frame.type) {
                        FrameType.DATA -> {
                            tunOut.write(frame.payload)
                            tunOut.flush()
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
     * Stop the data-path loops. TUN fd remains open for reconnection.
     */
    fun stopDataPath() {
        if (!running) return
        running = false

        vpnReadThread?.interrupt()
        transportReadThread?.interrupt()

        vpnReadThread?.join(1000)
        transportReadThread?.join(1000)

        vpnReadThread = null
        transportReadThread = null
        Log.i(TAG, "Data path stopped")
    }

    // ── Socket protection ───────────────────────────────────────────────────

    /**
     * Protect a transport socket from VPN capture.
     *
     * Must be called before connecting the socket. Without this,
     * the transport's own packets would be captured by the VPN TUN
     * and create a routing loop.
     *
     * @return true if protection succeeded
     */
    fun protectSocket(socket: Socket): Boolean = service.protect(socket)

    /**
     * Protect a datagram socket from VPN capture.
     */
    fun protectSocket(socket: DatagramSocket): Boolean = service.protect(socket)

    /**
     * Protect a raw file descriptor from VPN capture.
     */
    fun protectFd(fd: Int): Boolean = service.protect(fd)

    // ── Callbacks ───────────────────────────────────────────────────────────

    /** Called when a KEEPALIVE frame is received from the peer. */
    var onKeepaliveReceived: (() -> Unit)? = null

    /** Called when a SHUTDOWN frame is received from the peer. */
    var onShutdownReceived: (() -> Unit)? = null
}
