package com.katim.dts.tpan.provision

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Internal dual-slot persistence for USB commission records.
 */
class ProvisioningStore(private val baseDir: File) {

    companion object {
        private const val TAG = "ProvisioningStore"
        const val ROOT_DIR = "tpan"
        const val COMMISSION_FILENAME = "usb-commission.json"
        const val ACTIVE_DIR = "commission-active"
        const val PREVIOUS_DIR = "commission-previous"
        const val STAGING_DIR = "commission-staging"
    }

    private val activeDir get() = File(baseDir, ACTIVE_DIR)
    private val previousDir get() = File(baseDir, PREVIOUS_DIR)
    private val stagingDir get() = File(baseDir, STAGING_DIR)

    init {
        activeDir.mkdirs()
        previousDir.mkdirs()
        stagingDir.mkdirs()
    }

    fun getActiveCommissionRecord(): UsbCommissionRecord? = readSlot(activeDir)

    fun getPreviousCommissionRecord(): UsbCommissionRecord? = readSlot(previousDir)

    fun persistCommissionRecord(record: UsbCommissionRecord): UsbCommissionRecord {
        val json = record.toJson()
        val errors = UsbCommissionRecord.validate(record)
        require(errors.isEmpty()) {
            "Invalid commission record: ${errors.joinToString("; ")}"
        }

        try {
            clearDir(stagingDir)
            writeAtomically(File(stagingDir, COMMISSION_FILENAME), json)
            promoteStaging()
            Log.i(TAG, "Stored commission record for role=${record.localEud.role}")
            return record
        } catch (e: IOException) {
            throw IllegalStateException("Failed to persist commission record", e)
        }
    }

    fun validateOnBoot(): UsbCommissionRecord? {
        val active = getActiveCommissionRecord()
        if (active != null && UsbCommissionRecord.validate(active).isEmpty()) {
            Log.i(TAG, "Active commission record valid for role=${active.localEud.role}")
            return active
        }

        val staged = readSlot(stagingDir)
        if (staged != null && UsbCommissionRecord.validate(staged).isEmpty()) {
            Log.w(TAG, "Recovering valid staged commission record")
            return try {
                promoteStaging()
                staged
            } catch (e: IOException) {
                Log.e(TAG, "Failed to recover staged commission record", e)
                null
            }
        }

        val previous = getPreviousCommissionRecord()
        if (previous != null && UsbCommissionRecord.validate(previous).isEmpty()) {
            Log.w(TAG, "Rolling back to previous commission record")
            try {
                clearDir(activeDir)
                writeAtomically(
                    File(activeDir, COMMISSION_FILENAME),
                    File(previousDir, COMMISSION_FILENAME).readText()
                )
                return previous
            } catch (e: IOException) {
                Log.e(TAG, "Rollback failed", e)
            }
        }

        Log.w(TAG, "No valid commission record available")
        return null
    }

    private fun readSlot(dir: File): UsbCommissionRecord? {
        val file = File(dir, COMMISSION_FILENAME)
        if (!file.exists()) return null
        return try {
            UsbCommissionRecord.fromJson(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read ${file.absolutePath}: ${e.message}")
            null
        }
    }

    private fun promoteStaging() {
        val activeFile = File(activeDir, COMMISSION_FILENAME)
        val stagingFile = File(stagingDir, COMMISSION_FILENAME)
        val stagedJson = stagingFile.readText()
        if (activeFile.exists()) {
            clearDir(previousDir)
            writeAtomically(
                File(previousDir, COMMISSION_FILENAME),
                activeFile.readText()
            )
        }

        clearDir(activeDir)
        writeAtomically(File(activeDir, COMMISSION_FILENAME), stagedJson)
        clearDir(stagingDir)
    }

    private fun clearDir(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun writeAtomically(target: File, contents: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(contents)
        if (!temp.renameTo(target)) {
            throw IOException("Failed to rename ${temp.absolutePath} to ${target.absolutePath}")
        }
    }
}
