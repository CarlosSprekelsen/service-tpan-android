# service-tpan-android

**DTS Android TPAN Client Service**

An Android foreground service that provides TPAN (Tactical Proximity Area Network) connectivity between a DTS EUD (TT or TWT) and the DTS Hub.

Authoritative design reference: [`docs/swad-dts/designs/svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)

## Architecture

`service-tpan-android` uses Android `VpnService` to create a TUN interface with the EUD's stable IP address and carries framed IP packets over the highest-priority enabled bearer. Applications connect to Hub services using normal IP sockets — TPAN is invisible to all apps.

```
┌──────────────────────────────────── EUD (TT / TWT) ─────────────────────────────────┐
│                                                                                       │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐ │
│  │                     TpanService (Android Foreground Service)                    │ │
│  │                                                                                  │ │
│  │   ┌──────────────┐   ┌──────────────┐   ┌─────────────────────────────────┐   │ │
│  │   │  VPN Engine   │   │ Frame Codec  │   │  Transport Layer (pluggable)    │   │ │
│  │   │  VpnService   │◄──►  3-byte      │◄──►  BT SPP (RFCOMM)              │   │ │
│  │   │  TUN fd r/w   │   │  DATA/KA/SD  │   │  UWB (future)                 │   │ │
│  │   │  Stable IP    │   │              │   │  WiFi (future)                 │   │ │
│  │   └──────────────┘   └──────────────┘   └─────────────────────────────────┘   │ │
│  │                                                      ▲                         │ │
│  │   ┌──────────────────────┐   ┌──────────────────────┴──────┐                  │ │
│  │   │  Provisioning Store   │   │  Bearer Monitor              │                  │ │
│  │   │  USB file-drop        │──►│  USB > UWB > WiFi > BT       │                  │ │
│  │   │  active/previous      │   │  stability hold on fail-up   │                  │ │
│  │   └──────────────────────┘   └─────────────────────────────┘                  │ │
│  └──────────────────────────────────────────────────────────────────────────────────┘ │
│                     │ Wireless bearer                    ▲ App traffic                 │
└─────────────────────┼────────────────────────────────────┼────────────────────────────┘
                      ▼                              ┌─────┴──────────────────┐
              tpan-bt-manager (Hub)                  │  DTS Applications      │
              192.168.101.35                         │  (normal IP sockets)   │
                                                     └────────────────────────┘
```

## Repository Structure

```
service-tpan-android/
├── AGENT.md                        # Architecture reference and build guidance
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/katim/dts/tpan/
│           ├── TpanService.kt      # Foreground service lifecycle
│           └── TpanBootReceiver.kt # Auto-start on boot
├── build.gradle.kts
└── settings.gradle.kts
```

## Mission Planning Integration

The provisioning bundle is delivered via USB from the Mission Planning tool. The canonical bundle shape is defined in `svc-tpan-architecture.md` §EUD-Side Bundle. The Android-side importer validates, persists (active + previous slots), and auto-starts TPAN according to bundle policy.

## Build

```bash
./gradlew assembleDebug
```

No NDK required. Pure Kotlin/Android SDK project.

## Deployment

| EUD | Transport | Notes |
|-----|-----------|-------|
| TT (Tactical Terminal) | BT SPP (RFCOMM) | When USB not connected |
| TWT (Wrist Terminal) | BT SPP (RFCOMM) | When USB not connected; no PANU dependency |

The service auto-starts on boot via `TpanBootReceiver` and runs as a foreground service with a persistent notification.

## Status

| Component | Status | Notes |
|-----------|--------|-------|
| TpanService (foreground service) | Scaffold | Lifecycle shell, notification management |
| TpanBootReceiver | Ready | Boot auto-start |
| VPN Engine (VpnService + TUN) | Planned | Transparent IP capture — Epic 3 |
| Frame Codec (Kotlin) | Planned | 3-byte frame protocol — Epic 2 |
| BT Transport (RFCOMM) | Planned | BluetoothSocket to Hub SPP profile — Epic 4 |
| Provisioning Store | Planned | USB file-drop, dual-slot, boot validation — Epic 5 |
| Bearer Monitor | Planned | USB detection, priority selection — Epic 6 |
| Integration + Lifecycle | Planned | KEEPALIVE, reconnection, auto-start — Epic 7 |
