# 项目状态 — Cursor + ChatGPT 审计模式

| 字段 | 当前值 |
|---|---|
| execution_mode | CURSOR_CHATGPT_GITHUB_AUDIT + 项目所有者临时授权（见"遗留项"首条） |
| implementer | Cursor |
| independent_reviewer | ChatGPT Web + GitHub fixed SHA |
| audit_base | cc63f24893696c075723eb8934529534529f31e2（历史参照） |
| observed_local_head | 实际见 HANDOFF（固定 `git log --oneline -4`） |
| observed_local_branch | fix/p01-room-off-main-thread |
| observed_upstream | origin/fix/p01-room-off-main-thread |
| current_phase | **S2-B1 已实现并候选推送** → **S2-B2 实施中（临时授权连续实施）** |
| current_work_order | S2-B2-APPLY-STOP-HANDSHAKE（S2-B1 之后，S2-B3 仅测试准备） |
| work_order_state | S2-B1 候选 `354c472` 已推送（base `d1be7dc` PENDING）；`S1-B3` 保持 IMPLEMENTED_PENDING_AUDIT；`S2-B2` 实施中；S2-B3 仅测试代码+编译检查；完成后即结束授权范围并恢复逐批审计 |
| current_state | 所有者临时授权连续实施：S2-B1 DONE → S2-B2 IN_PROGRESS →（S2-B3 测试准备） |
| write_owner | Cursor |
| working_tree_state | S2-B1 就绪：代码候选已提交；交接为独立 metadata（不与代码 SHA 同 commit） |
| base_code_sha | **S2-B1** `d1be7dc99cd245efd3616e1f7569eef2777f57f9` → **S2-B2** `354c472260314e2bc1e3a7a2c0da741bb9a83a1e`（链式，未审） |
| candidate_code_sha | **S2-B1** `354c472260314e2bc1e3a7a2c0da741bb9a83a1e`；S2-B2 见下一批 HANDOFF |
| handoff_metadata_sha | S1-B3=`ea2d901`；S2-B1 metadata 见本 commit（HEAD） |
| github_push_state | `d1be7dc`(S1-B3)+`354c472`(S2-B1) 已推 `origin/fix/p01-room-off-main-thread` |
| latest_audit_verdict | S1-B2 `ACCEPTED`；S1-B3/S2-B1 `IMPLEMENTED_PENDING_AUDIT`（待恢复逐批审计后执行） |
| last_accepted_batch | S1-B2-CONFIRM-SEMANTICS-CLOSURE |
| last_accepted_code_sha | 1e140ca720a54dfa42cc37235484af7faae499bf |
| next_action | 直接实施 **S2-B2-APPLY-STOP-HANDSHAKE**（基线 `354c472`）；本地必需验证通过即推进；完成后准备 S2-B3 测试代码及编译检查，不装 APK/不进 S3 |

## 阶段状态

| 阶段 | 状态 | 核心门槛 | 证据 |
|---|---|---|---|
| S0 | ACCEPTED | 实际 HEAD/分支/工作树已对账 | 本仓库 `git log`/`git status` |
| S1 | IN_PROGRESS | B1/B2 **ACCEPTED**；B3 **候选已推待审** | `evidence/s1-b1/`, `evidence/s1-b2/`, `evidence/s1-b3/` |
| S2 | IN_PROGRESS | B1 **GREEN 已推**；B2 实施中；B3 仅测试准备 | `evidence/s2-b1/`, `evidence/s2-b2/` |
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

## 永久回归约束（审计冻结）

1. per-key generation stale-ACK 保护。2) readEpoch local-commit snapshot 保护。3) 串行化 snapshot read+merge。4) snapshot 防御拷贝。5) duplicate ACK 幂等。6) delete→put latest-mutation wins。7) reset 使更早 mutation 失效。测试锚点：`KvMemoryCacheTest` / `KvMemoryCacheLinearizabilityTest` / `KvMemoryCacheFullTableFenceTest` / `RoomPreferenceDataStoreSnapshotOrderingTest` / `PreferenceWriteQueueTest` / `StoreAsyncReadinessTest`。

## 遗留项

- **所有者临时授权（2026-09，本次连续实施范围，覆盖"上一批 ACCEPTED 才继续"门槛）**：保留 S1-B3 `d1be7dc`/`ea2d901` 为 IMPLEMENTED_PENDING_AUDIT（绝不标 ACCEPTED）；基于该未审候选连续实施 S2-B1→S2-B2（本地验证通过即推进）；S2-B3 仅准备测试代码+编译检查，不装 APK/不操作设备/不进 S3；每批独立 commit/push 并记录依赖的未审候选；`last_accepted_code_sha` 保持 `1e140ca`。授权范围完成后恢复逐批审计规则。后续任何会话以本节为停止问题的答案，勿再因旧门槛停下。
- P2-TEST-ROBUSTNESS：`SnapshotOrderingTest` 内 `Thread.sleep(80)` → 后续 test-infrastructure 清理批，S1-B3 保持。
- 剩余非关键 dbOffMain 调用清单在 S2-B1 HANDOFF 记录，S7 冻结前清理。

## 状态纪律

业务批次完成后必须存在固定 `candidate_code_sha` 与 ChatGPT 回执才能进入下一批次；`SELF_REVIEW` 不得冒充独立审计。

