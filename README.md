# service-tpan-android

**DTS Android TPAN Client Service**

An Android foreground service that provides TPAN (Tactical Proximity Area Network) connectivity between a DTS EUD (TT or TWT) and the DTS Hub.

Authoritative design reference: [`docs/swad-dts/designs/svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)

## Architecture

`service-tpan-android` uses Android `VpnService` to create a TUN interface with the EUD's stable IP address and carries framed IP packets over the highest-priority enabled bearer. Applications connect to Hub services using normal IP sockets -- TPAN is invisible to all apps.

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
| USB commissioning client | TODO | Auto-detect Internal Zone USB, call commission endpoint, persist response |
| VPN Engine (VpnService + TUN) | Planned | Transparent IP capture |
| Frame Codec (Kotlin) | Planned | 3-byte frame protocol |
| BT Transport (RFCOMM) | Planned | BluetoothSocket to Hub SPP profile with passkey pairing |
| Provisioning Store | Planned | Dual-slot persistence for USB commission data, boot validation |
| Bearer Monitor | Planned | USB detection, priority selection, VPN activation |
| Integration + Lifecycle | Planned | KEEPALIVE, reconnection, auto-start |

## Open Dependencies

| ID | Dependency | Priority |
| -- | ---------- | -------- |
| TPAN-A1 | Implement VPN Engine (VpnService + TUN): transparent IP capture | Critical |
| TPAN-A2 | Implement Frame Codec (Kotlin): 3-byte frame protocol | Critical |
| TPAN-A3 | Implement BT Transport (RFCOMM): BluetoothSocket to Hub SPP profile | Critical |
| TPAN-A4 | Implement USB commissioning client: auto-detect Internal Zone USB (on plug and on service start), call commission endpoint, persist response | Critical |
| TPAN-A5 | Implement auto-pairing with USB-exchanged passkey (KATIM privileged build auto-confirm) | Critical |
| TPAN-A6 | Implement Bearer Monitor: USB detection, priority selection, VPN activation | High |
| TPAN-A7 | Implement Provisioning Store: dual-slot persistence, boot-time validation | High |
| TPAN-A8 | Implement reconnection with exponential backoff (1s..30s) | High |
| TPAN-A9 | Integrate Root PKI mTLS into provisioning | Medium |
