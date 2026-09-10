# 当前独立审计

reviewer：ChatGPT Web via GitHub fixed-SHA。
work_order：S1-B1-KV-LINEARIZABILITY。
状态：CHANGES_REQUIRED 已修正并重推候选，等待新审计。

## 最新审计

- repo：`Gitefy/NekoBoxForAndroid`。
- branch：`fix/p01-room-off-main-thread`。
- work_order：`S1-B1-KV-LINEARIZABILITY`。
- base_code_sha：`cc63f24893696c075723eb8934529534529f31e2`。
- candidate_code_sha（已审）：`a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c`。
- handoff_metadata_sha（对应）：`115615d10c938adacf6b236d5edc57fa67f3e60d`。
- review_scope：`cc63f248..a34a0cf`。
- verdict：`CHANGES_REQUIRED`。
- critical_count：0；p1_count：1（`P1-SNAPSHOT-ORDERING`）；p2_count：0。
- ChatGPT 是否直接读取 GitHub 差异：是（fixed-SHA）。
- 结论来源：源码 + L1（ChatGPT 确认 stale-ACK 场景已覆盖；concurrent snapshot ordering 场景在 a34a0cf 上未覆盖）。

## Findings（a34a0cf）

- Accepted：per-key generation 正确阻止 stale put ACK 回退/复活；production put/delete 传播 generation；readEpoch 保护本地提交不被读取起点早于它的 snapshot 回滚；原有 focused stale-ACK 回归正确。
- Blocking P1-SNAPSHOT-ORDERING：`RoomPreferenceDataStore.readAndMergeSnapshot` 仅做 `captureReadEpoch→tableSnapshot→merge`，未串行化完整 read+merge；`A(capture 0)+A(read old)` 先于 `B(capture 0)+B(read new)+B(merge new)` 的交错中，A 后续 `merge old` 可在无 pending/lastCommitted 时覆盖 new，进程可在无后续 invalidation 时长期 stale。要求：用一个 snapshot lock/coordinator 串行化 capture+read+merge，不扩 executor、不改重试、不加 delay/sleep，不做复杂 version clock。

## 本次修正后新候选

- new candidate_code_sha：`e9b92cf79625c555ecd21b10991642b76037de71`（`a34a0cf..e9b92cf` 仅补 `RoomPreferenceDataStore.snapshotLock` + 1 定序并发回归测试 `RoomPreferenceDataStoreSnapshotOrderingTest`）。
- 完整可审范围：`cc63f248..e9b92cf`。
- 修正性质：最小串行化整改（`ReentrantLock snapshotLock` 包起来 `captureReadEpoch + tableSnapshot + merge`），保留全部 per-key generation、主线程不读 DB、重试语义。
- 证据：`run-e-red-concurrent-snapshot.txt`（a34a0cf 上 `expected:<[new]> but was:<[old]>`）；`run-f-green-focused-after-fix.txt`、`run-g-green-full-suite-after-fix.txt`（exit 0，29 suites 131 tests / 0 failures）。

## AUDIT_RECEIPT（a34a0cf）

```
AUDIT_RECEIPT
repo=Gitefy/NekoBoxForAndroid
branch=fix/p01-room-off-main-thread
work_order=S1-B1-KV-LINEARIZABILITY
base_code_sha=cc63f24893696c075723eb8934529534529f31e2
candidate_code_sha=a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c
handoff_metadata_sha=115615d10c938adacf6b236d5edc57fa67f3e60d
verdict=CHANGES_REQUIRED
critical_count=0
p1_count=1
p2_count=0
blocking_finding=P1-SNAPSHOT-ORDERING
review_scope=cc63f24893696c075723eb8934529534529f31e2..a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c
reviewer=ChatGPT Web via GitHub fixed-SHA audit
END_AUDIT_RECEIPT
```

## required_next_action

Keep S1-B1 open（已执行）；最小修正串行化整个 snapshot read+merge 路径（已用 `snapshotLock.withLock` 实现）；追加 CountDownLatch 定序并发 snapshot 回归并演示 RED→GREEN（已完成）；跑 focused + 全量 `testOssDebugUnitTest`（已完成 exit 0）；提交新 candidate `e9b92cf` 并 push 同非 main 分支（已完成）；独立 metadata commit 更新 HANDOFF/STATUS/REVIEW（本 commit），返回新 base/candidate 并 WAIT_AUDIT。禁止开启 S1-B2，禁止用 delay/sleep/扩线程。

## 完整归档

原始 receipt 文本已完整保留于本文件；如需独立 `docs/agent/reports/` 归档，可原样复制。
