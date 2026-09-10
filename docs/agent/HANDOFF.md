# 当前交接记录

模式：Cursor 实现 → GitHub 固定 SHA → 网页 ChatGPT 审计 → receipt 回 Cursor。

## 已关闭：S1-B1-KV-LINEARIZABILITY（ACCEPTED）

- base `cc63f24893696c075723eb8934529534529f31e2`；candidate `e9b92cf79625c555ecd21b10991642b76037de71`；metadata `d499eaa2..`/`1de9de8..`（关闭+签发）。
- verdict `ACCEPTED`（critical 0 / p1 0 / p2 1），完整 receipt 见 `REVIEW.md`。
- P2-TEST-ROBUSTNESS（测试内 `Thread.sleep(80)`）→ 后续 test-infrastructure 清理项。
- ci_status 无 check 的根因 = A08（push `branches:'*'` 不匹配含 `/` 分支）→ 本批修复。

## 待审：S1-B2-CONFIRM-SEMANTICS-CLOSURE

模式：Cursor 实现 → GitHub 固定 SHA → 网页 ChatGPT 审计 → receipt 回 Cursor。
状态：CANDIDATE_PUSHED / WAIT_CHATGPT_AUDIT。

## 固定候选

- repo：`Gitefy/NekoBoxForAndroid`（origin `https://github.com/Gitefy/NekoBoxForAndroid.git`）。
- branch：`fix/p01-room-off-main-thread`。
- base_code_sha：`e9b92cf79625c555ecd21b10991642b76037de71`（S1-B1 accepted candidate）。
- candidate_code_sha：`1e140ca720a54dfa42cc37235484af7faae499bf`。
- 可审范围：`e9b92cf..1e140ca`（metadata commit `1de9de8` 仅交接文档；增量即本批代码+测试）。
- handoff_metadata_sha：见本批次 metadata commit（本文件所在 commit）。
- owner：Cursor。

## 修改文件与核心符号

- `.github/workflows/ci.yml`：push `branches: '*' → '**'`（A08；保留 `tags-ignore: v*`、`pull_request`、全部 job/步骤，无权限变更）。
- `app/src/main/java/io/nekohasekai/sagernet/database/preference/KvMemoryCache.kt`：仅 `snapshot()` 改为逐行防御性拷贝（新增私有 `copiedRow`：复制 key/valueType + `value.copyOf()`）；其余逻辑零改动。
- `app/src/test/java/io/nekohasekai/sagernet/database/preference/KvMemoryCacheTest.kt`：+4 legacy 钉子（`deleteThenPutOrderingPinsLatestValue`、`duplicateLegacyAckIsIdempotent`、`resetThenOldLegacyPutAckDoesNotResurrect`、`snapshotRowsAreDefensiveCopies`）。
- `app/src/test/java/io/nekohasekai/sagernet/database/preference/KvMemoryCacheLinearizabilityTest.kt`：+3 generation 钉子（`deleteThenPutGenerationsAckedIndependently`、`duplicateGenerationAckIsIdempotent`、`oldGenerationAckAfterResetDoesNotResurrect`）。

## 根因与不变量

- 根因：`KeyValuePair` 字段公开可变，`snapshot()` 返回活引用 → `cachedAll()`（`CrashHandler`/`BackupFragment` 只读消费）外部改行会污染镜像；plan-S1-B2 遗留钉子未覆盖 delete→put/重复 ack/reset 后旧 ack；A08 使含 `/` 分支从不触发 CI（审计 ci_status 证实）。
- 不变量：镜像只能由 cache 自身 mutation/ack/merge 改写；快照消费方拿到的是拷贝；ack 语义各代独立、幂等、reset 后失效；CI 覆盖全部非 tag push 分支。

## 本地测试（真实执行，非模板）

- cwd：`C:/Users/renos/Documents/Proxy/NekoBoxForAndroid-router-groups`；shell：PowerShell。
- Run H（base `e9b92cf`，修复前 RED）：focused `--tests "*KvMemoryCache*"` exit 1，`27 tests, 1 failed`：`snapshotRowsAreDefensiveCopies: expected:<[1]> but was:<[mutated]>`。证据 `run-h-red-snapshot-pollution.txt`。
- Run I（基线 `cc63f248`，plan-B2 旧逻辑验证）：临时 `git checkout cc63f248 -- KvMemoryCache.kt RoomPreferenceDataStore.kt` + 将 `KvMemoryCacheLinearizabilityTest.kt` 移出 source set；`--tests "*KvMemoryCacheTest"` exit 1，`17 tests, 4 failed`：新增 `deleteThenPutOrderingPinsLatestValue`（`v[2]→v[1]`，旧 ack 复活）与 `snapshotRowsAreDefensiveCopies`（`[1]→[mutated]`）双双在基线失败；另 2 个失败为 run-a 已知 stale-ack；重复 ack/reset 钉子基线即绿（约束性）。跑完立即 `git checkout HEAD --` 恢复并移回 LinearizabilityTest，`git status` 核验仅余预期测试改动。证据 `run-i-red-baseline.txt`。
- Run J（修复后 focused GREEN）：`--tests "*KvMemoryCache*" --tests "*RoomPreferenceDataStoreSnapshotOrderingTest"` exit 0，BUILD SUCCESSFUL。证据 `run-j-green-focused.txt`。
- Run K（修复后 full-suite GREEN）：`:app:testOssDebugUnitTest` exit 0，**29 suites / 131 tests / 0 failures / 0 errors / 0 skipped**（`KvMemoryCacheTest 17/0`、`KvMemoryCacheLinearizabilityTest 10/0`、`RoomPreferenceDataStoreSnapshotOrderingTest 1/0`）。证据 `run-k-green-full-suite.txt` + `TEST-*.xml`。
- 证据计数更正：e9b92cf 批次 run-g 实际 **124** tests（先前 metadata 误记 131，算术错误，已在 STATUS 更正）；run-k 131 = 124 + 7 新钉子。

## 未执行的 L2/L3/L4 门槛与原因

- 双进程 Room 仪器/真机/Go race/性能 A/B 归 S2+/S4/S6；本批 L1 已覆盖快照防污染与 ack 语义钉子。CI 实际运行结果由 push 后 GitHub Actions 判定（A08 修复后本分支应触发），不替代也不被本地证据替代。

## push 状态

- 代码候选 `1e140ca` 已 push `origin/fix/p01-room-off-main-thread`（`1de9de8..1e140ca`）；metadata commit 随后同分支 push。

## 安全/数据/兼容性影响

- `snapshot()` 拷贝对消费方（`CrashHandler`/`BackupFragment` 只读序列化）无行为变化，防的是写污染；CI 仅触发条件变更；无 schema/签名/重试/线程池变更；回滚即 revert `1e140ca`。

## 下一动作

`WAIT_CHATGPT_AUDIT`（S1-B2，scope `e9b92cf..1e140ca`）。`ACCEPTED` 后关闭本批并签发 S1-B3（写队列/`flushPendingWrites` barrier/失败协议，架构批，单批执行）；`CHANGES_REQUIRED` 只修本批。禁止自动进入下一批。
