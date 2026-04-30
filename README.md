# service-tpan-android

> **Status: prototype / stalled.**
>
> This Android service is retained as historical client-side prototype code. It
> is not the current KATIM TNN access implementation and must not be used as the
> architecture contract, supplier acceptance baseline, or production Android
> delivery plan.

## Current Architecture Boundary

The current access architecture is documented in
[`svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md):

- USB remains the primary wired bearer.
- UWB/TAP, or a TNN-equivalent transparent data plane, is the target wireless
  access path.
- Android management should be provided through platform-owned hooks, HAL, or a
  privileged helper.
- TPAN is treated as TNN-owned authentication/authorization.

## Historical Value

This repository may still be useful for archaeology around:

- creating a TAP interface from privileged Android code,
- JNI use of `/dev/net/tun` and `TUNSETIFF`,
- foreground-service persistence patterns, and
- simple Ethernet-frame wrapping experiments.

The old Bluetooth transport, commissioning, and bearer-arbitration logic is not
target architecture.

## Maintenance Rule

Only make changes here for one of these reasons:

- preserving buildability for archaeology,
- extracting a narrowly reviewed TAP/JNI idea into a platform-owned design,
- adding a deprecation note, or
- deleting/archiving the repository when the project owner approves it.

No new feature work should be started in this repository.
