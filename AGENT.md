# AGENT.md - service-tpan-android

## Design Reference

[`docs/swad-dts/designs/svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)

This module is now a DTS/TWT client-side reference for the TNN access
alignment. Bluetooth RFCOMM/SPP code in this tree is a deprecated
risk-reduction prototype, not the target KATIM TNN access contract. New work
must preserve the TAP/L2 client model and align the real bearer with the
TNN-owned multi-bearer EUD access service agreed with JC/TNN. TPAN is the TNN
device authentication/authorization framework unless JC/TNN explicitly names
the data plane TPAN.

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
- **Prototype BT transport layer** - RFCOMM SPP exists only as a deprecated
  reference path.
- **Bearer monitor** - current code is fixed `USB > BT`; target TNN behavior
  must be supplied by the TNN-owned UWB access contract.
- **Provisioning store** - internal active/previous/staging persistence for
  `UsbCommissionRecord`.
- **USB commission client** - stable USB detection plus
  `POST /api/v1/commission/usb`.

The NDK layer is minimal: a single C file (`tap_jni.c`) opens `/dev/net/tun`
and issues the `TUNSETIFF` ioctl with `IFF_TAP | IFF_NO_PI`.

## Key Constraints

1. **No Bluetooth-over-IP target.** The RFCOMM path is historical only; do not
   expand it as the TNN target.
2. **USB takes priority.** TAP deactivates when the USB interface is stable.
   Target wireless behavior is owned by the TNN access platform contract.
3. **Multicast is mandatory.** The target path must carry multicast/IGMPv3 for
   SITAWARE/STC COP and radio-network use cases.
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
