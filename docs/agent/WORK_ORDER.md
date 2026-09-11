# 当前工作单：S2-B3-CROSS-PROCESS-VERIFICATION（短指针｜准备阶段）

work_order：S2-B3-CROSS-PROCESS-VERIFICATION。
design_version：FD-1.0（设计冻结；授权推进状态见 STATUS 顶部临时授权）。
design_path：`docs/agent/final-design/S2.md` § B3。
base_code_sha：`07e7f4552d40015ab2eb4eaa469f63b8a4837a80`（S2-B2 candidate，PENDING_AUDIT，未 ACCEPTED——依赖关系已按所有者临时授权明示记录）。
state：TEST_PREPARATION_DONE（仅提交 `app/src/androidTest/` 测试代码并通过 `:app:compileOssDebugAndroidTestKotlin`；`connectedAndroidTest` 未执行，待设备授权后 `NOT_RUN/BLOCKED`）。

要点（摘自 S2.md B3，全文以设计文件为准）：
- 以测试为主，允许 `app/src/androidTest/` 及测试包可用的远端 helper；不为测试导出生产组件；单 JVM 双对象不视为双进程证明。
- 真实 Room 事务/invalidation 与双进程 PID 场景；延迟 invalidation 仍可启动、快速 A→B 仅认最后有效请求、写失败阻止应用、重启后重读已提交数据、同 key 双写 STOP 后收敛、restore/reset 回滚、停止启动交错、取消不撤销 SQL。
- 命令：`./gradlew :app:connectedOssDebugAndroidTest` 需设备授权，记录设备/Android/PID/fixture/结果；缺环境可提交测试代码并标 BLOCKED。

后续：本授权范围结束后恢复逐批审计；S2-B3 不自动激活 S3。
