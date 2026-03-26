# AGENT.md — service-tpan-android

## Authoritative Design Reference

[`docs/swad-dts/designs/svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)

This is the frozen architecture for TPAN. All implementation must align with this document.

## Architecture Summary

`service-tpan-android` is a pure Kotlin Android service that provides transparent IP tunneling
between the EUD (TT/TWT) and the DTS Hub. The architecture uses:

- **Android VpnService** — creates a TUN interface with the EUD's stable IP (.10 for TT, .20 for TWT)
- **3-byte Frame Codec** — wraps IP packets for transport (DATA/KEEPALIVE/SHUTDOWN)
- **Pluggable Transport Layer** — BT SPP (RFCOMM) first; UWB and WiFi share the same interface
- **Bearer Monitor** — selects the best available bearer (USB > UWB > WiFi > BT)
- **Provisioning Store** — USB file-drop importer with dual-slot active/previous persistence

There is no C++/NDK layer, no JNI, no SOCKS5H proxy. All APIs used are standard Android SDK:
`VpnService`, `BluetoothSocket`, `ConnectivityManager`, `FileObserver`.

## Key Constraints

1. **No PANU/BNEP.** BT transport is SPP (RFCOMM) only.
2. **USB takes priority.** VPN deactivates when USB interface is stable. Bearer Monitor controls this.
3. **Foreground service.** Android 12+ requires persistent notification and `FOREGROUND_SERVICE_CONNECTED_DEVICE` type.
4. **Boot auto-start.** `TpanBootReceiver` starts service if a valid provisioning bundle exists and `autoStartTpan=true`.
5. **Standard Android SDK only.** No third-party networking, BT, or crypto libraries.

## Build

```bash
./gradlew assembleDebug
```

No NDK required. Pure Kotlin/Android SDK project.
