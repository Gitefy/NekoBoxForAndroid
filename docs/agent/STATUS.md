# 项目状态 — Cursor + ChatGPT 审计模式

| 字段 | 当前值 |
|---|---|
| execution_mode | CURSOR_CHATGPT_GITHUB_AUDIT（**临时授权范围已结束，回归逐批审计**） |
| implementer | Cursor |
| independent_reviewer | ChatGPT Web + GitHub fixed SHA |
| audit_base | cc63f24893696c075723eb8934529534529f31e2（历史参照） |
| observed_local_head | `89658b9`（S2-B3 准备 candidate，PENDING_AUDIT） |
| observed_local_branch | fix/p01-room-off-main-thread |
| observed_upstream | origin/fix/p01-room-off-main-thread |
| current_phase | **S2-B3 已准备** → **临时授权结束，恢复逐批审计** |
| current_work_order | （等待审计激活 S2-B3 设备执行 / S3） |
| work_order_state | S2-B1 `354c472`、S2-B2 `07e7f45`、S2-B3 准备 `89658b9` 均为 PENDING_AUDIT；S2-B3 设备执行与 S3 起均需匹配 SHA 的 `ACCEPTED` 才激活 |
| current_state | WAIT_AUDIT（临时授权已结束，重新成为停止点） |
| write_owner | Cursor |
| working_tree_state | S2-B3 准备就绪：测试代码已提交并通过编译检查；交接为独立 metadata（本 commit） |
| base_code_sha | **S2-B3** `07e7f4552d40015ab2eb4eaa469f63b8a4837a80` |
| candidate_code_sha | **S2-B3** `89658b93d5d2eef5db2ca667044d57b33241db8c`（仅测试代码，零生产改动） |
| handoff_metadata_sha | S2-B1=`7ea8740`；S2-B2=`fa4776c`；S2-B3 metadata 见本 commit（HEAD） |
| github_push_state | `d1be7dc`(S1-B3)+`354c472`(S2-B1)+`07e7f45`(S2-B2)+`89658b9`(S2-B3 准备) 已推 |
| latest_audit_verdict | S1-B2 `ACCEPTED`；S1-B3/S2-B1/S2-B2/S2-B3 准备 `IMPLEMENTED_PENDING_AUDIT` |
| last_accepted_batch | S1-B2-CONFIRM-SEMANTICS-CLOSURE |
| last_accepted_code_sha | 1e140ca720a54dfa42cc37235484af7faae499bf |
| next_action | 网页 ChatGPT 按固定 SHA 分批审计；`S1-B3 d1be7dc` 起逐批 `ACCEPTED` 后再激活下一批；设备未授权时 S2-B3 标记 `NOT_RUN/BLOCKED` 不绕过门槛 |

## 阶段状态

| 阶段 | 状态 | 核心门槛 | 证据 |
|---|---|---|---|
| S0 | ACCEPTED | 实际 HEAD/分支/工作树已对账 | 本仓库 `git log`/`git status` |
| S1 | IN_PROGRESS | B1/B2 **ACCEPTED**；B3 **候选已推待审** | `evidence/s1-b1/`, `evidence/s1-b2/`, `evidence/s1-b3/` |
| S2 | IN_PROGRESS | B1/B2 **GREEN 已推**；B3 **测试代码已通过编译**，设备执行 `NOT_RUN` | `evidence/s2-b1/`, `evidence/s2-b2/`, `evidence/s2-b3/` |
| S3 | NOT_STARTED | 安全恢复 | `final-design/S3.md` + `BATCHES.md` |
| S4 | NOT_STARTED | 运行时/网络恢复 | `final-design/S4.md` + `BATCHES.md` |
| S5 | NOT_STARTED | 配置快照一致性 | `final-design/S5.md` + `BATCHES.md` |
| S6 | NOT_STARTED | 真机/性能基线 | `final-design/S6.md` + `BATCHES.md` |
| S7 | NOT_STARTED | 验收冻结 | `final-design/S7.md` + `FINISH.md` |

## 已验收批次索引

| 批次 | base..candidate | verdict | 证据 |
|---|---|---|---|
| S1-B1-KV-LINEARIZABILITY | `cc63f248..e9b92cf` | ACCEPTED（P2 遗留） | `evidence/s1-b1/`；REVIEW |
| S1-B2-CONFIRM-SEMANTICS-CLOSURE | `e9b92cf..1e140ca` | ACCEPTED + CI PASS | `evidence/s1-b2/`；REVIEW |
| S1-B3-WRITE-QUEUE-DURABILITY-BARRIER | `1e140ca..d1be7dc` | PENDING_AUDIT（所有者临时授权保留） | `evidence/s1-b3/`；HANDOFF |
| S2-B1-ASYNC-READINESS | `d1be7dc..354c472` | PENDING_AUDIT（本次授权连续实施） | `evidence/s2-b1/`；HANDOFF |
| S2-B2-APPLY-STOP-HANDSHAKE | `354c472..07e7f45` | PENDING_AUDIT（本次授权连续实施） | `evidence/s2-b2/`；HANDOFF |
| S2-B3（准备） | `07e7f45..89658b9` | PENDING_AUDIT（仅测试代码） | `evidence/s2-b3/`；HANDOFF |

## 永久回归约束（审计冻结）

1. per-key generation stale-ACK 保护。2) readEpoch local-commit snapshot 保护。3) 串行化 snapshot read+merge。4) snapshot 防御拷贝。5) duplicate ACK 幂等。6) delete→put latest-mutation wins。7) reset 使更早 mutation 失效。测试锚点：`KvMemoryCacheTest` / `KvMemoryCacheLinearizabilityTest` / `KvMemoryCacheFullTableFenceTest` / `RoomPreferenceDataStoreSnapshotOrderingTest` / `PreferenceWriteQueueTest` / `StoreAsyncReadinessTest` / `ApplyHandshakeTest`；S2-B3 `S2B3CrossProcessVerificationTest`（`androidTest`，待设备执行）。

## 遗留项

- **所有者临时授权（2026-09，范围已结束）**：S1-B3 `d1be7dc`/`ea2d901` 保留为 IMPLEMENTED_PENDING_AUDIT，基于该未审候选连续实施 S2-B1/S2-B2 且本地验证通过，S2-B3 仅准备测试代码+编译检查（`89658b9`）。自本 STATUS 起恢复逐批审计门槛，下一业务批需匹配 SHA 的 `ACCEPTED` 激活。`AGENTS.md` SATET 提示继续指向本节作为历史说明；后续会话以"授权范围已结束"为答案，仍按逐批审计执行。
- P2-TEST-ROBUSTNESS：`SnapshotOrderingTest` 内 `Thread.sleep(80)` → 后续 test-infrastructure 清理批，S1-B3 保持。
- S2-B3 设备门槛：无设备 `NOT_RUN` 且 `BLOCKED`，不绕过。

## 状态纪律

业务批次完成后必须存在固定 `candidate_code_sha` 与 ChatGPT 回执才能进入下一批次；`SELF_REVIEW` 不得冒充独立审计。

