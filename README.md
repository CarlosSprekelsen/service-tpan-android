# service-tpan-android

Android EUD access reference service for TT and TWT EUDs.

> **Alignment status.**
>
> This module is the DTS/TWT client-side reference for a privileged Android
> system service that can create and route through a TAP interface. The earlier
> Bluetooth RFCOMM/SPP path is working reference material for IP-over-SPP and is
> not retired for KATIM TNN. The target KATIM TNN architecture is TNN-owned
> multi-bearer EUD access over UWB and/or BT SPP with TAP/L2 or an equivalent
> transparent data plane, plus any bearer policy JC/TNN freezes. TPAN is the TNN device
> authentication/authorization framework unless JC/TNN explicitly names the
> data plane TPAN.
> INVISIO wireless continuity is handled by the agreed interim WiFi profile,
> not by this reference Bluetooth-over-IP container path.

`service-tpan-android` is a mission-agnostic foreground service that persists
USB commission state and provides the framed Ethernet data path through a
root TAP device (JNI). Current code contains the Bluetooth RFCOMM prototype
transport as TNN-shareable reference code; the target TNN
integration must bind the same TAP and framing model to the TNN-owned access
data-plane contract.

## Package identity

- Android package: `com.katim.dts.service.tpan`
- Main service class: `com.katim.dts.service.tpan.TpanService`
- Boot receiver: `com.katim.dts.service.tpan.TpanBootReceiver`

## Build model

- Development path: Gradle app module for local debug and unit-test workflows
- Production path: AOSP-integrated privileged app via `Android.bp`
- Production signing: shared DTS platform certificate (`certificate: "platform"`)
- Production permission policy: `privapp-permissions-tpan.xml`

Authoritative design reference:
[`docs/swad-dts/designs/svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)

## Architecture

```text
+-------------------------------- EUD --------------------------------+
|                                                                     |
|  +---------------------------------------------------------------+  |
|  | TpanService                                                   |  |
|  |                                                               |  |
|  |  UsbNetworkMonitor  -> UsbCommissionClient -> Provisioning    |  |
|  |          |                                   Store            |  |
|  |          v                                                     |  |
|  |     BondManager  -> BtTransport -> ConnectionManager -> TAP  |  |
|  |                                            ^            |      |  |
|  |                                            +-- FrameCodec --+  |  |
|  +---------------------------------------------------------------+  |
|                                                                     |
+---------------------------------------------------------------------+
```

## Repository Structure

```text
service-tpan-android/
+-- app/src/main/java/com/katim/dts/service/tpan/
|   +-- TpanService.kt                    # Foreground service orchestrator
|   +-- TpanBootReceiver.kt               # Boot start from commission state
|   +-- ConnectionManager.kt              # KEEPALIVE, SHUTDOWN, reconnect backoff
|   +-- bearer/BearerMonitor.kt           # Prototype USB > BT bearer behavior
|   +-- codec/Frame*.kt                   # 3-byte access framing
|   +-- provision/
|   |   +-- UsbCommissionRecord.kt        # Persisted commission payload
|   |   +-- RuntimeTpanConfig.kt          # Effective runtime view
|   |   +-- ProvisioningStore.kt          # active/previous/staging internal store
|   |   +-- UsbNetworkMonitor.kt          # Stable USB detection
|   |   +-- UsbCommissionClient.kt        # POST /api/v1/commission/usb
|   |   +-- BondManager.kt                # Bond maintenance and pairing assist
|   |   +-- LocalBluetoothIdentityProvider.kt
|   +-- transport/BtTransport.kt          # RFCOMM SPP IP-over-SPP reference transport
|   +-- tap/TapEngine.kt                  # Root TAP device lifecycle (JNI)
|   +-- vpn/VpnEngine.kt                  # DEPRECATED — retained for git history
+-- app/src/test/java/com/katim/dts/service/tpan/
    +-- codec/FrameCodecTest.kt
    +-- provision/UsbCommissionRecordTest.kt
    +-- provision/ProvisioningStoreTest.kt
```

## Runtime Model

The service runs from `UsbCommissionRecord` only. There is no mission bundle,
overlay, callsign, mission ID, or `autoStartTpan` flag.

Persisted commission state contains:

- `hub.btMac`
- `hub.profileUuid`
- `pairingPasskey`
- `localEud.btMac`
- `localEud.role`
- `localEud.tapAddress`
- `commissionedAt`

Boot behavior:

- If a valid active commission record exists, `TpanBootReceiver` starts the service.
- If the active record is broken but `previous` is valid, the store rolls back on boot.
- If only `staging` is valid after an interrupted write, the store promotes it on boot.

## USB Commissioning

When the EUD sees a stable USB network, it:

1. Resolves the local BT MAC.
2. Calls `http://192.168.101.35:8002/api/v1/commission/usb` over that USB `Network`.
3. Persists the returned commission record internally.
4. Removes the old Hub bond if the Hub identity changed.
5. Initiates pairing only when the Hub bond is missing.
6. Rebuilds runtime state from the persisted commission record.

Retry behavior:

- Immediate attempt on stable USB-up and on service start if USB is already present.
- Exponential backoff while USB remains up: `1s -> 2s -> 4s -> 8s -> 16s -> 30s`.

Dev fallback:

- Production/privileged builds use the real local BT adapter MAC.
- Non-privileged builds require `/sdcard/DTS/tpan-dev/local-bt-mac.txt`.
- Without a real local BT MAC, the service stays in USB-active commission retry mode and logs the requirement.

## Bearer and Platform Rules

- Current prototype bearer behavior is fixed to `USB > BT`; this remains useful
  reference behavior, but production priority and ownership belong to TNN.
- Target TNN access behavior must be agreed with JC/TNN, with UWB and/or BT SPP
  carrying the TAP/L2 data plane, or an equivalent transparent data plane, and
  multicast/IGMPv3 mandatory.
- When USB is stable, VPN is deactivated and traffic stays on USB.
- When USB drops, the service activates the access TAP. The active wireless
  transport must be supplied by the TNN platform contract.
- Same-EUD re-commission can rotate stored credentials without forcing a new
  pairing/session if the platform trust state is still valid.

## Build and Test

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The local development path requires the Android NDK for the TAP JNI bridge in
`app/src/main/cpp/`.

Production DTS image integration uses:

```bash
# In AOSP root
m TpanService
```

Production integration notes:

- Include `TpanService` through `PRODUCT_PACKAGES`.
- Deploy `privapp-permissions-tpan.xml` under `etc/permissions/`.
- Sign the production image build with the shared DTS platform certificate.

The Gradle path remains the development workflow. The production delivery path is the
AOSP-integrated privileged app.

## Status

| Component | Status | Notes |
| ---- | ---- | ---- |
| TpanService | Implemented | Mission-agnostic service lifecycle and runtime restart |
| TpanBootReceiver | Implemented | Starts from valid commission state only |
| ProvisioningStore | Implemented | Internal active/previous/staging persistence with boot recovery |
| UsbNetworkMonitor | Implemented | Stable USB detection |
| UsbCommissionClient | Implemented | Hub USB commission POST over bound `Network` |
| LocalBluetoothIdentityProvider | Implemented | Privileged MAC path plus dev override |
| BondManager | Implemented | Hub bond replacement and pairing confirmation |
| VPN Engine | Implemented | Stable TUN addressing and framed IP relay |
| BT transport | Reference implementation | RFCOMM SPP IP-over-SPP path for TNN review/adaptation |
| Connection Manager | Implemented | KEEPALIVE, SHUTDOWN, reconnect backoff |
| UWB transport | Target contract | To be provided by the TNN-owned access platform service; TPAN may authorize the path |
| WiFi transport | Planned | Not implemented in this pass |
| Root PKI mTLS | Out of scope | Not part of this pass |

## References

- [`svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)
- [`icd-footprint-android-api.md`](../../docs/swad-dts/specifications/icd-footprint-android-api.md)
