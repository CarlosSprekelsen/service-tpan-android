package com.katim.dts.tpan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Environment
import android.os.IBinder
import android.util.Log
import com.katim.dts.tpan.bearer.BearerMonitor
import com.katim.dts.tpan.provision.BundleImporter
import com.katim.dts.tpan.provision.ProvisioningStore
import com.katim.dts.tpan.provision.TpanBundle
import com.katim.dts.tpan.transport.BtTransport
import com.katim.dts.tpan.transport.TpanTransport
import com.katim.dts.tpan.vpn.VpnEngine
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * TPAN foreground service — orchestrates all components.
 *
 * Provides EUD-to-Hub IP connectivity via an Android VpnService TUN interface
 * and framed IP tunnel over the highest-priority enabled bearer (USB > UWB > WiFi > BT).
 *
 * Component wiring (Epic 7):
 * ```
 * ProvisioningStore → TpanBundle
 *     ↓
 * BearerMonitor → selects bearer, controls VPN
 *     ↓
 * VpnEngine ↔ FrameCodec ↔ TpanTransport (BtTransport)
 *     ↓
 * ConnectionManager → KEEPALIVE, reconnect, shutdown
 * ```
 *
 * Authoritative design: docs/swad-dts/designs/svc-tpan-architecture.md
 */
class TpanService : VpnService() {

    companion object {
        private const val TAG = "TpanService"
        private const val CHANNEL_ID = "tpan_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var vpnEngine: VpnEngine
    private lateinit var provisioningStore: ProvisioningStore
    private lateinit var bundleImporter: BundleImporter
    private var connectionManager: ConnectionManager? = null
    private var bearerMonitor: BearerMonitor? = null
    private var activeBundle: TpanBundle? = null

    // ── Service lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        vpnEngine = VpnEngine(this)
        provisioningStore = ProvisioningStore(
            File(Environment.getExternalStorageDirectory(), "DTS")
        )
        bundleImporter = BundleImporter(provisioningStore)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Idle"))
    }

    /**
     * Entry point (A707): load bundle → start Bearer Monitor → data path.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")

        // Load active provisioning bundle (boot-time validation)
        val bundle = provisioningStore.validateOnBoot()
        if (bundle == null) {
            Log.w(TAG, "No valid provisioning bundle — awaiting USB import")
            updateNotification("Awaiting provisioning")
            startBundleImporter()
            return START_STICKY
        }

        startWithBundle(bundle)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — ordered shutdown")

        // Ordered shutdown: connection → transport → bearer → VPN → importer
        connectionManager?.gracefulShutdown()
        bearerMonitor?.stop()
        vpnEngine.deactivateVpn()
        bundleImporter.stop()
        connectionManager?.destroy()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Component wiring ────────────────────────────────────────────────────

    private fun startWithBundle(bundle: TpanBundle) {
        activeBundle = bundle
        Log.i(TAG, "Starting with bundle: mission=${bundle.missionId}, callsign=${bundle.callsign}")
        updateNotification("${bundle.callsign} — Connecting")

        val connMgr = ConnectionManager(vpnEngine)
        connectionManager = connMgr

        val monitor = BearerMonitor(this, vpnEngine, bundle)
        bearerMonitor = monitor

        // Wire Bearer Monitor callbacks
        monitor.onStateChanged = { state, label ->
            updateNotification("${bundle.callsign} — $label")
        }

        monitor.onConnectTransport = { bearerType ->
            connectTransportForBearer(bearerType, bundle)
        }

        monitor.onDataPathReady = { transport ->
            connMgr.startDataPath(transport)
        }

        monitor.onDataPathDown = {
            connMgr.stopDataPath()
        }

        // Wire ConnectionManager callbacks
        connMgr.onLinkDead = {
            Log.w(TAG, "Link dead — triggering bearer failover")
            connMgr.stopDataPath()
            monitor.onTransportFailed()
        }

        connMgr.onReconnect = {
            val transport = monitor.activeTransport
                ?: connectTransportForBearer("bt", bundle)
            if (transport != null) {
                connMgr.reconnectSucceeded()
                connMgr.startDataPath(transport)
            } else {
                connMgr.reconnectFailed()
            }
        }

        // Wire VPN Engine SHUTDOWN callback
        vpnEngine.onShutdownReceived = {
            Log.i(TAG, "Peer sent SHUTDOWN — disconnecting transport, keeping VPN up")
            connMgr.stopDataPath()
            monitor.disconnectTransport()
            connMgr.startReconnect()
            updateNotification("${bundle.callsign} — Reconnecting...")
        }

        // Start bundle importer for live re-provisioning
        startBundleImporter()

        // Start bearer monitor — evaluates USB state and begins connection
        monitor.start()
    }

    /**
     * Create a transport for the given bearer type.
     *
     * @return connected transport, or null on failure
     */
    private fun connectTransportForBearer(
        bearerType: String,
        bundle: TpanBundle
    ): TpanTransport? {
        return when (bearerType) {
            "bt" -> {
                val transport = BtTransport(
                    hubMac = bundle.hub.btMac,
                    profileUuid = UUID.fromString(bundle.hub.profileUuid)
                )
                try {
                    transport.connect()
                    transport.onDisconnect = { cause ->
                        Log.w(TAG, "BT transport disconnected: ${cause.message}")
                        bearerMonitor?.onTransportFailed()
                    }
                    transport
                } catch (e: IOException) {
                    Log.e(TAG, "BT connect failed: ${e.message}")
                    null
                }
            }
            // "uwb", "wifi" — future transport modules
            else -> {
                Log.w(TAG, "No transport implementation for bearer: $bearerType")
                null
            }
        }
    }

    // ── Bundle importer ─────────────────────────────────────────────────────

    private fun startBundleImporter() {
        bundleImporter.onBundleReady = { bundle ->
            Log.i(TAG, "New bundle imported — restarting with mission=${bundle.missionId}")

            // Tear down current connection if active
            connectionManager?.gracefulShutdown()
            bearerMonitor?.stop()
            connectionManager?.destroy()

            // Restart with new bundle
            startWithBundle(bundle)
        }
        bundleImporter.start()
    }

    // ── Notification (A705) ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "TPAN Service", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "DTS TPAN connectivity" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TPAN")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
