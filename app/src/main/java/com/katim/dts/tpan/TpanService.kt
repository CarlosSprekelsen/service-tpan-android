package com.katim.dts.tpan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import com.katim.dts.tpan.bearer.BearerMonitor
import com.katim.dts.tpan.provision.BondManager
import com.katim.dts.tpan.provision.LocalBluetoothIdentityProvider
import com.katim.dts.tpan.provision.ProvisioningStore
import com.katim.dts.tpan.provision.RuntimeTpanConfig
import com.katim.dts.tpan.provision.UsbCommissionClient
import com.katim.dts.tpan.provision.UsbCommissionRecord
import com.katim.dts.tpan.provision.UsbNetworkMonitor
import com.katim.dts.tpan.transport.BtTransport
import com.katim.dts.tpan.transport.TpanTransport
import com.katim.dts.tpan.vpn.VpnEngine
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Mission-agnostic TPAN foreground service.
 *
 * The service persists USB commissioning state, manages the Hub bond,
 * commissions over the stable USB link, and activates the BT data path
 * when USB is unavailable.
 */
class TpanService : VpnService() {

    companion object {
        private const val TAG = "TpanService"
        private const val CHANNEL_ID = "tpan_service_channel"
        private const val NOTIFICATION_ID = 1001
        private val COMMISSION_RETRY_DELAYS_MS = longArrayOf(1000, 2000, 4000, 8000, 16000, 30000)
    }

    private lateinit var vpnEngine: VpnEngine
    private lateinit var provisioningStore: ProvisioningStore
    private lateinit var usbNetworkMonitor: UsbNetworkMonitor
    private lateinit var usbCommissionClient: UsbCommissionClient
    private lateinit var localBluetoothIdentityProvider: LocalBluetoothIdentityProvider
    private lateinit var bondManager: BondManager

    private val commissionExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "tpan-commission").apply { isDaemon = true }
        }

    @Volatile
    private var currentCommissionRecord: UsbCommissionRecord? = null

    private var connectionManager: ConnectionManager? = null
    private var bearerMonitor: BearerMonitor? = null
    private var usbMonitorStarted = false
    private var commissionFuture: ScheduledFuture<*>? = null
    private var commissionGeneration = 0L
    private var commissionRetryIndex = 0
    private var activeUsbNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()

        vpnEngine = VpnEngine(this)
        provisioningStore = ProvisioningStore(File(filesDir, ProvisioningStore.ROOT_DIR))
        usbNetworkMonitor = UsbNetworkMonitor(this)
        usbCommissionClient = UsbCommissionClient()
        localBluetoothIdentityProvider = LocalBluetoothIdentityProvider(
            File("/sdcard/DTS/tpan-dev/local-bt-mac.txt")
        )
        bondManager = BondManager(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Awaiting USB commission"))
        bondManager.start()
        configureUsbCommissioning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!usbMonitorStarted) {
            usbNetworkMonitor.start()
            usbMonitorStarted = true
        }

        val record = provisioningStore.validateOnBoot()
        if (record == null) {
            if (currentCommissionRecord == null) {
                updateNotification("Awaiting USB commission")
            }
            return START_STICKY
        }

        startOrRestartRuntime(record)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy - ordered shutdown")
        commissionGeneration++
        cancelCommissionRetry()
        usbNetworkMonitor.stop()
        stopRuntime()
        bondManager.stop()
        commissionExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun configureUsbCommissioning() {
        usbNetworkMonitor.onUsbStableUp = { network ->
            Log.i(TAG, "Stable USB network detected - starting commission flow")
            activeUsbNetwork = network
            commissionGeneration += 1
            commissionRetryIndex = 0
            scheduleCommissionAttempt(commissionGeneration, 0)
        }

        usbNetworkMonitor.onUsbDown = {
            Log.i(TAG, "USB network down - stopping commission retries")
            activeUsbNetwork = null
            commissionGeneration += 1
            cancelCommissionRetry()
            if (currentCommissionRecord == null) {
                updateNotification("Awaiting USB commission")
            }
        }
    }

    private fun scheduleCommissionAttempt(generation: Long, delayMs: Long) {
        cancelCommissionRetry()
        commissionFuture = commissionExecutor.schedule(
            { attemptCommission(generation) },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun cancelCommissionRetry() {
        commissionFuture?.cancel(false)
        commissionFuture = null
    }

    private fun attemptCommission(generation: Long) {
        val network = activeUsbNetwork
        if (generation != commissionGeneration || network == null) {
            return
        }

        val localBtMac = localBluetoothIdentityProvider.getLocalBtMac()
        if (localBtMac == null) {
            Log.w(TAG, "No usable local BT MAC - commission deferred")
            updateNotification("USB Active - BT MAC unavailable")
            rescheduleCommission(generation)
            return
        }

        try {
            val record = usbCommissionClient.commission(network, localBtMac)
            val persisted = provisioningStore.persistCommissionRecord(record)
            commissionRetryIndex = 0
            startOrRestartRuntime(persisted)
        } catch (e: Exception) {
            Log.w(TAG, "USB commission attempt failed", e)
            rescheduleCommission(generation)
        }
    }

    private fun rescheduleCommission(generation: Long) {
        if (generation != commissionGeneration || activeUsbNetwork == null) {
            return
        }

        val delay = COMMISSION_RETRY_DELAYS_MS[commissionRetryIndex]
        if (commissionRetryIndex < COMMISSION_RETRY_DELAYS_MS.lastIndex) {
            commissionRetryIndex += 1
        }
        updateNotification("USB Active - commission retry in ${delay / 1000}s")
        scheduleCommissionAttempt(generation, delay)
    }

    private fun startOrRestartRuntime(record: UsbCommissionRecord) {
        val previous = currentCommissionRecord
        val runtimeConfig = RuntimeTpanConfig.fromCommission(record)

        if (previous == record && bearerMonitor != null && connectionManager != null) {
            ensureBond(record)
            updateNotification("${runtimeConfig.roleLabel} - ${stateLabelForCurrentMonitor()}")
            return
        }

        currentCommissionRecord = record
        bondManager.updateCommission(previous, record)
        ensureBond(record)

        stopRuntime()

        val connection = ConnectionManager(vpnEngine)
        val monitor = BearerMonitor(this, vpnEngine, runtimeConfig)
        connectionManager = connection
        bearerMonitor = monitor

        monitor.onStateChanged = { _, label ->
            updateNotification("${runtimeConfig.roleLabel} - $label")
        }

        monitor.onConnectTransport = { bearerType ->
            connectTransportForBearer(bearerType, runtimeConfig)
        }

        monitor.onDataPathReady = { transport ->
            connection.reconnectSucceeded()
            connection.startDataPath(transport)
        }

        monitor.onDataPathDown = {
            connection.stopDataPath()
        }

        connection.onLinkDead = {
            Log.w(TAG, "Link dead - triggering transport failover")
            connection.stopDataPath()
            monitor.onTransportFailed()
        }

        connection.onReconnect = {
            val transport = monitor.activeTransport ?: connectTransportForBearer("bt", runtimeConfig)
            if (transport != null) {
                connection.reconnectSucceeded()
                connection.startDataPath(transport)
            } else {
                connection.reconnectFailed()
            }
        }

        vpnEngine.onShutdownReceived = {
            Log.i(TAG, "Peer sent SHUTDOWN - reconnecting current runtime")
            connection.stopDataPath()
            monitor.disconnectTransport()
            connection.startReconnect()
            updateNotification("${runtimeConfig.roleLabel} - Reconnecting...")
        }

        updateNotification("${runtimeConfig.roleLabel} - Starting")
        monitor.start()
    }

    private fun stopRuntime() {
        connectionManager?.gracefulShutdown()
        bearerMonitor?.stop()
        connectionManager?.destroy()
        bearerMonitor = null
        connectionManager = null
        vpnEngine.onShutdownReceived = null
    }

    private fun ensureBond(record: UsbCommissionRecord) {
        if (bondManager.isBonded(record)) {
            Log.i(TAG, "Existing bond retained for Hub ${record.hub.btMac}")
            return
        }

        val requested = bondManager.ensureBond(record)
        Log.i(TAG, "Bond missing for Hub ${record.hub.btMac} - createBond requested=$requested")
    }

    private fun connectTransportForBearer(
        bearerType: String,
        runtimeConfig: RuntimeTpanConfig
    ): TpanTransport? {
        return when (bearerType) {
            "bt" -> connectBtTransport(runtimeConfig)
            else -> {
                Log.w(TAG, "No transport implementation for bearer=$bearerType")
                null
            }
        }
    }

    private fun connectBtTransport(runtimeConfig: RuntimeTpanConfig): TpanTransport? {
        val transport = try {
            BtTransport(
                hubMac = runtimeConfig.hubBtMac,
                profileUuid = UUID.fromString(runtimeConfig.hubProfileUuid)
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid Hub profile UUID ${runtimeConfig.hubProfileUuid}", e)
            return null
        }

        return try {
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

    private fun stateLabelForCurrentMonitor(): String {
        return when (bearerMonitor?.state) {
            BearerMonitor.State.USB_ACTIVE -> "USB Active"
            BearerMonitor.State.WIRELESS_ACTIVE -> "Connected via BT"
            BearerMonitor.State.RECONNECTING -> "Reconnecting..."
            else -> "Idle"
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TPAN Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mission-agnostic TPAN connectivity"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TPAN")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
