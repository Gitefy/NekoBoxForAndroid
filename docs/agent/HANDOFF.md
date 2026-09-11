# S2-B1 交接记录

执行期间切换为**项目所有者临时授权**：`S1..7 间逐批审计门槛`被临时覆盖（见 `docs/agent/STATUS.md` 顶部与末节）；`STATUS/AGENTS.md` 已写入封面注释指向授权段，未重写设计档案。

## 已关闭

- **S1-B1（ACCEPTED）**：`cc63f248..e9b92cf`；修复 P1 snapshot 串行化。
- **S1-B2（ACCEPTED，CI PASS）**：`e9b92cf..1e140ca`；补齐防御性拷贝与写版本契约；七项永久回归约束冻结。
- **S1-B3**：候选 `d1be7dc` / metadata `ea2d901`，**IMPLEMENTED_PENDING_AUDIT** — 按临时授权保留，不重做，不标 `ACCEPTED`，`last_accepted_code_sha=1e140ca` 不变。

## 本批 S2-B1-ASYNC-READINESS（CANDIDATE_PUSHED）

范围：`SagerNet.kt`；`database/DataStore.kt`；`database/preference/RoomPreferenceDataStore.kt`；`ktx/Db.kt`；`bg/*` 中读数据库标题的路径；`ui/MainActivity.kt`、`ui/SettingsPreferenceFragment.kt` 及 bootstrap 调用方；`bg/ServiceNotification.kt`/`bg/proto/ProxyInstance.kt`；**允许必要的 Loading/错误展示**。

### 差异（scope `d1be7dc..354c472`）

- `database/preference/RoomPreferenceDataStore.kt`：导入 `StoreReadiness(Loading/Ready/Failed)`、`readinessScope`+`MutableStateFlow`、`primeJob`+`bootDirty`+`bootstrapDone`、`awaitReady()`/`isReady()`/`retryPrime()`（`retryMutex` 单飞）、`launchPrime()` + 构造期 `readiness=Loading` + `invalidationSource` 记录窗口 dirty。构造期零 SQLite：`launchPrime` 内才 `readAndMergeSnapshot()`；`readiness=Failed` 时状态码为异常名且不写入任何默认值。
- `database/preference/KvMemoryCache.kt`：`prime` 在 bootstrap 窗口保留已接纳写入（`pendingKeys`/`pendingGenerations`/`pendingReset` 快照回写），防止 late prime 覆盖用户写。
- `database/DataStore.kt`：暴露 `awaitReady()`/`configurationReady()`/`isConfigurationReady()`，`currentGroupId()` 先检查 `isReady()==-1` 短路不再 `dbOffMain`，新增 `currentGroupIdAsync()`/`currentGroupAsync()` 以协程形式 `awaitReady` 后再 `dbOffMain`，`currentGroup()` 在未 Ready 主动 `error(...)`。
- `bg/BaseService.kt`：`[E03]` 前台合法晋升：`onMainDispatcher` 用应用名占位 `createNotification(...app_name)`→ 立即 `show()`，失败即 `stopRunner(..., foreground service)`，随后 `awaitReady()` 再 `SagerDatabase.proxyDao.getById(selectedProxy)`，`ProxyInstance` 由占位后起，标题 `ServiceNotification.genTitle(profile)` 改为后台异步 `postNotificationTitle`；原"profile 查询后再建通知"整段被前置到 Ready 门禁之前。
- `ui/MainActivity.kt`/`ui/SettingsPreferenceFragment.kt`/`SagerNet.kt`/`ktx/Db.kt`/`SagerDatabase.kt` 等剩余调用方未在本批重建业务根节点，仅记录于本 handed-off 下的"剩余清单"。

### 本地测试（真实执行）

| run | dst | 命令 | exit | 统计 | 证据 |
|---|---|---|---|---|---|
| M-GREEN | `354c472` | `:app:testOssDebugUnitTest --tests "*StoreAsyncReadiness*" --tests "*KvMemoryCache*" --tests "*RoomPreferenceDataStore*" --tests "*PreferenceWrite*"`（`--rerun-tasks`） | 0 | `62/62`（含新 `StoreAsyncReadinessTest` 6 项：`constructorDoesNotReadDao`/`failedLoadDoesNotWriteDefaults`/`dependentActionWaitsForReady`/`retryLoadIsSingleFlight`/`invalidationDuringBootstrapIsObserved`/`notificationPromotionDoesNotAwaitDb`） | `docs/agent/evidence/s2-b1/run-m-green-focused.txt` |
| N-GREEN | `354c472` | `:app:testOssDebugUnitTest` | 0 | `165/165` | `docs/agent/evidence/s2-b1/run-n-green-full.txt` |

`--no-daemon --console=plain`；`--rerun-tasks` 后 `34 tasks executed`，`run-n` `34 tasks: 3 executed`。设备门槛（StrictMode/trace 启动与设置页面）标 `NOT_RUN`（无设备执行环境）。

### 剩余清单（S2-B1 允许保留，S7 前清完）

需后续批次/冻结前清理的非关键 `dbOffMain` 同步调用（均在后台路径，主线程不再直接等待）：
`SagerNet.onCreate` 内 `PackageCache/register & cleanWebview` 已异步；遗留集中在 `DataStore` bootstrap（`currentGroupId`/`currentGroup` 同步变体）、`ServiceNotification.genTitle` 默认 `groupNameProvider` 的同步查询（已在 B1 被异步调用方包裹）、`ui/MainActivity` 部分历史拉取。已按 S2-B1 设计逐一迁移或标注，下批不再重建为 `dbOffMain`。

### CI

`354c472` 已推 `origin/fix/p01-room-off-main-thread`；`ci_status=NOT_AVAILABLE`（本机查询受限，以 GitHub Actions 实际记录为准，不以 push 冒充 PASS）。

## 下一动作

本地必需验证已通过（`162` 报名项无新增编译错误、`M`/`N` 全绿）；按临时授权**不等待网页审计**，直接进入 `S2-B2-APPLY-STOP-HANDSHAKE`，其基线为 `354c472`（依赖的未审候选链：`1e140ca(d1be7dc PENDING)..354c472`）。
