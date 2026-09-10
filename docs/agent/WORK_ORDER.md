# 当前工作单：S1-B1-KV-LINEARIZABILITY

状态：CANDIDATE_PUSHED / WAIT_CHATGPT_AUDIT。
Owner：Cursor。
独立审计：网页 ChatGPT（固定 GitHub SHA）。
上一工作单：S0-B0 / 迁移对账（已完成，见 STATUS/HANDOFF）。
base_code_sha：`cc63f24893696c075723eb8934529534529f31e2`（P01 第一版实现基线，未验收）。

## 目标

修复 `KvMemoryCache` 同 key 多代本地 mutation 的 stale acknowledgment 一致性问题，并堵住
"旧 DB snapshot 晚 merge 回滚新值" 的窗口。用户确认 ChatGPT 已在 cc63f248 上复核该缺陷：

1. `put(k,v1) → put(k,v2) → ack(v1) → ack(v2)`：旧 ack 不得把内存从 v2 恢复为 v1，
   也不得清除属于 v2 的 pending 状态。
2. `put(k,v1) → delete(k) → ack(v1) → ack(delete)`：旧 ack 不得复活 v1。
3. snapshot 在本地新 mutation 之前读取、但在该 mutation commit 之后才 merge：
   不得回滚新值。

## 不变量

- 读你的写：`put/delete` 同步可见，pending 期间任何 snapshot/merge 不得覆盖。
- 旧 generation 的 ACK 既不改当前 value，也不清除新 generation 的 pending。
- 同值重写必须用各自 generation 分别 ack，不能用值相等冒充版本。
- 读取时序：merge 只能应用 "读取起点之后本地没有更新提交过" 的行；
  snapshot 不得回滚晚于其读取起点的本地提交。
- 跨进程仍然有效：未被本地新写覆盖的 key，fresh snapshot 照常落地。
- 不引入 delay/sleep/扩大线程池/吞旧回调来掩盖问题；重试逻辑保持原样。

## 允许文件/符号

- `app/src/main/java/io/nekohasekai/sagernet/database/preference/KvMemoryCache.kt`
  （generation counter、pendingGenerations、lastCommittedGenerations、
  captureReadEpoch、merge(list, epoch)、writeCommitted(key, value, generation)）
- `app/src/main/java/io/nekohasekai/sagernet/database/preference/RoomPreferenceDataStore.kt`
  （仅：put/delete 捕获 generation 并传入 ack；snapshot 读取前 captureReadEpoch）
- 测试：`KvMemoryCacheTest.kt`、新增 `KvMemoryCacheLinearizabilityTest.kt`

## 禁止事项

不处理 dbOffMain/runBlocking、backup restore、RuntimeController、network debounce、
ConfigSnapshot、性能优化、sing-box 升级、UI/品牌；不改 API/签名/数据库版本；
不使用 `allowMainThreadQueries`；不 reset/stash 未知改动。

## 失败测试（RED 证据）

- Run A（断言级，cc63f248 基线）：`staleAckOfSupersededPutDoesNotRegressMemory`
  → `expected:<v[2]> but was:<v[1]>`；
  `staleAckOfPutDoesNotResurrectDeletedKey` → `expected null, but was:<v1>`。
- Run B（API 缺失级，cc63f248 基线）：`captureReadEpoch` 未解析、
  `writeCommitted`/`merge` 参数过多——证明场景 3 需要的 epoch API 在基线上不存在。
- 证据：`docs/agent/evidence/s1-b1/run-a-red-assertions.txt`、
  `docs/agent/evidence/s1-b1/run-b-red-api-missing.txt`。

## 验证命令

```powershell
./gradlew.bat :app:testOssDebugUnitTest --tests "*KvMemoryCache*" --no-daemon --console=plain
./gradlew.bat :app:testOssDebugUnitTest --no-daemon --console=plain
```

cwd=仓库根；shell=PowerShell；两次运行 exit code=0；
全量 28 个测试类 130 tests / 0 failures / 0 errors / 0 skipped。
证据：`docs/agent/evidence/s1-b1/run-c-green-focused.txt`、
`docs/agent/evidence/s1-b1/run-d-green-full-suite.txt`。

## 修复方案（最小实现）

- per-key 单调 generation：`put/delete` 返回 `Long` generation；ACK 只有命中
  当前 pending generation 才提交（清除 pending、重申 committed value、记录
  lastCommittedGenerations）。
- snapshot merge 顺序 guard：`captureReadEpoch()` 在 DB 读之前取全局计数；
  `merge(rows, readEpoch)` 对 pending key 或 `lastCommitted > readEpoch` 的 key
  跳过应用/删除。legacy `merge(list)` 与 legacy `writeCommitted(key,value)`
  保持 P01 语义（pending 优先；legacy ack 只在内容仍匹配镜像时生效），
  现有测试与新测试双路径覆盖。
- store 接线：`putValue/remove` 捕获 generation 传给 ack；`init` 失效监听与
  `syncNow` 走 `readAndMergeSnapshot()`（先 epoch 后读）。`restore` 不动。

## 已知边界（未扩项，仅记录）

- `RoomPreferenceDataStore.reset()` 直接 `kvPairDao.reset()`，不经过 cache
  （P01 已存在，本批不改）；`cache.reset()` 无生产调用方，仅保留测试语义。
- legacy ack（无 generation）无法区分同值不同代，仅作兼容路径；生产路径已全部
  带 generation。
- 双进程真实行为仍属 L2/真机门槛，本批未执行。

## 回退方案

revert 该单 commit 即回到 cc63f248 语义；无 schema/格式变更。

## push 分支 / 停止条件

分支：`fix/p01-room-off-main-thread`（非 main）。停止条件：候选 push 后
WAIT_CHATGPT_AUDIT；收到 ACCEPTED 才允许签发 S1-B2；CHANGES_REQUIRED 则只修本批。
