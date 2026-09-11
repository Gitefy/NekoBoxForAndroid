# S2-B2 交接记录

执行期间切换为**项目所有者临时授权**：`S1..7 间逐批审计门槛`被临时覆盖（见 `docs/agent/STATUS.md` 顶部与末节）；`STATUS/AGENTS.md` 已写入封面注释指向授权段，未重写设计档案。

## 已关闭

- **S1-B1（ACCEPTED）**：`cc63f248..e9b92cf`
- **S1-B2（ACCEPTED，CI PASS）**：`e9b92cf..1e140ca`
- **S1-B3**：候选 `d1be7dc` / metadata `ea2d901`，**IMPLEMENTED_PENDING_AUDIT**
- **S2-B1**：候选 `354c472` / metadata `7ea8740`，**IMPLEMENTED_PENDING_AUDIT**

> S1-B3 与 S2-B1 均未标 `ACCEPTED`，`last_accepted_code_sha=1e140ca` 不变；S2-B2 基于未审链 `d1be7dc→354c472` 连续实施，依赖关系已显式记录。

## 本批 S2-B2-APPLY-STOP-HANDSHAKE（CANDIDATE_PUSHED）

范围：`SagerNet.kt` 的 `start/reload/stop`；`bg/SagerConnection.kt`、`bg/BaseService.kt`；`Constants.kt` 及 `aidl/*`；`ui/ConfigurationFragment.kt`、`MainActivity.kt`、`SettingsPreferenceFragment.kt`、`SwitchActivity.kt`、`QuickToggleShortcut.kt`、`QuickEnableShortcut.kt`、`database/GroupManager.kt` 的控制入口；`ktx/Utils.kt` 的重启入口。只改相关调用链。

### 差异（scope `354c472..07e7f45`）

- `aidl/ISagerNetServiceCallback.aidl`：新增 `oneway commandResult(requestId, outcome, instanceGeneration, persisted, errorCode)`（关联回执，不引入外部接口）。
- `Constants.kt / Action`：新增 `APPLY` + `EXTRA_REQUEST_ID/KIND/TARGET_PROFILE_ID`。
- `bg/ApplyModels.kt`：新增 `CommandKind{START,RELOAD,STOP}` / `CommandOutcome{APPLIED,STOPPED,FAILED,SUPERSEDED}` / `ApplyRequest(requestId, kind, targetProfileId, routerStableTag, routerMemberId, forceFullReload)`（router 字段同有或同空校验）/ `ApplyResult(requestId, outcome, instanceGeneration, persisted, errorCode)` / `ApplyErrorCodes{FLUSH_FAILED,PERSIST_FAILED,INVALID_TARGET,NOT_READY,TIMEOUT}`；`generateRequestId()`。
- `bg/ApplyCoordinator.kt`：单例 coordinator；`commandGeneration(AtomicLong)` 单调；`pendingByKind(CommandKind→PendingRequest)` 单终态；`accept` 分配 generation 并立即将同类前一 pending 以 `SUPERSEDED` 完成；`publish` 仅当 `pending.generation==generation` 时完成其 deferred（stale 静默丢弃）；`awaitResult` 30s 观察超时未决时返回 `FAILED+PERSIST_FAILED/TIMEOUT`（不自动重发，不视为已回滚）；`awaitReadyAndFlush` 共享门禁（`Ready`→`flushPendingWrites`，失败码 `NOT_READY/FLUSH_FAILED`）。
- `bg/ApplyService.kt`：`gateForSettingsApply()`（门禁原语）；`applyCommitted(request, generation)` 接收端入口（`STOP` 仅 `awaitReady`，其余 `awaitReadyAndFlush`；`readCommittedSettingsSnapshot()` 非乐观镜像校验显式目标，缺失/无效即 `FAILED` 不回退到其他节点，router 键对校验，无显式目标时从提交快照/已提交设置解析目标，失败即 `FAILED`）；`broadcastToRequest` 兼容适配。
- `bg/SagerConnection.kt + CallbackWithCommandResult.kt`：`serviceCallback.commandResult` 分派到 `CallbackWithCommandResult.onCommandResult`。
- `SagerNet.kt`：新增 `startServiceViaApply(request)`（显式 Intent 启动+ `APPLY` 广播，小请求）。
- `bg/BaseService.kt`：接收器统一为同一 `ApplyRequest` 处理函数：`RELOAD` 兼容分支仍经 `broadcastToRequest→accept→applyCommitted→publish`，`APPLY` 直通；receiver `IntentFilter` 注册 `Action.APPLY`；失败回退保持既有 `reload` 语义，新增协议与发/收同候选提交（无半升级）。

### 本地测试（真实执行）

| run | dst | 命令 | exit | 统计 | 证据 |
|---|---|---|---|---|---|
| M-GREEN（含新 9） | `07e7f45` | `:app:testOssDebugUnitTest --tests "*ApplyHandshake*" --tests "*StoreAsyncReadiness*" --tests "*KvMemoryCache*" --tests "*PreferenceWrite*"` | 0 | `70/70` | `docs/agent/evidence/s2-b2/run-m-apply.txt` |
| N-GREEN（全量） | `07e7f45` | `:app:testOssDebugUnitTest` | 0 | `174/174` | `docs/agent/evidence/s2-b2/run-n-green-full.txt` |

`--no-daemon --console=plain`；`--tests` 组合命中含 `ApplyHandshakeTest` 9 项：`failedFlushDoesNotSendApply`/`routerSelectionCommitIsAwaited`/`explicitTargetWinsOverStaleMirror`/`invalidTargetFailsWithoutFallback`/`staleRequestResultCannotOverrideNewRequest`/`duplicateRequestIsNotAppliedTwice`/`stopAckWaitsForCleanup`/`stopDuringStartCannotReconnect`/`timeoutDoesNotReportRollback`。公共传输字段仅含 ID/布尔/脱敏错误码，不输出真实配置。

### CI

`07e7f45` 已推 `origin/fix/p01-room-off-main-thread`；`ci_status=NOT_AVAILABLE`（本机查询受限，以 GitHub 实际记录为准）。

## 下一动作

本地必需验证已通过；按临时授权继续实施 **S2-B3** — 本批起**仅允许准备** `S2-B3-CROSS-PROCESS-VERIFICATION` 的测试代码及编译检查，不得安装 APK、操作设备或进入 `S3`。完成后即结束授权范围并恢复逐批审计。
