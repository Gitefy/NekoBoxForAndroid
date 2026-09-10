# 项目状态 — Cursor + ChatGPT 审计模式

| 字段 | 当前值 |
|---|---|
| execution_mode | CURSOR_CHATGPT_GITHUB_AUDIT |
| implementer | Cursor |
| independent_reviewer | ChatGPT Web + GitHub fixed SHA |
| audit_base | cc63f24893696c075723eb8934529534529f31e2（历史参照） |
| observed_local_head | e9b92cf79625c555ecd21b10991642b76037de71 |
| observed_local_branch | fix/p01-room-off-main-thread |
| observed_upstream | origin/fix/p01-room-off-main-thread |
| current_phase | S1（S1-B1-KV-LINEARIZABILITY CANDIDATE_PUSHED / WAIT_AUDIT，P1 已修） |
| current_work_order | S1-B1-KV-LINEARIZABILITY（同单 Keep Open，按 P1 审计意见修正后重推） |
| work_order_state | CANDIDATE_PUSHED / WAIT_CHATGPT_AUDIT |
| write_owner | Cursor |
| working_tree_state | 代码候选 e9b92cf 已提交并推送；剩余未跟踪为迁移包/证据目录，无业务未提交改动 |
| base_code_sha | cc63f24893696c075723eb8934529534529f31e2 |
| candidate_code_sha | e9b92cf79625c555ecd21b10991642b76037de71 |
| handoff_metadata_sha | 见本 commit 下一 metadata commit（或同 push） |
| github_push_state | PUSHED（代码 e9b92cf + 待推送 metadata，见 HANDOFF） |
| latest_audit_verdict | 上一候选 a34a0cf 为 CHANGES_REQUIRED（P1-SNAPSHOT-ORDERING）；本候选 e9b92cf 已推等待新审计 |
| last_accepted_batch | S0-B0 迁移对账完成；S1-B1 待独立审计 ACCEPTED 后关闭 |
| next_action | WAIT_CHATGPT_AUDIT；对 e9b92cf 收到 ACCEPTED 再签发 S1 下一批，否则按新 CHANGES_REQUIRED 只修本批 |

## 阶段状态

| 阶段 | 状态 | 核心门槛 | 证据 |
|---|---|---|---|
| S0 | ACCEPTED | 实际 HEAD/分支/工作树已对账；HEAD=cc63f248 正是 P01 基线本身 | 本仓库 git log/status；对账记录 |
| S1 | IN_PROGRESS（S1-B1 CANDIDATE_PUSHED，P1 已修） | KV 线性一致性：旧 ack 不回退/不复活，snapshot 时序不回滚（含并发 snapshot 定序） | S1-B1 RED→GREEN；新增并发 snapshot 定序回归；testOssDebugUnitTest 29 suites 131 tests / 0 failures |
| S2 | NOT_STARTED | 保存后应用、双进程、主线程响应 | — |
| S3 | NOT_STARTED | 恢复只写一次、旧写入隔离 | — |
| S4 | NOT_STARTED | 网络 trailing、生命周期与 race | — |
| S5 | NOT_STARTED | 配置快照与 core 语义 | — |
| S6 | NOT_STARTED | 真机性能基线与 A/B | — |
| S7 | NOT_STARTED | 完整回归与 release 候选 | — |

## 本批证据索引（S1-B1 含 P1 修正）

- base `cc63f248` 上 RED：`run-a-red-assertions.txt`（`v2->v1` 与 `null->v1` 断言失败）、`run-b-red-api-missing.txt`（`captureReadEpoch`/带 epoch 的 `merge`/`writeCommitted` 未解析）。
- 候选 `a34a0cf` 上 RED（P1）：`run-e-red-concurrent-snapshot.txt`（`expected:<[new]> but was:<[old]>`，`CountDownLatch` 定序并发 snapshot 回退）。
- 修复后 GREEN：`run-f-green-focused-after-fix.txt`（`--tests "*KvMemoryCache*" --tests "*RoomPreferenceDataStoreSnapshotOrderingTest"` exit 0）、`run-g-green-full-suite-after-fix.txt`（full `testOssDebugUnitTest` exit 0，29 suites 131 tests / 0 failures，其中 `KvMemoryCacheTest 13/0`、`KvMemoryCacheLinearizabilityTest 7/0`、`RoomPreferenceDataStoreSnapshotOrderingTest 1/0`）。
- 候选链：`cc63f248..a34a0cf`（generation+epoch guard，4 files）→ `a34a0cf..e9b92cf`（`snapshotLock` 串行化 read+merge + 1 定序回归，2 files）；当前可审候选 `e9b92cf` 即 `cc63f248..e9b92cf`。

## 状态纪律

业务批次完成后必须存在固定 `candidate_code_sha` 与 ChatGPT verdict 才能进入下一业务批次；`SELF_REVIEW` 不得冒充独立审计。S1-B1 同单已按 P1 审计意见重推候选 e9b92cf 并保持 WAIT_AUDIT。
