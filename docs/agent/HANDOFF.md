# S1-B3 交接记录

模式：Cursor 实现 → GitHub 固定 SHA → 网页 ChatGPT 审计 → receipt 回 Cursor。

## 已关闭

- **S1-B1（ACCEPTED）**：`cc63f248..e9b92cf`；修复 P1 snapshot 串行化；在途 snapshot 全程由 `snapshotLock` 协调。
- **S1-B2（ACCEPTED，CI PASS）**：`e9b92cf..1e140ca`；补齐防御性拷贝与写版本/回调契约；七项永久回归约束冻结。

## 本批 S1-B3：WRITE-QUEUE-DURABILITY-BARRIER（CANDIDATE_PUSHED / WAIT_AUDIT）

### 差异（scope 1e140ca..d1be7dc）

- `database/preference/KvMemoryCache.kt`：同计数器全表 fence（`beginFullTableFence`/`commitFullTableFence`/`abortFullTableFence`、`pendingFullTableToken`/`lastCommittedFullTableEpoch`、`mergeLocked` 在途阻塞与 stale 守护、`revertPendingMutation`）；`generationCounter` 同步自增，stale fence 回调幂等、无反向持锁。
- `database/preference/RoomPreferenceDataStore.kt`：`WriteOperationKind`/`WriteTicket`/`FlushResult`；`queueSequence`+`ledgerLock`+`snapshotLock`；`restore(rows): FlushResult`（阻塞 caller 深拷贝输入、在途写先落盘后 fence，`snapshotLock`→DB 事务→`commit/abort`，失败直接 `abort fence` + 失败 `FlushResult`，不通过 `Logs` 依赖原生库）；`suspend reset(): FlushResult`；`flushPendingWrites()`/`flushPendingWritesAsync()` marker；cut-scoped recovery。
- `database/DataStore.kt`：`configurationStore` 显式注入 `PublicDatabase.instance::runInTransaction`；`profileCacheStore` 保持直通。
- `ui/BackupFragment.kt`：移除带外 `PublicDatabase.kvPairDao.reset/insert` 复写，仅 `configurationStore.restore`；失败抛 `IllegalStateException` 触发既有 `catch` → `Logs.w`+`MessageStore`。
- `ui/SettingsPreferenceFragment.kt`：`reset` 改 `suspend` 在 `viewLifecycleOwner.lifecycleScope.launch` 中 `await`，`settingsResetInProgress` 挡重复点击，`success` 才 `triggerFullRestart`。
- `ktx/Logs.kt`：`safeLog` 捕获 `Libcore.nekoLogPrintln` 的 JNI 缺失，避免 JVM 测试宿主杀死 writer 线程。
- 测试：`database/preference/KvMemoryCacheFullTableFenceTest`（5 cache 组 + boundary 2 + refresh/legacy 兼容）、`database/preference/PreferenceWriteQueueTest`（barrier 8 + failure 4 + fence/integration 8，gate+future 控制，无 sleep 定序）。

### 本地测试（真实执行）

| run | dst | 命令 | exit | 统计 | 证据 |
|---|---|---|---|---|---|
| L-RED | — | focused + full on base 1e140ca + raw diff | 1 | 编译级：`begin/commit/abortFullTableFence`、`restoreTransaction` 注入点等；`FlushResult` 行为断言 | `docs/agent/evidence/s1-b3/run-l-red.txt` |
| M-GREEN | d1be7dc | `:app:testOssDebugUnitTest --tests "*KvMemoryCache*" --tests "*RoomPreferenceDataStore*" --tests "*PreferenceWrite*"` | 0 | 56/0（9/10/17/1/19），无 UpToDate 冒充 | `docs/agent/evidence/s1-b3/run-m-green-focused.txt` |
| N-GREEN | d1be7dc | `:app:testOssDebugUnitTest` | 0 | 159/0/0 | `docs/agent/evidence/s1-b3/run-n-green-full.txt` |

`--no-daemon --console=plain`；`run-m`/`run-n` 均为 `BUILD SUCCESSFUL` 后新生成 `TEST-*.xml`，无缓存复用。

### 剩余风险

- `DataStore.configurationStore` 全表 fence 依赖缩进路径首帧（`DataStore` 已按 S1-B3.md 固定绑定），`profileCacheStore` 不依赖。
- `Logs.w` 默认走 `libcore` JNI；测试宿主补 `safeLog` 回退到 `System.err`。
- `restore` 调用方校验（`DataStore.DEPRECATED_SETTING_KEYS` 过滤、`validate`）仍在 `BackupFragment` 上游执行；本批只收口 fence 与失败传播。

### CI

`d1be7dc` 已推送 `origin/fix/p01-room-off-main-thread`；`ci_status=NOT_AVAILABLE`（本机查询受限，以 GitHub Actions 实际记录为准，不以 push 冒充 PASS）。

## 下一动作

提交 SHA 为 **实际 git 写入 SHA 载于下方 `candidate_code_sha`** 的候选，进入 `WAIT_AUDIT`。无 `ACCEPTED` 不激活下一批。
