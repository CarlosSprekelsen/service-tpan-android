package com.katim.dts.tpan

import android.util.Log

/**
 * JNI bridge to the C++ TPAN client core (tpan_client / socks5h_server).
 *
 * The native library is built from app/src/main/cpp via CMake + Android NDK.
 * Port the tpan-bt-client C++ code from the TNN tpan-over-bt prototype into
 * the native layer — see AGENT.md for porting guidance.
 */
class TpanManager {

    companion object {
        private const val TAG = "TpanManager"

        init {
            System.loadLibrary("tpan_client")
        }
    }

    private var statusCallback: ((String) -> Unit)? = null

    fun setStatusCallback(cb: (String) -> Unit) {
        statusCallback = cb
    }

    /**
     * Start the TPAN client: connect to Hub via RFCOMM and start the
     * SOCKS5H proxy on [socks5hPort].
     *
     * @param hubBtAddress  Hub Bluetooth MAC address (from kit config)
     * @param socks5hPort   Loopback port to expose SOCKS5H proxy (default 1080)
     */
    fun start(hubBtAddress: String, socks5hPort: Int = 1080) {
        Log.i(TAG, "start() hub=$hubBtAddress socks5h=:$socks5hPort")
        nativeStart(hubBtAddress, socks5hPort)
    }

    fun stop() {
        Log.i(TAG, "stop()")
        nativeStop()
    }

    // Called from C++ to update service notification
    @Suppress("unused")
    fun onStatusChanged(status: String) {
        Log.d(TAG, "status: $status")
        statusCallback?.invoke(status)
    }

    // ── JNI declarations ─────────────────────────────────────────────────────
    // Implemented in app/src/main/cpp/tpan_client.cpp

    private external fun nativeStart(hubBtAddress: String, socks5hPort: Int)
    private external fun nativeStop()
}
