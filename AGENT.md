# AGENT.md - service-tpan-android

## Status

This repository is a prototype/stalled Android EUD access experiment. It is not
the current KATIM TNN access implementation.

Use [`docs/swad-dts/designs/svc-tpan-architecture.md`](../../docs/swad-dts/designs/svc-tpan-architecture.md)
as the architecture authority.

## Maintenance Guidance

- Do not add new feature work here.
- Do not present the Bluetooth transport or old bearer monitor as target
  architecture.
- If touching this tree, keep changes limited to deprecation, build
  preservation, or extraction of a narrowly reviewed TAP/JNI idea into a new
  platform-owned design.
- Current target access is USB first, then TNN-owned UWB/TAP or an equivalent
  transparent data plane managed through Android platform hooks.
