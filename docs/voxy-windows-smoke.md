# Voxy 26.3 Windows smoke checklist — sealed legacy
> **SEALED LEGACY — mods/voxy only, superseded by docs/todo/jbp-lod.md.** 本文完全撤销为当前验收清单；下列矩阵、命令、V0 路径、日志与 Pass/Fail 条件均是旧 Voxy 的历史记录，不可执行、不可复制为当前 Vulkan/JBP-LOD 验收。当前唯一计划见 [JBP-LOD TODO](todo.md)。

**SEALED / LEGACY (ADR 0036).** This checklist is not a runnable PC Voxy acceptance test. `mods/voxy` is read-only reference code; its GL core and Vulkan guard remain unchanged. PC launch is Vulkan-only, so old Voxy is expected to fail at its guard and is explicitly excluded from the PC manifest by the 2026-10-18 cutover. The new JBP-LOD project is a separate, not-yet-implemented mod; its current work and acceptance plan are in [`docs/todo/jbp-lod.md`](todo.md) and [`voxy-vulkan-core-design.md`](voxy-vulkan-core-design.md). This checklist's old V0 steps are sealed historical records and do not correspond to the new JBP-LOD V0–V6 plan; no current Voxy support is claimed.

## Test matrix

- Minecraft **26.3**.
- Fabric Loader **0.19.5** and Fabric API **0.162.0+26.3**.
- Java **25**.
- Voxy **0.2.20-beta**, built from the `263` baseline at `247adf2625324782e13a3fa8b646c0f95e68031b` with the JBP node-storage patch.
- JubaopenCraft Fabric client mod **0.1.0** (exposes `JbpNodeIdProvider.currentNodeId()`).
- Use a Windows GPU/driver that can run Minecraft 26.3 with Vulkan. Sodium **0.9.2+mc26.3** is the pinned renderer studied for the sealed Voxy path. Iris/Vivecraft are not relevant to a current Voxy pass.

## Historical launcher observation (withdrawn; not a current acceptance checklist)

The former launcher inspection and its expected Vulkan settings are retained only as historical context. Do not execute these steps or use them as current acceptance: the present launcher contract is maintained outside this sealed Voxy record, and this checklist cannot establish a JBP-LOD pass. The old Voxy entry was removed from the PC manifest; current PC LOD acceptance is defined only by the JBP-LOD V0–V6 plan.

All former world-join, Node A/B, and cache steps below describe the sealed Voxy V0 patch only. They are historical evidence, not executable steps for the current Vulkan-only client or contracts/phases for JBP-LOD V0–V6.
## Historical V0 log signatures (not current expected logs)

These old signatures were recorded for the sealed Voxy implementation only; do not use them to claim JBP-LOD behavior or Vulkan support:

```text
[Voxy] JBP node=<node_id> storage=<data_dir>\cache\voxy\nodes\<node_id>
```

`<node_id>` must be the target platform node ID, not the proxy address. If the node is unknown at join time, Voxy emits once:

```text
[Voxy] Inactive: multiplayer node id is unavailable
```

and stays disabled for that session. Any `Failed to query JBP node id` line is a failure to call `JbpNodeIdProvider`.

## Historical V0 cache layout (not JBP-LOD contract)

With `-Djbp.voxy.dir` set, the expected outer root is:

```text
<data_dir>\cache\voxy\nodes\<node_id>\
```

The sealed Voxy storage layout below documents the old implementation only; it is not a current acceptance step or a JBP-LOD property/path decision.

## Historical Voxy pass/fail record (withdrawn; not current acceptance)

The former pass/fail criteria applied only to the sealed Voxy/launcher check at the time of that record. They are not current acceptance criteria and must not be run or used to claim current launcher or JBP-LOD support. Current LOD acceptance is exclusively defined by [JBP-LOD TODO](todo.md); it remains unverified until that plan has evidence.

The historical WSLg/llvmpipe OpenGL run is not Windows acceptance and does not establish Vulkan support. PC LOD acceptance remains unverified under the JBP-LOD plan.
