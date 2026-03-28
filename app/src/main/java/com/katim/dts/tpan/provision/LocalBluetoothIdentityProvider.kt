package com.katim.dts.tpan.provision

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.util.Log
import java.io.File

/**
 * Resolves the local EUD Bluetooth MAC for USB commissioning.
 *
 * Privileged builds use the platform adapter address. Non-privileged dev
 * builds can supply an override file.
 */
class LocalBluetoothIdentityProvider(
    private val devOverrideFile: File
) {
    companion object {
        private const val TAG = "LocalBtIdentity"
        private const val UNKNOWN_MAC = "02:00:00:00:00:00"
    }

    @SuppressLint("MissingPermission")
    fun getLocalBtMac(): String? {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val adapterMac = adapter?.address?.trim()?.uppercase()
        if (!adapterMac.isNullOrBlank() && adapterMac != UNKNOWN_MAC) {
            return adapterMac
        }

        if (devOverrideFile.exists()) {
            val overrideMac = devOverrideFile.readText().trim().uppercase()
            if (overrideMac.isNotBlank()) {
                Log.w(TAG, "Using developer BT MAC override from ${devOverrideFile.absolutePath}")
                return overrideMac
            }
        }

        Log.w(TAG, "No usable local Bluetooth MAC available")
        return null
    }
}
