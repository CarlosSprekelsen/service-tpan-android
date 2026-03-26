package com.katim.dts.tpan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import com.katim.dts.tpan.provision.ProvisioningStore
import java.io.File

/**
 * Starts [TpanService] automatically on device boot if a valid
 * provisioning bundle exists and `autoStartTpan` is enabled.
 *
 * If no valid bundle exists or auto-start is disabled, the service
 * is not started and the device operates in USB-only mode until
 * manually provisioned.
 *
 * Requires RECEIVE_BOOT_COMPLETED permission (declared in AndroidManifest.xml).
 */
class TpanBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TpanBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed — checking provisioning bundle")

        val store = ProvisioningStore(
            File(Environment.getExternalStorageDirectory(), "DTS")
        )
        val bundle = store.getActiveBundle()

        if (bundle == null) {
            Log.w(TAG, "No active provisioning bundle — TPAN not started (USB-only mode)")
            return
        }

        if (!bundle.localEud.autoStartTpan) {
            Log.i(TAG, "autoStartTpan=false — TPAN not started")
            return
        }

        Log.i(TAG, "Starting TpanService — mission=${bundle.missionId}, callsign=${bundle.callsign}")
        context.startForegroundService(Intent(context, TpanService::class.java))
    }
}
