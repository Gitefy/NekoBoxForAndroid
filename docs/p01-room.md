# P01 Room off-main + KV 镜像（≤200 节点范围）

## 已完成

- **主线程 Room 已移除**：`PublicDatabase` / `SagerDatabase` 均 `WAL + enableMultiInstanceInvalidation`，`setQueryExecutor` / `setTransactionExecutor` 指向 `DbExecutors`；已去掉 `allowMainThreadQueries()`（仅 `TempDatabase` 保留 in-memory 并注释原因）。阻塞 DAO 在调用方保证非主线程，引导路径用 `dbOffMain`（见 `ktx/Db.kt`）短暂切 `Dispatchers.IO`。
- **KV 内存镜像 + 异步写回**：`KvMemoryCache` 托管 `KeyValuePair` 全表镜像；`get` 只读内存、`put/delete/reset` 同步可见（read-your-writes）、`merge` 受 `pendingKeys / pendingReset` 保护，`writeCommitted` 后才允许远端覆盖；`RoomPreferenceDataStore` 单线程 `kv-store-writer` FIFO 落盘，`merge` 来自 `InvalidationTracker`（含跨进程 `enableMultiInstanceInvalidation`）。
- **写回失败不丢内存**：`put/delete` 失败保留镜像、单 key 最多 3 次重试（50/100/200ms 退避）、`Logs.w` 记录 key+异常，不抛到主线程；下次 `put` 可继续写回。单测可模拟 DAO 抛错验证 UI 仍读到刚写入值。

## 目标规模

- 日常 **≤100 节点**，上限 **≤200 节点**。
- 不做大批量优化：不分片、不 LRU、不为 200+ 节点扩线程池；`DbExecutors` 固定 `sagerQuery 4` + `sagerTransaction 1`，`PublicDatabase` 各 1。

## 主用协议验证

- 主用 **AnyTLS / VLESS** 按现有 `ConfigBuilder` / 对应 `fmt` 类验证；出站生成无全表扫描、无已废弃协议阻塞主路径。
- 其他协议（SSR/旧 Snell 等）仅保证启动失败有明确错误，不补实现；不为“兼容所有协议”在 `buildConfig` 加重分支。
