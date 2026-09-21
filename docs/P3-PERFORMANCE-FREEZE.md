# P3 PERFORMANCE FREEZE

Status: **COMPLETE — FROZEN**

Frozen: 2026-09-21

## Completed Phases

| Phase   | Commit   | Description                                      |
|---------|----------|--------------------------------------------------|
| P3-0    | `7775f08` | Remove Router member N+1 queries                 |
| P3-A1   | `fefad94` | Avoid redundant connection history eviction scans |
| P3-B1-A | `f22a0a9` | Gate current-outbound JNI queries when no consumer|
| P3-B1-B | `c87d907` | Short-circuit zero-delta steady-state traffic     |
| P3-B2   | `601420e` | Skip unchanged request snapshot serialization     |
| P3-D1   | `8d607cc` | Batch traffic stats across JNI                    |
| P3-D2   | `32bc899` | Merge request revision and snapshot bridge        |
| P3-D3   | `197e0a9` | Batch group selection queries                     |
| P3-D4   | `107c3d6` | Simplify steady-state traffic application         |
| P3-D5   | `16f2243` | Suspend dormant traffic loop                      |

## Hardening

| Item                            | Result |
|---------------------------------|--------|
| P3-D Review                     | PASS   |
| P3-D Final Hardening (`fd9bb64`)| PASS   |
| Upgrade compatibility           | GOOD   |

## Structural Metrics (Before → After)

```
Traffic:
  N JNI stats queries per tick  →  1 native snapshot per tick

Requests (changed poll):
  2 JNI + 2 live scans  →  1 JNI + ≤1 live scan

Group selection:
  N JNI per group  →  1 batch JNI when needed, 0 when idle

Idle:
  no consumers  →  dormant/suspended (no periodic wake)
```

---

## Permanent Invariants

### INV-P3-01 — NO ROUTER MEMBER N+1

Router member snapshot must use `routerMemberDao.all()` → group/index once → enabled routers filter.
Restoring `N routers → N × routerMemberDao.getByRouter()` is prohibited.
`userOrder` semantics, dangling member behavior, and shared proxy behavior must be preserved.

### INV-P3-02 — NO REDUNDANT HISTORY EVICTION

Existing connection updates must not trigger full eviction scans.
Eviction remains for new flow / snapshot scenarios only.

### INV-P3-03 — GROUP SELECTION JNI ≤ 1 WHEN NEEDED

With consumers: N groups → 1 batch native/JNI selection query.
Without consumers: 0 selection queries.
Per-group JNI loops and idle batch queries are both prohibited.

### INV-P3-04 — ZERO-DELTA STEADY STATE SHORT CIRCUIT

When no traffic delta, rates already zero, and selection unchanged:
no full traffic aggregation, no TrafficData allocation, no Binder traffic updates,
no unnecessary speed object building, no unnecessary list/map construction.

### INV-P3-05 — ONE TRAFFIC SNAPSHOT PER TICK

Normal TrafficLooper tick: N tags → 1 native traffic snapshot → 1 Kotlin apply pass.
Traffic JNI calls per collection tick ≤ 1 (excluding explicit fallback/recovery/debug paths).
Legacy per-tag QueryStats must not become the normal path.

### INV-P3-06 — TRAFFIC ACCOUNTING EXACTLY ONCE

rx, tx, direct traffic, selector traffic, Router member traffic, Router winner switch,
traffic reset, and final stop flush must maintain exactly-once accounting.
No lost traffic, no double counting, no stale profile mapping,
no old runtime counter writes to new runtime.

### INV-P3-07 — ONE REQUEST SNAPSHOT JNI / ONE LIVE SCAN

Requests polling must use `ConnectionSnapshotSince(lastRevision)` → 1 JNI → ≤ 1 live scan → JSON only when changed.
Restoring `revision JNI → live scan → snapshot JNI → second live scan → JSON` is prohibited.

### INV-P3-08 — REQUEST SESSION FIRST FRAME GUARANTEED

New observer session starts with `lastRevision = null`.
Must always receive a complete first frame regardless of native revision state.
First frame, stop/start, reconnect, runtime generation change, snapshot failure retry,
and unchanged skip behaviors must all be preserved.

### INV-P3-09 — NO CROSS-RUNTIME SNAPSHOT DELIVERY

All async results (Traffic / Requests / Selection) must belong to the current runtime generation.
Late results from a previous runtime must be discarded.
Old Box data must never pollute new Box state.

### INV-P3-10 — NO IDLE PERIODIC WAKE WITHOUT CONSUMER

When no consumers exist (foreground speed, notification speed, profile traffic statistics,
Router/URLTest accounting), TrafficLooper must suspend, not wake periodically.

### INV-P3-11 — DORMANT LOOP MUST WAKE ON DEMAND

The following events must wake a dormant loop:
Activity foreground, notification speed enabled, profile traffic statistics enabled,
ProxyInstance replacement, reconnect, Router accounting requirement change,
explicit update request. Idle optimization must not cause permanent sleep.

### INV-P3-12 — NO UNCONDITIONAL BINDER BROADCAST

Must maintain foreground gating, change gating, batching, and zero-delta suppression.
`every tick → unconditional Binder broadcast` is prohibited.

### INV-P3-13 — SING-BOX DEPENDENCY CONTAINMENT

Android/Kotlin layer must not directly depend on: `adapter.Outbound`, `adapter.OutboundManager`,
`trafficcontrol.Manager`, `v2rayapi.StatsService`, `group.Selector`, `group.URLTest`, `N.Dialer`.
These dependencies must be contained in the libcore compatibility/bridge layer.
Future sing-box upgrades (1.15 → 1.16 → 1.17+) should only modify bridge modules.

### INV-P3-14 — NO SING-BOX INTERNAL FORK

Prohibited: patching sing-box upstream, copying `trafficcontrol.Manager` / `StatsService` /
group runner, reflecting private fields, unsafe access, `go:linkname`.
If public API changes, modify the compatibility bridge only.

---

## Reopen Conditions

P3 may only be reopened for:

**A. Performance regression** — idle CPU rise, background wake frequency increase,
JNI count regression, significant GC increase, or TrafficLooper becoming a profiling hotspot.
Must have profiling/instrumentation evidence.

**B. Functional regression** — traffic loss/duplication, Requests not refreshing,
Router winner stale, post-reconnect data corruption, or dormant loop unable to wake.

**C. sing-box upgrade** — if a sing-box version upgrade changes bridge API,
execute `P3 SING-BOX COMPATIBILITY AUDIT` first. Do not redesign P3.

### Not valid reopen reasons

- "Theoretically could save one more allocation"
- "Could probably be a bit faster"
- "Want to make the code cooler"
- "A loop could be a few lines shorter"

---

## Prohibited Future Work (unless reopen conditions met)

P3-E, P3-F, traffic zero-copy JNI, native shared memory, direct ByteBuffer hacks,
per-packet revision, per-read/write atomic revision, persistent polling worker,
aggressive cache layering, speculative precomputation, further micro-allocation rewrites.

Development goal: **long-term stable operation**.
