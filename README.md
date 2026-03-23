# service-tpan-android

**DTS Android TPAN Client Service**

An Android background service that provides TPAN (Tactical Proximity Area Network) connectivity between a DTS EUD (TT or TWT) and the DTS Hub.

It establishes a Bluetooth SPP (RFCOMM) connection to `tpan-bt-manager` running on the Hub, authenticates with mTLS using the device's Root PKI identity, and exposes a local **SOCKS5H proxy** (or Per-App VPN) so that applications on the EUD can reach Hub container services without any direct network interface configuration.

This service is the Android port of the `tpan-bt-client` from the TNN prototype.
See [`AGENT.md`](AGENT.md) for agentic porting guidance from the prototype.

## Architecture

```
┌─────────────────────────────────── EUD (TT / TWT) ──────────────────────────────────┐
│                                                                                       │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐ │
│  │                         TpanService (Android Foreground Service)                │ │
│  │                                                                                  │ │
│  │   ┌─────────────────────────────────┐   ┌──────────────────────────────────┐   │ │
│  │   │  tpan_client (C++ NDK)           │   │  SOCKS5H Server (loopback :1080) │   │ │
│  │   │  - RFCOMM connect to Hub         │◄──►  (or Per-App VPN)               │   │ │
│  │   │  - mTLS handshake (Root PKI)     │   └──────────────────────────────────┘   │ │
│  │   │  - TPAN Control Protocol         │             ▲                             │ │
│  │   └──────────────┬──────────────────┘             │                             │ │
│  └──────────────────┼─────────────────────────────────┼─────────────────────────────┘ │
│                     │ Bluetooth SPP (RFCOMM)          │ App traffic (SOCKS5H)          │
└─────────────────────┼─────────────────────────────────┼───────────────────────────────┘
                      │                           ┌──────┴──────────────────────┐
                      │                           │  DTS Applications           │
                      │                           │  (SITAWARE, KATIM apps)     │
                      ▼                           └─────────────────────────────┘
              tpan-bt-manager (Hub)
              192.168.101.35
```

## Repository Structure

```
service-tpan-android/
├── AGENT.md                        # Agentic porting guidance (from tpan-over-bt)
├── app/
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── cpp/
│   │   │   │   ├── CMakeLists.txt
│   │   │   │   ├── tpan_client.cpp     # C++ TPAN client core (RFCOMM + mTLS)
│   │   │   │   └── socks5h_server.cpp  # SOCKS5H proxy server
│   │   │   └── java/com/katim/dts/tpan/
│   │   │       ├── TpanService.kt      # Android foreground service
│   │   │       ├── TpanBootReceiver.kt # Auto-start on boot
│   │   │       └── TpanManager.kt      # JNI bridge + connection state
│   │   └── test/
├── build.gradle.kts
└── settings.gradle.kts
```

## Porting Reference

The prototype to port from is in the TNN repository:
[`tpan-over-bt`](https://bitbucket.katim.com/projects/TNN/repos/tpan-over-bt/browse)

Key files to port:
- `tpan-bt-client/` → `app/src/main/cpp/tpan_client.cpp`
- SOCKS5H server → `app/src/main/cpp/socks5h_server.cpp`

See `AGENT.md` in both repos for porting guidance. The C++ core builds via Android NDK (CMake).

## Build

```bash
./gradlew assembleDebug
```

NDK must be installed. See `app/src/main/cpp/CMakeLists.txt` for C++ build configuration.

## Deployment

| EUD | Transport | Notes |
|-----|-----------|-------|
| TT (Tactical Terminal) | BT SPP (RFCOMM) | When USB not connected |
| TWT (Wrist Terminal) | BT SPP (RFCOMM) | When USB not connected; no PANU dependency |

The service auto-starts on boot via `TpanBootReceiver` and is a foreground service with a persistent notification.

## Status

| Component | Status | Notes |
|-----------|--------|-------|
| TpanService (Android) | Scaffolded | Foreground service skeleton |
| TpanBootReceiver | Scaffolded | Boot auto-start |
| tpan_client.cpp (NDK) | Stub | Port from `tpan-over-bt` |
| socks5h_server.cpp (NDK) | Stub | Port from `tpan-over-bt` |
| mTLS (Root PKI) | Planned | Integrate with DTS PKI |
| Per-App VPN mode | Planned | Alternative to SOCKS5H for transparent proxying |
