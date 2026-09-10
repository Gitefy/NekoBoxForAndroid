# 当前交接记录

模式：Cursor 实现 → GitHub 固定 SHA → 网页 ChatGPT 审计 → receipt 回 Cursor。

## 已关闭批次

- **S1-B1-KV-LINEARIZABILITY（ACCEPTED）**：base `cc63f248` → candidate `e9b92cf`（含 P1 修正）；P2-TEST-ROBUSTNESS 非阻塞遗留。
- **S1-B2-CONFIRM-SEMANTICS-CLOSURE（ACCEPTED + CI PASS）**：base `e9b92cf` → candidate `1e140ca`；metadata `f6a2def`；GitHub Actions push 事件 head_sha=1e140ca success；七条 persistent_regression_constraints 冻结（REVIEW.md）。

## 进行中：S1-B3-WRITE-QUEUE-DURABILITY-BARRIER（仅设计，未实现）

模式：Cursor 起草 → 网页 ChatGPT 设计评审 → 修订 → **最终设计确认** → 确认后 Cursor 实现 → 固定 SHA 审计。
状态：**REVISED DRAFT v2 / AWAITING_FINAL_DESIGN_CONFIRMATION**；`implementation_authorized=false`；本轮无业务源码改动。

- base_code_sha（实现起点）：`1e140ca720a54dfa42cc37235484af7faae499bf`。
- candidate_code_sha：无（未实现）。
- 评审历史：v1 草案（cb31848）→ `DESIGN_REVIEW_RECEIPT` verdict=CHANGES_REQUIRED（12 条）→ **v2 修订版（d25dc16）**，12 条全部落入 `WORK_ORDER.md` A—H。

## v2 设计核心（对应 12 条修订）

1. **flush 语义 = CUT-SCOPED EFFECTIVE DURABLE STATE**：linearization point 在 barrier admission（cut=marker 的 queueSequence）；只有 cut 前接纳的操作影响该 FlushResult；同 key 恢复只在**同一 cut 内**生效；cut 后操作不能回溯治愈；cut 前捕获的 barrier 在恢复前失败、恢复后捕获的成功。
2. **in-band FIFO barrier marker**：在 admission 锁内把 marker 任务插入既有单线程 writer；FIFO 保证其运行时所有 ≤cut 操作已终态（writer 线程内终态更新天然有序）→ 直接读紧凑状态槽求值并 complete `CompletableFuture`；不阻塞 writer；弃用 v1 的 lock+Condition/逐操作等待。
3. **时钟解耦**：`queueSequence`（coordinator）≠ `cacheGeneration`（cache）；测试断言映射正确而非计数器相等。cache 内部保持单一单调时钟同时服务 keyed generation 与全表 fence generation。
4. **全表 cache fence（KvMemoryCache 最小解冻）**：`beginFullTableFence(optimisticRows)`（推进 cache 单时钟、显式取代 pre-fence pending keyed 世代、清 pendingReset sentinel、置乐观全表态、在途阻塞快照）/ `commitFullTableFence`（保留 post-fence keyed 乐观值与 pending；stamp `lastCommittedFullTableEpoch` 读序 guard：`readEpoch <` fence 提交代的快照不得回滚）/ `abortFullTableFence`。`prime()` 退为 bootstrap 专用。
5. **单一 restore 权威**：静态清单确认设置表在 store 外唯一绕道写 = `BackupFragment.kt:569-571`（PublicDatabase 事务 reset+insert）；B3 移除之；`DataStore.configurationStore.restore()` 成为唯一权威。`restore()` 唯一调用方 `BackupFragment.kt:576`（suspend finishImport，off-main）消费终态。
6. **restore 原子 + durable-before-return**：writer 任务内经 `restoreTransaction` seam（默认 `PublicDatabase.instance::runInTransaction`，构造注入）执行**单个 Room 事务** reset+insert；`restore(rows): FlushResult` 阻塞（非主线程）至终态；失败时调用方观察到失败且导入不得 as-success 继续。
7. **全表失败显式建模**：`WriteOperationKind = PUT/DELETE/RESET/RESTORE`；`WriteFailure(operation, key?, reason)`（全表 key=null，禁魔法 key）；判定顺序：cut 内最新 fence 失败 → 全局失败（keyed 不能治愈）；最新成功 fence 之后才按 key 判定。
8. **全表失败镜像政策（选定并文档化）**：P-OPTIMISTIC-HOLD——失败后 getters 见乐观 fence 态；失败 fence 不 stamp 读序保护（fresh snapshot 允许应用、自愈对齐 DB 真值）；在途 fence 阻塞快照；唯一清除者=下一次成功的全表 fence；失败后 keyed 写照常 admit/记账。
9. **有界状态**：pendingQueue + 活跃 marker futures + 每键单槽 latestKeyedOutcome + 全表 fence 单槽；终态即折叠，无 journal。
10. **RED 测试 v2**：cache fence 5 条（`failedPendingWriteIsSupersededBySuccessfulRestore`、`staleSnapshotStartedBeforeRestoreCannotRollbackRestore`、`staleSnapshotStartedBeforeResetCannotResurrectRows`、`postFenceKeyedWriteSurvivesFenceCommit`、`freshSnapshotAfterFenceStillApplies`）+ store 16 条（原 8 + `barrierCapturedBeforeRecoveryStillFails`、`barrierCapturedAfterRecoverySucceeds`、`restoreReplacementIsAtomicOnInsertFailure`、`restoreFailurePreventsSuccessfulReturn`、`restoreFailureAppearsInFlushResult`、`resetFailureAppearsInFlushResult`、`keyedSuccessDoesNotHealFailedWholeTableFence`、`laterSuccessfulWholeTableFenceRecoversEarlierFenceFailure`）+ 保留扩展（listener 重入、取消、invalidation 合并、prime 失败、ticket→cacheGeneration 映射）。全部 latch/fake，禁 sleep 作时序控制。
11. **范围**：RoomPreferenceDataStore.kt；PreferenceWriteCoordinator.kt（按需）；KvMemoryCache.kt 仅 fence；BackupFragment.kt 仅移除带外写+消费结果；KeyValuePair.kt/PublicDatabase.kt 仅事务 seam 备选；JVM tests；metadata。排除项不变（dbOffMain/RuntimeController/network debounce/ConfigSnapshot/sing-box/性能/UI/P2 清理）。
12. **Option 1 保留**：单线程 writer executor + admission 锁 + 独立 queueSequence + cache generation token + 全表 fence generation + 紧凑失败状态 + in-band marker/future + 原子 restore/reset 任务；拒绝 coroutine actor。

## 静态清单（review 第 5/6 条，已执行并记录；base 1e140ca）

| # | 位置 | 写操作 | B3 处置 |
|---|---|---|---|
| 1 | BackupFragment.kt:569-571 | PublicDatabase 事务 kvPairDao.reset()+insert（与 :576 双写） | **移除**，单一权威取代 |
| 2 | BackupFragment.kt:576 | configurationStore.restore（唯一 restore 调用方，suspend finishImport 内 off-main） | 消费终态 FlushResult；失败不 as-success |
| 3 | SettingsPreferenceFragment.kt:203 | configurationStore.reset()（唯一生产 reset 调用方；主线程对话框回调、忽略返回值） | B3 通道化、返回 Unit（调用点兼容）；reset-durable-before-restart 缺口登记 → S2 |
| 4 | BackupFragment.kt:538-561 | SagerDatabase 事务（router/proxy/group/rules 表） | 非设置表，S3 范围，仅登记 |
| 5 | RouteFragment.kt:114 | SagerDatabase.rulesDao.reset() | 非设置表，仅登记 |
| 6 | BackupSerializer.exportDatabase | runInTransaction 内只读导出 | 判定 read-only |
| 7 | RoomPreferenceDataStore 内部 | 唯一合法通道 | 本批改造对象 |

reload/start durability 分析（L0）与未来 flush 接入链（`mutation → flushPendingWrites() → sync/snapshot → reload/start`）见 WORK_ORDER.md E 节；本批不改 RuntimeController/dbOffMain。

## 下一动作

网页 ChatGPT 对 v2（`d25dc16` 的 WORK_ORDER.md）做一次最终设计确认；确认前禁止实现。确认后按单执行：RED Run L → 最小实现 → GREEN Run M/N → 候选 commit/push → metadata → `WAIT_CHATGPT_AUDIT`。
