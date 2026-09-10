# 项目状态 — Cursor + ChatGPT 审计模式

| 字段 | 当前值 |
|---|---|
| execution_mode | CURSOR_CHATGPT_GITHUB_AUDIT |
| implementer | Cursor |
| independent_reviewer | ChatGPT Web + GitHub fixed SHA |
| audit_base | cc63f24893696c075723eb8934529534529f31e2（历史参照） |
| observed_local_head | 见 HANDOFF（S1-B2 候选已产出） |
| observed_local_branch | fix/p01-room-off-main-thread |
| observed_upstream | origin/fix/p01-room-off-main-thread |
| current_phase | S1（B1 ACCEPTED；S1-B2-CONFIRM-SEMANTICS-CLOSURE CANDIDATE_PUSHED） |
| current_work_order | S1-B2-CONFIRM-SEMANTICS-CLOSURE |
| work_order_state | CANDIDATE_PUSHED / WAIT_CHATGPT_AUDIT |
| write_owner | Cursor |
| working_tree_state | S1-B2 代码候选已提交推送；剩余未跟踪为迁移包/证据目录，无未知业务改动 |
| base_code_sha | e9b92cf79625c555ecd21b10991642b76037de71（S1-B1 accepted candidate） |
| candidate_code_sha | 见 HANDOFF（S1-B2 批次候选） |
| handoff_metadata_sha | 见 HANDOFF |
| github_push_state | S1-B2 候选已推送；CI（A08 修复后）应开始对本分支触发 |
| latest_audit_verdict | S1-B1 `ACCEPTED`（e9b92cf）；S1-B2 待审 |
| last_accepted_batch | S1-B1-KV-LINEARIZABILITY |
| next_action | WAIT_CHATGPT_AUDIT（S1-B2）；ACCEPTED 后签发 S1-B3（队列/刷新/失败 barrier，架构批） |

## 阶段状态

| 阶段 | 状态 | 核心门槛 | 证据 |
|---|---|---|---|
| S0 | ACCEPTED | 实际 HEAD/分支/工作树已对账 | 本仓库 git log/status |
| S1 | IN_PROGRESS | B1 真实类失败测试 ACCEPTED + **CI 入口已修（A08）**；B2 写版本与回调 ACCEPTED + 遗留钉子/快照防污染已补（S1-B2 待审）；B3 队列/刷新/失败 barrier NOT_STARTED | `evidence/s1-b1/`、`evidence/s1-b2/` |
| S2 | NOT_STARTED | 保存后应用、双进程、主线程响应 | — |
| S3 | NOT_STARTED | 恢复只写一次、旧写入隔离 | — |
| S4 | NOT_STARTED | 网络 trailing、生命周期与 race | — |
| S5 | NOT_STARTED | 配置快照与 core 语义 | — |
| S6 | NOT_STARTED | 真机性能基线与 A/B | — |
| S7 | NOT_STARTED | 完整回归与 release 候选 | — |

## 已验收批次索引

| 批次 | base..candidate | verdict | 证据 |
|---|---|---|---|
| S1-B1-KV-LINEARIZABILITY | `cc63f248..e9b92cf`（含 P1 修正 `a34a0cf..e9b92cf`） | ACCEPTED（P2-TEST-ROBUSTNESS 非阻塞） | `docs/agent/evidence/s1-b1/run-{a..g}-*.txt`；REVIEW.md receipt |
| S1-B2-CONFIRM-SEMANTICS-CLOSURE | `e9b92cf..<候选>`（代码候选） | 待审 | `docs/agent/evidence/s1-b2/run-{h,i,j,k}-*.txt` |

## S1-B1 不变量 → 永久回归约束

以下不变量已由测试钉死，后续所有 settings/database 批次不得回退（改动相关代码必须保持这些测试通过）：

1. 旧 generation ACK 不得回退新值、不得复活删除、不得清除新 pending（`KvMemoryCacheLinearizabilityTest`）。
2. snapshot `readEpoch` 顺序：读取起点晚于本地提交的快照不得回滚；fresh 快照仍传播跨进程值（`KvMemoryCacheLinearizabilityTest`）。
3. 并发 sibling snapshot 全程串行于 `snapshotLock`，终值取最新完整 refresh（`RoomPreferenceDataStoreSnapshotOrderingTest`）。
4. legacy 无 token ack 仅在内容匹配镜像时生效（`KvMemoryCacheTest`）。
5. **S1-B2 新增约束**：delete→put 定序 ack 各代独立（legacy+generation 双路径）；重复 ack 幂等；reset 后旧 put ack 不复活值；`snapshot()/cachedAll()` 返回防御性拷贝，外部修改不污染镜像（`KvMemoryCacheTest`/`KvMemoryCacheLinearizabilityTest`）。

## 证据计数更正（integrity note）

- e9b92cf 批次全量 run-g 实际计数为 **29 suites / 124 tests / 0 failures**；先前 metadata（d499eaa/1de9de8）误记为 131 tests，属算术错误，现予更正。
- S1-B2 全量 run-k 实际计数 **29 suites / 131 tests / 0 failures**（较 run-g 新增 7 个钉子测试），以 `run-k-green-full-suite.txt` 与 `app/build/test-results/testOssDebugUnitTest/TEST-*.xml` 为准。

## 状态纪律

业务批次完成后必须存在固定 `candidate_code_sha` 与 ChatGPT verdict 才能进入下一业务批次；`SELF_REVIEW` 不得冒充独立审计。P2-TEST-ROBUSTNESS（`SnapshotOrderingTest` 内 `Thread.sleep(80)`）为记录性遗留，后续 test-infrastructure 清理批处理，不得为删 sleep 扩大生产设计。
