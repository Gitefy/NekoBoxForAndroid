# 当前交接记录

模式：Cursor 实现 → GitHub 固定 SHA → 网页 ChatGPT 审计 → receipt 回 Cursor。
work_order：S1-B1-KV-LINEARIZABILITY（同单 Keep Open，P1 已修后重推）。
状态：CANDIDATE_PUSHED / WAIT_CHATGPT_AUDIT。

## 固定候选（本次可审）

- repo：`Gitefy/NekoBoxForAndroid`（origin `https://github.com/Gitefy/NekoBoxForAndroid.git`）。
- branch：`fix/p01-room-off-main-thread`。
- base_code_sha：`cc63f24893696c075723eb8934529534529f31e2`（P01 第一版基线，未验收）。
- candidate_code_sha：`e9b92cf79625c555ecd21b10991642b76037de71`（本批新代码候选，审计范围 `cc63f248..e9b92cf`）。
- 上一候选：`a34a0cf20e10b2dedad5d30d7ed77ff7a74a533c`（已被审计 `CHANGES_REQUIRED/P1-SNAPSHOT-ORDERING`）。
- handoff_metadata_sha：见本批次第二 commit HEAD（如无则与 candidate 相同）。
- owner：Cursor。

## 审计往返

- 上次审计 scope `cc63f248..a34a0cf` verdict `CHANGES_REQUIRED`（P1 1 项），blocking `P1-SNAPSHOT-ORDERING`：仅 epoch 不定序并发 snapshot read，`A(capture0/read old)‖B(capture0/read new/merge new)→A(merge old)` 可回退镜像。required_next_action：用单一 snapshot lock/coordinator 串行化完整 read+merge，保持 generation、无主线程 DB 读、重试语义、不扩 executor、不用 delay/sleep。
- 本次修正 scope `a34a0cf..e9b92cf`：`RoomPreferenceDataStore.snapshotLock: ReentrantLock` 包起来 `captureReadEpoch+tableSnapshot+merge`，`CountDownLatch` 定序回归 `RoomPreferenceDataStoreSnapshotOrderingTest`。

## 修改文件与核心符号

- `app/src/main/java/io/nekohasekai/sagernet/database/preference/RoomPreferenceDataStore.kt`：新增 `snapshotLock: ReentrantLock`；`readAndMergeSnapshot()` 改为 `snapshotLock.withLock { captureReadEpoch; tableSnapshot; merge(epoch) }`，使整个 snapshot 操作对并发 sibling 原子。
- `app/src/test/java/io/nekohasekai/sagernet/database/preference/RoomPreferenceDataStoreSnapshotOrderingTest.kt`（新建）：`CountDownLatch/AtomicReference` 定序强制上述交错，断言最终值 `new`。
- 沿用 `a34a0cf` 的 per-key generation + epoch guard（`KvMemoryCache: pendingGenerations/lastCommittedGenerations/captureReadEpoch/merge(epoch)/writeCommitted(key,value,generation)`）及 `KvMemoryCacheTest`/`KvMemoryCacheLinearizabilityTest` 九回归。

## 根因与不变量

- 根因两部分：(1) 旧 ack 只按 key 判 pending，无 generation；(2) concurrent snapshot readers 之间无定序，即使各 snapshot 自身 epoch 正确，并发交错仍可让 stale snapshot 后 merge。
- 不变量：每本地 mutation 单调 generation、ACK 只承认对应 generation；snapshot merge 以 `readEpoch` 为守卫（`pending||lastCommitted>readEpoch` 跳过）；新增 `snapshotLock` 使 `capture+read+merge` 不可交错；跨进程 fresh snapshot 对 untouched key 仍落地；不使用 delay/sleep/扩线程/吞回调。

## 本地测试（真实执行，非模板）

- cwd：`C:/Users/renos/Documents/Proxy/NekoBoxForAndroid-router-groups`（= 仓库根）。
- shell：PowerShell。
- 先前 RED（cc63f248）：`run-a-red-assertions.txt` exit 1（`v2->v1` / `null->v1`）、`run-b-red-api-missing.txt` exit 1 编译期（`captureReadEpoch` 未解析）。
- P1 RED（a34a0cf 上）：`run-e-red-concurrent-snapshot.txt` exit 1，`RoomPreferenceDataStoreSnapshotOrderingTest: expected:<[new]> but was:<[old]>`。
- 修复后 focused GREEN：`run-f-green-focused-after-fix.txt` exit 0（`--tests "*KvMemoryCache*" --tests "*RoomPreferenceDataStoreSnapshotOrderingTest"`，BUILD SUCCESSFUL）。
- 修复后 full-suite GREEN：`run-g-green-full-suite-after-fix.txt` exit 0，`29 suites 131 tests 0 failures 0 errors 0 skipped`（其中 `KvMemoryCacheTest 13/0`、`KvMemoryCacheLinearizabilityTest 7/0`、`RoomPreferenceDataStoreSnapshotOrderingTest 1/0`）。
- 证据：`docs/agent/evidence/s1-b1/run-{a,b,e,f,g}-*.txt` 及 `app/build/test-results/testOssDebugUnitTest/TEST-*.xml`。

## 未执行的 L2/L3/L4 门槛与原因

- 无 `enableMultiInstanceInvalidation`/SQLite 真机双进程仪器、真机/API 签名、Go race/性能 A/B：属 S2/S4/S6，本批不执行；L1 三类时序（stale ACK 回退/复活 + concurrent snapshot 定序）已定点。

## push 状态

- 代码差异 `a34a0cf..e9b92cf`（`snapshotLock` + 1 定序回归）已 push 至 `origin/fix/p01-room-off-main-thread` 为独立可审候选；完整可审范围 `cc63f248..e9b92cf`；metadata（STATUS/HANDOFF/REVIEW 与证据）将以独立 commit 同分支 push。

## 安全/数据/兼容性影响

- 仅 snapshot 路径加锁与 ack/merge 守卫；无 `allowMainThreadQueries`、无 schema/备份格式/签名变更；重试/通知逻辑不变；回滚即 revert `e9b92cf`（及/或 `a34a0cf`）。

## 下一动作

`WAIT_CHATGPT_AUDIT`。网页 ChatGPT 按 `CHATGPT_AUDIT_PROMPT.txt` 固定审核 `cc63f248..e9b92cf`（ChatGPT 若增量审可审 `a34a0cf..e9b92cf`）并出具新 `AUDIT_RECEIPT`；`ACCEPTED` 后关闭 S1-B1 并签发 S1 下一批，`CHANGES_REQUIRED` 则只修本批并生成新候选。禁止自动进入下一实现批。
