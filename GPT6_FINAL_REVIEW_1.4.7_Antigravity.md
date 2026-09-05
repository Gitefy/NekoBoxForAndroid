# NekoBox 1.4.7 终版前复审问题清单 — Antigravity

日期：2026-09-05。

## 范围与结论

用户要求：重新检查 Antigravity 按 `GPT6_BUG_REPORT_1.4.7_Antigravity.md` 做的修改，**只提出问题并写 Markdown，不修改代码**。计划在后续修复后结束本轮维护，因此本报告只列影响数据、路由与稳定性的具体问题，不提出功能扩展或无关重构。

- 审查目录：`C:\Users\renos\Documents\Proxy\NekoBoxForAndroid-router-groups`。
- 审查提交：`c790efb80670d2dccd40bf63a5bab252091d83a9`，分支 `router-groups`。
- 对照基线：`acdd4ee95cb7f61307278badfc1c159229911207` 与上一份报告。
- 开始审查时工作区干净。本轮不改生产代码、测试、构建配置，不提交、不推送、不发布；仅新增本文及被忽略的验证日志。独立验证小程序位于系统临时目录。
- 未读取或使用 A7、isA8、私人备份、订阅或真实节点凭据。
- **仍有 5 个问题：3 个 P1、2 个 P2。当前不宜直接认定为可收尾终版。** 行号对应上述提交；后续按符号定位。

P1：涉及正常操作下错误路由、选择丢失，或损坏恢复导致持久化设置异常，优先关闭。
P2：特定损坏输入仍被接受，属于备份完整性承诺的缺口，也应在收尾前关闭。

## R01 — P1：只给 select 加事务，不能阻止同步用旧快照覆盖新选择和组配置

位置：

- `app/src/main/java/io/nekohasekai/sagernet/database/GroupManager.kt:148-238`，特别是 `151`、`198`、`210-234`。
- `app/src/main/java/io/nekohasekai/sagernet/database/RouterGroupRepository.kt:130-156,171-193`。

### 触发与影响

订阅刷新与手动点选或编辑 Router 同时发生时，新选择可能被恢复成旧选择；模式、名称、过滤条件等字段也可能被旧整行覆盖。运行时已接受一次热切换后，数据库又变回旧选择，会导致页面、实际连接、下次启动状态不一致。

可控交错步骤：

1. 同步进入 `reconcileRouterMembers()`，第 151 行读取 Router，旧选择为 A。
2. 暂停同步；用户调用 `select()`，其事务成功提交选择 B。
3. 恢复同步；它继续用第 151 行保存的旧 Router 计算结果，再在第 228 行 `update(router.copy(...))` 写回 A。

`select()` 的事务在第 2 步已经结束，不会阻止第 3 步。同步的读取、计算、成员替换和整行更新没有包在共同事务中。`replaceMembers()` 自身的事务也不能覆盖外面的 Router 读取/更新。`save()` 也在事务外读取 existing，再整行写回，有相同的旧值覆盖窗口。

**证据等级：** 当前源代码可确定的交错路径；未运行 Android Room 并发测试，不把上述步骤称为已在设备复现。上一份报告 F10 的“同步事务化”描述与当前提交不符，不能据该描述关闭此项。

### 关闭标准

以可控暂停点覆盖“同步读完 → select/save 提交 → 同步继续”的交错，最后明确保留最新用户操作；成员与选择能在一致状态下读取。仅测试纯 `RouterReconciler` 或只检查 select 有事务，不能覆盖此问题。

## R02 — P1：部分恢复仍按数字 ID 判断身份，会把规则静默绑定到另一组/节点

位置：

- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt:379-400`。
- `app/src/main/java/io/nekohasekai/sagernet/fmt/BackupSerializer.kt:35-63`。

### 触发与影响

备份 A 的 Router `id=3, stableTag=router.A` 被规则引用；目标设备的 `id=3` 实际是 `stableTag=router.B`。仅恢复规则时，代码只传入本机 Router ID 集合 `{3}`，校验通过，规则最终指向 B。

仅恢复配置、保留现有规则也有对称问题：旧规则引用本机 id=3 的 A，而导入配置用 id=3 的 B 替代，存在性检查仍通过。正数 legacy outbound 在同 ID 不同节点时也有相同缺口。

这不需要损坏备份：来自另一设备、重建过数据的合法备份即可触发。影响是用户看到恢复成功，但流量进入意外策略组/节点。当前 `routerRuleRefs` 只保存 ruleId/routerGroupId，规则单独导出的数据没有足够的稳定身份信息。

**证据等级：** 已调用当前编译版本 `validateRuleReferences()`，`routerGroupId=3` 对 `{3}` 被接受；函数不接收 stableTag 或节点身份，无法区分上述两种对象。完整跨设备导入未执行。此项属于旧 B09 尚未解决的身份部分，不是新增功能要求。

### 关闭标准

分别覆盖“只恢复规则”和“只恢复配置”的同 ID 不同身份场景。能证明身份一致才关联；无法证明时应在写库前明确拒绝，不能按名字或数字 ID 猜测。`0/-1/-2` 的旧语义与合法旧备份兼容性须保留。

## R03 — P1：设置只验证 key 唯一，错误的类型负载仍可写入，恢复后读取异常

位置：

- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt:303-312,433-436`。
- `app/src/main/java/io/nekohasekai/sagernet/database/preference/KeyValuePair.kt:66-91,150-154`。
- `app/src/main/java/io/nekohasekai/sagernet/database/preference/RoomPreferenceDataStore.kt:8-14`。
- `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt:40`。

### 触发与影响

人工记录：`key="profileId"`，`valueType=TYPE_LONG`，`value=byte[0]`。

它可以放进合法 Parcel：key 非空，value 数组非 null；`KeyValuePair(parcel)` 只读取这三个字段。导入校验只检查重复 key，因此会接受并写入 PublicDatabase。后续 `DataStore.selectedProxy` 读取该值，`KeyValuePair.long` 执行 `ByteBuffer.wrap(value).long`，抛出 `BufferUnderflowException`，默认值回退无法处理已经抛出的异常。

类似问题存在于固定长度 boolean/float/int，以及内部长度损坏的 stringSet。它们不经过 Kryo bean 解码，所以新增 strict 模式不能保护设置。

**实际独立验证：** 使用当前编译的 `KeyValuePair` 构造上述人工记录，key 检查通过，调用 `getLong()` 得到 `BufferUnderflowException`。未向真实数据库写入，也未在手机上制造崩溃。

### 关闭标准

任何 reset 前完成选中设置段的类型/负载结构检查。验证上述输入及截断 stringSet 被拒绝，原配置和设置均未变化；正常设置备份往返仍一致。不能只增加重复 key 测试。

## R04 — P2：严格解码的 null 字节数组仍直接返回默认对象

位置：

- `app/src/main/java/io/nekohasekai/sagernet/fmt/KryoConverters.java:77-78`。
- `app/src/main/java/io/nekohasekai/sagernet/fmt/Serializable.kt:25-26`。
- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt:333-353,411-414`。

### 触发与影响

JSON 数组本身不是 null，但其中某条 Base64 Parcel 的内部 byteArray 是 null。`CREATOR.createFromParcel()` 将其交给 `KryoConverters.deserialize()`，第 78 行在严格检查前直接 `return bean`。

因此，严格模式仍会把无有效内容的普通组/Router 记录当成默认对象。默认 Router 为 id=0、空 stableTag；导入未验证这些身份字段。无成员、无引用的此类记录能绕过现有关系检查并进入插入路径，自动生成数据库 ID 后成为不能正常参与 Router 构建的记录。普通组也有默认对象被接受的路径。

**实际独立验证：** `withStrictDeserialization(() -> deserialize(new RouterGroup(), null))` 正常返回 `id=0, stableTag=""`。严格模式退出后恢复正常状态也已核对；问题不是 ThreadLocal 泄漏。未执行 Android Parcel → Room 的完整导入。

### 关闭标准

区分“旧版可选段缺失”与“段内记录负载缺失”。备份严格路径必须拒绝后者，且保持数据库日常读取的原容错边界；覆盖普通组、Router 等外层 Serializable 记录，不能只测截断 SOCKS bean。

## R05 — P2：备份校验漏掉链式节点/前置落地引用，损坏链可恢复成功后递归崩溃或缩短路径

位置：

- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt:339-353`：只验证 bean 存在、所属普通组，以及 Router member/source。
- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt:223-235`：递归展开 ChainBean；不存在的子节点直接 continue。
- 同文件 `250-260`：前置/落地节点找不到时不添加。

### 触发与影响

人工构造 `ProxyEntity(id=11, groupId=1)`，ChainBean.proxies 为 `[11]`，并提供正常 group 1。bean 非 null，序列化字节完整，所属组存在，没有规则或 Router 悬空引用，现有导入检查全部没有针对其自引用的条件。

导入后构建该节点配置时，`resolveChainInternal()` 再次展开自己，没有 visited/递归环检查，最终栈溢出。A→B→A 同样成立。

此外，ChainBean 引用缺失节点、普通组 frontProxy/landingProxy 指向不存在节点时，恢复检查也不拒绝，ConfigBuilder 会忽略缺失环节，可能让实际代理路径短于备份要求。

**实际独立验证：** 当前生产 `ProxyEntity` 和 `KryoConverters` 能完成上述自引用链的严格序列化/反序列化往返，`requireBean()` 通过，读回引用仍为 `[11]`。递归后果由当前 ConfigBuilder 调用链确定；没有在 Android 上运行到栈溢出。属于既有缺口，当前备份完整性修复仍未覆盖。

### 关闭标准

恢复写库前检查正向链式引用存在性与环，以及普通组已有前置/落地引用。自引用、双节点环、缺失子节点均拒绝且原库不变；合法多跳链往返后路径顺序不变。保留现有“未设置前置/落地”的合法哨兵值。

## 本轮验证事实与未验收项

以下仅界定证据范围，不表示上述问题已修复。

| 验证 | 本轮结果 |
|---|---|
| 综合命令 | `testOssDebugUnitTest compileOssDebugAndroidTestKotlin lintOssDebug assembleOssDebug --offline --no-daemon --console=plain`，退出 0，33 秒；多数任务 UP-TO-DATE |
| JVM 重新实际执行 | 使用临时 init script 将 test 任务 outputs.upToDateWhen 设为 false 后再次执行；退出 0，76 tests / 0 failures / 0 errors；并非只引用缓存测试报告 |
| Android 测试源码编译 | Gradle 检查通过（UP-TO-DATE）；没有运行 instrumentation |
| lint | 0 errors / 30 warnings，未将 warning 扩展成额外修复清单 |
| debug APK | assemble 成功；aapt 检查包名 com.nb4a.debug、versionName=1.4.7、versionCode=250、native-code=arm64-v8a；旧 B02 的产物版本问题不再列入本轮问题 |
| 独立 JVM 探针 | 退出 0，输出见下；直接调用当前编译生产类，不依赖真实用户数据 |
| 设备 | adb devices 无设备；备份往返、恢复旋转/重启、Router 热切换/urltest/订阅刷新、Room 并发与迁移均未做本轮真机验收 |
| 工作区 | 审查前干净；无生产代码/测试/构建配置修改；git diff --check 通过 |

日志位于仓库根目录，均为被忽略的本地日志：

- `final-review-verification.log`
- `final-review-tests-fresh.log`
- `final-review-probe.log`
- `final-review-probe-setup.log`（最终成功；初次辅助脚本未跳过 buildSrc 而失败，修正临时脚本后重跑通过，与产品代码无关）

独立验证程序：`C:\Users\renos\AppData\Local\Temp\nekobox-final-review-20260905\ReviewProbe.java`。其输出为：

```text
SELF_REFERENCING_CHAIN_STRICT_ROUNDTRIP=ACCEPTED id=11 refs=[11]
STRICT_NULL_ACCEPTED id=0 tag=''
STRICT_MODE_RESTORED=true
SETTINGS_PRECHECK_ACCEPTED=true
SETTING_READ=BufferUnderflowException
RULE_ONLY_DIFFERENT_STABLE_TAG_SAME_ID=ACCEPTED (validator receives no identity)
```

探针没有调用 Android 的 `finishImport()`，也没有写任何用户数据库。不能把局部验证表述为完整手机复现。

终版尚缺的设备证据应单独补齐：正常全量备份往返；部分恢复拒绝错误关联；确认恢复后旋转仍能完成重启；运行中手动/自动切换与订阅刷新交错；旧数据库迁移。没有设备时，交付说明必须保留“未验证”，不能以本轮 JVM/构建通过替代。

## 给 Antigravity 的范围边界

本报告未授权本轮 GPT-6 修改代码。用户后续要求你修复时，以 R01–R05 为有限收尾范围，逐项提供回归证据；不要把旧报告已处理的部分重写，也不要扩展 UI、架构或配置导入功能。保留 Room 10、arm64、现有默认出口和受保护配置。提交、推送、发布仍按用户实际授权执行。
