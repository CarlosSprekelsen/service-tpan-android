package com.katim.dts.tpan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * TPAN foreground service.
 *
 * Establishes a Bluetooth SPP (RFCOMM) connection to the DTS Hub running
 * [tpan-bt-manager] and provides EUD-to-Hub IP connectivity via a SOCKS5H
 * proxy exposed on loopback port 1080.
 *
 * Lifecycle:
 *  - Started on boot by [TpanBootReceiver]
 *  - Runs as a persistent foreground service
 *  - Holds RFCOMM connection; idles relay when USB gadget interface is active
 *  - Reconnects automatically on BT link loss
 *
 * Native layer: [TpanManager] (JNI bridge to C++ tpan_client / socks5h_server)
 */
class TpanService : Service() {

    companion object {
        private const val TAG = "TpanService"
        private const val CHANNEL_ID = "tpan_service_channel"
        private const val NOTIFICATION_ID = 1001

        // TODO: read from kit config (persistent storage)
        private const val HUB_BT_ADDRESS = ""   // e.g. "AA:BB:CC:DD:EE:FF"
        private const val SOCKS5H_PORT   = 1080
    }

    private lateinit var tpanManager: TpanManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        tpanManager = TpanManager()
        tpanManager.setStatusCallback { status -> updateNotification(status) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand — starting TPAN client")

        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter

        if (btAdapter == null || !btAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available; will retry when BT is enabled")
            // TODO: register BtStateReceiver and retry
            return START_STICKY
        }

        if (HUB_BT_ADDRESS.isEmpty()) {
            Log.e(TAG, "Hub BT address not configured — check kit config")
            return START_STICKY
        }

        tpanManager.start(
            hubBtAddress = HUB_BT_ADDRESS,
            socks5hPort  = SOCKS5H_PORT,
        )

        return START_STICKY
    }

    override fun onDestroy() {
        tpanManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "TPAN Service", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "DTS TPAN BT connectivity" }
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
