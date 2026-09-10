# 项目状态 — Cursor + ChatGPT 审计模式

| 字段 | 当前值 |
|---|---|
| execution_mode | CURSOR_CHATGPT_GITHUB_AUDIT |
| implementer | Cursor |
| independent_reviewer | ChatGPT Web + GitHub fixed SHA |
| audit_base | cc63f24893696c075723eb8934529534529f31e2（历史参照） |
| observed_local_head | 实际见 HANDOFF（固定 `git log --oneline -4`） |
| observed_local_branch | fix/p01-room-off-main-thread |
| observed_upstream | origin/fix/p01-room-off-main-thread |
| current_phase | S1（B1/B2 ACCEPTED；B3 FD-1.0 IMPLEMENTED / WAIT_AUDIT） |
| current_work_order | S1-B3-WRITE-QUEUE-DURABILITY-BARRIER |
| work_order_state | FD-1.0 `DESIGN_ACCEPTED` 已实现并推送候选（见 HANDOFF `candidate_code_sha`）；AWAITING_CODE_AUDIT |
| current_state | WAIT_AUDIT（S1-B3 代码审计） |
| write_owner | Cursor |
| working_tree_state | 本轮就绪：代码候选已提交；交接为独立 metadata（不与代码 SHA 同 commit） |
| base_code_sha | 1e140ca720a54dfa42cc37235484af7faae499bf（S1-B2 accepted candidate） |
| candidate_code_sha | 实际见下方 HANDOFF（d1be7dc…，git 写入 SHA） |
| handoff_metadata_sha | 本 commit（见 HEAD） |
| github_push_state | `d1be7dc` 已推 `origin/fix/p01-room-off-main-thread` |
| latest_audit_verdict | S1-B2 `ACCEPTED`（CI PASS）；S1-B3 设计 `DESIGN_ACCEPTED` 并已落实（代码待审） |
| last_accepted_batch | S1-B2-CONFIRM-SEMANTICS-CLOSURE |
| last_accepted_code_sha | 1e140ca720a54dfa42cc37235484af7faae499bf |
| next_action | 网页 ChatGPT 按 `docs/agent/final-design/S1-B3.md` 固定 `1e140ca..candidate` 审计；`ACCEPTED` 后再激活 `BATCHES.md` 下一行 |

## 阶段状态

| 阶段 | 状态 | 核心门槛 | 证据 |
|---|---|---|---|
| S0 | ACCEPTED | 实际 HEAD/分支/工作树已对账 | 本仓库 `git log`/`git status` |
| S1 | IN_PROGRESS | B1/B2 **ACCEPTED**；B3 **已交付并推送候选** | `evidence/s1-b1/`, `evidence/s1-b2/`, `evidence/s1-b3/` |
| S2 | NOT_STARTED | 保存后应用/异步启动 | `final-design/S2.md` + `BATCHES.md` |
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
| S1-B3-WRITE-QUEUE-DURABILITY-BARRIER | `1e140ca..`实际 candidate… | **AWAITING_CODE_AUDIT** | `evidence/s1-b3/`；HANDOFF |

## 永久回归约束（审计冻结）

1. per-key generation stale-ACK 保护。2) readEpoch local-commit snapshot 保护。3) 串行化 snapshot read+merge。4) snapshot 防御拷贝。5) duplicate ACK 幂等。6) delete→put latest-mutation wins。7) reset 使更早 mutation 失效。测试锚点：`KvMemoryCacheTest` / `KvMemoryCacheLinearizabilityTest` / `KvMemoryCacheFullTableFenceTest` / `RoomPreferenceDataStoreSnapshotOrderingTest` / `PreferenceWriteQueueTest`。

## 遗留项

- P2-TEST-ROBUSTNESS：`SnapshotOrderingTest` 内 `Thread.sleep(80)` → 后续 test-infrastructure 清理批，S1-B3 保持。
- `RoomPreferenceDataStoreSnapshotOrderingTest` 仍保留对逝去 `putRetryDelaysMs=200ms` 的用例名，并作为 B3 定序验证回归。

## 状态纪律

业务批次完成后必须存在固定 `candidate_code_sha` 与 ChatGPT 回执才能进入下一批次；`SELF_REVIEW` 不得冒充独立审计。
