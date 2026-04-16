package com.katim.dts.service.tpan.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProvisioningStoreTest {

    private fun tempStore(): Pair<File, ProvisioningStore> {
        val baseDir = Files.createTempDirectory("tpan-store-test").toFile()
        return baseDir to ProvisioningStore(baseDir)
    }

    private fun record(
        role: String = "tt",
        tapAddress: String = "192.168.101.10",
        hubMac: String = "AA:BB:CC:DD:EE:FF",
        localMac: String = "10:20:30:40:50:60",
        passkey: Int = 123456,
        commissionedAt: String = "2026-03-28T09:12:44Z"
    ): UsbCommissionRecord {
        return UsbCommissionRecord(
            source = "usb-commission",
            hub = UsbCommissionRecord.Hub(
                btMac = hubMac,
                profileUuid = "53505000-0000-4000-8000-425400000001"
            ),
            pairingPasskey = passkey,
            localEud = UsbCommissionRecord.LocalEud(
                btMac = localMac,
                role = role,
                tapAddress = tapAddress
            ),
            commissionedAt = commissionedAt
        )
    }

    @Test
    fun `persist stores active commission record`() {
        val (_, store) = tempStore()
        val persisted = store.persistCommissionRecord(record())

        val active = store.getActiveCommissionRecord()

        assertEquals(persisted, active)
    }

    @Test
    fun `validateOnBoot rolls back to previous valid record`() {
        val (baseDir, store) = tempStore()
        val previous = record(commissionedAt = "2026-03-28T09:12:44Z")
        val active = record(commissionedAt = "2026-03-28T09:12:55Z", passkey = 222222)
        store.persistCommissionRecord(previous)
        store.persistCommissionRecord(active)

        val invalidActive = File(File(baseDir, ProvisioningStore.ACTIVE_DIR), ProvisioningStore.COMMISSION_FILENAME)
        invalidActive.writeText("{\"broken\":true}")

        val recovered = store.validateOnBoot()

        assertEquals(previous, recovered)
        assertEquals(previous, store.getActiveCommissionRecord())
    }

    @Test
    fun `validateOnBoot recovers staged record`() {
        val (baseDir, store) = tempStore()
        val staged = record(role = "twt", tapAddress = "192.168.101.20")
        val stagingFile = File(File(baseDir, ProvisioningStore.STAGING_DIR), ProvisioningStore.COMMISSION_FILENAME)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeText(staged.toJson())

        val recovered = store.validateOnBoot()

        assertEquals(staged, recovered)
        assertEquals(staged, store.getActiveCommissionRecord())
    }

    @Test
    fun `validateOnBoot returns null when no valid records exist`() {
        val (_, store) = tempStore()

        assertNull(store.validateOnBoot())
        assertNull(store.getActiveCommissionRecord())
        assertNull(store.getPreviousCommissionRecord())
    }
}

