# 当前工作单：S2-B2-APPLY-STOP-HANDSHAKE（短指针）

work_order：S2-B2-APPLY-STOP-HANDSHAKE。
design_version：FD-1.0（设计冻结；授权推进状态见 STATUS 顶部临时授权）。
design_path：`docs/agent/final-design/S2.md` § B2。
base_code_sha：`354c472260314e2bc1e3a7a2c0da741bb9a83a1e`（S2-B1 candidate，PENDING_AUDIT，未 ACCEPTED——依赖关系已按所有者临时授权明示记录）。
state：IMPLEMENTATION_IN_PROGRESS（完成后置 WAIT_AUDIT 标记，但按授权不等待审计；随后准备 S2-B3 仅测试代码）。

要点（摘自 S2.md B2，全文以设计文件为准）：
- 发送端：点击时捕获目标 + requestId，不在后续异步重猜；本进程 Ready + 实际持久化（settings await B3 flush，Router 选择等待 Sager DAO 事务）后才发送小请求；失败不发“应用成功”。
- 启动走显式 service Intent，已运行时复用 SagerConnection/AIDL；内部广播仅作兼容 adapter 进入同一请求函数。
- 接收端：等待 Ready + 等待先前 settings 写入，新读 committed 不可变副本（`readCommittedSettingsSnapshot()`，不取 `cachedAll()`）；显式目标校验失败不回退到其他节点；完整跨库快照由 S5 完成。
- 回执：现有 AIDL callback 增加 `commandResult(requestId,outcome,instanceGeneration,persisted,errorCode)`；`commandGeneration` 单调，SUPERSEDED 语义，STOP ack 仅在资源清理后发布。
- 调用方 30s 观察超时仅显示“结果未确认”，不自动重发/回滚；一般 App 重启先确认待写提交，涉及服务时先收停止确认。

测试门槛见 S2.md B2（9 项）；新增协议与发/收两侧同候选提交，禁止半升级。
