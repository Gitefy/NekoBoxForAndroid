# 当前工作单：S1-B3-WRITE-QUEUE-DURABILITY-BARRIER（DRAFT — 设计待网页 ChatGPT 确认，未实现）

状态：DRAFTED / AWAITING_DESIGN_CONFIRMATION。
Owner：Cursor。
独立审计：网页 ChatGPT（固定 GitHub SHA；本单先做**设计确认**，确认前禁止实现）。
base_code_sha：`1e140ca720a54dfa42cc37235484af7faae499bf`（S1-B2 accepted candidate，CI PASS）。
上一工作单：S1-B2-CONFIRM-SEMANTICS-CLOSURE（ACCEPTED，见 REVIEW.md）。
取代：本单取代此前草稿名 `S1-B3-WRITE-QUEUE-FLUSH-BARRIER`；本文按用户/审计 A—H 规格完整重定义。

## 0. 本单性质与停止条件

架构正确性批次。**本轮只交付本工作单（设计定义），不改任何业务源码、不写测试、不实现。**
实现必须等网页 ChatGPT 明确确认本单设计（A—H）之后，按既有节奏单独执行：
RED（Run L）→ 最小实现 → GREEN（Run M focused / Run N full）→ commit 代码候选 → push → metadata → `WAIT_CHATGPT_AUDIT`。

## 目标（DESIGN D03/D04 前置落地）

把 preference 持久化从"单线程 executor + 日志级失败"升级为**可观察、可等待、有序、可 fence 的写通道**，
并提供 `flushPendingWrites()` durable barrier。保持 P01 结构（单线程 FIFO writer、3 次重试、
snapshotLock、readEpoch、per-key generation），不迁移并发模型（见 H）。

## A. flushPendingWrites 的精确定义

```kotlin
// RoomPreferenceDataStore 新公开方法（本单冻结）：
suspend fun flushPendingWrites(): FlushResult

data class FlushResult(val success: Boolean, val completed: Int, val failures: List<WriteFailure>)
data class WriteFailure(val key: String, val reason: String)   // reason=异常类名+message；不持有 Throwable
```

**成功唯一语义**：`success=true` 当且仅当——

> 在 `flushPendingWrites()` 调用开始时刻**已被 store 接纳（admitted）**的所有 mutation（含 put/delete/restore/reset
> 通道任务），均已到达 durable 终态：其 SQL 已在 DB 上执行完毕（commit 由单通道任务完成回调证实），
> 且在 barrier 判定时刻该 key 的最新终态不是永久失败。

**明确禁止的伪成功**：
- 不得把 "writer queue 已跑空 / executor 空闲" 当作成功（队列空 ≠ 前置操作终态齐全，且 Future 语义不覆盖失败）。
- 不得把 "等待超时/被取消" 当作成功。
- 不得把 "该 mutation 已被 restore/reset 取代" 记为失败之外又含糊成功——取代由 fence 终态 `SUPERSEDED` 显式表达（见 D）。
- 任何 barrier 覆盖范围内 mutation 最终持久化失败 → `success=false` 且 `failures` 含该 key（幂等去重后）。

`completed` = 本 barrier 覆盖并到达终态的 operation 数（含 SUPERSEDED，便于诊断）；barrier 后新接纳的操作不计入。

## B. Barrier 边界

1. **捕获点**：进入 `flushPendingWrites` 后，在 coordinator 的 admission 锁内读取当前 `sequence` 作为
   `upTo`（单一分配点，见"冻结签名"：sequence 与 cache generation 对 keyed 操作 1:1 同锁分配；
   restore/reset 消耗 coordinator 专属 sequence）。
2. **等待集合**：所有 `sequence ≤ upTo` 的 operation；**之后**新接纳的 mutation（sequence > upTo）不延长本次
   barrier——barrier 不追逐未来写入。
3. **同 key 多 generation**：每个 operation 独立终态（ledger 逐项记账）；key 的 barrier 判定结果 =
   `sequence ≤ upTo` 中**最新终态**的 outcome（最新成功即干净；最新终态为失败即失败）。旧 generation 被
   新 generation 取代时仍须各自到达终态（其 DB 写仍会执行并提交，cache ACK 按既有 stale 规则忽略）。
4. **delete 与 put 统一模型**：delete 是普通 operation（进入同一 ledger/同一 barrier；`dao.delete` 影响 0 行
   亦为成功——终态即"该 key 在 DB 无行"）。restore/reset 是 fence 型 operation（见 D），同样占 sequence。
5. **等待实现**：锁 + Condition（`awaitAllTerminal(upTo)`），flush 挂在 `Dispatchers.IO` 且
   `runInterruptible`/`suspendCancellableCoroutine` 包装——**writer 线程绝不阻塞等 barrier**；
   等待者取消只取消等待，不取消已接纳写入（回归 T8）。
6. **无超时**（本批）：writer 为常驻单线程、任务不外抛，理论上必达终态；不设 timeout，S2 接入方自行决定
   调用策略。此决定显式记录，防止"用超时掩盖失败"。

## C. Persistence failure protocol

现状（base `1e140ca`）：3 次重试（50/100/200ms）耗尽后仅 `Logs.w`，系统假装成功——本批终结该状态。

| 项 | 定义 |
|---|---|
| mutation 成功状态 | `SUCCESS`：DAO 调用无异常返回；cache ACK 按既有 generation 规则处理；ledger 记 `lastSuccess[gen,key]` |
| mutation 最终失败状态 | `FAILED_PERMANENT`：3 次重试全部异常；ledger 记 `lastFailure[gen,key]`；镜像保留乐观值、pending 保留（既有语义）；`Logs.w` 保留 |
| fence 取代状态 | `SUPERSEDED`：被 restore/reset fence 判定为不落盘的旧 epoch 操作（见 D），非失败非成功，不计入 failures |
| barrier 如何看到失败 | `awaitAllTerminal` 返回时，收集"`sequence ≤ upTo` 且 key 最新终态为 FAILED_PERMANENT"的 key 集合 → `FlushResult(success=false, failures)` |
| 下一次 put 是否恢复 | **是**：同 key 更新的 put 成功后 `lastSuccess > lastFailure`，后续 barrier 视该 key 干净（RED 测试 4）；旧失败不屏蔽新状态 |
| degraded/dirty 状态 | 本批**不引入**全局 dirty 布尔；ledger 即状态源，提供内部查询 `failedWriteKeys(): Set<String>`（测试与 S2 消费），不做 UI |
| 错误传给 durable 调用方 | 唯一通道是 `FlushResult.failures`（key + reason）；公共 setter 签名与行为不变、不抛持久化异常；S2 的关键握手（D04）消费 FlushResult |
| 失败后的队列 | 队列继续推进，不卡死、不重排；后续 key 的写入照常（RED 测试 6 相关回归） |

## D. restore/reset 与排队写入（fence 语义；本批最小修复）

**现状根因（base `1e140ca`，L0 静态核对）**：
`restore()` 在 `Dispatchers.IO` 直接执行 `kvPairDao.reset()+insert`，与 writer 线程**并发**——
`put(v1) 已入队未执行 → restore 先跑完 → 旧 put(v1) 随后落盘` 会污染恢复后的库。
`reset()` 直接 `kvPairDao.reset()` 且完全绕过镜像（不清缓存、无 sentinel ACK）——同样是绕通道缺陷。

**修复（最小、总序）**：restore 与 reset 的 DB 段作为**一个任务排入同一条 writer FIFO**：

1. `restore(rows)`：同步 `cache.prime(rows)`（read-your-writes 不变）→ admission 分配 sequence + **新 write-epoch** →
   writer 任务 `dao.reset(); insert(rows)` → 完成回调标 ledger 终态并 `cache.merge` 校准（既有语义）。
   FIFO 总序保证：restore 前入队的 put/delete 先执行、其 SQL 先落盘、随后被 `dao.reset` 抹除——
   **旧排队写不可能在 restore 落盘后重现**；restore 后入队的写落在新库上（新 epoch）。
2. `reset()`：admission 分配 sequence + 新 epoch → `cache.reset()` 同步清镜像（sentinel pendingReset，既有语义）→
   writer 任务 `dao.reset()` → 完成回调走既有 `writeCommitted(PENDING_RESET)` 清 sentinel；失败可观察（A/C）。
3. 三个指定竞争的判定：
   - `put(v1) 排队未执行 → restore → put 执行`：put 先落盘，被 reset 抹除；终态记 `SUCCESS` 后由 restore 整体取代，
     barrier 汇总不因它失败（它本身成功；DB 最终态=restore 赢家）。**测试 5** 断言 fake DAO 最终表 = restored rows，无 v1。
   - `delete → restore`：delete 先执行（行已删），restore 重写全表。
   - `多个 queued mutations → restore`：FIFO 依序执行后统一被 restore 取代；barrier（覆盖它们）在这些任务终态后返回。
4. 旧失败 × restore：restore 完成后，其 epoch 之前的 FAILED_PERMANENT 一律 `SUPERSEDED`（整表被替换，"失败"不再有意义）；
   不向后续 barrier 报告。flush 捕获点在 restore 之前则如实报告当时失败。
5. epoch 与 cache：`KvMemoryCache.kt` 本批**冻结不改**（S1-B2 刚验收）；write-epoch 只存在于 coordinator，
   用于 ledger 取代判定；镜像层既有 pendingReset/readEpoch 语义不动。restore 内 `cache.prime/merge` 既有调用保持。

## E. reload/start durability（现状分析，L0；本批只记录，不改 RuntimeController/dbOffMain）

静态调用点核对（base `1e140ca`）：

| 路径 | 位置 | 现状风险 |
|---|---|---|
| 组管理变更后全量 reload | `GroupManager.kt:155 → SagerNet.reloadServiceFully()` | 组/配置写入与 reload 之间无 durable 屏障 |
| 配置页代理更新/切换后 reload | `ConfigurationFragment.kt:1036/2309/2672/2698 → SagerNet.reloadService()` | setter 乐观可见，DB 未 durable 即 reload |
| 主界面/快捷开关/切换页 reload | `MainActivity.kt:525/531`、`Utils.kt:233`、`SwitchActivity.kt:33`、`QuickToggleShortcut.kt:68` | 同上 |
| 启动路径 | `SagerNet.startService()` → `BaseService` init/reload 读 DataStore/Database | 进程早期读库；crash/跨进程窗口可见旧值 |
| `syncNow()` | P01 引入，**当前无生产调用方**（dead API） | flush 的姊妹原语；S2 决定接线 |

**未来关键路径统一链（S2 接线，非本批）**：`mutation → flushPendingWrites() → sync/snapshot → reload/start`。
本批交付 flush 原语与失败协议，不改编以上任何调用点；`syncNow` 保持现状（snapshotLock 通道）。

## F. RED tests（确定性；fake DAO + CountDownLatch/CompletableFuture；禁 sleep 作为时序控制）

必含（用户指定 8 条）：

| # | 测试 | 机制要点 |
|---|---|---|
| 1 | `barrierWaitsForPriorWrite` | put 后 DAO 阻塞于 latch；flush 未完成；放行 → flush success |
| 2 | `barrierDoesNotWaitForLaterWrite` | put(k1) 阻塞 → flush 启动 → 再 put(k2)（阻塞不放行）；放行 k1 → flush 返回且 k2 仍 outstanding |
| 3 | `barrierReportsPermanentWriteFailure` | fake DAO 恒抛异常 → flush `success=false` 且 failures 含该 key；镜像保留乐观值；setter 不抛 |
| 4 | `newerSuccessfulMutationCanRecoverFromPreviousFailure` | 首写失败、同 key 二写成功 → 后续 flush success（C 恢复语义） |
| 5 | `queuedWriteBeforeRestoreCannotCommitAfterRestoreWinner` | put(v1) 阻塞 → restore(rows) 入队 → 放行 put → 最终 fake DAO 表 == rows，无 v1；v1 记 SUCCESS 或 SUPERSEDED，最终表不被污染 |
| 6 | `multipleWritesBarrierCompletesOnlyAfterAllPriorWrites` | 两写分别阻塞，flush 在两放行后才完成，completed==2 |
| 7 | `deleteParticipatesInBarrier` | put+delete（delete 阻塞）→ flush 等 delete 终态；DB 无该行 |
| 8 | `repeatedFlushWhenCleanReturnsImmediately` | 无 outstanding、无未恢复失败 → flush 立即 success |

扩展（保留既有草稿中的高价值项，仍禁 sleep）：listener 重入写保持 admission 顺序且锁外通知（I7）、
等待者取消传播且已接纳写入完成、并发 invalidation 合并为一次 refresh、prime 失败可观察不伪装空表、
reset 走通道（镜像即清 + DAO 失败可观察 + sentinel ACK）、同 key 多 generation barrier 判定（B.3）、
delete 与 put 统一 ledger、`sequence==cache.generation` 1:1 不变式断言。

**RED 形式（如实标注）**：`flushPendingWrites/FlushResult/WriteTicket/PreferenceWriteCoordinator` 在 base
`1e140ca` 上不存在 → Run L 为**编译级 RED**（同 run-b 先例）；凡现有 API 可表达的断言（如 failure 不冒泡 setter 的
对照行为）尽量补断言级红。证据存 `docs/agent/evidence/s1-b3/`。

## G. 范围限制

**允许修改**：
- `app/src/main/java/io/nekohasekai/sagernet/database/preference/RoomPreferenceDataStore.kt`（admission/ledger/flush/restore-reset 路由/注入点）
- 新增同包 `PreferenceWriteCoordinator.kt`（仅当职责确需聚合；禁止无功能分层）
- 对应 JVM tests（新增 `PreferenceWriteQueueTest.kt` 等）
- restore/reset **仅限** D 所述 queue-fence 必须部分

**冻结不改**：`KvMemoryCache.kt`（S1-B2 刚验收；若实现暴露编译级冲突，须在 HANDOFF 说明理由并最小处理）。

**禁止顺带处理**：dbOffMain/runBlocking 主线程阻塞（含存量 init `runBlocking(Dispatchers.IO)`，S2）、
RuntimeController、network debounce、ConfigSnapshot、sing-box、性能优化、UI/品牌、
P2-TEST-ROBUSTNESS（`Thread.sleep(80)` 测试清理）、无关重构。

## H. 方案比较与推荐

| | 方案 1（推荐）：现有单线程 writer executor + 单调 sequence + completion/failure ledger + barrier | 方案 2：coroutine actor/channel 串行状态机 |
|---|---|---|
| 与 P01 的差异 | writer 结构/重试/线程数不变；仅加 admission 锁、ledger、Condition barrier、restore/reset 入队 | 整个 store 并发模型重写（含 init runBlocking、invalidation 回调、restore） |
| barrier/失败表达 | lock+Condition + per-op ledger 足以精确表达 A/B/C；FIFO 总序使 restore fence 几乎零成本表达 | 语义等价，但需重造 job/supervision 与取消语义 |
| 风险 | 低（增量、可回退） | 高（迁移即大改 P01，违背 D01-A 与单批纪律） |
| 测试确定性 | fake DAO + latch 直接可控 | 需虚拟时间/调度注入，改动面更大 |
| 结论 | **采用** | 仅当方案 1 无法表达 A—C 时再提案（现为否） |

推荐理由：D01-A 渐进修复原则；现有 executor 完全可表达 barrier/failure/fence（本单 A—D 已给出精确语义）；
方案 2 属并发模型整体迁移，必须独立立项，不得混入 S1-B3。

## 冻结的 Kotlin 签名（实现阶段以本节为准）

```kotlin
// RoomPreferenceDataStore 同包内部契约；不跨进程比较。
data class WriteTicket(val epoch: Long, val sequence: Long)   // epoch=写纪元(restore/reset 推进)；sequence=admission 单调序
data class WriteFailure(val key: String, val reason: String)  // reason=异常类名+message，不携带 Throwable
data class FlushResult(val success: Boolean, val completed: Int, val failures: List<WriteFailure>)

suspend fun flushPendingWrites(): FlushResult   // RoomPreferenceDataStore 公开；Dispatchers.IO + 可中断等待
```

- 公共 setter 签名不变；token 为内部契约。keyed 操作 `sequence` 与 `cache.put/delete` 返回的 generation 在
  同一 admission 临界区内 1:1 分配（不变式，测试断言）；restore/reset 只消耗 coordinator sequence + 推进 epoch。
- admission 临界区（单锁）完成：分配 sequence、修改镜像、入队；`fireChangeListener` 必须锁外（I7）。
- `syncNow()` 签名不变，维持 snapshotLock 通道。

## 验证命令（实现阶段）

```powershell
./gradlew.bat :app:testOssDebugUnitTest --tests "*KvMemoryCache*" --tests "*RoomPreferenceDataStore*" --tests "*PreferenceWrite*" --no-daemon --console=plain
./gradlew.bat :app:testOssDebugUnitTest --no-daemon --console=plain
```

cwd=仓库根；shell=PowerShell；GREEN exit 0 且 0 failures；证据 `docs/agent/evidence/s1-b3/`（Run L=RED、M=GREEN focused、N=GREEN full）。

## 回退方案

revert 本批单 commit 回 `1e140ca`；FlushResult 为新增 API，回退无破坏；无 schema/格式变更。

## push 分支 / 停止条件

分支：`fix/p01-room-off-main-thread`（非 main）。
**当前停止条件：设计确认**——网页 ChatGPT 明确 ACCEPT 本单 A—H 前，不写任何实现代码。
实现获准后的停止条件：候选 push → `WAIT_CHATGPT_AUDIT`；`ACCEPTED` 后 S1 仅剩阶段验收核对（B1—B3 + I1—I8 覆盖），再签发 S2-B1；`CHANGES_REQUIRED` 只修本批。

## 明确不在本批

S2 关键操作握手（D04：把 flush 接进 reload/start 调用点）、restore 完整重写（S3/D06：两库事务/中断恢复）、
prime 失败 UI 状态机（S2/D05）、双进程验证（S2-B3）、P2-TEST-ROBUSTNESS 清理、dbOffMain/runBlocking 改造。
