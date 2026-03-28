package com.katim.dts.tpan.bearer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.katim.dts.tpan.provision.RuntimeTpanConfig
import com.katim.dts.tpan.transport.TpanTransport
import com.katim.dts.tpan.vpn.VpnEngine
import java.io.IOException

/**
 * Bearer Monitor — selects the best available bearer and controls VPN activation.
 *
 * Monitors USB interface state via [ConnectivityManager.NetworkCallback].
 * When USB is active and stable (2 s hold), VPN is deactivated and apps
 * route directly over USB. When USB goes down, VPN is activated immediately
 * and the best wireless transport is selected by fixed TPAN policy.
 *
 * Current implementation is mission-agnostic and fixed to **USB > BT**.
 *
 * See svc-tpan-architecture.md §Bearer State Machine (lines 276–351),
 * §Bearer Switching (lines 678–717).
 */
class BearerMonitor(
    private val context: Context,
    private val vpnEngine: VpnEngine,
    private val runtimeConfig: RuntimeTpanConfig
) {
    companion object {
        private const val TAG = "BearerMonitor"
        private const val USB_STABILITY_HOLD_MS = 2000L
    }

    /** Current bearer state. */
    enum class State {
        /** USB cable connected, VPN off, apps route via USB. */
        USB_ACTIVE,
        /** Wireless transport active, VPN on. */
        WIRELESS_ACTIVE,
        /** All wireless transports lost, VPN TUN stays up, reconnecting. */
        RECONNECTING,
        /** Not started. */
        IDLE
    }

    @Volatile
    var state: State = State.IDLE
        private set

    /** Currently active wireless transport, null when USB active or idle. */
    @Volatile
    var activeTransport: TpanTransport? = null
        private set

    /** Callback when bearer state changes (for notification updates). */
    var onStateChanged: ((State, String) -> Unit)? = null

    /** Callback to request transport connection for a bearer type. */
    var onConnectTransport: ((bearerType: String) -> TpanTransport?)? = null

    /** Callback when VPN + transport are ready for data path. */
    var onDataPathReady: ((transport: TpanTransport) -> Unit)? = null

    /** Callback when data path should be torn down. */
    var onDataPathDown: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var usbStabilityRunnable: Runnable? = null

    @Volatile
    private var usbUp = false

    @Volatile
    private var running = false

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Start monitoring bearers.
     *
     * Registers USB network callback and evaluates initial state.
     */
    fun start() {
        if (running) return
        running = true

        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        registerUsbCallback()
        evaluateInitialState()
        Log.i(TAG, "Bearer monitor started")
    }

    /**
     * Stop monitoring. Deactivates VPN and disconnects transport.
     */
    fun stop() {
        if (!running) return
        running = false

        cancelStabilityHold()
        unregisterUsbCallback()
        disconnectTransport()
        vpnEngine.deactivateVpn()
        state = State.IDLE
        Log.i(TAG, "Bearer monitor stopped")
    }

    // ── USB detection (A601) ────────────────────────────────────────────────

    private fun registerUsbCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_USB)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "USB network available")
                usbUp = true
                onUsbUp()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "USB network lost")
                usbUp = false
                onUsbDown()
            }
        }

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterUsbCallback() {
        networkCallback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (e: IllegalArgumentException) {
                // Already unregistered
            }
        }
        networkCallback = null
    }

    // ── Stability hold (A603) ───────────────────────────────────────────────

    /**
     * USB UP: wait 2 s stability hold before transitioning.
     * If USB goes DOWN within the hold window, cancel (transient plug).
     */
    private fun onUsbUp() {
        if (!running) return

        cancelStabilityHold()
        usbStabilityRunnable = Runnable {
            if (usbUp && running) {
                transitionToUsb()
            }
        }
        handler.postDelayed(usbStabilityRunnable!!, USB_STABILITY_HOLD_MS)
        Log.d(TAG, "USB stability hold started (${USB_STABILITY_HOLD_MS}ms)")
    }

    /**
     * USB DOWN: immediately activate VPN (no hold on fail-down — speed matters).
     */
    private fun onUsbDown() {
        if (!running) return

        cancelStabilityHold()
        transitionToWireless()
    }

    private fun cancelStabilityHold() {
        usbStabilityRunnable?.let { handler.removeCallbacks(it) }
        usbStabilityRunnable = null
    }

    // ── VPN activation/deactivation (A604) ──────────────────────────────────

    private fun transitionToUsb() {
        Log.i(TAG, "Transitioning to USB — deactivating VPN")
        onDataPathDown?.invoke()
        disconnectTransport()
        vpnEngine.deactivateVpn()
        state = State.USB_ACTIVE
        onStateChanged?.invoke(state, "USB Active")
    }

    private fun transitionToWireless() {
        Log.i(TAG, "Transitioning to wireless — activating VPN")

        val eudIp = runtimeConfig.tunAddress
        if (!vpnEngine.isActive) {
            if (!vpnEngine.activateVpn(eudIp)) {
                Log.e(TAG, "Failed to activate VPN")
                state = State.RECONNECTING
                onStateChanged?.invoke(state, "VPN activation failed")
                return
            }
        }

        connectBestTransport()
    }

    // ── Bearer priority selector (A602) ─────────────────────────────────────

    /**
     * Current mission-agnostic implementation always prefers BT when USB
     * is not active.
     */
    fun selectBearers(): List<String> {
        return listOf("bt")
    }

    // ── Transport lifecycle (A605) ──────────────────────────────────────────

    private fun connectBestTransport() {
        val bearers = selectBearers()
        Log.d(TAG, "Available wireless bearers: $bearers")

        for (bearerType in bearers) {
            val transport = onConnectTransport?.invoke(bearerType)
            if (transport != null) {
                activeTransport = transport
                state = State.WIRELESS_ACTIVE
                onStateChanged?.invoke(state, "Connected via ${bearerType.uppercase()}")
                onDataPathReady?.invoke(transport)
                return
            }
        }

        // No transport connected
        Log.w(TAG, "No wireless transport available")
        state = State.RECONNECTING
        onStateChanged?.invoke(state, "Reconnecting...")
    }

    /**
     * Disconnect the active wireless transport.
     */
    fun disconnectTransport() {
        activeTransport?.let { t ->
            try {
                t.disconnect()
            } catch (e: IOException) {
                Log.w(TAG, "Error disconnecting transport", e)
            }
            activeTransport = null
        }
    }

    /**
     * Called when the active transport reports a failure.
     *
     * If an alternative transport is available, switch immediately
     * (VPN TUN stays up). Otherwise, signal reconnect state.
     */
    fun onTransportFailed() {
        if (!running) return

        Log.w(TAG, "Active transport failed")
        activeTransport = null
        onDataPathDown?.invoke()

        if (usbUp) {
            transitionToUsb()
            return
        }

        // Try next available bearer
        connectBestTransport()
    }

    // ── Initial state evaluation ────────────────────────────────────────────

    private fun evaluateInitialState() {
        val cm = connectivityManager ?: return

        // Check if USB is currently active
        val activeNetwork = cm.activeNetwork
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
        usbUp = caps?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true

        if (usbUp) {
            state = State.USB_ACTIVE
            onStateChanged?.invoke(state, "USB Active")
            Log.i(TAG, "Initial state: USB active")
        } else {
            transitionToWireless()
        }
    }
}
