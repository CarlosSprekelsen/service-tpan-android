# service-tpan-android

**DTS Android TPAN Client Service**

An Android foreground service that provides transparent IP connectivity between
a DTS EUD (TT or TWT) and the DTS Hub over TPAN. Uses Android `VpnService` to
create a TUN interface with the EUD's stable IP address and carries framed IP
packets over the highest-priority available bearer. Applications connect to Hub
services using normal IP sockets -- TPAN is invisible to all apps.

Authoritative design reference:
[`docs/swad-dts/designs/svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)

## Architecture

```
+--------------------------------- EUD (TT / TWT) --------------------------------+
|                                                                                   |
|  +-----------------------------------------------------------------------------+ |
|  |                  TpanService (Android Foreground Service)                    | |
|  |                                                                              | |
|  |  +--------------+  +--------------+  +----------------------------------+   | |
|  |  | VPN Engine   |  | Frame Codec  |  | Transport Layer (pluggable)      |   | |
|  |  | VpnService   |  | 3-byte       |  | BT SPP (RFCOMM) -- implemented  |   | |
|  |  | TUN fd r/w   |  | DATA/KA/SD   |  | UWB (future)                    |   | |
|  |  | Stable IP    |  |              |  | WiFi (future)                    |   | |
|  |  +--------------+  +--------------+  +----------------------------------+   | |
|  |                                                     ^                        | |
|  |  +-----------------------+  +-----------------------+-----+                  | |
|  |  | Provisioning Store    |  | Bearer Monitor               |                 | |
|  |  | USB file-drop         |->| USB > UWB > WiFi > BT        |                 | |
|  |  | active/previous       |  | stability hold on fail-up    |                 | |
|  |  +-----------------------+  +---+--------------------------+                  | |
|  |                                 |                                             | |
|  |  +-----------------------+  +---v--------------------------+                  | |
|  |  | Bundle Importer       |  | Connection Manager            |                 | |
|  |  | FileObserver on USB   |  | KEEPALIVE 1s / 3-miss dead   |                 | |
|  |  | import dir            |  | reconnect backoff 1s..30s    |                 | |
|  |  +-----------------------+  +------------------------------+                  | |
|  +-----------------------------------------------------------------------------+ |
|                    | Wireless bearer                   ^ App traffic              |
+--------------------+-----------------------------------+--------------------------+
                     v                             +-----+-------------------+
             tpan-bt-manager (Hub)                 |  DTS Applications       |
             192.168.101.35                        |  (normal IP sockets)    |
                                                   +-------------------------+
```

## Repository Structure

```
service-tpan-android/
+-- AGENT.md                                    # Architecture reference
+-- build.gradle.kts                            # Root build config
+-- settings.gradle.kts                         # Module declaration
+-- gradle.properties
+-- gradle/libs.versions.toml                   # Dependency version catalog
+-- app/
    +-- build.gradle.kts                        # App module: SDK 34, minSdk 29
    +-- src/
        +-- main/
        |   +-- AndroidManifest.xml
        |   +-- java/com/katim/dts/tpan/
        |       +-- TpanService.kt              # Foreground service orchestrator
        |       +-- TpanBootReceiver.kt         # Auto-start on boot
        |       +-- ConnectionManager.kt        # KEEPALIVE timer, link-dead, reconnect backoff
        |       +-- vpn/
        |       |   +-- VpnEngine.kt            # VpnService TUN creation, IP packet relay
        |       +-- codec/
        |       |   +-- FrameType.kt            # DATA(0x00), KEEPALIVE(0x01), SHUTDOWN(0x02)
        |       |   +-- Frame.kt                # Immutable frame data class
        |       |   +-- FrameCodec.kt           # 3-byte wire protocol encoder/decoder
        |       +-- transport/
        |       |   +-- TpanTransport.kt        # Pluggable transport interface
        |       |   +-- BtTransport.kt          # BluetoothSocket RFCOMM implementation
        |       +-- bearer/
        |       |   +-- BearerMonitor.kt        # USB detection, bearer priority, VPN control
        |       +-- provision/
        |           +-- TpanBundle.kt           # Commission bundle data class (JSON)
        |           +-- ProvisioningStore.kt    # Dual-slot persistence, import, rollback
        |           +-- BundleImporter.kt       # FileObserver on USB import directory
        +-- test/java/com/katim/dts/tpan/
            +-- codec/
            |   +-- FrameCodecTest.kt           # Wire format, round-trip, partial reads, errors
            +-- provision/
            |   +-- TpanBundleTest.kt           # JSON parsing, validation
            +-- bearer/
                +-- BearerSelectorTest.kt       # Bearer priority filtering
```

## USB Commissioning

TPAN is mission-agnostic. When plugged into a Hub via USB, the service
automatically discovers the Hub on the Internal Zone and calls the USB
commissioning endpoint. The Hub responds with its BT identity and a one-time
pairing passkey. BT pairing proceeds automatically. No user interaction or
Mission Planning Tool involvement is needed.

The service auto-commissions on USB plug or on service start when USB is already
connected. Commissioning state persists across reboots and power loss (active +
previous slots in Android internal storage).

See `svc-tpan-architecture.md` for the full USB commissioning design.

## Build

```bash
./gradlew assembleDebug
```

No NDK required. Pure Kotlin/Android SDK project (compileSdk 34, minSdk 29,
targetSdk 34, Java 17).

## Deployment

| EUD | Transport | Notes |
|-----|-----------|-------|
| TT (Tactical Terminal) | BT SPP (RFCOMM) | When USB not connected |
| TWT (Wrist Terminal) | BT SPP (RFCOMM) | When USB not connected; no PANU dependency |

The service auto-starts on boot via `TpanBootReceiver` if a valid provisioning
bundle exists and `autoStartTpan` is true. Runs as a foreground service with
a persistent notification.

## Status

| Component | Status | Notes |
|-----------|--------|-------|
| TpanService | Implemented | Foreground service lifecycle, component wiring, ordered shutdown, live re-provisioning on bundle import |
| TpanBootReceiver | Implemented | Boot auto-start with provisioning validation (`autoStartTpan` flag) |
| VPN Engine (VpnService + TUN) | Implemented | TUN creation, stable IP, bidirectional IP relay, socket protection |
| Frame Codec (3-byte) | Implemented | DATA/KEEPALIVE/SHUTDOWN, partial-read handling, synchronized writes |
| BT Transport (RFCOMM) | Implemented | BluetoothSocket SPP, disconnect callback, no VPN capture needed (L2CAP) |
| Bearer Monitor | Implemented | USB detection via ConnectivityManager, bearer priority filtering, VPN activation/deactivation, 2s stability hold on USB up |
| Connection Manager | Implemented | KEEPALIVE timer (1s), link-dead detection (3s/3-miss), graceful SHUTDOWN, exponential backoff reconnection (1s..30s) |
| Provisioning Store | Implemented | Dual-slot persistence (active/previous), staging-then-promote import, boot-time validation with rollback |
| Bundle Importer | Implemented | FileObserver on `/sdcard/DTS/tpan-import/`, pending checks on start, rollback trigger support |
| TpanBundle | Implemented | JSON parsing, schema validation, immutable data class with nested structures |
| USB commissioning client | TODO | Auto-detect Internal Zone USB, call Hub commission endpoint, persist BT identity + passkey |
| Auto-pairing with passkey | TODO | Use USB-exchanged passkey for BT bond (KATIM privileged build auto-confirm) |
| UWB transport | Planned | Stubbed in TpanService.connectTransportForBearer() |
| WiFi transport | Planned | Stubbed in TpanService.connectTransportForBearer() |
| Root PKI mTLS | Planned | Bundle carries `security.mtlsRequired` flag but not yet integrated |

## Open Dependencies

| ID | Dependency | Priority |
| -- | ---------- | -------- |
| TPAN-A1 | Implement USB commissioning client: auto-detect Internal Zone USB, call Hub commission endpoint, persist response | Critical |
| TPAN-A2 | Implement auto-pairing with USB-exchanged passkey (KATIM privileged build auto-confirm) | Critical |
| TPAN-A3 | End-to-end integration testing with `tpan-bt-manager` on TT and TWT hardware | Critical |
| TPAN-A4 | Integrate Root PKI mTLS into transport layer | Medium |
