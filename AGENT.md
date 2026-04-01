# AGENT.md — service-tpan-android

## Authoritative Design Reference

[`docs/swad-dts/designs/svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)

This is the frozen architecture for TPAN. All implementation must align with this document.

## Architecture Summary

`service-tpan-android` is a Kotlin Android service with a thin JNI/NDK layer that provides
transparent Layer 2 (Ethernet) tunneling between the EUD (TT/TWT) and the DTS Hub. The
architecture uses:

- **Root TAP device via JNI** — creates a TAP interface (`tpan0`) with the EUD's stable IP (.10 for TT, .20 for TWT) via TUNSETIFF ioctl. Requires root or CAP_NET_ADMIN. Supports L2 multicast required by SITAWARE/STC COP distribution.
- **3-byte Frame Codec** — wraps Ethernet frames for transport (DATA/KEEPALIVE/SHUTDOWN)
- **BT transport layer** — RFCOMM SPP is the only implemented wireless bearer in this pass
- **Bearer Monitor** — fixed `USB > BT` behavior
- **Provisioning Store** — internal active/previous/staging persistence for `UsbCommissionRecord`
- **USB Commission Client** — stable USB detection plus `POST /api/v1/commission/usb`
- **Bond Manager** — Hub bond replacement and passkey-assisted pairing

The NDK layer is minimal: a single C file (`tap_jni.c`) that opens `/dev/net/tun` and issues
the TUNSETIFF ioctl with `IFF_TAP | IFF_NO_PI`. All other APIs are standard Android SDK:
`BluetoothSocket`, `ConnectivityManager`, and the Bluetooth framework.

## Key Constraints

1. **No PANU/BNEP.** BT transport is SPP (RFCOMM) only.
2. **USB takes priority.** TAP deactivates when USB interface is stable. Wireless fallback is BT only.
3. **Foreground service.** Android 12+ requires persistent notification and `FOREGROUND_SERVICE_CONNECTED_DEVICE` type.
4. **Boot auto-start.** `TpanBootReceiver` starts service if a valid commission record exists.
5. **Standard Android SDK only.** No third-party networking, BT, or crypto libraries.
6. **Mission-agnostic runtime.** No callsign, mission ID, bundle import, or mission overlay is part of TPAN.

## Build

```bash
./gradlew assembleDebug
```

NDK required for the TAP JNI bridge (`app/src/main/cpp/`). Otherwise pure Kotlin/Android SDK.