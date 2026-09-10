# 项目状态 — Cursor + ChatGPT 审计模式

| 字段 | 当前值 |
|---|---|
| execution_mode | CURSOR_CHATGPT_GITHUB_AUDIT |
| implementer | Cursor |
| independent_reviewer | ChatGPT Web + GitHub fixed SHA |
| audit_base | cc63f24893696c075723eb8934529534529f31e2（历史参照） |
| observed_local_head | 见 HANDOFF（本轮 metadata commit） |
| observed_local_branch | fix/p01-room-off-main-thread |
| observed_upstream | origin/fix/p01-room-off-main-thread |
| current_phase | S1（B1 ACCEPTED；B2 ACCEPTED+CI PASS；B3 REVISED DRAFT v2 / AWAITING_FINAL_DESIGN_CONFIRMATION） |
| current_work_order | S1-B3-WRITE-QUEUE-DURABILITY-BARRIER |
| work_order_state | DRAFTED / DESIGN_CHANGES_REQUIRED 已修订（v2, d25dc16）；implementation_authorized=false |
| current_state | PLAN_NEXT_BATCH |
| write_owner | Cursor |
| working_tree_state | 仅 S1-B3 设计/交接文档变更；无业务源码改动 |
| base_code_sha | 1e140ca720a54dfa42cc37235484af7faae499bf（= S1-B2 accepted candidate） |
| candidate_code_sha | 无（S1-B3 未产出候选；实现待最终设计确认） |
| handoff_metadata_sha | 本 commit |
| github_push_state | S1-B2 候选 1e140ca 已推送且 GitHub Actions CI PASS；S1-B3 v1 设计 CHANGES_REQUIRED 已按 12 条修订为 v2 并推送 |
| latest_audit_verdict | S1-B2 `ACCEPTED`（CI PASS）；S1-B3 v1 设计 `CHANGES_REQUIRED`（12 条，已全部落入 v2） |
| last_accepted_batch | S1-B2-CONFIRM-SEMANTICS-CLOSURE |
| last_accepted_code_sha | 1e140ca720a54dfa42cc37235484af7faae499bf |
| next_action | 网页 ChatGPT 对 v2（A—H，含静态清单与全表失败政策 P-OPTIMISTIC-HOLD）做**一次最终设计确认** → 才允许按单实现（RED Run L → 最小实现 → GREEN Run M/N → 候选 push → WAIT_AUDIT） |

## 阶段状态

| 阶段 | 状态 | 核心门槛 | 证据 |
|---|---|---|---|
| S0 | ACCEPTED | 实际 HEAD/分支/工作树已对账 | 本仓库 git log/status |
| S1 | IN_PROGRESS | B1 **ACCEPTED**（A08 经 GitHub 行为验证 CI PASS）；B2 **ACCEPTED**；B3 队列/flush barrier/失败协议 **v2 设计待最终确认，未实现** | `evidence/s1-b1/`、`evidence/s1-b2/`；S1-B3 证据随实现批产出 |
| S2 | NOT_STARTED | 保存后应用（D04 握手，消费 FlushResult；含 reset-before-restart 缺口关闭）、异步初始化/晋升/停止回执（D05）、双进程验证 | — |
| S3 | NOT_STARTED | 恢复只写一次、旧写入隔离、故障注入（D06；S1-B3 已含设置表 queue-fence 最小部分 + BackupFragment 单一权威） | — |
| S4 | NOT_STARTED | 网络 trailing、生命周期与 race（D07） | — |
| S5 | NOT_STARTED | 配置快照与 core 语义（D08） | — |
| S6 | NOT_STARTED | 真机性能基线与 A/B（D09） | — |
| S7 | NOT_STARTED | 完整回归与 release 候选 | — |

## 已验收批次索引

| 批次 | base..candidate | verdict | 证据 |
|---|---|---|---|
| S1-B1-KV-LINEARIZABILITY | `cc63f248..e9b92cf`（含 P1 修正） | ACCEPTED（P2-TEST-ROBUSTNESS 非阻塞） | `evidence/s1-b1/run-{a..g}-*.txt`；REVIEW.md |
| S1-B2-CONFIRM-SEMANTICS-CLOSURE | `e9b92cf..1e140ca` | ACCEPTED（P2=0）+ CI PASS | `evidence/s1-b2/run-{h..k}-*.txt`；GitHub Actions head_sha=1e140ca success；REVIEW.md |

## 永久回归约束（审计冻结，跨批次强制）

1. per-key generation stale-ACK 保护（旧 ACK 不回退新值/不复活删除/不清新 pending）。
2. readEpoch local-commit snapshot 保护。
3. 串行化 snapshot read+merge ordering（snapshotLock）。
4. snapshot 防御性拷贝（不得暴露可变缓存状态）。
5. duplicate ACK idempotency。
6. delete→put latest-mutation wins。
7. reset invalidates older ACK。

（测试锚点：`KvMemoryCacheTest` / `KvMemoryCacheLinearizabilityTest` / `RoomPreferenceDataStoreSnapshotOrderingTest`；S1-B3 实现批追加 `KvMemoryCacheFullTableFenceTest` / `PreferenceWriteQueueTest` 系。）

## 遗留项

- P2-TEST-ROBUSTNESS：`SnapshotOrderingTest` 内 `Thread.sleep(80)` → 后续 test-infrastructure 清理批；**不在 S1-B3 顺手修**。
- reset-before-restart 缺口（`SettingsPreferenceFragment.kt:203` 主线程 reset + `triggerFullRestart` 进程重启，排队 reset 可能未 durable）→ 已在工作单 E 节记录，S2 用 flush-before-restart 握手关闭。
- e9b92cf run-g 计数更正（131→124）已落档；后续以 `TEST-*.xml` 汇总为准。

## 状态纪律

业务批次完成后必须存在固定 `candidate_code_sha` 与 ChatGPT verdict 才能进入下一业务批次；`SELF_REVIEW` 不得冒充独立审计。
S1-B3 为架构批：v1 设计 CHANGES_REQUIRED（12 条）→ v2 已修订；**最终设计确认前禁止实现**。
