package com.katim.dts.tpan.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TpanBundleTest {

    private val validJson = """
        {
          "schemaVersion": "1.0.0",
          "missionId": "f7c64b44-61e6-4c00-b809-2afaf0f34f9a",
          "callsign": "ALPHA-001",
          "role": "tt",
          "kitProfile": {
            "id": "kit-alpha-001",
            "name": "ALPHA-001 personal kit",
            "stickyReuse": true
          },
          "localEud": {
            "btMac": "10:20:30:40:50:60",
            "tunAddress": "192.168.101.10",
            "autoStartTpan": true
          },
          "hub": {
            "btMac": "AA:BB:CC:DD:EE:FF",
            "profileUuid": "53505000-0000-4000-8000-425400000001"
          },
          "bearerPolicy": {
            "priority": ["usb", "uwb", "wifi", "bt"],
            "disabledBearers": ["wifi"],
            "singleActiveBearer": true
          },
          "security": {
            "mtlsRequired": false,
            "clientIdentityRef": "root-pki://tt/10:20:30:40:50:60",
            "trustAnchorSet": "root-pki-2026-01"
          }
        }
    """.trimIndent()

    @Test
    fun `parse valid bundle`() {
        val bundle = TpanBundle.fromJson(validJson)
        assertEquals("1.0.0", bundle.schemaVersion)
        assertEquals("ALPHA-001", bundle.callsign)
        assertEquals("tt", bundle.role)
        assertEquals("AA:BB:CC:DD:EE:FF", bundle.hub.btMac)
        assertEquals("192.168.101.10", bundle.localEud.tunAddress)
        assertTrue(bundle.localEud.autoStartTpan)
        assertTrue(bundle.kitProfile.stickyReuse)
        assertEquals(listOf("usb", "uwb", "wifi", "bt"), bundle.bearerPolicy.priority)
        assertEquals(listOf("wifi"), bundle.bearerPolicy.disabledBearers)
    }

    @Test
    fun `validate valid bundle returns no errors`() {
        val bundle = TpanBundle.fromJson(validJson)
        assertTrue(TpanBundle.validate(bundle).isEmpty())
    }

    @Test
    fun `validate catches invalid role`() {
        val json = validJson.replace("\"tt\"", "\"invalid\"")
        val bundle = TpanBundle.fromJson(json)
        val errors = TpanBundle.validate(bundle)
        assertTrue(errors.any { "role" in it })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject unsupported schema version`() {
        val json = validJson.replace("\"1.0.0\"", "\"2.0.0\"")
        TpanBundle.fromJson(json)
    }

    @Test(expected = org.json.JSONException::class)
    fun `reject missing required field`() {
        // Remove hub object entirely
        val json = """
            {
              "schemaVersion": "1.0.0",
              "missionId": "test",
              "callsign": "TEST",
              "role": "tt",
              "kitProfile": { "id": "k1", "name": "test", "stickyReuse": false },
              "localEud": { "btMac": "AA:BB:CC:DD:EE:FF", "tunAddress": "192.168.101.10", "autoStartTpan": true },
              "bearerPolicy": { "priority": ["bt"], "disabledBearers": [], "singleActiveBearer": true },
              "security": { "mtlsRequired": false, "clientIdentityRef": "ref", "trustAnchorSet": "set" }
            }
        """.trimIndent()
        TpanBundle.fromJson(json)
    }

    @Test
    fun `validate catches blank hub btMac`() {
        val json = validJson.replace("AA:BB:CC:DD:EE:FF", "")
        val bundle = TpanBundle.fromJson(json)
        val errors = TpanBundle.validate(bundle)
        assertTrue(errors.any { "hub.btMac" in it })
    }

    @Test
    fun `TWT role parses correctly`() {
        val json = validJson
            .replace("\"tt\"", "\"twt\"")
            .replace("192.168.101.10", "192.168.101.20")
        val bundle = TpanBundle.fromJson(json)
        assertEquals("twt", bundle.role)
        assertEquals("192.168.101.20", bundle.localEud.tunAddress)
        assertTrue(TpanBundle.validate(bundle).isEmpty())
    }

    @Test
    fun `parse preserves bearer policy disabled list`() {
        val bundle = TpanBundle.fromJson(validJson)
        assertFalse(bundle.bearerPolicy.disabledBearers.contains("bt"))
        assertTrue(bundle.bearerPolicy.disabledBearers.contains("wifi"))
        assertTrue(bundle.bearerPolicy.singleActiveBearer)
    }
}
