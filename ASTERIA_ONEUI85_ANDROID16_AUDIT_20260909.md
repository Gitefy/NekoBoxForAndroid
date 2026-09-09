# P01 性能/ANR:Room 全局 allowMainThreadQueries —— 修复报告

分支:`fix/p01-remove-main-thread-db-queries`
基线:`router-groups-go-tun-clean @ e152950`
日期:2026-09-09

## 1. 现象

列表页(订阅节点列表/路由规则列表)、设置对话框、服务启动/重载路径可在主线程同
步查库;叠加 `PublicDatabase` 的 `TRUNCATE` journal,大订阅(>1k 节点)写入时全
库串行阻塞,易卡顿/ANR。

## 2. 根因

- `SagerDatabase` / `PublicDatabase` / `TempDatabase` 全局
  `.allowMainThreadQueries()`,且查询执行器是 `setQueryExecutor {
  GlobalScope.launch { it.run() } }`——无界协程、无背压、无命名,ANR 堆栈不可归因。
- `PublicDatabase`(设置 KV 库)用 `JournalMode.TRUNCATE`:每次提交都做全文件
  checkpoint,`:bg` 进程的读写会互相串行阻塞。
- `RoomPreferenceDataStore` 每个 getter 都是同步 `kvPairDao[key]`:任何在主线
  程读 `DataStore.xxx` 的调用(设置页绑定、服务启动、通知标题)都是一次同步
  SQLite 查询。
- `RouteFragment.bind` / `GroupPreference` inflate /
  `ProfileSettingsActivity.onCreateOptionsMenu` / `ConfigurationFragment`
  菜单项在主线程直接读 `proxy_entities` / `proxy_groups` 全表;`BaseService`
  `onStartCommand` 在主线程查 profile 并同步 `buildConfig`。

## 3. 修复(方案 B:彻底移除,保持同步调用语义)

### 3.1 KV 库:内存镜像 + 异步写回 + 跨进程失效

新增 `KvMemoryCache.kt`(纯 JVM 可测):

- `prime`/`merge`/`put`/`delete`/`reset`/`writeCommitted`,读全部走内存,写
  立即可见(read-your-writes),本地未确认写(`pendingKeys` + `pendingReset`)
  永远压过跨进程快照;`reset` 在 flight 期间整表屏蔽远端快照。
- 写回由 `kv-store-writer` 单线程 FIFO 执行;写失败保留乐观镜像,下次同步
  自愈,不崩溃调用方。

重写 `RoomPreferenceDataStore.kt`:

- 构造时在 `Dispatchers.IO` 做一次全表 prime(进程启动唯一一次阻塞读,替代
  以往每次 getter 的同步查询);之后所有 `getXxx` 零 I/O。
- `db.invalidationTracker`(`PublicDatabase` 侧,以 Room 自身失效线程运行)
  把本进程提交和 `:bg` 跨进程广播合并进同一镜像。
- `BackupFragment` 导出走 `cachedAll()`(零查询);恢复走 `restore()`,持久化
  与镜像替换原子化在 IO 线程完成。
- `SettingsPreferenceFragment.resetSettings` 仍走 `configurationStore.reset()`
  (Room 同步语句);调用点本就在对话框回调的同步上下文,行为不变。

### 3.2 数据库定义

- `PublicDatabase`:TRUNCATE → WAL,删 `allowMainThreadQueries`,查询/事务执
  行器换 `DbExecutors.single` 命名线程。
- `SagerDatabase`:删 `allowMainThreadQueries`,`DbExecutors` 4 线程查询池 +
  单线程事务执行器。
- `TempDatabase`(in-memory,无磁盘 I/O):保留 `allowMainThreadQueries` + 命名
  线程池(已在代码注释说明原因;属 P01 豁免项)。
- 新增 `ktx/Db.kt#dbOffMain`:主线程兜底跳 IO(仅用于首启建库等不可挂起的
  同步引导路径),非主线程零开销直行。

### 3.3 SagerDatabase 调用点(按审计三类逐个收口)

- 列表页:`GroupFragment.action_update_all` 进 `runOnDefaultDispatcher`;
  `groupRemoved` 先在后台取 `allGroups().size`,再回主线程更新;
  `RouteFragment.reload` 后台一次预取 `routerNames`/`profileNames` 映射,
  `bind` 改走 `displayOutboundCached`(零查询);`ConfigurationFragment`
  `action_switch_group_view` / `action_update_subscription` 移入
  `runOnLifecycleDispatcher`。
- 对话框/设置页:`GroupPreference.init` + `getSummary`、`OutboundPreference`
  router 分支、`RouterGroupSelectActivity`、`RouterGroupSettingsActivity`
  的三处查询经 `dbOffMain`;`ProfileSettingsActivity` 的 `proxyEntity`
  lazy 与“移动分组”菜单可见性预计算到 `onCreate` 的后台块,action_move 对
  话框改为后台取分组后回主线程 `show()`。
- reload/服务路径:`BaseService.onStartCommand` 的 profile 查询 +
  `proxy.init()`(含 `buildConfig` 全表扫描)移入 `connectingJob` 的
  `onDefaultDispatcher`;`reload()` 的 `trySelectRouter`/`getById` 经
  `dbOffMain`;`ProfileManager.getProfile/getProfiles`(被数十个编辑器/摘要
  路径复用)内部加 `dbOffMain`;`ServiceNotification.genTitle` 默认
  `groupNameProvider` 加 `dbOffMain`;`TileService.cbSelectorUpdate`
  改走 `runOnDefaultDispatcher`;`RuleEntity.displayOutbound` 拆出生查询版与
  缓存版;`CrashHandler` 崩溃转储改读镜像(崩溃线程安全);`DataStore`
  的 `currentGroupId/currentGroup/selectedGroupForImport` 与
  `sanitizeDeprecatedPreferences` 全部脱离主线程查询。
- `SagerNet.onCreate` 的 `DataStore.logBufSize/logLevel` 读取:进程启动时
  store 构造 prime 已完成,读镜像零 I/O,无需改动。

### 3.4 刻意保留(主线程同步、但零 I/O)

- 全部 `DataStore.xxx` 同步属性语义不变(设置页 `PreferenceDataStore` 绑定依
  赖同步 getter;ConfigBuilder 等数百调用点无需重构)。
- TempDatabase 的 `allowMainThreadQueries`(in-memory,注释说明)。
- `dbOffMain` 在 `reload()` 等 binder/广播线程是直通调用,无额外切换。

## 4. 验证

- `:app:compileOssDebugKotlin` PASS(构建期曾抓出 `onInvalidated` 签名、
  `put()` 返回值、`finishImport` 非协程上下文三个真实错误,已修复)。
- `:app:testOssDebugUnitTest` PASS:106 tests,0 failed。新增
  `KvMemoryCacheTest` 8 例(read-your-writes/delete/reset/远端快照不覆盖
  flight 写/远端删除收敛/in-flight reset 屏蔽/导出快照一致性)。
- `:app:lintOssDebug` PASS:0 errors,55 warnings;新增告警 0 条,无
  RunBlocking/MainThread/GlobalScope 相关告警。
- 需真机回归(本环境无设备):大订阅列表滑动帧率、订阅刷新时前台无掉帧、
  备份恢复后设置即时生效且 `:bg` 进程一致、VPN 启动/热切换/停止全路径、
  路由组选择器对话框。

## 5. 涉及文件

新增:`KvMemoryCache.kt`、`DbExecutors.kt`、`ktx/Db.kt`、
`KvMemoryCacheTest.kt`、`RoomPreferenceDataStoreTestContract.kt`(测试策略说明)。

修改:`RoomPreferenceDataStore`、`PublicDatabase`、`SagerDatabase`、
`TempDatabase`、`DataStore`、`BaseService`、`ServiceNotification`、
`TileService`、`ProfileManager`、`RuleEntity`、`GroupFragment`、
`ConfigurationFragment`、`RouteFragment`、`ProfileSettingsActivity`、
`RouterGroupSettingsActivity`、`RouterGroupSelectActivity`、
`GroupPreference`、`OutboundPreference`、`BackupFragment`、`CrashHandler`。
