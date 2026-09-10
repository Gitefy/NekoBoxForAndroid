# 项目状态 — Cursor + ChatGPT 审计模式

| 字段 | 当前值 |
|---|---|
| execution_mode | CURSOR_CHATGPT_GITHUB_AUDIT |
| implementer | Cursor |
| independent_reviewer | ChatGPT Web + GitHub fixed SHA |
| audit_base | cc63f24893696c075723eb8934529534529f31e2（历史参照） |
| observed_local_head | a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c |
| observed_local_branch | fix/p01-room-off-main-thread |
| observed_upstream | origin/fix/p01-room-off-main-thread（RECONCILE 时无 tracking；S1-B1 后 push 建立） |
| current_phase | S1（S1-B1-KV-LINEARIZABILITY CANDIDATE_PUSHED） |
| current_work_order | S1-B1-KV-LINEARIZABILITY |
| work_order_state | CANDIDATE_PUSHED / WAIT_CHATGPT_AUDIT |
| write_owner | Cursor |
| working_tree_state | 代码候选 a34a0cf 已提交；其余未跟踪为迁移包/证据目录，无业务未提交改动 |
| base_code_sha | cc63f24893696c075723eb8934529534529f31e2 |
| candidate_code_sha | a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c |
| handoff_metadata_sha | 见本 commit 下一 metadata commit（或同 push） |
| github_push_state | PUSHED（见 HANDOFF） |
| latest_audit_verdict | WAIT_CHATGPT_AUDIT（CC63F248 被 ChatGPT 定性为未验收，此候选待审） |
| last_accepted_batch | S0-B0 迁移对账完成；S1-B1 待独立审计 ACCEPTED 后关闭 |
| next_action | WAIT_CHATGPT_AUDIT；收到 ACCEPTED 再签发 S1 下一批，否则按 CHANGES_REQUIRED 只修本批 |

## 阶段状态

| 阶段 | 状态 | 核心门槛 | 证据 |
|---|---|---|---|
| S0 | ACCEPTED | 实际 HEAD/分支/工作树已对账；HEAD=cc63f248 正是 P01 基线本身 | 本仓库 git log/status；对账记录 |
| S1 | IN_PROGRESS（S1-B1 CANDIDATE_PUSHED） | KV 线性一致性：旧 ack 不回退/不复活，snapshot 时序不回滚 | S1-B1 RED→GREEN；testOssDebugUnitTest 130/0/0；证据 `docs/agent/evidence/s1-b1/` |
| S2 | NOT_STARTED | 保存后应用、双进程、主线程响应 | — |
| S3 | NOT_STARTED | 恢复只写一次、旧写入隔离 | — |
| S4 | NOT_STARTED | 网络 trailing、生命周期与 race | — |
| S5 | NOT_STARTED | 配置快照与 core 语义 | — |
| S6 | NOT_STARTED | 真机性能基线与 A/B | — |
| S7 | NOT_STARTED | 完整回归与 release 候选 | — |

## 本批证据索引（S1-B1）

- base `cc63f248` 上 RED：`run-a-red-assertions.txt`（exit 1，`v2->v1` 与 `null->v1` 两个断言失败）、`run-b-red-api-missing.txt`（`captureReadEpoch`/带 epoch 的 `merge`/`writeCommitted` 未解析）。
- 修复后 GREEN：`run-c-green-focused.txt`（focused exit 0，BUILD SUCCESSFUL）、`run-d-green-full-suite.txt`（full exit 0，28 suites 130 tests/0 failures）。
- 候选：`cc63f248..a34a0cf`（4 files，KvMemoryCache generation+epoch guard + RoomPreferenceDataStore 接线 + 9 regressions）。

## 状态纪律

业务批次完成后必须存在固定 `candidate_code_sha` 与 ChatGPT verdict 才能进入下一业务批次；`SELF_REVIEW` 不得冒充独立审计。S1-B1 候选已固定并 push，等待网页 ChatGPT 对固定 SHA 的 AUDIT_RECEIPT。
