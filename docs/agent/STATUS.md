# 项目状态 — Cursor + ChatGPT 审计模式

| 字段 | 当前值 |
|---|---|
| execution_mode | CURSOR_CHATGPT_GITHUB_AUDIT |
| implementer | Cursor |
| independent_reviewer | ChatGPT Web + GitHub fixed SHA |
| audit_base | cc63f24893696c075723eb8934529534529f31e2（历史参照） |
| observed_local_head | 见 HANDOFF（S1-B2 批次推进中） |
| observed_local_branch | fix/p01-room-off-main-thread |
| observed_upstream | origin/fix/p01-room-off-main-thread |
| current_phase | S1（B1 ACCEPTED；S1-B2-CONFIRM-SEMANTICS-CLOSURE IN_PROGRESS） |
| current_work_order | S1-B2-CONFIRM-SEMANTICS-CLOSURE |
| work_order_state | ISSUED / IN_PROGRESS（候选未产出前本文件不写 candidate SHA） |
| write_owner | Cursor |
| working_tree_state | S1-B1 已关闭；S1-B2 按工作单推进，无未知未提交业务改动 |
| base_code_sha | e9b92cf79625c555ecd21b10991642b76037de71（= S1-B1 accepted candidate） |
| candidate_code_sha | 批次完成后由 HANDOFF 固定 |
| handoff_metadata_sha | 批次完成后由 HANDOFF 固定 |
| github_push_state | S1-B1 全链已推送；S1-B2 待产出候选后推送 |
| latest_audit_verdict | S1-B1-KV-LINEARIZABILITY `ACCEPTED`（candidate e9b92cf，P2-TEST-ROBUSTNESS 非阻塞） |
| last_accepted_batch | S1-B1-KV-LINEARIZABILITY |
| next_action | 执行 S1-B2：CI 入口修复（A08）+ plan-B2 遗留确认语义钉子 + snapshot() 防污染；完成后固定候选并 WAIT_AUDIT |

## 阶段状态

| 阶段 | 状态 | 核心门槛 | 证据 |
|---|---|---|---|
| S0 | ACCEPTED | 实际 HEAD/分支/工作树已对账 | 本仓库 git log/status |
| S1 | IN_PROGRESS | B1 CI/真实类失败测试（B1 真实类部分 ACCEPTED；**CI 入口 A08 未修，归入 S1-B2**）；B2 写版本与回调（核心 ACCEPTED，遗留钉子与 snapshot 防污染归入 S1-B2）；B3 队列/刷新/失败 barrier（NOT_STARTED） | `evidence/s1-b1/`；S1-B2 证据随批产出 |
| S2 | NOT_STARTED | 保存后应用、双进程、主线程响应 | — |
| S3 | NOT_STARTED | 恢复只写一次、旧写入隔离 | — |
| S4 | NOT_STARTED | 网络 trailing、生命周期与 race | — |
| S5 | NOT_STARTED | 配置快照与 core 语义 | — |
| S6 | NOT_STARTED | 真机性能基线与 A/B | — |
| S7 | NOT_STARTED | 完整回归与 release 候选 | — |

## 已验收批次索引

| 批次 | base..candidate | verdict | 证据 |
|---|---|---|---|
| S1-B1-KV-LINEARIZABILITY | `cc63f248..e9b92cf`（含 P1 修正 `a34a0cf..e9b92cf`） | ACCEPTED（P2-TEST-ROBUSTNESS 非阻塞） | `docs/agent/evidence/s1-b1/run-{a,b,c,d,e,f,g}-*.txt`；REVIEW.md receipt |

## S1-B1 不变量 → 永久回归约束

以下不变量已由测试钉死，后续所有 settings/database 批次不得回退（改动相关代码必须保持这些测试通过）：

1. 旧 generation ACK 不得回退新值、不得复活删除、不得清除新 pending（`KvMemoryCacheLinearizabilityTest`）。
2. snapshot `readEpoch` 顺序：读取起点晚于本地提交的快照不得回滚；fresh 快照仍传播跨进程值（`KvMemoryCacheLinearizabilityTest`）。
3. 并发 sibling snapshot 全程串行于 `snapshotLock`，终值取最新完整 refresh（`RoomPreferenceDataStoreSnapshotOrderingTest`）。
4. legacy 无 token ack 仅在内容匹配镜像时生效（`KvMemoryCacheTest`）。

## 状态纪律

业务批次完成后必须存在固定 `candidate_code_sha` 与 ChatGPT verdict 才能进入下一业务批次；`SELF_REVIEW` 不得冒充独立审计。P2-TEST-ROBUSTNESS（测试内 `Thread.sleep(80)`）为记录性遗留，后续 test-infrastructure 清理批处理，不得为删 sleep 扩大生产设计。
