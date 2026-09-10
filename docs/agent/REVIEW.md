# 当前独立审计

reviewer：ChatGPT Web via GitHub fixed-SHA。
work_order：S1-B1-KV-LINEARIZABILITY — **已关闭（ACCEPTED）**；S1-B2-CONFIRM-SEMANTICS-CLOSURE 进行中。

## 审计一（a34a0cf）

- scope `cc63f248..a34a0cf`，verdict `CHANGES_REQUIRED`（P1-SNAPSHOT-ORDERING），完整 receipt 与修正记录见下方审计二的前文与 `docs/agent/evidence/s1-b1/`。该 P1 已由候选 `e9b92cf` 修正。

## 审计二（e9b92cf）— 最终 ACCEPTED

- repo：`Gitefy/NekoBoxForAndroid`。
- branch：`fix/p01-room-off-main-thread`。
- work_order：`S1-B1-KV-LINEARIZABILITY`。
- base_code_sha：`cc63f24893696c075723eb8934529534529f31e2`。
- candidate_code_sha：`e9b92cf79625c555ecd21b10991642b76037de71`。
- previous_candidate_sha：`a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c`。
- handoff_metadata_sha：`d499eaa297cd768605c9c77b35f77f8c2c3c6ec3`。
- review_scope：`cc63f248..e9b92cf`；incremental_fix_scope：`a34a0cf..e9b92cf`。
- verdict：**ACCEPTED**。critical_count=0；p1_count=0；p2_count=1。
- ChatGPT 直接读取 GitHub 固定 SHA 差异：是。
- 结论来源：源码 + L1（本地 JVM 定点测试）；L2/L3/L4 未执行（阶段门槛另计）。

### Accepted findings（摘要，原文见 receipt）

1. per-key generation 阻止 stale ACK 回退被取代的 put。
2. per-key generation 阻止 stale put ACK 复活其后的 delete。
3. snapshot readEpoch 阻止 "读取起点早于本地提交" 的快照回滚该本地提交。
4. a34a0cf 的 P1-SNAPSHOT-ORDERING 已修复：`RoomPreferenceDataStore` 用单一 `snapshotLock` 串行化完整 `captureReadEpoch + tableSnapshot + merge`；两个并发 sibling snapshot 不可能乱序 merge。
5. 单 writer 持久化与重试语义不变；无 executor 扩容、无生产 delay 同步、无 schema 变更、无无关重构；候选范围收敛于 `RoomPreferenceDataStore` + 其定序回归测试。

### Non-blocking finding（P2-TEST-ROBUSTNESS，记录不阻塞）

`RoomPreferenceDataStoreSnapshotOrderingTest` 的关键交错用 `CountDownLatch/AtomicReference`，但仍含 `Thread.sleep(80)` 供线程 B 争抢 `snapshotLock`。不影响生产修复、不阻塞验收；RED 行为在极端调度延迟下非数学完全确定。**记录为后续 test-infrastructure 清理项，不得为删 sleep 扩大生产设计。** 已登记 `docs/agent/ISSUES.md` S1 遗留（见 WORK_ORDER S1-B2 范围外备注）。

### ci_status

审计期间未见 candidate `e9b92cf` 的 GitHub commit status/check。**ACCEPTED 不等于 GitHub CI 通过**；执行证据以 handoff 本地测试记录为准。原因与 A08 一致：`ci.yml` push `branches: '*'` 不匹配含 `/` 的分支名（`fix/p01-room-off-main-thread`），push 未触发 CI——已列为 S1-B2 工作单修复项。

### required_next_action（已执行）

1. 关闭 S1-B1 为 ACCEPTED（本文件 + STATUS/HANDOFF，metadata only）。
2. 按 roadmap 签发唯一下一工作单 `S1-B2-CONFIRM-SEMANTICS-CLOSURE`（WORK_ORDER.md）。
3. S1-B1 不变量成为后续 settings/database 工作的回归约束（KvMemoryCacheTest / KvMemoryCacheLinearizabilityTest / RoomPreferenceDataStoreSnapshotOrderingTest 为约束锚点，写入 WORK_ORDER）。

## AUDIT_RECEIPT（最终，e9b92cf）

```
AUDIT_RECEIPT
repo=Gitefy/NekoBoxForAndroid
branch=fix/p01-room-off-main-thread
work_order=S1-B1-KV-LINEARIZABILITY

base_code_sha=cc63f24893696c075723eb8934529534529f31e2
candidate_code_sha=e9b92cf79625c555ecd21b10991642b76037de71
previous_candidate_sha=a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c
handoff_metadata_sha=d499eaa297cd768605c9c77b35f77f8c2c3c6ec3

verdict=ACCEPTED

critical_count=0
p1_count=0
p2_count=1

accepted_findings=

* Per-key generation prevents stale ACK from regressing a superseding put.
* Per-key generation prevents stale put ACK from resurrecting a subsequent delete.
* Snapshot readEpoch prevents a snapshot started before a later local commit from rolling that local commit back.
* The P1-SNAPSHOT-ORDERING defect from candidate a34a0cf is fixed.
* RoomPreferenceDataStore now serializes the complete captureReadEpoch + tableSnapshot + merge operation with one snapshotLock.
* Two sibling snapshot refreshes therefore cannot merge out of read order.
* Existing single-writer persistence and retry semantics remain unchanged.
* No executor expansion, production delay-based synchronization, database schema change, or unrelated refactor was introduced.
* The code candidate commit is scoped to RoomPreferenceDataStore plus its snapshot-ordering regression test.

non_blocking_finding=P2-TEST-ROBUSTNESS

non_blocking_detail=
RoomPreferenceDataStoreSnapshotOrderingTest uses CountDownLatch/AtomicReference for the important interleaving, but still contains Thread.sleep(80) to give thread B time to contend for snapshotLock.

This does not invalidate the production fix and does not block S1-B1 acceptance. However, the RED behavior of the regression test is not mathematically fully deterministic under extreme scheduler delay. Record this for a later test-infrastructure cleanup; do not expand the current production design solely to remove the sleep.

ci_status=
No visible GitHub commit status/check was available for candidate e9b92cf during this audit. ACCEPTED therefore does not mean GitHub CI passed. Local test evidence recorded in the handoff remains the execution evidence.

required_next_action=
Close S1-B1-KV-LINEARIZABILITY as ACCEPTED.

Update STATUS/HANDOFF/REVIEW with this receipt in metadata only.

Then issue exactly one next work order according to the existing roadmap. Do not combine multiple architectural batches.

S1-B1 invariants must become regression constraints for all later settings/database work.

review_scope=cc63f24893696c075723eb8934529534529f31e2..e9b92cf79625c555ecd21b10991642b76037de71
incremental_fix_scope=a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c..e9b92cf79625c555ecd21b10991642b76037de71
reviewer=ChatGPT Web via GitHub fixed-SHA audit
END_AUDIT_RECEIPT
```

## 纪律

Cursor 的 SELF_REVIEW 不得写入本文件冒充独立审计。后续每次审计只针对一个固定 `base..candidate`。
