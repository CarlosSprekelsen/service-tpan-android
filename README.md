# service-tpan-android

Android TPAN client service for TT and TWT EUDs.

`service-tpan-android` is a mission-agnostic foreground service that persists
USB commission state, bonds to the Hub over Bluetooth, and provides the TPAN
framed Ethernet data path through a root TAP device (JNI). USB is always preferred over
Bluetooth in this pass; UWB and WiFi remain future transports.

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
|   +-- bearer/BearerMonitor.kt           # Fixed USB > BT bearer behavior
|   +-- codec/Frame*.kt                   # 3-byte TPAN framing
|   +-- provision/
|   |   +-- UsbCommissionRecord.kt        # Persisted commission payload
|   |   +-- RuntimeTpanConfig.kt          # Effective runtime view
|   |   +-- ProvisioningStore.kt          # active/previous/staging internal store
|   |   +-- UsbNetworkMonitor.kt          # Stable USB detection
|   |   +-- UsbCommissionClient.kt        # POST /api/v1/commission/usb
|   |   +-- BondManager.kt                # Bond maintenance and pairing assist
|   |   +-- LocalBluetoothIdentityProvider.kt
|   +-- transport/BtTransport.kt          # RFCOMM SPP transport
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

## Bearer and Pairing Rules

- Effective bearer behavior is fixed to `USB > BT`.
- When USB is stable, VPN is deactivated and traffic stays on USB.
- When USB drops, the service activates the TPAN VPN and reconnects over BT.
- Same-MAC re-commission rotates the stored passkey even if the bond is kept.
- If the bond is already present, the service does not force `createBond()`.
- If the bond is missing, pairing uses the most recently stored passkey.

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
| BT transport | Implemented | RFCOMM SPP with reconnect handled above the transport |
| Connection Manager | Implemented | KEEPALIVE, SHUTDOWN, reconnect backoff |
| UWB transport | Planned | Not implemented in this pass |
| WiFi transport | Planned | Not implemented in this pass |
| Root PKI mTLS | Out of scope | Not part of this pass |

## References

- [`svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)
- [`icd-footprint-android-api.md`](../../docs/swad-dts/specifications/icd-footprint-android-api.md)
