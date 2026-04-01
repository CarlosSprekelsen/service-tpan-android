package com.katim.dts.tpan.provision

/**
 * Effective TPAN runtime configuration derived only from USB commission state
 * plus fixed local defaults.
 */
data class RuntimeTpanConfig(
    val role: String,
    val tapAddress: String,
    val localBtMac: String,
    val hubBtMac: String,
    val hubProfileUuid: String,
    val pairingPasskey: Int
) {
    companion object {
        fun fromCommission(record: UsbCommissionRecord): RuntimeTpanConfig =
            RuntimeTpanConfig(
                role = record.localEud.role,
                tapAddress = record.localEud.tapAddress,
                localBtMac = record.localEud.btMac,
                hubBtMac = record.hub.btMac,
                hubProfileUuid = record.hub.profileUuid,
                pairingPasskey = record.pairingPasskey
            )
    }

    val roleLabel: String
        get() = role.uppercase()
}
