package com.katim.dts.service.tpan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.katim.dts.service.tpan.provision.ProvisioningStore
import java.io.File

/**
 * Starts [TpanService] automatically on device boot when a valid USB
 * commission record exists.
 *
 * Requires RECEIVE_BOOT_COMPLETED permission (declared in AndroidManifest.xml).
 */
class TpanBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TpanBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed - checking TPAN commission state")

        val store = ProvisioningStore(
            File(context.filesDir, ProvisioningStore.ROOT_DIR)
        )
        val record = store.validateOnBoot()

        if (record == null) {
            Log.w(TAG, "No valid commission record - TPAN not started")
            return
        }

        Log.i(TAG, "Starting TpanService for role=${record.localEud.role}")
        context.startForegroundService(Intent(context, TpanService::class.java))
    }
}

