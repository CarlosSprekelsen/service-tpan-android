package com.katim.dts.tpan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import com.katim.dts.tpan.vpn.VpnEngine

/**
 * TPAN foreground service.
 *
 * Provides EUD-to-Hub IP connectivity via an Android VpnService TUN interface
 * and framed IP tunnel over the highest-priority enabled bearer (USB > UWB > WiFi > BT).
 *
 * Lifecycle:
 *  - Started on boot by [TpanBootReceiver] if a valid provisioning bundle exists
 *  - Runs as a persistent foreground service
 *  - Bearer Monitor controls VPN activation and transport selection
 *
 * Authoritative design: docs/swad-dts/designs/svc-tpan-architecture.md
 */
class TpanService : VpnService() {

    companion object {
        private const val TAG = "TpanService"
        private const val CHANNEL_ID = "tpan_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    /** VPN Engine — manages TUN interface and data-path relay. */
    lateinit var vpnEngine: VpnEngine
        private set

    override fun onCreate() {
        super.onCreate()
        vpnEngine = VpnEngine(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Idle"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")

        // TODO (Epic 5): load active provisioning bundle from ProvisioningStore
        // TODO (Epic 7): start Bearer Monitor → VPN Engine → Transport data path

        updateNotification("Awaiting provisioning")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        vpnEngine.deactivateVpn()
        // TODO (Epic 7): ordered shutdown — transport → Bearer Monitor
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ─────────────────────────────────────────────────────────

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

    internal fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
