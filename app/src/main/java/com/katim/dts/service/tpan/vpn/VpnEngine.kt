package com.katim.dts.service.tpan.vpn

/**
 * DEPRECATED — replaced by [com.katim.dts.service.tpan.tap.TapEngine].
 *
 * The VpnService / TUN approach has been replaced by a root TAP device
 * created via JNI (TUNSETIFF ioctl with IFF_TAP). TAP operates at Layer 2,
 * which is required for multicast support (SITAWARE / STC COP distribution).
 *
 * This file is retained only for git history. Do not use or reference.
 */
@Deprecated("Use TapEngine instead — VpnService/TUN does not support L2 multicast")
class VpnEngine {
    init {
        throw UnsupportedOperationException(
            "VpnEngine is deprecated. Use com.katim.dts.service.tpan.tap.TapEngine instead."
        )
    }
}

