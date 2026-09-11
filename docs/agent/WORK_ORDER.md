# 当前工作单：S2-B1-ASYNC-READINESS（短指针）

work_order：S2-B1-ASYNC-READINESS。
design_version：FD-1.0（设计冻结；授权推进状态见 STATUS 顶部临时授权）。
design_path：`docs/agent/final-design/S2.md` § B1。
base_code_sha：`d1be7dc99cd245efd3616e1f7569eef2777f57f9`（S1-B3 candidate，IMPLEMENTED_PENDING_AUDIT，未 ACCEPTED——依赖关系已按所有者临时授权明示记录）。
state：IMPLEMENTATION_IN_PROGRESS（完成后置 WAIT_AUDIT 标记，但按授权不等待审计即进入 S2-B2）。

要点（摘自 S2.md B1，全文以设计文件为准）：
- store 构造零 SQLite/runBlocking；Loading→Ready/Failed 单飞初始化；Failed 不写默认值、不自动覆盖；retry 单飞。
- bootstrap dirty 订阅窗口合并（init 即订阅 invalidation，Ready 后补读一次）；Ready 前不接纳业务写入。
- 前台服务晋升用应用名占位，DB 标题异步更新；晋升失败终止该次启动。
- 迁移 bootstrap 同步依赖（currentGroupId/currentGroup/selectedGroupForImport、BaseService 启动路径、主题/语言仍读内存占位）。
- 非关键 dbOffMain 残留记录在 HANDOFF，不本批全清（S7 冻结前清完）。

S2-B2 完成后本文件随批次推进更新；历史长稿在 `work-order-archive/`。
