# 当前交接记录

模式：Cursor 实现 → GitHub 固定 SHA → 网页 ChatGPT 审计 → receipt 回 Cursor。

## 已关闭：S1-B1-KV-LINEARIZABILITY（ACCEPTED）

- base `cc63f24893696c075723eb8934529534529f31e2`；candidate `e9b92cf79625c555ecd21b10991642b76037de71`。
- verdict `ACCEPTED`（p2=1 非阻塞）；receipt 见 `REVIEW.md`。

## 已关闭：S1-B2-CONFIRM-SEMANTICS-CLOSURE（ACCEPTED + CI PASS）

- base `e9b92cf79625c555ecd21b10991642b76037de71`；candidate `1e140ca720a54dfa42cc37235484af7faae499bf`；metadata `f6a2def`。
- verdict `ACCEPTED`（critical 0 / p1 0 / p2 0）；**ci_status=PASS**（GitHub Actions push 事件 head_sha=1e140ca，status=completed，conclusion=success）。
- 完整 receipt 见 `REVIEW.md`（含 persistent_regression_constraints 七条冻结）。
- 本地证据：`evidence/s1-b2/run-{h,i,j,k}-*.txt`（RED→GREEN；full suite 29 suites / 131 tests / 0 failures）。

## 起草中（未实现）：S1-B3-WRITE-QUEUE-DURABILITY-BARRIER

模式：Cursor 起草 → 网页 ChatGPT **设计确认** → 确认后 Cursor 实现 → 固定 SHA 审计。
状态：DRAFTED / AWAITING_DESIGN_CONFIRMATION。**本轮无业务源码改动。**

- base_code_sha（实现起点）：`1e140ca720a54dfa42cc37235484af7faae499bf`。
- candidate_code_sha：无（未实现）。
- 工作单：`WORK_ORDER.md`（A—H 完整定义：flush 精确语义、barrier 边界、失败协议、restore/reset fence、
  reload/start durability 分析、8 条指定 RED 测试、范围限制、双方案比较与推荐）。
- 设计要点速览：方案 1（现有单线程 writer + admission 锁 + (epoch,sequence) 令牌 + per-op ledger + Condition barrier）
  优于方案 2（coroutine actor 整体重写）；`flushPendingWrites(): FlushResult` 只等调用前已接纳操作；
  3 次重试耗尽 → FAILED_PERMANENT 可观察（不假成功）；restore/reset DB 段入同条 FIFO 作为 fence（修 S3 前置的
  最小 queue fence：旧排队写无法在 restore 落盘后重现）；`KvMemoryCache.kt` 冻结不改。
- 明确不在本批：dbOffMain/runBlocking、RuntimeController、network debounce、ConfigSnapshot、sing-box、性能、UI、
  P2-TEST-ROBUSTNESS（`Thread.sleep(80)` 留待 test-infrastructure 批）。
- 停止条件：网页 ChatGPT 确认设计前禁止实现；实现批完成后 `WAIT_CHATGPT_AUDIT`。
