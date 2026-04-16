package com.katim.dts.service.tpan.provision

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches for a stable USB network and notifies TPAN commissioning logic.
 */
class UsbNetworkMonitor(
    private val context: Context
) {
    companion object {
        private const val TAG = "UsbNetworkMonitor"
        private const val USB_STABILITY_HOLD_MS = 2000L
    }

    var onUsbStableUp: ((Network) -> Unit)? = null
    var onUsbDown: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingStableNetwork: Network? = null
    private var stabilityRunnable: Runnable? = null

    fun start() {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_USB)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "USB network available")
                scheduleStableUp(network)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "USB network lost")
                cancelStableUp()
                onUsbDown?.invoke()
            }
        }

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        evaluateInitialState()
    }

    fun stop() {
        cancelStableUp()
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
            }
        }
        networkCallback = null
    }

    private fun evaluateInitialState() {
        val cm = connectivityManager ?: return
        val activeNetwork = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) {
            scheduleStableUp(activeNetwork)
        }
    }

    private fun scheduleStableUp(network: Network) {
        cancelStableUp()
        pendingStableNetwork = network
        stabilityRunnable = Runnable {
            val stableNetwork = pendingStableNetwork
            if (stableNetwork != null) {
                onUsbStableUp?.invoke(stableNetwork)
            }
        }
        handler.postDelayed(stabilityRunnable!!, USB_STABILITY_HOLD_MS)
    }

    private fun cancelStableUp() {
        stabilityRunnable?.let { handler.removeCallbacks(it) }
        stabilityRunnable = null
        pendingStableNetwork = null
    }
}

