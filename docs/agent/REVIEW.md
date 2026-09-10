# 当前独立审计

reviewer：ChatGPT Web via GitHub fixed-SHA。
work_order：S1-B1 与 S1-B2 均 **已关闭（ACCEPTED）**；S1-B3-WRITE-QUEUE-FLUSH-BARRIER 已起草、未实现。

## 审计一（a34a0cf）

scope `cc63f248..a34a0cf`，verdict `CHANGES_REQUIRED`（P1-SNAPSHOT-ORDERING）。P1 已由候选 `e9b92cf` 修正并复审计通过。

## 审计二（e9b92cf）— S1-B1 ACCEPTED

- scope `cc63f248..e9b92cf`；incremental `a34a0cf..e9b92cf`（snapshotLock 串行化）。
- verdict **ACCEPTED**（critical 0 / p1 0 / p2 1）。完整 receipt 见下文 AUDIT_RECEIPT(S1-B1)。
- P2-TEST-ROBUSTNESS（`SnapshotOrderingTest` 内 `Thread.sleep(80)`）→ 遗留至 test-infrastructure 清理批，不得扩生产设计。
- ci_status 当时无 check（A08）。

## 审计三（1e140ca）— S1-B2 ACCEPTED

- repo：`Gitefy/NekoBoxForAndroid`；branch：`fix/p01-room-off-main-thread`。
- work_order：`S1-B2-CONFIRM-SEMANTICS-CLOSURE`。
- base_code_sha：`e9b92cf79625c555ecd21b10991642b76037de71`；candidate_code_sha：`1e140ca720a54dfa42cc37235484af7faae499bf`；handoff_metadata_sha：`f6a2def`。
- review_scope：`e9b92cf..1e140ca`。
- verdict **ACCEPTED**（critical 0 / p1 0 / p2 0）。
- ChatGPT 直接读取 GitHub 固定 SHA 差异：是。结论来源：源码 + L1 本地证据 + **GitHub Actions 实际运行结果**（ci_evidence：push 事件 head_sha=1e140ca，status=completed，conclusion=success）。

### Accepted findings（摘要）

1. `snapshot()` 返回逐行防御拷贝；可变 `value` ByteArray 以 `copyOf()` 深拷贝，消费方无法借返回行改写缓存。
2. S1-B1 的 snapshot 定序与 per-key generation 逻辑未变且保持有效。
3. legacy+generation 双路径钉子正确覆盖 delete→put 定序、重复 ack 幂等、reset 后旧 ack 不复活。
4. 未改 `RoomPreferenceDataStore` 生产行为、重试语义、executor 规模、schema、签名、运行时生命周期或无关架构。
5. A08 CI 分支过滤 `'*'→'**'` 正确（GitHub 官方 glob 语义：`'*'` 不匹配斜杠，`'**'` 匹配）；**修复已被 GitHub 行为验证——分支 `fix/p01-room-off-main-thread` 生成了 push workflow，CI 对 1e140ca 跑完且成功**。
6. 本地 RED/GREEN 与全量证据一致；**run-g 计数错误（131→124）被显式更正而非延续**。

### ci_status = PASS

GitHub Actions workflow CI，push event，head_sha=`1e140ca720a54dfa42cc37235484af7faae499bf`，status=completed，conclusion=success。

### persistent_regression_constraints（审计冻结，后续所有 settings/database 批次强制）

- S1-B1 per-key generation stale-ACK 保护必须保留。
- S1-B1 readEpoch/local-commit snapshot 保护必须保留。
- S1-B1 串行化 snapshot read+merge（snapshotLock）必须保留。
- `snapshot()` 不得暴露缓存持有的可变 `KeyValuePair` 状态。
- 重复 ACK 必须保持幂等。
- delete-then-put 必须保留最新 mutation。
- reset 必须使更旧的 mutation ACK 失效。

### carried_non_blocking_item

P2-TEST-ROBUSTNESS：`RoomPreferenceDataStoreSnapshotOrderingTest` 仍用 `Thread.sleep(80)` 作调度辅助；继续排队等待 test-infrastructure 清理，不得为此扩生产设计。

### required_next_action（已执行）

1. 关闭 S1-B2 为 ACCEPTED（本文件 + STATUS/HANDOFF，metadata only）。
2. 起草唯一下一工作单 `S1-B3-WRITE-QUEUE-DURABILITY-BARRIER`（WORK_ORDER.md，按用户/审计 A—H 规格完整重定义，
   取代早前草稿名 WRITE-QUEUE-FLUSH-BARRIER）：flush 精确语义（A）、barrier 边界（B）、失败协议（C）、
   restore/reset fence（D，含旧排队写不得在 restore 后重现）、reload/start durability 调用点分析（E，L0）、
   8 条指定 RED 测试 + 扩展（F）、范围限制（G）、双方案比较与推荐方案 1（H）。
3. **实现冻结**：在网页 ChatGPT 明确确认 S1-B3 工作单设计之前，不开始业务实现。

## AUDIT_RECEIPT（S1-B2，1e140ca）

```
AUDIT_RECEIPT

repo=Gitefy/NekoBoxForAndroid
branch=fix/p01-room-off-main-thread
work_order=S1-B2-CONFIRM-SEMANTICS-CLOSURE

base_code_sha=e9b92cf79625c555ecd21b10991642b76037de71
candidate_code_sha=1e140ca720a54dfa42cc37235484af7faae499bf
handoff_metadata_sha=f6a2def

verdict=ACCEPTED

critical_count=0
p1_count=0
p2_count=0

accepted_findings=

* KvMemoryCache.snapshot now returns per-row defensive copies rather than live KeyValuePair references.
* KeyValuePair mutable ByteArray payload is deep-copied with value.copyOf(), so snapshot consumers cannot mutate the cache by retaining or modifying returned rows.
* Snapshot ordering and per-key generation logic accepted in S1-B1 remain unchanged.
* Additional legacy and generation-path tests correctly pin delete-then-put ordering, duplicate acknowledgment idempotency, and reset-after-old-ack non-resurrection semantics.
* No RoomPreferenceDataStore production behavior, retry semantics, executor sizing, schema, signing, runtime lifecycle, or unrelated architecture was changed in this batch.
* A08 CI branch filter was correctly changed from '*' to '**'.
* GitHub official glob semantics support this correction: '*' does not match slash while '**' can.
* The fix has been behaviorally verified by GitHub itself: a push workflow was created for branch fix/p01-room-off-main-thread.
* GitHub Actions CI run for candidate 1e140ca720a54dfa42cc37235484af7faae499bf completed successfully.
* Local RED/GREEN evidence and full unit-suite evidence are consistent with the intended change.
* The previous run-g test-count metadata error was explicitly corrected rather than propagated.

ci_status=
PASS

ci_evidence=
GitHub Actions workflow CI, push event, head_sha=1e140ca720a54dfa42cc37235484af7faae499bf, status=completed, conclusion=success.

persistent_regression_constraints=

* S1-B1 per-key generation stale-ACK protection remains mandatory.
* S1-B1 readEpoch/local-commit snapshot protection remains mandatory.
* S1-B1 serialized snapshot read+merge ordering remains mandatory.
* snapshot() must not expose mutable cache-owned KeyValuePair state.
* duplicate ACK must remain idempotent.
* delete-then-put must preserve the latest mutation.
* reset must invalidate older mutation acknowledgments.

carried_non_blocking_item=
P2-TEST-ROBUSTNESS: RoomPreferenceDataStoreSnapshotOrderingTest still uses Thread.sleep(80) as a scheduling aid. Keep queued for later test-infrastructure cleanup. Do not expand production design solely to address it.

required_next_action=
Close S1-B2-CONFIRM-SEMANTICS-CLOSURE as ACCEPTED.

Update STATUS/HANDOFF/REVIEW in metadata only.

Then draft exactly one next work order:
S1-B3 write queue / flushPendingWrites durability barrier / persistence failure protocol.

S1-B3 is an architectural correctness batch. Do not begin implementation until its invariants, failure semantics, scope, RED tests, and interaction with restore/reset are explicitly defined.

Do not combine S1-B3 with dbOffMain removal, RuntimeController, network debounce, ConfigSnapshot, performance work, sing-box upgrades, UI work, or test-infrastructure cleanup.

review_scope=e9b92cf79625c555ecd21b10991642b76037de71..1e140ca720a54dfa42cc37235484af7faae499bf
reviewer=ChatGPT Web via GitHub fixed-SHA audit

END_AUDIT_RECEIPT
```

## 纪律

Cursor 的 SELF_REVIEW 不得写入本文件冒充独立审计。后续每次审计只针对一个固定 `base..candidate`。
