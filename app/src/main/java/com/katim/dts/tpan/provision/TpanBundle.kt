package com.katim.dts.tpan.provision

import org.json.JSONException
import org.json.JSONObject

/**
 * EUD-side TPAN provisioning bundle.
 *
 * Matches `svc-tpan-architecture.md` §EUD-Side Bundle (lines 923–955).
 * Parsed from JSON delivered via USB file-drop from Mission Planning.
 */
data class TpanBundle(
    val schemaVersion: String,
    val missionId: String,
    val callsign: String,
    val role: String,
    val kitProfile: KitProfile,
    val localEud: LocalEud,
    val hub: Hub,
    val bearerPolicy: BearerPolicy,
    val security: Security
) {
    data class KitProfile(
        val id: String,
        val name: String,
        val stickyReuse: Boolean
    )

    data class LocalEud(
        val btMac: String,
        val tunAddress: String,
        val autoStartTpan: Boolean
    )

    data class Hub(
        val btMac: String,
        val profileUuid: String
    )

    data class BearerPolicy(
        val priority: List<String>,
        val disabledBearers: List<String>,
        val singleActiveBearer: Boolean
    )

    data class Security(
        val mtlsRequired: Boolean,
        val clientIdentityRef: String,
        val trustAnchorSet: String
    )

    companion object {
        private const val SUPPORTED_SCHEMA = "1.0.0"

        /**
         * Parse a [TpanBundle] from JSON text.
         *
         * @throws JSONException if required fields are missing or malformed
         * @throws IllegalArgumentException if schema version is unsupported
         */
        fun fromJson(json: String): TpanBundle {
            val root = JSONObject(json)

            val schema = root.getString("schemaVersion")
            require(schema == SUPPORTED_SCHEMA) {
                "Unsupported schema version: $schema (expected $SUPPORTED_SCHEMA)"
            }

            val kp = root.getJSONObject("kitProfile")
            val le = root.getJSONObject("localEud")
            val hub = root.getJSONObject("hub")
            val bp = root.getJSONObject("bearerPolicy")
            val sec = root.getJSONObject("security")

            return TpanBundle(
                schemaVersion = schema,
                missionId = root.getString("missionId"),
                callsign = root.getString("callsign"),
                role = root.getString("role"),
                kitProfile = KitProfile(
                    id = kp.getString("id"),
                    name = kp.getString("name"),
                    stickyReuse = kp.getBoolean("stickyReuse")
                ),
                localEud = LocalEud(
                    btMac = le.getString("btMac"),
                    tunAddress = le.getString("tunAddress"),
                    autoStartTpan = le.getBoolean("autoStartTpan")
                ),
                hub = Hub(
                    btMac = hub.getString("btMac"),
                    profileUuid = hub.getString("profileUuid")
                ),
                bearerPolicy = BearerPolicy(
                    priority = bp.getJSONArray("priority").let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    disabledBearers = bp.getJSONArray("disabledBearers").let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    singleActiveBearer = bp.getBoolean("singleActiveBearer")
                ),
                security = Security(
                    mtlsRequired = sec.getBoolean("mtlsRequired"),
                    clientIdentityRef = sec.getString("clientIdentityRef"),
                    trustAnchorSet = sec.getString("trustAnchorSet")
                )
            )
        }

        /**
         * Validate required fields beyond schema presence.
         *
         * @return list of validation errors, empty if valid
         */
        fun validate(bundle: TpanBundle): List<String> {
            val errors = mutableListOf<String>()
            if (bundle.hub.btMac.isBlank()) errors += "hub.btMac is required"
            if (bundle.localEud.tunAddress.isBlank()) errors += "localEud.tunAddress is required"
            if (bundle.role !in listOf("tt", "twt")) errors += "role must be 'tt' or 'twt'"
            if (bundle.hub.profileUuid.isBlank()) errors += "hub.profileUuid is required"
            if (bundle.localEud.btMac.isBlank()) errors += "localEud.btMac is required"
            return errors
        }
    }
}
