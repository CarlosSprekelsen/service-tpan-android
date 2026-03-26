package com.katim.dts.tpan.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * BT SPP (RFCOMM) transport to Hub's `tpan-bt-manager` SPP profile.
 *
 * Uses standard Android [BluetoothSocket] API — no PANU/BNEP dependency.
 *
 * RFCOMM traffic goes over the Bluetooth radio (L2CAP), not through the
 * IP routing table, so it is inherently not captured by the VPN TUN.
 * `VpnService.protect()` is therefore not required for BT, but IS required
 * for future WiFi (TCP) transports — see [TpanTransport] contract.
 *
 * Error reporting: [IOException] on connect or during read/write is NOT
 * retried internally. The Bearer Monitor controls reconnection timing.
 * Callers should register [onDisconnect] to receive disconnect events.
 *
 * See svc-tpan-architecture.md §External Interfaces (line 179),
 * §Level 2 (lines 410–414).
 */
class BtTransport(
    private val hubMac: String,
    private val profileUuid: UUID
) : TpanTransport {

    companion object {
        private const val TAG = "BtTransport"
    }

    private var socket: BluetoothSocket? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    override val input: InputStream
        get() = socket?.inputStream
            ?: throw IOException("BT transport not connected")

    override val output: OutputStream
        get() = socket?.outputStream
            ?: throw IOException("BT transport not connected")

    /** Called when the transport disconnects unexpectedly. */
    var onDisconnect: ((cause: IOException) -> Unit)? = null

    /**
     * Connect to the Hub's RFCOMM SPP profile.
     *
     * @throws IOException if BT adapter is unavailable, device not bonded,
     *         or connection fails
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT granted at manifest level
    override fun connect() {
        if (isConnected) {
            Log.w(TAG, "Already connected")
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IOException("Bluetooth adapter not available")

        if (!adapter.isEnabled) {
            throw IOException("Bluetooth adapter is disabled")
        }

        val device = adapter.getRemoteDevice(hubMac)
            ?: throw IOException("Cannot resolve Hub MAC: $hubMac")

        val btSocket = device.createInsecureRfcommSocketToServiceRecord(profileUuid)

        try {
            btSocket.connect()
        } catch (e: IOException) {
            Log.e(TAG, "RFCOMM connect failed to $hubMac", e)
            closeQuietly(btSocket)
            throw e
        }

        // Note: RFCOMM bypasses IP routing (L2CAP over BT radio), so
        // VpnService.protect() is not needed. Future WiFi/TCP transports
        // MUST call vpnEngine.protectSocket() before connecting.

        socket = btSocket
        Log.i(TAG, "Connected to Hub $hubMac via RFCOMM")
    }

    /**
     * Disconnect from the Hub. Safe to call multiple times.
     */
    override fun disconnect() {
        socket?.let { s ->
            closeQuietly(s)
            socket = null
            Log.i(TAG, "Disconnected from Hub $hubMac")
        }
    }

    /**
     * Report an IO error from the data-path loops.
     *
     * Called by the VPN Engine read/write threads when an IOException
     * occurs on this transport's streams. Triggers [onDisconnect] so
     * the Bearer Monitor can initiate failover or reconnection.
     */
    fun reportError(cause: IOException) {
        Log.e(TAG, "Transport error", cause)
        disconnect()
        onDisconnect?.invoke(cause)
    }

    private fun closeQuietly(s: BluetoothSocket) {
        try {
            s.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing BluetoothSocket", e)
        }
    }
}
