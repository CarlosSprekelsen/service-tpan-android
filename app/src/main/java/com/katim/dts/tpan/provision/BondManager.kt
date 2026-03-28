package com.katim.dts.tpan.provision

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Owns Hub bond maintenance for TPAN.
 */
class BondManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "BondManager"
    }

    @Volatile
    private var activeRecord: UsbCommissionRecord? = null

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                ?: return
            val record = activeRecord ?: return
            if (!device.address.equals(record.hub.btMac, ignoreCase = true)) {
                return
            }

            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> handlePairingRequest(intent, device, record)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> handleBondStateChanged(intent, device)
            }
        }
    }

    fun start() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    fun stop() {
        if (!receiverRegistered) return
        context.unregisterReceiver(receiver)
        receiverRegistered = false
    }

    fun updateCommission(oldRecord: UsbCommissionRecord?, newRecord: UsbCommissionRecord) {
        if (oldRecord != null &&
            !oldRecord.hub.btMac.equals(newRecord.hub.btMac, ignoreCase = true)) {
            removeBond(oldRecord.hub.btMac)
        }
        activeRecord = newRecord
    }

    @SuppressLint("MissingPermission")
    fun isBonded(record: UsbCommissionRecord): Boolean {
        val device = BluetoothDevice::class.java
        return try {
            val adapterClass = Class.forName("android.bluetooth.BluetoothAdapter")
            val getDefaultAdapter = adapterClass.getMethod("getDefaultAdapter")
            val adapter = getDefaultAdapter.invoke(null)
            val getRemoteDevice = adapterClass.getMethod("getRemoteDevice", String::class.java)
            val remote = getRemoteDevice.invoke(adapter, record.hub.btMac) as BluetoothDevice
            remote.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query bond state", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun ensureBond(record: UsbCommissionRecord): Boolean {
        activeRecord = record
        val device = resolveDevice(record.hub.btMac) ?: return false
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return true
        }
        Log.i(TAG, "Creating bond to Hub ${record.hub.btMac}")
        return device.createBond()
    }

    @SuppressLint("MissingPermission")
    fun removeBond(mac: String): Boolean {
        val device = resolveDevice(mac) ?: return false
        return try {
            val method = device.javaClass.getMethod("removeBond")
            val result = method.invoke(device) as? Boolean ?: false
            Log.i(TAG, "Removed existing bond for $mac result=$result")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove bond for $mac", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handlePairingRequest(
        intent: Intent,
        device: BluetoothDevice,
        record: UsbCommissionRecord
    ) {
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        val passkey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
        if (passkey >= 0 && passkey != record.pairingPasskey) {
            Log.w(TAG, "Pairing key mismatch for ${device.address}: got=$passkey expected=${record.pairingPasskey}")
            return
        }

        when (variant) {
            BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> {
                device.setPairingConfirmation(true)
                Log.i(TAG, "Auto-confirmed pairing for ${device.address}")
            }
            BluetoothDevice.PAIRING_VARIANT_PIN -> {
                val pin = String.format("%06d", record.pairingPasskey)
                device.setPin(pin.toByteArray(Charsets.UTF_8))
                Log.i(TAG, "Set pairing PIN for ${device.address}")
            }
            else -> {
                device.setPairingConfirmation(true)
                Log.i(TAG, "Accepted pairing variant=$variant for ${device.address}")
            }
        }
    }

    private fun handleBondStateChanged(intent: Intent, device: BluetoothDevice) {
        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
        Log.i(TAG, "Bond state for ${device.address} -> $state")
    }

    @SuppressLint("MissingPermission")
    private fun resolveDevice(mac: String): BluetoothDevice? {
        return try {
            val adapterClass = Class.forName("android.bluetooth.BluetoothAdapter")
            val getDefaultAdapter = adapterClass.getMethod("getDefaultAdapter")
            val adapter = getDefaultAdapter.invoke(null) ?: return null
            val getRemoteDevice = adapterClass.getMethod("getRemoteDevice", String::class.java)
            getRemoteDevice.invoke(adapter, mac) as BluetoothDevice
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve BluetoothDevice for $mac", e)
            null
        }
    }
}
