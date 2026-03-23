# AGENT.md — service-tpan-android

Guidance for agentic coding assistants porting and extending this service.

## Context

This is an Android foreground service that provides TPAN (Tactical Proximity Area Network) connectivity
for DTS EUDs (TT, TWT). It is a port of the `tpan-bt-client` from the TNN prototype:

**Source prototype:** https://bitbucket.katim.com/projects/TNN/repos/tpan-over-bt/browse

The prototype's `AGENT.md` contains the original porting guidance for Android conversion.
Read it first before making changes to the C++ NDK layer.

## Port Scope

| Prototype file          | Target in this repo                              | Notes                        |
|-------------------------|--------------------------------------------------|------------------------------|
| `tpan-bt-client/main.c` | `app/src/main/cpp/tpan_client.cpp`              | Refactor for Android NDK     |
| SOCKS5H server          | `app/src/main/cpp/socks5h_server.cpp`           | Same logic, JNI-callable     |
| RFCOMM connect          | `app/src/main/cpp/tpan_client.cpp`              | Use Android BT socket        |
| mTLS session            | `app/src/main/cpp/tpan_client.cpp`              | Use BoringSSL or mbedTLS     |
| (none — new)            | `app/src/main/java/.../TpanService.kt`          | Android foreground service   |
| (none — new)            | `app/src/main/java/.../TpanBootReceiver.kt`     | Boot auto-start              |

## Key Constraints

1. **No PANU dependency.** This service uses Bluetooth SPP (RFCOMM), not BT PAN (PANU/NAP).
   The QCOM PANU blocker on TWT does NOT affect this service.

2. **USB takes priority.** When the USB gadget interface (`rndis0` or `ncm0`) is active,
   this service should hold its RFCOMM connection but idle the SOCKS5H relay.
   The Android TransportPolicy class (to be implemented) coordinates this.

3. **mTLS required.** All TPAN sessions must authenticate with the Root PKI.
   The Hub presents its certificate; the EUD presents its device certificate.
   Do NOT disable certificate verification.

4. **Foreground service.** Android requires BLUETOOTH_CONNECT permission and foreground
   service with a persistent notification. Do not run as a background service.

5. **Boot auto-start.** `TpanBootReceiver` must start `TpanService` on `BOOT_COMPLETED`.
   Requires `RECEIVE_BOOT_COMPLETED` permission.

## Android API Targets

- `minSdk`: 29 (Android 10 — aligns with TT/TWT baseline)
- `targetSdk`: 34
- NDK version: r26 or later
- C++ standard: C++17

## How to Add a Feature

### Add a new TPAN control message

1. Update `proto/tpan_bt.proto` in `services/tpan-bt-manager` (Hub side first).
2. Add the corresponding handler in `tpan_client.cpp`.
3. Expose via JNI in `TpanManager.kt` if the Android app layer needs it.

### Add Per-App VPN mode (alternative to SOCKS5H)

Android `VpnService` API can intercept all device traffic transparently without SOCKS5H.
This is the preferred long-term approach. See `TpanService.kt` for the placeholder.
Requires `BIND_VPN_SERVICE` permission and user confirmation dialog on first use.

## Build Instructions

```bash
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (requires signing config)
```

NDK path must be set in `local.properties`:
```
ndk.dir=/path/to/android-ndk-r26
```
