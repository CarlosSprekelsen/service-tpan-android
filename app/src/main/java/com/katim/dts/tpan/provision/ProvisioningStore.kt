package com.katim.dts.tpan.provision

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Dual-slot provisioning store for TPAN EUD bundles.
 *
 * Storage layout on `/sdcard/DTS/`:
 * ```
 * tpan-active/tpan-bundle.json     ← runtime bundle
 * tpan-previous/tpan-bundle.json   ← rollback slot
 * tpan-staging/tpan-bundle.json    ← transient during import
 * tpan-import/                     ← USB file-drop folder
 *   tpan-bundle.json               ← incoming bundle
 *   .tpan-import-ok                ← success marker
 *   .tpan-import-fail              ← failure marker (contains error)
 *   .tpan-rollback                 ← rollback trigger marker
 * ```
 *
 * See svc-tpan-architecture.md §EUD Provisioning Safety (lines 958–967).
 */
class ProvisioningStore(private val baseDir: File) {

    companion object {
        private const val TAG = "ProvisioningStore"
        const val BUNDLE_FILENAME = "tpan-bundle.json"
        const val IMPORT_DIR = "tpan-import"
        const val ACTIVE_DIR = "tpan-active"
        const val PREVIOUS_DIR = "tpan-previous"
        const val STAGING_DIR = "tpan-staging"
        const val MARKER_OK = ".tpan-import-ok"
        const val MARKER_FAIL = ".tpan-import-fail"
        const val MARKER_ROLLBACK = ".tpan-rollback"
    }

    val importDir get() = File(baseDir, IMPORT_DIR)
    private val activeDir get() = File(baseDir, ACTIVE_DIR)
    private val previousDir get() = File(baseDir, PREVIOUS_DIR)
    private val stagingDir get() = File(baseDir, STAGING_DIR)

    init {
        importDir.mkdirs()
        activeDir.mkdirs()
        previousDir.mkdirs()
        stagingDir.mkdirs()
    }

    // ── Read slots ──────────────────────────────────────────────────────────

    /**
     * Read the active provisioning bundle, or null if absent or corrupt.
     */
    fun getActiveBundle(): TpanBundle? = readSlot(activeDir)

    /**
     * Read the previous (rollback) bundle, or null if absent or corrupt.
     */
    fun getPreviousBundle(): TpanBundle? = readSlot(previousDir)

    // ── Import ──────────────────────────────────────────────────────────────

    /**
     * Import a bundle from the import folder.
     *
     * 1. Parse and validate the bundle JSON
     * 2. Write to staging
     * 3. Promote: active → previous, staging → active
     * 4. Write success/failure marker
     *
     * @return the imported bundle, or null on failure
     */
    fun importBundle(): TpanBundle? {
        val importFile = File(importDir, BUNDLE_FILENAME)
        if (!importFile.exists()) {
            Log.d(TAG, "No bundle in import folder")
            return null
        }

        clearMarkers()

        val json = try {
            importFile.readText()
        } catch (e: IOException) {
            writeFailMarker("Cannot read bundle file: ${e.message}")
            return null
        }

        val bundle = try {
            TpanBundle.fromJson(json)
        } catch (e: Exception) {
            writeFailMarker("Bundle parse error: ${e.message}")
            return null
        }

        val errors = TpanBundle.validate(bundle)
        if (errors.isNotEmpty()) {
            writeFailMarker("Validation failed: ${errors.joinToString("; ")}")
            return null
        }

        // Write to staging
        try {
            clearDir(stagingDir)
            File(stagingDir, BUNDLE_FILENAME).writeText(json)
        } catch (e: IOException) {
            writeFailMarker("Staging write error: ${e.message}")
            return null
        }

        // Promote: active → previous, staging → active
        try {
            promoteStaging()
        } catch (e: IOException) {
            writeFailMarker("Promotion error: ${e.message}")
            return null
        }

        // Clean up import file after successful import
        importFile.delete()
        writeOkMarker()
        Log.i(TAG, "Bundle imported: mission=${bundle.missionId}, callsign=${bundle.callsign}")
        return bundle
    }

    // ── Boot-time validation ────────────────────────────────────────────────

    /**
     * Validate the active slot on boot. If invalid, attempt rollback
     * to previous slot.
     *
     * @return the valid active bundle, or null if both slots invalid
     */
    fun validateOnBoot(): TpanBundle? {
        val active = getActiveBundle()
        if (active != null) {
            val errors = TpanBundle.validate(active)
            if (errors.isEmpty()) {
                Log.i(TAG, "Active bundle valid: mission=${active.missionId}")
                return active
            }
            Log.w(TAG, "Active bundle invalid: ${errors.joinToString("; ")}")
        } else {
            Log.w(TAG, "No active bundle found")
        }

        // Try rollback to previous
        val previous = getPreviousBundle()
        if (previous != null) {
            val errors = TpanBundle.validate(previous)
            if (errors.isEmpty()) {
                Log.w(TAG, "Rolling back to previous bundle: mission=${previous.missionId}")
                try {
                    clearDir(activeDir)
                    File(previousDir, BUNDLE_FILENAME).copyTo(
                        File(activeDir, BUNDLE_FILENAME), overwrite = true
                    )
                    return previous
                } catch (e: IOException) {
                    Log.e(TAG, "Rollback copy failed", e)
                }
            } else {
                Log.e(TAG, "Previous bundle also invalid: ${errors.joinToString("; ")}")
            }
        }

        Log.e(TAG, "No valid bundle in any slot — TPAN disabled (USB-only mode)")
        return null
    }

    // ── Rollback trigger ────────────────────────────────────────────────────

    /**
     * Check for a rollback marker and perform rollback if found.
     *
     * @return the restored bundle, or null if no rollback needed or rollback failed
     */
    fun checkRollbackTrigger(): TpanBundle? {
        val marker = File(importDir, MARKER_ROLLBACK)
        if (!marker.exists()) return null

        Log.i(TAG, "Rollback marker detected")
        marker.delete()
        clearMarkers()

        val previous = getPreviousBundle()
        if (previous == null) {
            writeFailMarker("Rollback failed: no previous bundle")
            return null
        }

        val errors = TpanBundle.validate(previous)
        if (errors.isNotEmpty()) {
            writeFailMarker("Rollback failed: previous bundle invalid — ${errors.joinToString("; ")}")
            return null
        }

        try {
            // Swap: active → (discarded), previous → active
            clearDir(activeDir)
            File(previousDir, BUNDLE_FILENAME).copyTo(
                File(activeDir, BUNDLE_FILENAME), overwrite = true
            )
        } catch (e: IOException) {
            writeFailMarker("Rollback IO error: ${e.message}")
            return null
        }

        writeOkMarker()
        Log.i(TAG, "Rolled back to previous bundle: mission=${previous.missionId}")
        return previous
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun readSlot(dir: File): TpanBundle? {
        val file = File(dir, BUNDLE_FILENAME)
        if (!file.exists()) return null
        return try {
            TpanBundle.fromJson(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read bundle from ${dir.name}: ${e.message}")
            null
        }
    }

    private fun promoteStaging() {
        // active → previous (overwrite previous)
        val activeBundle = File(activeDir, BUNDLE_FILENAME)
        if (activeBundle.exists()) {
            clearDir(previousDir)
            activeBundle.copyTo(File(previousDir, BUNDLE_FILENAME), overwrite = true)
        }
        // staging → active
        clearDir(activeDir)
        File(stagingDir, BUNDLE_FILENAME).copyTo(
            File(activeDir, BUNDLE_FILENAME), overwrite = true
        )
        clearDir(stagingDir)
    }

    private fun clearDir(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun clearMarkers() {
        File(importDir, MARKER_OK).delete()
        File(importDir, MARKER_FAIL).delete()
    }

    private fun writeOkMarker() {
        File(importDir, MARKER_OK).writeText("ok")
    }

    private fun writeFailMarker(reason: String) {
        Log.e(TAG, "Import failed: $reason")
        File(importDir, MARKER_FAIL).writeText(reason)
    }
}
