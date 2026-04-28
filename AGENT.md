# AGENT.md - service-tpan-android

## Design Reference

[`docs/swad-dts/designs/svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)

This module is now a DTS/TWT client-side reference for the TNN access
alignment. Bluetooth RFCOMM/SPP code in this tree is working reference material
for a TNN-owned IP-over-SPP bearer, especially for TWT if TNN resources allow.
New work must preserve the TAP/L2 client model and align the real bearer with
the TNN-owned multi-bearer EUD access service agreed with JC/TNN. TPAN is the
TNN device authentication/authorization framework unless JC/TNN explicitly
names the data plane TPAN.

## Architecture Summary

`service-tpan-android` is a Kotlin Android service with a thin JNI/NDK layer
that provides transparent Layer 2 Ethernet tunneling between the EUD (TT/TWT)
and the Hub. The architecture uses:

- **Root TAP device via JNI** - creates a TAP interface (`tpan0`) with the
  EUD's stable IP (`.10` for TT, `.20` for TWT) via `TUNSETIFF`. Requires root
  or `CAP_NET_ADMIN`. Supports L2 multicast required by SITAWARE/STC COP
  distribution.
- **3-byte frame codec** - wraps Ethernet frames for transport
  (`DATA`/`KEEPALIVE`/`SHUTDOWN`).
- **BT transport layer** - RFCOMM SPP is the current working reference for a
  small IP-over-SPP bearer; production ownership belongs to TNN.
- **Bearer monitor** - current code is fixed `USB > BT`; target TNN behavior
  must be supplied by the TNN-owned access contract across UWB and/or BT SPP.
- **Provisioning store** - internal active/previous/staging persistence for
  `UsbCommissionRecord`.
- **USB commission client** - stable USB detection plus
  `POST /api/v1/commission/usb`.

The NDK layer is minimal: a single C file (`tap_jni.c`) opens `/dev/net/tun`
and issues the `TUNSETIFF` ioctl with `IFF_TAP | IFF_NO_PI`.

## Key Constraints

1. **Bluetooth is TNN-owned in production.** The RFCOMM/SPP path is valid
   reference code for IP-over-SPP, but production bearer policy and ownership
   belong to TNN.
2. **USB takes priority.** TAP deactivates when the USB interface is stable.
   Target wireless behavior is owned by the TNN access platform contract.
3. **Multicast is mandatory.** The target path, including BT SPP/IP-over-SPP if
   enabled by TNN, must carry multicast/IGMPv3 for SITAWARE/STC COP and
   radio-network use cases.
4. **Foreground service.** Android 12+ requires persistent notification and
   `FOREGROUND_SERVICE_CONNECTED_DEVICE` type.
5. **Boot auto-start.** `TpanBootReceiver` starts service if a valid commission
   record exists.
6. **Mission-agnostic runtime.** No callsign, mission ID, bundle import, or
   mission overlay is part of the access layer.

## Build

```bash
./gradlew assembleDebug
```

Development builds use the Gradle path above.

Production image integration should follow the AOSP path:

```bash
m TpanService
```

The production build is expected to be platform-signed, privileged, and paired
with `privapp-permissions-tpan.xml`.
