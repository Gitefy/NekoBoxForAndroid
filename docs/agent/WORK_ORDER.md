# 当前工作单：S1-B2-CONFIRM-SEMANTICS-CLOSURE

状态：ISSUED / IN_PROGRESS。
Owner：Cursor。
独立审计：网页 ChatGPT（固定 GitHub SHA）。
base_code_sha：`e9b92cf79625c555ecd21b10991642b76037de71`（S1-B1 accepted candidate）。
上一工作单：S1-B1-KV-LINEARIZABILITY（ACCEPTED，见 REVIEW.md）。

## 目标

按 roadmap 完成收 S1 的 B1/B2 计划遗留，一次只做本批、不做 B3 队列架构：

1. **CI 入口修复（A08 / plan-S1-B1 遗留）**：`.github/workflows/ci.yml` push `branches: '*'` 不匹配含 `/` 的分支（当前工作分支 `fix/p01-room-off-main-thread` 从未触发 CI，审计 ci_status 已证实）。改为 `'**'`；保留 `tags-ignore: v*` 与 `pull_request` 触发及全部现有 job/步骤；不引入发布权限/secret 变更。
2. **plan-S1-B2 遗留确认语义钉子**（测试为主，代码仅 snapshot 防污染）：
   - delete 后再 put 的 ack 定序（legacy 与 generation 两种路径）；
   - 重复 ack 幂等（legacy 与 generation）；
   - reset 后旧 put ack 不复活值（legacy 与 generation）；
   - **snapshot() 防污染**（plan-B2 "读取快照不能被外部修改污染"）：`KeyValuePair` 字段可变，`snapshot()` 当前返回活引用，外部改行会污染镜像（`cachedAll()` 消费方 `CrashHandler`/`BackupFragment` 均只读序列化，防御性拷贝安全）。最小修复 = `snapshot()` 返回逐行拷贝。
3. 将 S1-B1 不变量登记为后续批次的永久回归约束（见 STATUS.md 约束清单）。

## 允许文件/符号

- `.github/workflows/ci.yml`（仅 push branches 过滤器）
- `app/src/main/java/io/nekohasekai/sagernet/database/preference/KvMemoryCache.kt`（仅 `snapshot()` 防御性拷贝 + 私有 copy 辅助；其余不动）
- `app/src/test/java/io/nekohasekai/sagernet/database/preference/KvMemoryCacheTest.kt`（legacy 路径钉子）
- `app/src/test/java/io/nekohasekai/sagernet/database/preference/KvMemoryCacheLinearizabilityTest.kt`（generation 路径钉子）

## 禁止事项

不动 `RoomPreferenceDataStore.kt`（本批无 store 改动）；不动 B3 队列/barrier；不删 legacy `writeCommitted(key,value)` 兼容路径（已被 ACCEPTED 审计认可，生产 ack 路径保持 generation-only）；不改 schema/签名/重试参数；不用 sleep/delay/扩线程；不做 reset epoch 等扩项（ISSUES 边界记录，不在本批）。

## 失败测试（RED 要求）

- Run H（当前 base `e9b92cf`）：`snapshotRowsAreDefensiveCopies` 必须失败（返回活引用被外部污染），exit code ≠ 0。
- Run I（基线 `cc63f248`，plan-B2 "验证旧逻辑确实能使新增断言失败"）：临时 `git checkout cc63f248 -- KvMemoryCache.kt RoomPreferenceDataStore.kt` 并将 `KvMemoryCacheLinearizabilityTest.kt` 移出 source set（其引用 baseline 不存在的 API），仅运行 `--tests "*KvMemoryCacheTest"`：`deleteThenPutOrderingPinsLatestValue`（baseline 旧 ack 复活 v1）与 `snapshotRowsAreDefensiveCopies` 必须失败；重复 ack/reset 钉子应为绿（约束性）。跑完立即 `git checkout HEAD -- <两文件>` 并恢复 LinearizabilityTest，`git status` 核验无损。

## 验证命令

```powershell
./gradlew.bat :app:testOssDebugUnitTest --tests "*KvMemoryCache*" --no-daemon --console=plain
./gradlew.bat :app:testOssDebugUnitTest --no-daemon --console=plain
```

cwd=仓库根；shell=PowerShell；GREEN 时 exit code=0 且 0 failures。证据存 `docs/agent/evidence/s1-b2/`。

## 回退方案

revert 本批单 commit（ci.yml + snapshot 拷贝 + 测试），回到 `e9b92cf` 语义；无 schema/格式变更。

## push 分支 / 停止条件

分支：`fix/p01-room-off-main-thread`（非 main）。停止条件：候选 push 后 `WAIT_CHATGPT_AUDIT`；`ACCEPTED` 才允许签发 S1-B3（队列/刷新/失败 barrier，架构批）；`CHANGES_REQUIRED` 只修本批。

## 明确不在本批（后续批次排队）

- P2-TEST-ROBUSTNESS：`SnapshotOrderingTest` 内 `Thread.sleep(80)` 的确定性改造 → 后续 test-infrastructure 清理批（审计明示不得为此扩生产设计）。
- plan-S1-B3：写队列/`flushPendingWrites` barrier/失败协议 → 下一架构批。
- reset 全表 epoch、真实 DAO 注入异常矩阵 → B3 范围。
