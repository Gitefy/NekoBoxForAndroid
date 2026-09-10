# 当前交接记录

模式：Cursor 实现 → GitHub 固定 SHA → 网页 ChatGPT 审计 → receipt 回 Cursor。
work_order：S1-B1-KV-LINEARIZABILITY。
状态：CANDIDATE_PUSHED / WAIT_CHATGPT_AUDIT。

## 固定候选

- repo：`Gitefy/NekoBoxForAndroid`（origin `https://github.com/Gitefy/NekoBoxForAndroid.git`）。
- branch：`fix/p01-room-off-main-thread`。
- base_code_sha：`cc63f24893696c075723eb8934529534529f31e2`（P01 第一版实现基线，未验收）。
- candidate_code_sha：`a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c`（本批新代码 commit）。
- 审核范围：`cc63f248..a34a0cf`（代码候选差异）。metadata commit 如有，仅更新交接元数据，不影响该审核范围。
- handoff_metadata_sha：见本批次第二 commit HEAD（如无则与 candidate 相同）。
- owner：Cursor。

## 修改文件与核心符号

- `app/src/main/java/io/nekohasekai/sagernet/database/preference/KvMemoryCache.kt`：新增 `pendingGenerations/lastCommittedGenerations/generationCounter`、`captureReadEpoch()`、重载 `merge(list, readEpoch)`、`put: Long`/`delete: Long`（返回 generation）、`writeCommitted(key,value,generation)`（仅命中当前 pending generation）、`mergeLocked/isLocallyNewer/commitLocked/matchesMirror`。
- `app/src/main/java/io/nekohasekai/sagernet/database/preference/RoomPreferenceDataStore.kt`：新增 `readAndMergeSnapshot()`（先取 epoch 后读表）、`init` 监听与 `syncNow` 走 epoch 守护；`putValue/remove` 捕获 generation 并传入带 generation 的 `writeCommitted`。
- `app/src/test/java/io/nekohasekai/sagernet/database/preference/KvMemoryCacheTest.kt`：新增 2 个只用旧 API 的 RED regression（put v1→put v2→ack v1 旧值不得回退；put→delete→ack put 不得复活）。
- `app/src/test/java/io/nekohasekai/sagernet/database/preference/KvMemoryCacheLinearizabilityTest.kt`：新增 7 个 generation+epoch 定点测试（含 snapshot read-before-write/merge-after-commit、同值重写、跨进程 fresh snapshot 等）。

## 根因与不变量

- 根因：旧 ack 只按 key 判 pending，无 generation/token，无法区分旧 commit 与当前 pending mutation；snapshot 无读取时序防护，旧快照晚 merge 可回滚新值。
- 修复不变量：每本地 mutation 单调 generation、ACK 只承认对应 generation、旧 generation 既不改 value 也不清新 pending；snapshot merge 以 readEpoch 为守卫，`pendingKeys||lastCommitted>readEpoch` 的 key 不被旧快照覆盖/删除；跨进程 fresh snapshot 对 untouched key 仍落地；不使用 delay/sleep/扩线程/吞回调。

## 本地测试（真实执行，非模板）

- cwd：`C:/Users/renos/Documents/Proxy/NekoBoxForAndroid-router-groups`（= 仓库根）。
- shell：PowerShell。
- Run A（cc63f248 基线，断言级 RED，`--tests "*KvMemoryCacheTest"`）：`EXIT_CODE=1`，`13 tests, 2 failed`；`staleAckOfSupersededPutDoesNotRegressMemory: expected:<v[2]> but was:<v[1]>`、`staleAckOfPutDoesNotResurrectDeletedKey: expected null, but was:<v1>`。
- Run B（cc63f248 基线，API 缺失级 RED，加入 LinearizabilityTest）：`EXIT_CODE=1` 编译失败：`captureReadEpoch` 未解析、`writeCommitted`/`merge` 参数过多。
- Run C（修复后 focused GREEN，`--tests "*KvMemoryCache*"`）：`EXIT_CODE=0`，`BUILD SUCCESSFUL`。
- Run D（修复后 full-suite GREEN，`:app:testOssDebugUnitTest`）：`EXIT_CODE=0`，`28 suites, 130 tests, 0 failures, 0 errors, 0 skipped`；其中 `KvMemoryCacheTest 13/0`、`KvMemoryCacheLinearizabilityTest 7/0`。
- 证据：`docs/agent/evidence/s1-b1/run-{a,b,c,d}-*.txt` 及 `app/build/test-results/testOssDebugUnitTest/TEST-*.xml`。

## 未执行的 L2/L3/L4 门槛与原因

- 无 `enableMultiInstanceInvalidation`/SQLite 驱动的双进程 Room 仪器测试、真机/API 签名/Go race/性能 A/B：属于后续阶段 S2/S4/S6，按工作单本批不执行；本批 L1 已覆盖的三类时序不变量已定点。

## push 状态

- 代码差异 `cc63f248..a34a0cf` 为独立可审候选；metadata（STATUS/HANDOFF/WORK_ORDER 与证据）将以独立 commit 同分支 push，审计对象仍为上述代码候选 SHA。

## 安全/数据/兼容性影响

- 仅内存语义与 ack/merge 守卫；无 `allowMainThreadQueries`、无 schema/备份格式/签名变更；重试/通知逻辑不变；回滚即 revert 单 commit。

## 下一动作

`WAIT_CHATGPT_AUDIT`。网页 ChatGPT 按 `CHATGPT_AUDIT_PROMPT.txt` 固定审核 `cc63f248..a34a0cf` 并出具 `AUDIT_RECEIPT`；Cursor 收到 `ACCEPTED` 后关闭 S1-B1 并签发 S1 下一批，`CHANGES_REQUIRED` 则只修本批并生成新候选。禁止自动进入下一实现批。
