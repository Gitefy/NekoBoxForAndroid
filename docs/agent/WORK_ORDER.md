# 当前工作单：S1-B3-WRITE-QUEUE-DURABILITY-BARRIER（REVISED DRAFT v2 — 待最终设计确认，未实现）

状态：DRAFTED / DESIGN_CHANGES_REQUIRED（v2 已按 DESIGN_REVIEW_RECEIPT 全部 12 条修订；implementation_authorized=false）。
Owner：Cursor。
独立审计：网页 ChatGPT（固定 GitHub SHA；v1 设计被 CHANGES_REQUIRED 退回，本 v2 待**一次最终设计确认**）。
base_code_sha：`1e140ca720a54dfa42cc37235484af7faae499bf`（S1-B2 accepted candidate，CI PASS）。
修订历史：v1（WRITE-QUEUE-FLUSH-BARRIER 草案）→ DESIGN_REVIEW_RECEIPT（12 条 CHANGES_REQUIRED）→ **本 v2**。
取代：v1 全文。v1 中"KvMemoryCache 冻结 / restore 推迟 S3 / sequence==generation 1:1 / lock+Condition barrier"均被本 v2 取代。

## 0. 本单性质与停止条件

架构正确性批次。当前只交付设计（本文件）；**未写任何业务代码**。
网页 ChatGPT 最终确认本 v2 A—H 后，才按既有节奏单独执行实现批：
RED（Run L）→ 最小实现 → GREEN（Run M/N）→ commit 候选 → push → metadata → `WAIT_CHATGPT_AUDIT`。

## 方案决定（对应 review 第 12 条；Option 1 保留）

单线程 writer executor + admission 锁 + **独立单调 queueSequence**（与 cache generation 解耦）+
keyed ACK 使用 cache generation token + **cache 级全表 fence generation** + **紧凑有效失败状态（无操作日志式 journal）** +
**in-band FIFO barrier marker（CompletableFuture）** + **原子 restore/reset writer 任务**。
拒绝 coroutine actor/channel 迁移（理由同 v1：属并发模型整体迁移，必须独立立项）。

## 冻结的 Kotlin 签名 v2

```kotlin
// RoomPreferenceDataStore 同包内部契约；不跨进程比较。
enum class WriteOperationKind { PUT, DELETE, RESET, RESTORE }

data class WriteTicket(
    val queueSequence: Long,       // writer/admission/barrier/fence 的排序时钟（coordinator 独立计数）
    val writeEpoch: Long,          // store 写纪元（RESTORE/RESET admission 时推进）
    val cacheGeneration: Long?,    // 仅 keyed 操作：cache.put/delete 返回值；全表操作为 null
)

data class WriteFailure(
    val operation: WriteOperationKind,
    val key: String?,              // 全表操作（RESET/RESTORE）为 null；禁止用魔法 key 字符串表示全表
    val reason: String,            // 异常类名+message；不持有 Throwable
)

data class FlushResult(val success: Boolean, val completed: Int, val failures: List<WriteFailure>)

suspend fun flushPendingWrites(): FlushResult      // 公开；在 Dispatchers.IO 上可取消等待 marker future
fun restore(rows: List<KeyValuePair>): FlushResult // 语义修订：阻塞至 fence 终态，不再 enqueue-and-return（见 D.4）
fun reset(): Unit                                  // 语义修订：走通道 fence；调用点清单见 E/静态清单
```

- **时钟解耦（review 第 3 条）**：`queueSequence`（coordinator）与 `cacheGeneration`（cache）是**不同时钟**，
  不得假设数值相等；测试断言 "ticket.cacheGeneration == 对应 cache.put/delete 返回值、且该代 ACK 语义正确"，
  不断言两计数器相等。cache 内部保留**单一单调计数器**同时充当 keyed generation 与全表 fence generation
  （v1 的 captureReadEpoch/merge(readEpoch) guard 语义因此保持不变）。
- 公共 setter 签名不变；token 为内部契约。`syncNow()` 签名不变，维持 snapshotLock 通道。

## A. flushPendingWrites 精确语义（CUT-SCOPED EFFECTIVE DURABLE STATE）

```kotlin
suspend fun flushPendingWrites(): FlushResult
```

**linearization point = barrier cut**：进入 flush 后，在 admission 锁内分配 `cut`（marker 的 queueSequence）
并把 **barrier-marker 任务**插入既有单线程 writer FIFO（与 `writer.execute` 同一 admission 锁排序）。

**marker 任务（in-band，review 第 2 条）**：
- 由 FIFO 保证在**所有先于它入队的任务到达终态之后**才在 writer 线程上执行（writer 天然串行，无锁竞争）；
- 执行时读取 coordinator 的**紧凑有效状态槽**（见 C/有界状态），对 cut 求值 CUT-SCOPED EFFECTIVE DURABLE STATE；
- 用求值结果 complete 一个 `CompletableFuture<FlushResult>`；**不阻塞、不等待任何后续任务**；
- 之后入队的 mutation 排在 marker 之后：**不能延长、也不能治愈该 barrier**。

**成功唯一语义**：`success=true` 当且仅当在 cut 处——
1. 不存在未恢复的全表 fence 失败（见 C 判定顺序）；
2. 每个 key 的"cut 内最新有效 keyed 操作"为 SUCCESS（或被 cut 内成功 fence 取代）。

**明确禁止的伪成功**："writer queue 已跑空"不是判据（判据是 cut 处有效状态求值，queue 排空只是 marker
可执行的 FIFO 前提）；等待超时/取消不是成功；被 fence 取代不是含糊成功（显式 SUPERSEDED）。
cut 覆盖范围内任何 key 的最终持久化失败且未被 cut 内更晚成功修复 → `success=false`。

**等待与取消**：`flushPendingWrites` 在 `Dispatchers.IO` 上等待 marker future，可取消；
取消等待者不取消已接纳写入（marker 任务照常完成）。
**无内部超时**，且**修正 v1 的错误断言**：DAO 调用理论上可无限阻塞，**不存在"每个任务必然终态"的保证**；
marker 会与被阻塞 DAO 一样等待；调用方以后可自行加超时/取消，取消不得取消已接纳写入。

`completed` = 先于 marker 入队并到达终态的 operation 数（coordinator 计数；含 fence 任务；不含 cut 后操作）。

## B. Barrier 边界

1. **cut**：marker 的 `queueSequence`；等待集合 = 所有 `queueSequence < marker.queueSequence` 的 operation。
2. **cut 后操作不可回溯治愈**：barrier 求值只读"marker 执行时"的有效状态槽；FIFO 保证此刻**所有 ≤cut 操作已终态、
   所有 >cut 操作尚未执行**（终态更新都在 writer 线程任务内完成，天然按序），因此槽状态恰为 cut 状态——
   这就是 cut-scoped 语义的实现依据（无需额外等待机构）。
3. **同 key 多 generation**：key 判定 = cut 内该 key **最新终态**的 outcome（cut 内先失败后成功 → 干净；
   cut 内先成功后失败 → 失败；cut 前失败、cut 后才成功且成功在 cut 外 → 本 barrier 仍失败）。
4. **delete 与 put 统一模型**：同一 ledger/同一 marker 求值；`dao.delete` 影响 0 行也是 SUCCESS（终态="DB 无该行"）。
   RESTORE/RESET 是 fence 型 operation，结果支配 keyed 结果（见 C）。
5. v1 的 lock+Condition/逐操作等待机构**移除**：in-band marker 已表达全部需求（review 第 2 条），
   除非实现暴露 marker 无法表达的具体需求（须在 HANDOFF 论证）。

## C. Persistence failure protocol（全表失败显式建模，review 第 7 条）

| 项 | 定义 |
|---|---|
| 操作状态 | `ADMITTED → SUCCESS \| FAILED_PERMANENT`（fence 取代时旧记录转 `SUPERSEDED` 语义，由水位比较实现，不逐条改写） |
| 全表 fence 记账 | coordinator 单槽 `latestWholeTableFence(queueSequence, kind=RESET\|RESTORE, success, reason)`；每次 fence 终态覆盖该槽 |
| keyed 记账 | 每键单槽 `latestKeyedOutcome(queueSequence, kind, success)`（有界：每键一条，见"有界状态"） |
| marker 判定顺序 | ① 若 cut 处最新 fence 为 FAILED → **全局 durability 失败**：`failures=[WriteFailure(kind, null, reason)]`，keyed 结果不影响；② 否则以最新**成功** fence 的 queueSequence 为水位，仅对水位后的 keyed 槽按 key 判定；③ `failures` = 最新有效 keyed 终态为 FAILED_PERMANENT 的 key 集合 |
| 恢复规则 | cut 内同 key 更晚成功 keyed 写修复该 key（且仅当该修复在**同一 cut 之前接纳**）；cut 前捕获的 barrier 在恢复前求值 → 失败；恢复后捕获的 barrier → 成功；**成功的全表 fence 取代其之前的一切失败**；**失败的全表 fence 保持未解决**：更晚的 keyed 成功**不能**治愈它；更晚的成功全表 fence 可以治愈/取代它 |
| 失败后的队列 | 队列继续推进、不卡死、不重排；后续 key 照常记账 |
| degraded/dirty 状态 | 无全局布尔；ledger 槽即状态源，内部查询 `pendingWholeTableFailure(): WriteFailure?` 与 `failedWriteKeys(): Set<String>`（测试与 S2 消费），无 UI |
| 错误传递 | 唯一通道 `FlushResult.failures`（operation/key/reason）；公共 setter 不抛持久化异常；restore() 直接向调用方返回终态 FlushResult（见 D.4）；S2 的 D04 握手消费 FlushResult |
| 保留 | `Logs.w` 日志保留，但以 FlushResult/ledger 查询为准（失败是结果不是日志） |

## D. restore/reset：FULL_TABLE fence（review 第 4 条）+ 原子性（第 6 条）+ 单一权威（第 5 条）

### D.1 cache 级 fence API（`KvMemoryCache.kt` **最小解冻**，仅限此用途）

```kotlin
// 新增（prime() 保留为 bootstrap/初始 prime，不再是运行时 restore 通道）：
fun beginFullTableFence(optimisticRows: List<KeyValuePair>): Long   // 返回 cache 全表 fence 代
fun commitFullTableFence(committedEpoch: Long, authoritativeRows: List<KeyValuePair>)
fun abortFullTableFence(committedEpoch: Long)                        // 永久失败路径，见"全表失败镜像策略"
```

admission 临界区（cache 锁内一次完成）——`beginFullTableFence`：
`epoch = ++generationCounter`（cache 单时钟推进，满足"runtime restore/reset 推进 cache 可见全表代"）→
**显式取代全部 pre-fence pending keyed 世代**（`pendingKeys/pendingGenerations` 清空；其后续 ACK 按 stale 规则无效，
失败/在途的 pre-fence 写不再能阻塞快照传播）→ 清除 legacy `pendingReset` sentinel →
`values` 置为 optimisticRows（乐观全表态，read-your-writes）→ 置 `pendingFullTable`。

`commitFullTableFence`（writer 任务完成回调，cache 锁内）：
先快照 post-fence keyed 乐观值（`pendingGenerations` 中 fence 之后 admit 的项）→ `values` 替换为 authoritativeRows →
重放 post-fence 乐观值并保留其 pending（**post-fence keyed 写不被 fence 完成抹除**）→
`lastCommittedFullTableEpoch = committedEpoch`（**读序 guard**：`merge(list, readEpoch)` 中
`readEpoch < lastCommittedFullTableEpoch` 的快照视为 stale，不应用、不删除——旧快照无法回滚 fence）→
`pendingFullTable = false`。

`merge/mergeLocked` 守卫扩展（既有语义上追加）：`pendingFullTable` 为真时快照一律不应用（在途 fence 不可被覆盖）；
`readEpoch < lastCommittedFullTableEpoch` 的快照不应用、不删除（fence 后旧快照不可回滚）；
fresh 快照照常应用（属性 5）。既有 keyed guard（`pendingKeys || lastCommittedGenerations[key] > readEpoch`）不变。

### D.2 store 层通道语义

- **restore(rows)**（admission，在调用线程但**非主线程**——现状已如此）：
  admission 锁内分配 ticket（queueSequence/writeEpoch+1/cacheGeneration=null）→ `cache.beginFullTableFence(rows)`
  → writer 任务：`restoreTransaction { dao.reset(); dao.insert(rows) }`（**单一 Room 事务，原子**，见 D.3）→
  成功：`cache.commitFullTableFence(epoch, rows)` + ledger SUCCESS；失败（含事务回滚异常）：
  `cache.abortFullTableFence(epoch)` + ledger FAILED_PERMANENT（`WriteFailure(RESTORE, null, reason)`）。
  FIFO 总序保证：pre-restore 排队写先落盘、再被事务内 `reset` 抹除——**旧排队写不可能在 restore 落盘后重现**
  （RED 测试 5）；post-restore 写排在其后，落在新库（新 epoch）。
- **reset()**：admission → `cache.beginFullTableFence(emptyList())` → writer 任务 `dao.reset()`（包在同一
  restoreTransaction seam 内，单语句事务）→ commit/abort 同上。legacy `cache.reset()`/`PENDING_RESET`
  路径保留给既有测试与兼容，生产不再使用。
- **全表失败镜像策略（review 第 8 条，先选政策再写码）**——**选定政策 P-OPTIMISTIC-HOLD**：
  1. 永久失败后 getters 看到**乐观 fence 态**（restore=rows / reset=空）——与 keyed 失败的乐观语义一致（I5）；
  2. 失败 fence **不 stamp 读序保护**：fresh snapshot（反映 DB 真值=旧数据）**允许**应用，逐步把镜像拉回真值
     （不自愈会违反 I5"旧失败不得无限期屏蔽远端有效状态"）；在途（PENDING）fence 期间仍阻塞快照；
  3. 恢复途径：下一次成功的全表 fence（再次 restore/reset）在 cache 侧覆盖镜像、在 ledger 侧取代失败；
     重试由调用方（S2 的 D04/恢复协调）驱动；
  4. 清除者：唯一清除者是**下一次成功的全表 fence**；keyed 成功不清除（keyedSuccessDoesNotHealFailedWholeTableFence）；
  5. 失败后的 keyed 写：照常 admit/执行/记账（它们写的是真实 DB 旧态），其结果按普通 keyed 规则参与后续 barrier；
     全局失败仍由 fence 槽表达直到被成功 fence 取代。

### D.3 原子性 seam（review 第 6 条：不得用非事务 reset+insert 替换 BackupFragment 现有事务）

`RoomPreferenceDataStore` 构造函数新增可选参数 `restoreTransaction: (() -> Unit) -> Unit = { it() }`；
`DataStore.kt` 注入 `PublicDatabase.instance::runInTransaction`。restore/reset 的 writer 任务把
`dao.reset()+dao.insert(rows)` 包在该 seam 内执行 → 真实运行时由 Room 事务保证原子（成功全有或全无）；
测试注入可控 fake（模拟回滚）验证 `restoreReplacementIsAtomicOnInsertFailure`。
不改 `KeyValuePair.kt`/`PublicDatabase.kt`，除非实现证明 DAO 默认方法 seam 不可行（须在 HANDOFF 论证）。

### D.4 restore 调用语义冻结（不再 enqueue-and-return）

`restore(rows): FlushResult` **阻塞调用线程**（非主线程；现状 finishImport 已在非主线程）直至 restore 任务终态，
返回其 FlushResult。直接调用方（`BackupFragment.finishImport`）必须：成功才继续备份导入的收尾/重启路径；
失败必须观察到失败（showMessage + 不继续 as-success），不得假装恢复成功。
保留现有 off-main 用法；**不在本批**解决 dbOffMain/runBlocking 全局问题。

## E. reload/start durability（L0 静态分析 + 调用点清单；本批只记录，不改 RuntimeController/dbOffMain）

风险链（静态）：`UI setter（内存乐观可见）→ DB 尚未 durable → reload/start 读取设置`。

**静态清单（review 第 5/6 条要求，已在设计阶段执行并记录；base 1e140ca）**：

| # | 位置 | 写操作 | B3 处置 |
|---|---|---|---|
| 1 | `BackupFragment.kt:569-571`（`PublicDatabase.instance.runInTransaction { kvPairDao.reset(); kvPairDao.insert(decodedSettings) }`） | 设置表 reset+insert（与 576 的 store.restore **双写**） | **移除**；由 `configurationStore.restore()` 单一权威取代（事务 seam 见 D.3） |
| 2 | `BackupFragment.kt:576`（`DataStore.configurationStore.restore(decodedSettings)`，`suspend finishImport` 内，off-main） | restore 唯一调用方 | 消费终态 FlushResult；失败不继续 as-success |
| 3 | `SettingsPreferenceFragment.kt:203`（`DataStore.configurationStore.reset()`，主线程对话框回调，忽略返回值，随后 `triggerFullRestart()`） | reset 唯一生产调用方 | B3 改为通道 fence、返回 Unit（调用点兼容）；**记录缺口**：`triggerFullRestart` = stopService + delay(500) + ProcessPhoenix rebirth（进程死亡），排队 reset 可能未 durable——**不在 B3 修**，S2 用 flush-before-restart 握手关闭 |
| 4 | `BackupFragment.kt:538-561`（SagerDatabase.runInTransaction：router/proxy/group/rules 表） | **非设置表**（SagerDatabase） | 不属于设置通道范围（S3/D06 两库恢复处理）；仅登记 |
| 5 | `RouteFragment.kt:114`（`SagerDatabase.rulesDao.reset()`） | 非 设置表 | 仅登记（S3 范围） |
| 6 | `BackupSerializer.exportDatabase`（runInTransaction 内只读） | 只读导出 | 判定 read-only，无需路由 |
| 7 | `RoomPreferenceDataStore.kt` 内部 put/delete/reset/restore（166/190/97-98/103 行） | 唯一合法通道 | 本批改造对象 |

结论：**设置表（PublicDatabase.kvPairDao）在 store 之外只有一个绕道写入点**（#1），B3 移除后
`configurationStore.restore` 成为单一权威；其余绕道均为非设置表或只读。

**未来关键路径统一链（S2 接线，非本批）**：`mutation → flushPendingWrites() → sync/snapshot → reload/start`；
候选接入点即上表 reload 类路径（GroupManager.kt:155、ConfigurationFragment.kt:1036/2309/2672/2698、
MainActivity.kt:525/531、Utils.kt:233、SwitchActivity.kt:33、QuickToggleShortcut.kt:68、启动 startService）。
`syncNow()` 维持现状（无生产调用方，P01 dead API，flush 的姊妹原语）。

## F. RED tests（确定性；fake DAO/可控 fake transaction + CountDownLatch/CompletableFuture；禁 sleep 作时序控制）

**cache 层全表 fence（新文件 `KvMemoryCacheFullTableFenceTest.kt`，对 base 1e140ca 编译级 RED）**：

| # | 测试 | 断言 |
|---|---|---|
| C1 | `failedPendingWriteIsSupersededBySuccessfulRestore` | put 后不 ACK（pending）→ beginFullTableFence → pre-fence ACK 变 stale no-op；快照传播不再被阻塞 |
| C2 | `staleSnapshotStartedBeforeRestoreCannotRollbackRestore` | captureReadEpoch → begin+commit(rows) → merge(staleSnapshot, readEpoch) → rows 存活 |
| C3 | `staleSnapshotStartedBeforeResetCannotResurrectRows` | 同上，rows=empty → 空 table 存活，旧行不复活 |
| C4 | `postFenceKeyedWriteSurvivesFenceCommit` | begin(rows) → post-fence put → commit → put 值存活且 pending 保留 |
| C5 | `freshSnapshotAfterFenceStillApplies` | commit 后 fresh readEpoch 的 merge 照常应用远端行 |

**store/coordinator 层（`PreferenceWriteQueueTest.kt` 等）**——原 8 条 + review 追加 11 条：

| # | 测试 | 断言要点 |
|---|---|---|
| S1 | barrierWaitsForPriorWrite | DAO 阻塞→flush 未完成→放行→success |
| S2 | barrierDoesNotWaitForLaterWrite | cut 后 admit 的 k2 不延长 barrier |
| S3 | barrierReportsPermanentWriteFailure | 恒抛 DAO → failures 含 key；镜像保留乐观值；setter 不抛 |
| S4 | newerSuccessfulMutationCanRecoverFromPreviousFailure | 同 key cut 内后写成功修复 |
| S5 | queuedWriteBeforeRestoreCannotCommitAfterRestoreWinner | restore 后 fake DAO 终表==rows，无 v1 |
| S6 | multipleWritesBarrierCompletesOnlyAfterAllPriorWrites | 两写全放行才完成，completed==2 |
| S7 | deleteParticipatesInBarrier | delete 阻塞/放行参与 barrier；DB 无行 |
| S8 | repeatedFlushWhenCleanReturnsImmediately | 干净时立即返回 |
| S9 | barrierCapturedBeforeRecoveryStillFails | 恢复操作在 cut 之后 admit → 本 barrier 仍失败 |
| S10 | barrierCapturedAfterRecoverySucceeds | 恢复后新捕获的 barrier 成功 |
| S11 | restoreReplacementIsAtomicOnInsertFailure | fake transaction 回滚 → 终表不变；restore 报失败 |
| S12 | restoreFailurePreventsSuccessfulReturn | restore 返回 success=false；BackupFragment 消费契约：不继续 as-success（store 层断言返回值契约；UI 路径以代码走查+合同断言覆盖） |
| S13 | restoreFailureAppearsInFlushResult | failures 含 WriteFailure(RESTORE, null, reason) |
| S14 | resetFailureAppearsInFlushResult | failures 含 WriteFailure(RESET, null, reason) |
| S15 | keyedSuccessDoesNotHealFailedWholeTableFence | 失败 fence 后 keyed 成功 → barrier 仍全局失败 |
| S16 | laterSuccessfulWholeTableFenceRecoversEarlierFenceFailure | 后续成功 fence 取代早先 fence 失败 → barrier 成功 |

保留扩展（v1 高价值项）：listener 重入写保持 admission 顺序且锁外通知（I7）、等待者取消传播且已接纳写入完成、
并发 invalidation 合并为一次 refresh、bootstrap prime 失败可观察不伪装空表、
`ticketMapsToCacheGeneration`（断言映射正确而非计数器相等，review 第 3 条）、reset 通道化回归
（镜像即清、DAO 失败可观察；SettingsPreferenceFragment 调用点兼容）。

RED 形式：fence/flush/coordinator API 在 base `1e140ca` 不存在 → Run L 编译级 RED（同 run-b 先例，如实标注）；
现有 API 可表达的对照断言（如 S3 的"异常不冒泡 setter"）尽量补断言级红。证据 `docs/agent/evidence/s1-b3/`。

## G. 范围修订（review 第 11 条）

**允许**：
- `RoomPreferenceDataStore.kt`（admission/ledger/marker/flush/restore-reset 路由/restoreTransaction 注入点）
- `PreferenceWriteCoordinator.kt`（确有职责聚合才新增）
- `KvMemoryCache.kt` **仅限 D.1 全表 fence 最小语义**（begin/commit/abort + merge 守卫扩展 + 单时钟 fence 代；其余冻结）
- `BackupFragment.kt` **仅限**移除带外设置写（#1）并消费 authoritative restore 结果（D.4）
- `KeyValuePair.kt` / `PublicDatabase.kt` 仅当 D.3 seam 不可行时的原子事务最小改动
- 对应 JVM tests + metadata/evidence

**仍排除**：dbOffMain 清理、RuntimeController、network debounce、ConfigSnapshot、sing-box、性能、UI/品牌、
P2-TEST-ROBUSTNESS（`Thread.sleep(80)`）、无关重构、SagerDatabase 非设置表写入（S3）。

## H. 有界 coordinator 状态（review 第 9 条）

无操作日志式 journal。状态上限 ≈ `outstanding 队列深度 + 活跃 marker 数 + 每 key 一条有效槽 + 全表 fence 单槽`：

- `pendingQueue: ArrayDeque<WriteOp>` —— 已 admit 未终态（含 marker），终态即出队；
- `latestKeyedOutcome: HashMap<String, KeyedOutcome>` —— 每键仅保留最新一条（新终态覆盖旧条目；fence 成功后
  水位前的条目惰性失效、不主动清扫——总量受键数上界约束，设置表键数有限）；
- `latestWholeTableFence: WholeTableOutcome?` —— 单槽；
- `activeMarkers: Queue<CompletableFuture<FlushResult>>` —— 并发 flush 数上限。

终态历史不保留：terminal 回调只覆盖槽位并出队，旧记录立即折叠。barrier 求值只读槽位（B.2 FIFO 论证保证正确性）。

## I. 实现约束（不变部分，沿用 v1 + 修订）

- 注入点仅限测试：DAO、`restoreTransaction`、delay 策略（替代 50/100/200ms sleep）、executor/调度器；生产默认行为不变。
- 重试 3 次/50/100/200ms 保留，不为文档机械改参；失败是 FlushResult 结果不是日志。
- admission 单锁（分配 ticket + 改镜像 + 入队）；**禁止锁内调 listener/Binder/JNI**；`fireChangeListener` 锁外（I7）。
- marker 之外**禁止**再引入 lock+Condition 等待机构（review 第 2 条）；flush 等待可取消（Dispatchers.IO + 可中断）。
- 快照读取/发布维持 snapshotLock 单通道；`syncNow` 不绕过。
- 禁止：`allowMainThreadQueries`、主线程 runBlocking（存量 init `runBlocking(Dispatchers.IO)` 归 S2）、
  sleep/delay 同步、扩线程池、吞异常、删断言、用超时掩盖失败。

## J. 验证命令 / 回退 / push / 停止条件（实现阶段）

```powershell
./gradlew.bat :app:testOssDebugUnitTest --tests "*KvMemoryCache*" --tests "*RoomPreferenceDataStore*" --tests "*PreferenceWrite*" --no-daemon --console=plain
./gradlew.bat :app:testOssDebugUnitTest --no-daemon --console=plain
```

cwd=仓库根；shell=PowerShell；GREEN exit 0 且 0 failures；证据 `docs/agent/evidence/s1-b3/`（Run L=RED、M/N=GREEN）。
回退：revert 本批单 commit 回 `1e140ca`；FlushResult/fence 为新增 API，回退无破坏；无 schema/签名变更。
分支：`fix/p01-room-off-main-thread`（非 main）。
**停止条件：网页 ChatGPT 最终确认本 v2 设计后才实现**；实现完成后 `WAIT_CHATGPT_AUDIT`；
ACCEPTED 后 S1 仅剩阶段验收核对（B1—B3 + I1—I8），再签发 S2-B1；CHANGES_REQUIRED 只修本批。

## K. 明确不在本批

S2 关键操作握手（把 flush 接进 reload/start/reset-before-restart）、restore 完整重写（S3/D06：两库、中断恢复、
SagerDatabase 表）、prime 失败 UI 状态机（S2/D05）、双进程验证（S2-B3）、dbOffMain/runBlocking 全局改造、
P2-TEST-ROBUSTNESS 清理。
