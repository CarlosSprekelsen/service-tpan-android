package com.katim.dts.service.tpan.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbCommissionRecordTest {

    private val validJson = """
        {
          "source": "usb-commission",
          "hub": {
            "btMac": "AA:BB:CC:DD:EE:FF",
            "profileUuid": "53505000-0000-4000-8000-425400000001"
          },
          "pairingPasskey": 123456,
          "localEud": {
            "btMac": "10:20:30:40:50:60",
            "role": "tt",
            "tapAddress": "192.168.101.10"
          },
          "commissionedAt": "2026-03-28T09:12:44Z"
        }
    """.trimIndent()

    @Test
    fun `parse valid commission record`() {
        val record = UsbCommissionRecord.fromJson(validJson)

        assertEquals("usb-commission", record.source)
        assertEquals("AA:BB:CC:DD:EE:FF", record.hub.btMac)
        assertEquals("53505000-0000-4000-8000-425400000001", record.hub.profileUuid)
        assertEquals("tt", record.localEud.role)
        assertEquals("192.168.101.10", record.localEud.tapAddress)
        assertEquals(123456, record.pairingPasskey)
    }

    @Test
    fun `validate valid record returns no errors`() {
        val record = UsbCommissionRecord.fromJson(validJson)
        assertTrue(UsbCommissionRecord.validate(record).isEmpty())
    }

    @Test
    fun `validate rejects invalid role`() {
        val record = UsbCommissionRecord.fromJson(validJson.replace("\"tt\"", "\"invalid\""))
        val errors = UsbCommissionRecord.validate(record)

        assertTrue(errors.any { "localEud.role" in it })
    }

    @Test
    fun `validate rejects out of range passkey`() {
        val record = UsbCommissionRecord.fromJson(validJson.replace("123456", "1000000"))
        val errors = UsbCommissionRecord.validate(record)

        assertTrue(errors.any { "pairingPasskey" in it })
    }
}

