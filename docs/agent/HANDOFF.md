# S2-B3 交接记录（测试准备 / 设备门槛未过）

执行期间切换为**项目所有者临时授权**：`S1..7 间逐批审计门槛`被临时覆盖（见 `docs/agent/STATUS.md` 顶部与末节）。

## 已关闭（按授权连续实施，均 IMPLEMENTED_PENDING_AUDIT）

- **S1-B3**：候选 `d1be7dc` / metadata `ea2d901`
- **S2-B1**：候选 `354c472` / metadata `7ea8740`
- **S2-B2**：候选 `07e7f45` / metadata `fa4776c`

> 三批均未标 `ACCEPTED`，`last_accepted_code_sha=1e140ca` 不变；依赖链 `1e140ca→d1be7dc→354c472→07e7f45` 已显式记录。

## 本批 S2-B3-CROSS-PROCESS-VERIFICATION（仅测试代码 / TEST_PREPARATION_DONE）

范围（按 S2.md B3 授权）：仅 `app/src/androidTest/`；**无生产代码改动、无 APK 安装、无设备操作、未进入 S3**。

### 差异（scope `07e7f45..89658b9`）

- `app/src/androidTest/java/io/nekohasekai/sagernet/bg/S2B3CrossProcessVerificationTest.kt`（新增）：8 个固定场景——
  1. `delayedInvalidationStillCanStartWithNewCommittedSettings`（延迟 invalidation 仍用新提交设置启动）
  2. `fastAthenBOnlyLastValidRequestWins`（快速 A→B 只认最后有效请求）
  3. `writeFailurePreventsApply`（写失败阻止应用）
  4. `receiverRestartReReadsCommittedData`（接收端重启后重读已提交数据）
  5. `sameKeyDualWriteConvergesAfterStop`（同 key 双进程写 STOP 后收敛）
  6. `restoreSingleDbFailureRollsBack`（restore 单库故障回滚）
  7. `stopStartInterleavingDoesNotReconnectOldStart`（停止/启动交错）
  8. `cancellationDoesNotRevokeQueuedSql`（取消等待不撤销已入队 SQL）
- 真实文件级 Room DB（非 mock），两实例共享同一 DB 文件做文件级串行化验证；双 PID 场景在 `connectedAndroidTest` 上执行。
- `docs/agent/WORK_ORDER.md` 指向 S2.md § B3。

### 编译验证（真实执行）

| run | dst | 命令 | exit | 证据 |
|---|---|---|---|---|
| compile-check | `89658b9` | `:app:compileOssDebugAndroidTestKotlin` | 0（`BUILD SUCCESSFUL in 44s`，39 tasks: 14 executed） | `docs/agent/evidence/s2-b3/compile-check.txt` |

未执行项：`connectedOssDebugAndroidTest`（`NOT_RUN`，无设备/未授权）、双 PID 场景（`NOT_RUN`）。

### CI

`89658b9` 已推 `origin/fix/p01-room-off-main-thread`；`ci_status=NOT_AVAILABLE`。

## 授权范围完成 — 恢复逐批审计规则

临时授权范围（S2-B1→S2-B2 连续实施、S2-B3 仅测试准备）已全部完成。自本 HANDOFF 起恢复 `BATCHES.md` 原门槛：
下一业务批次（S2-B3 设备执行 / S3）必须以匹配 SHA 的 `AUDIT_RECEIPT ACCEPTED` 激活；`WAIT_AUDIT` 重新成为停止点。
待审计链：`1e140ca..d1be7dc`（S1-B3）→ `d1be7dc..354c472`（S2-B1）→ `354c472..07e7f45`（S2-B2）→ `07e7f45..89658b9`（S2-B3 测试代码）。

## 未执行项（设备门槛）

- 安装 APK、双进程 PID 验证、真实 Room invalidation 跨进程行为：`NOT_RUN`（无设备/未授权）。
