package com.katim.dts.tpan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.katim.dts.tpan.bearer.BearerMonitor
import com.katim.dts.tpan.provision.BondManager
import com.katim.dts.tpan.provision.LocalBluetoothIdentityProvider
import com.katim.dts.tpan.provision.ProvisioningStore
import com.katim.dts.tpan.provision.RuntimeTpanConfig
import com.katim.dts.tpan.provision.UsbCommissionClient
import com.katim.dts.tpan.provision.UsbCommissionRecord
import com.katim.dts.tpan.provision.UsbNetworkMonitor
import com.katim.dts.tpan.tap.TapEngine
import com.katim.dts.tpan.transport.BtTransport
import com.katim.dts.tpan.transport.TpanTransport
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
 *
 * Runs as root on KATIM builds — creates TAP device directly via
 * TUNSETIFF ioctl (JNI). No VpnService, no consent dialog.
 */
class TpanService : Service() {

    companion object {
        private const val TAG = "TpanService"
        private const val CHANNEL_ID = "tpan_service_channel"
        private const val NOTIFICATION_ID = 1001
        private val COMMISSION_RETRY_DELAYS_MS = longArrayOf(1000, 2000, 4000, 8000, 16000, 30000)
    }

    private lateinit var tapEngine: TapEngine
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

        tapEngine = TapEngine()
        provisioningStore = ProvisioningStore(File(filesDir, ProvisioningStore.ROOT_DIR))
        usbNetworkMonitor = UsbNetworkMonitor(this)
        usbCommissionClient = UsbCommissionClient()
        localBluetoothIdentityProvider = LocalBluetoothIdentityProvider(
            File("/sdcard/DTS/tpan-dev/local-bt-mac.txt")
        )
        bondManager = BondManager(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Awaiting USB commission"))
        checkBatteryOptimization()
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

        val connection = ConnectionManager(tapEngine)
        val monitor = BearerMonitor(this, tapEngine, runtimeConfig)
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

        tapEngine.onShutdownReceived = {
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
        tapEngine.onShutdownReceived = null
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

    /**
     * Logs a warning if battery optimizations are active for this package.
     *
     * On a correctly configured KATIM build the package is in the platform power
     * whitelist (power_whitelist_platform.xml) and this warning never appears.
     * On bench or CI builds without that whitelist entry, Doze mode may suspend
     * KEEPALIVE delivery, causing the Hub to declare the link dead during idle
     * periods. See svc-tpan-architecture.md §Battery Optimization Exemption.
     */
    private fun checkBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.w(TAG, "Battery optimizations are active for $packageName. " +
                "Doze mode may suspend KEEPALIVE frames and cause link interruptions. " +
                "Add the package to power_whitelist_platform.xml on KATIM builds, " +
                "or disable battery optimization manually for bench testing.")
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
