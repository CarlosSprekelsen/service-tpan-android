package com.katim.dts.service.tpan.provision

import org.json.JSONException
import org.json.JSONObject

/**
 * Persisted TPAN USB commissioning record.
 *
 * This is the only runtime provisioning input for TPAN in the
 * mission-agnostic model.
 */
data class UsbCommissionRecord(
    val source: String,
    val hub: Hub,
    val pairingPasskey: Int,
    val localEud: LocalEud,
    val commissionedAt: String
) {
    data class Hub(
        val btMac: String,
        val profileUuid: String
    )

    data class LocalEud(
        val btMac: String,
        val role: String,
        val tapAddress: String
    )

    companion object {
        private const val EXPECTED_SOURCE = "usb-commission"

        fun fromJson(json: String): UsbCommissionRecord {
            val root = JSONObject(json)
            val hub = root.getJSONObject("hub")
            val local = root.getJSONObject("localEud")

            return UsbCommissionRecord(
                source = root.getString("source"),
                hub = Hub(
                    btMac = hub.getString("btMac"),
                    profileUuid = hub.getString("profileUuid")
                ),
                pairingPasskey = root.getInt("pairingPasskey"),
                localEud = LocalEud(
                    btMac = local.getString("btMac"),
                    role = local.getString("role"),
                    tapAddress = local.getString("tapAddress")
                ),
                commissionedAt = root.getString("commissionedAt")
            )
        }

        fun validate(record: UsbCommissionRecord): List<String> {
            val errors = mutableListOf<String>()
            if (record.source != EXPECTED_SOURCE) {
                errors += "source must be '$EXPECTED_SOURCE'"
            }
            if (record.hub.btMac.isBlank()) {
                errors += "hub.btMac is required"
            }
            if (record.hub.profileUuid.isBlank()) {
                errors += "hub.profileUuid is required"
            }
            if (record.localEud.btMac.isBlank()) {
                errors += "localEud.btMac is required"
            }
            if (record.localEud.role !in listOf("tt", "twt")) {
                errors += "localEud.role must be 'tt' or 'twt'"
            }
            if (record.localEud.tapAddress.isBlank()) {
                errors += "localEud.tapAddress is required"
            }
            if (record.commissionedAt.isBlank()) {
                errors += "commissionedAt is required"
            }
            if (record.pairingPasskey !in 0..999999) {
                errors += "pairingPasskey must be between 0 and 999999"
            }
            return errors
        }
    }

    fun toJson(): String =
        JSONObject().apply {
            put("source", source)
            put(
                "hub",
                JSONObject().apply {
                    put("btMac", hub.btMac)
                    put("profileUuid", hub.profileUuid)
                }
            )
            put("pairingPasskey", pairingPasskey)
            put(
                "localEud",
                JSONObject().apply {
                    put("btMac", localEud.btMac)
                    put("role", localEud.role)
                    put("tapAddress", localEud.tapAddress)
                }
            )
            put("commissionedAt", commissionedAt)
        }.toString(2)
}

