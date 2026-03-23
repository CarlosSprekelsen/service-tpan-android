package com.katim.dts.tpan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts [TpanService] automatically on device boot.
 *
 * Requires RECEIVE_BOOT_COMPLETED permission (declared in AndroidManifest.xml).
 */
class TpanBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("TpanBootReceiver", "Boot completed — starting TpanService")
            context.startForegroundService(Intent(context, TpanService::class.java))
        }
    }
}
