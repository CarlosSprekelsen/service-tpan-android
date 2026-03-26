package com.katim.dts.tpan.provision

import android.os.FileObserver
import android.util.Log
import java.io.File

/**
 * Watches `/sdcard/DTS/tpan-import/` for USB-delivered bundle files.
 *
 * On `CLOSE_WRITE` of `tpan-bundle.json`: delegates to [ProvisioningStore.importBundle]
 * for validation, staging, and dual-slot promotion.
 *
 * On detection of `.tpan-rollback` marker: delegates to [ProvisioningStore.checkRollbackTrigger].
 *
 * See svc-tpan-architecture.md §EUD-Side Bundle, §EUD Provisioning Safety.
 */
class BundleImporter(
    private val store: ProvisioningStore
) {
    companion object {
        private const val TAG = "BundleImporter"
    }

    /** Callback invoked when a new valid bundle is imported or restored via rollback. */
    var onBundleReady: ((TpanBundle) -> Unit)? = null

    private var observer: FileObserver? = null

    /**
     * Start watching the import directory.
     */
    fun start() {
        val importDir = store.importDir
        Log.i(TAG, "Watching ${importDir.absolutePath}")

        observer = object : FileObserver(importDir, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return

                when (path) {
                    ProvisioningStore.BUNDLE_FILENAME -> {
                        Log.i(TAG, "Bundle file detected: $path")
                        val bundle = store.importBundle()
                        if (bundle != null) {
                            onBundleReady?.invoke(bundle)
                        }
                    }
                    ProvisioningStore.MARKER_ROLLBACK -> {
                        Log.i(TAG, "Rollback marker detected")
                        val bundle = store.checkRollbackTrigger()
                        if (bundle != null) {
                            onBundleReady?.invoke(bundle)
                        }
                    }
                }
            }
        }
        observer?.startWatching()

        // Check if a bundle or rollback marker was already present before start
        checkPending()
    }

    /**
     * Stop watching the import directory.
     */
    fun stop() {
        observer?.stopWatching()
        observer = null
        Log.i(TAG, "Stopped watching import directory")
    }

    /**
     * Check for files already present in the import directory.
     *
     * Handles the case where a bundle or rollback marker was dropped
     * while the service was not running.
     */
    private fun checkPending() {
        // Check rollback first (takes priority)
        val rollbackBundle = store.checkRollbackTrigger()
        if (rollbackBundle != null) {
            onBundleReady?.invoke(rollbackBundle)
            return
        }

        // Check for pending bundle
        val bundleFile = File(store.importDir, ProvisioningStore.BUNDLE_FILENAME)
        if (bundleFile.exists()) {
            Log.i(TAG, "Pending bundle found on start")
            val bundle = store.importBundle()
            if (bundle != null) {
                onBundleReady?.invoke(bundle)
            }
        }
    }
}
