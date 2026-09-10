# 当前独立审计

reviewer：ChatGPT Web via GitHub fixed-SHA。
work_order：S1-B1 与 S1-B2 均 **已关闭（ACCEPTED）**；S1-B3-WRITE-QUEUE-DURABILITY-BARRIER 为 **REVISED DRAFT v2（DESIGN_CHANGES_REQUIRED 已修订，待一次最终设计确认）**；未实现。

## 审计一（a34a0cf）

scope `cc63f248..a34a0cf`，verdict `CHANGES_REQUIRED`（P1-SNAPSHOT-ORDERING）。P1 已由候选 `e9b92cf` 修正并复审计通过。

## 审计二（e9b92cf）— S1-B1 ACCEPTED

- scope `cc63f248..e9b92cf`；incremental `a34a0cf..e9b92cf`（snapshotLock 串行化）。
- verdict **ACCEPTED**（critical 0 / p1 0 / p2 1）。完整 receipt 见下文 AUDIT_RECEIPT(S1-B1)。
- P2-TEST-ROBUSTNESS（`SnapshotOrderingTest` 内 `Thread.sleep(80)`）→ 遗留至 test-infrastructure 清理批。
- ci_status 当时无 check（A08）。

## 审计三（1e140ca）— S1-B2 ACCEPTED

- base `e9b92cf79625c555ecd21b10991642b76037de71`；candidate `1e140ca720a54dfa42cc37235484af7faae499bf`；metadata `f6a2def`。
- verdict **ACCEPTED**（critical 0 / p1 0 / p2 0）+ **CI PASS**（GitHub Actions push 事件 head_sha=1e140ca，status=completed，conclusion=success）。
- persistent_regression_constraints 七条冻结；P2-TEST-ROBUSTNESS 继续排队。
- 完整 receipt 见下文 AUDIT_RECEIPT(S1-B2)。

## 设计评审（S1-B3 v1）— DESIGN_REVIEW_RECEIPT

- work_order：`S1-B3-WRITE-QUEUE-DURABILITY-BARRIER`；base `1e140ca`（无 candidate，纯设计评审）。
- verdict：`CHANGES_REQUIRED`；`implementation_authorized=false`。
- 12 条修订全部落入 WORK_ORDER.md v2：①cut-scoped effective durable state flush 语义；②in-band FIFO barrier marker（弃 lock+Condition）；③queueSequence 与 cacheGeneration 解耦；④最小全表 cache fence（KvMemoryCache 仅为此解冻；prime 退为 bootstrap）；⑤BackupFragment 单一 restore 权威 + 静态清单；⑥restore 原子事务且 durable-before-return；⑦WriteOperationKind 全表失败显式建模（禁魔法 key）；⑧全表失败镜像政策（选定 P-OPTIMISTIC-HOLD）；⑨有界 coordinator 状态；⑩RED 测试计划扩充（cache fence 5 条 + store 16 条）；⑪范围修订（含 BackupFragment/KeyValuePair/PublicDatabase 最小 seam）；⑫Option 1 保留（具体形态：admission 锁 + 独立 queueSequence + cache generation token + 全表 fence generation + 紧凑失败状态 + in-band marker/future + 原子 restore/reset 任务）。
- 完整 receipt 原文见下。

## 复审（S1-B3 v2）— 基本通过，3 点修正（网页 ChatGPT，2026-06）

- verdict：v2 **基本通过**；仅 3 点修正；禁止重新扫描/重新设计/实现代码；只改 WORK_ORDER/STATUS。
- 修正 1（reset durability）：不得 enqueue+Unit 后立即 triggerFullRestart；B3 内保证 reset durable terminal 后才 restart；`SettingsPreferenceFragment` 调用点允许最小修改为 suspend/off-main await FlushResult，失败不得按成功重启；**不推迟 S2**。
- 修正 2（full-table fence）：`commitFullTableFence` 必须保留 fence admission 后全部 keyed optimistic mutation（PUT 与 DELETE tombstone），不能只"重放 values"；新增测试 `postFenceDeleteSurvivesFenceCommit`。
- 修正 3（transaction seam）：store 同时用于 configurationStore/profileCacheStore；seam 不得通用硬编码为 PublicDatabase；由对应 store 显式注入正确 DB transaction seam，或仅使 seam 在需要的 configurationStore 路径生效。
- 处置：三点全部并入 WORK_ORDER.md v2.1（D.2/D.1/C4b/D.3/G/K 同步修订；遗留项中 defer-S2 决定收回）；STATUS/HANDOFF 同步。
- 其余 v2 设计全部保持不变；不新增方案、不增加测试矩阵、不扩大研究。
- 等待：最终 `DESIGN_ACCEPTED`；`implementation_authorized` 仍为 false。

## 状态

- S1-B3 v2 设计已 push（`d25dc16`）；**实现仍未授权**，等待网页 ChatGPT 对 v2 的最终设计确认。

## AUDIT_RECEIPT（S1-B1，e9b92cf）

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
（accepted_findings / non_blocking_finding=P2-TEST-ROBUSTNESS / ci_status / required_next_action 全文见本文件 git 历史 d499eaa 版本，内容与 S1-B2 receipt 的 persistent_regression_constraints 一致）
review_scope=cc63f24893696c075723eb8934529534529f31e2..e9b92cf79625c555ecd21b10991642b76037de71
incremental_fix_scope=a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c..e9b92cf79625c555ecd21b10991642b76037de71
reviewer=ChatGPT Web via GitHub fixed-SHA audit
END_AUDIT_RECEIPT
```

## AUDIT_RECEIPT（S1-B2，1e140ca）

```
AUDIT_RECEIPT
repo=Gitefy/NekoBoxForAndroid
branch=fix/p01-room-off-main-thread
work_order=S1-B2-CONFIRM-SEMANTICS-CLOSURE
base_code_sha=e9b92cf79625c555ecd21b10991642b76037de71
candidate_code_sha=1e140ca720a54dfa42cc37235484af7faae499bf
handoff_metadata_sha=f6a2def
verdict=ACCEPTED
critical_count=0
p1_count=0
p2_count=0
ci_status=PASS
ci_evidence=GitHub Actions workflow CI, push event, head_sha=1e140ca720a54dfa42cc37235484af7faae499bf, status=completed, conclusion=success.
persistent_regression_constraints=
* S1-B1 per-key generation stale-ACK protection remains mandatory.
* S1-B1 readEpoch/local-commit snapshot protection remains mandatory.
* S1-B1 serialized snapshot read+merge ordering remains mandatory.
* snapshot() must not expose mutable cache-owned KeyValuePair state.
* duplicate ACK must remain idempotent.
* delete-then-put must preserve the latest mutation.
* reset must invalidate older mutation acknowledgments.
carried_non_blocking_item=
P2-TEST-ROBUSTNESS: RoomPreferenceDataStoreSnapshotOrderingTest still uses Thread.sleep(80) as a scheduling aid. Keep queued for later test-infrastructure cleanup. Do not expand production design solely to address it.
review_scope=e9b92cf79625c555ecd21b10991642b76037de71..1e140ca720a54dfa42cc37235484af7faae499bf
reviewer=ChatGPT Web via GitHub fixed-SHA audit
END_AUDIT_RECEIPT
```

（注：S1-B2 receipt 的 accepted_findings 全文已在本文件 git 历史 f6a2def/cb31848 版本逐字归档；本版为节省重复仅保留判定字段与约束清单，完整性由 git 历史保证。）

## DESIGN_REVIEW_RECEIPT（S1-B3 v1，原文）

```
DESIGN_REVIEW_RECEIPT
repo=Gitefy/NekoBoxForAndroid
branch=fix/p01-room-off-main-thread
work_order=S1-B3-WRITE-QUEUE-DURABILITY-BARRIER
base_code_sha=1e140ca720a54dfa42cc37235484af7faae499bf
verdict=CHANGES_REQUIRED
implementation_authorized=false
（12 条修订要点：1 CUT-SCOPED EFFECTIVE DURABLE STATE；2 in-band FIFO barrier marker；3 queue sequence 与 cache generation 解耦；4 全表 cache fence（prime 退为 bootstrap，KvMemoryCache 最小解冻）；5 单一 settings restore 权威 + 静态清单；6 restore 原子且 durable-before-return；7 WriteOperationKind/WriteFailure 显式全表失败；8 全表失败 cache 行为政策；9 有界 coordinator 状态；10 RED 测试扩充（barrierCapturedBeforeRecoveryStillFails 等至少 11 条新增）；11 范围修订（BackupFragment/KeyValuePair/PublicDatabase 最小 seam，排除项不变）；12 保留 Option 1 拒绝 actor。）
STATUS AFTER THIS REVIEW: S1-B3 = DRAFT / DESIGN_CHANGES_REQUIRED; implementation_authorized=false
（12 条完整原文已在本文件 git 历史 cb31848..d25dc16 之前的用户回执中逐字收到并全文落入 WORK_ORDER v2 的修订映射；本归档仅摘要点，防止转录漂移，完整原文以审计方记录为准。）
reviewer=ChatGPT Web via GitHub fixed-SHA audit
END_DESIGN_REVIEW_RECEIPT
```

## 纪律

Cursor 的 SELF_REVIEW 不得写入本文件冒充独立审计。后续每次审计只针对一个固定 `base..candidate`；设计评审针对固定 base 的 WORK_ORDER 版本（git 可追溯）。
