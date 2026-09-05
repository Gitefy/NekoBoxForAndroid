# NekoBox 1.4.7 问题清单与 Antigravity 接手说明

日期：2026-09-05。

## 1. 先读：这不是已完成的 1.4.7

用户最新要求：**暂停代码修改，把发现的问题写清楚，由 Antigravity 接手修复。** 本文整理的是本轮已发现的问题，不声称已穷尽所有缺陷。

- 唯一接手目录：`C:\Users\renos\Documents\Proxy\NekoBoxForAndroid-router-groups`。
- 基线提交：`acdd4ee95cb7f61307278badfc1c159229911207`，分支 `router-groups`。
- 本轮开始时工作区干净；当前已有未提交修改，来自 GPT-6 本轮修复及独立审查。**保留现场，不要 reset/clean/checkout 覆盖，也不要从主克隆重新开始。**
- 已把 `nb4a.properties` 改为 `VERSION_NAME=1.4.7`、`VERSION_CODE=50`；构建脚本乘以 5，预期 Android 版本码为 `250`。
- 尚未完成最后一轮修复。尤其新加的 `BackupIntegrityTest.kt` 引用了尚未实现的方法，当前测试源码不是可编译的最终状态。
- 上一次成功构建的 APK **实际仍为 1.4.6 / 245**，见第 3 节；不能把它当作 1.4.7 发给用户。
- 本轮未 commit、push、上传配置或发布 APK。用户的备份文件、订阅、节点凭据均不可上传。不要用 `git add .` 或整体目录打包上传。
- 不读取、不分析、不修改、不导入 A7、isA8/同类保留配置。测试使用人工构造的数据。
- 保持现有整体架构、arm64-v8a 单架构及 Room schema 10；不要借修复恢复已删除的 WebDAV/网络工具/侧边栏功能。

## 2. 验证记录：注意各记录对应的时间点

所有日志都在仓库根目录，受 `*.log` 忽略规则保护。

| 记录 | 实际结果 | 说明 |
|---|---|---|
| `baseline-test.log` | 原始 JVM 测试成功 | 交接基线能通过旧测试，并不表示下面的问题不存在 |
| `regression-red.log` | 5 项测试中 2 项失败 | 复现缺失 bean 仍可序列化、缺失 Chain bean 的错误路径依赖 Android 资源 |
| `regression-order-red.log` | 7 项测试中 2 项失败 | 前述 bean 测试已转绿；排序测试复现成员替换/重复 ID 导致错误顺序 |
| `regression-group-red.log` | 2 项测试中 1 项失败 | 复现普通分组备份丢失 selector/前置/落地字段 |
| `repair-check.log` | 一次 Android 测试编译失败 | 本轮新测试误用了 RoomDatabase.use；已改 try/finally，后续编译通过 |
| `verification-1.4.7.log` | 综合命令成功，耗时 2m 4s | 执行 JVM 测试、Android-test Kotlin 编译、lint、assemble；当时 73 个 JVM 测试、0 failures、0 errors；lint 0 errors / 48 warnings |
| `handoff-current-compile.log` | 当前测试编译实际失败，退出码 1，耗时 1m 53s | `compileOssDebugUnitTestKotlin` 报两个缺失方法的 Unresolved reference 及衍生类型推断错误；不能引用上次成功来宣称当前全绿 |

上一次综合成功命令：

```powershell
$env:JAVA_HOME='C:/Users/renos/AppData/Local/CodexTools/jdk-17.0.20/jdk-17.0.20.1+1'
./gradlew.bat testOssDebugUnitTest compileOssDebugAndroidTestKotlin lintOssDebug assembleOssDebug --offline --console=plain
```

**真机状态：** `adb devices` 返回空列表。Room instrumentation、实际备份导入导出、旋转页面、手动切换、自动测速、订阅刷新等均没有实际在 Android 设备上执行。Android-test 编译通过不等于设备测试通过。

## 3. 仍需修复的阻塞项

下列行号对应暂停时工作区，后续编辑后请按符号定位。

### B01 — P1：新完整性测试引用了未实现的方法

位置：`app/src/test/java/io/nekohasekai/sagernet/fmt/BackupIntegrityTest.kt:16`、`:27`。

尚未实现：

- `KryoConverters.withStrictDeserialization { ... }`
- `BackupSerializer.validateRuleReferences(rules, routerIds, proxyIds)`

这是 GPT-6 刚写入的测试先行状态，不是原来 Antigravity 代码的编译问题。用户要求暂停时尚未写实现。不要为了全绿直接删掉测试；可以按最终实现合理调整调用方式，但必须覆盖其表达的损坏数据与悬空引用问题。

暂停后只运行编译检查，已实际确认失败：`./gradlew.bat compileOssDebugUnitTestKotlin --offline --console=plain`，退出码 1。生产 Kotlin/Java 编译在这次检查中通过，失败点是新增测试的未实现契约。

另有 `ProxyGroupBackupTest.exportRejectsSubscriptionGroupWithNoSubscriptionData` 刚新增，生产代码还未实现对应拒绝逻辑，见 B08。它是在最后一次成功测试之后加入的，尚未得到执行结果。

### B02 — P1：版本属性已更新，成功构建的 APK 却仍然是旧版本

证据：

- `nb4a.properties` 是 `1.4.7 / 50`。
- `app/build/outputs/apk/oss/debug/output-metadata.json` 显示 `1.4.6 / 245`。
- 产物名：`NekoBox-1.4.6-arm64-v8a-debug.apk`，25,167,699 字节。
- 用 SDK `aapt dump badging` 直接检查该 APK，得到 `versionCode='245' versionName='1.4.6'`，`native-code: 'arm64-v8a'`。这不只是文件名旧。
- 后续启动新 Gradle daemon 的编译生成了 `BuildConfig.java` 的 `1.4.7 / 250`，但没有重打 APK，因此它不能推翻旧 APK 的检查结果。

可疑根因（**尚未用 A/B 实验最终证明**）：`buildSrc/src/main/kotlin/Helpers.kt:15` 的顶层 `lateinit var metadata/localProperties` 只在第一次初始化时读取文件；Gradle daemon/classloader 复用时可能保留旧元数据。

建议：将缓存限定到当前 Gradle Project/build，或每次构建可靠读取；避免跨构建进程缓存。先做小型属性变化实验确认。完成后用 `--no-daemon` 重新打包，并同时核对 APK 内 manifest、output-metadata、BuildConfig 和文件名为 `1.4.7 / 250`，不能仅重命名旧 APK。

### B03 — P1：备份主段显式 JSON null 被视为空数组，可能清空数据

位置：`fmt/BackupSerializer.kt:79,91`；`ui/BackupFragment.kt:295-348`。

`getParcelableArray()` 把“不存在键”和“存在但值为 null”都返回 `emptyList()`。随后 `finishImport()` 会 reset 对应表。

人工复现输入：

- 选中恢复设置，输入 `{"settings":null}`：会被当作空设置列表并清空设置。
- 选中恢复配置，输入 `{"profiles":null,"groups":[]}`：可通过当前校验并清空节点/分组。
- 选中恢复规则，输入 `{"rules":null}`：可清空规则。

以上为源码确定的分支，**未在设备上执行破坏性复现**。

修复方向：区分缺失的可选旧版 Router 段与显式损坏的段。选中恢复且键存在时，必须确认是合法数组；显式 null/错误类型应在任何 reset 前失败。合法的 `[]` 保持原有清空语义，不要混淆。

### B04 — P1：截断的 Kryo/协议数据可能被吞掉异常后当作有效备份恢复

位置：`fmt/KryoConverters.java:52-62`；`fmt/BackupSerializer.kt:125`；`database/ProxyEntity.kt` 的 `deserializeFromBuffer/putByteArray`。

当前 `KryoConverters.deserialize()` 捕获 `KryoException` 后继续 `initializeDefaultValues()` 并返回对象。只检验 `requireBean()` 不够：截断的非空 bean 字节仍可能得到一个“非 null 但只解出部分内容”的对象。

**重要：** 只在备份路径对外层 `Serializable.deserializeFromBuffer()` 严格解析还不够；ProxyEntity 内层协议 bean 仍然调用同一个容错 converter。

建议：实现只对备份导入启用、涵盖外层及嵌套 bean 的严格反序列化。普通数据库读取的历史容错行为不要顺手全局改变。如果用线程局部严格模式，必须 try/finally 恢复旧值，避免串扰并发任务和后续数据库读。

验收：完整数据可读；截断外层、截断内层、缺失必须字段都在 reset 前失败；旧版合法记录仍可导入。`BackupIntegrityTest.strictBackupDecodeRejectsTruncatedNestedProtocolData` 已表达其中一个边界。

### B05 — P1：确认恢复后旋转/销毁页面，可能已写入数据库但没有完整重启

位置：`ui/BackupFragment.kt:261-269`。

这是**本轮将导入迁移到 lifecycleScope 后的待修缺口**：

1. 用户确认恢复，`lifecycleScope.launch` 进入 `onDefaultDispatcher { finishImport(...) }`。
2. 页面旋转/Fragment 销毁，协程被取消。
3. `finishImport()` 内数据库事务没有协程挂起点，可能继续执行并提交。
4. 回主线程时 withContext 因取消抛异常，后面的 `triggerFullRestart(activity)` 不执行。
5. App 留在“数据库已更换、内存设置和运行态未完整重载”的状态。

建议：确认前解析保持可取消；一旦开始已授权的数据库恢复，让有限的“写入 + 保证重启”不受页面销毁取消影响。UI 消息仍要遵守生命周期，不要强行操作销毁的 Activity。检查 `ktx/Utils.kt:257` 的重启实现及 Context 使用。不能只吞掉 CancellationException。

### B06 — P1：设置重复 key 可导致配置已恢复、设置恢复失败

位置：`ui/BackupFragment.kt:295-348`；`database/preference/KeyValuePair.kt` 的 `Dao.insert`。

本轮已把设置解码提前，并为 PublicDatabase 的 reset/insert 加事务。但没有检查设置 key 唯一性。SagerDatabase 先提交；随后 PublicDatabase 插入重复 key 时可因约束失败而回滚设置事务。结果主数据库已经换了，设置仍是旧的。

建议：在任何写入前校验设置键唯一性，并完成全部选中段的预解码/语义校验。两个数据库各自 runInTransaction 不等于跨数据库原子恢复；进一步评估写入失败的恢复策略，不要宣称当前已是全局原子恢复。

### B07 — P1：已有损坏 Router 成员仍可能被“保留旧成员”分支留下

位置：`database/GroupManager.kt:190,207-214,259`。

本轮已在新节点快照/预览中调用 `requireBean()` 排除缺失 bean 的节点，但清理仍只检查节点 ID 是否存在。

确定路径：数据库里的节点都缺失 bean，但 RouterMember 仍指向这些 ID → 新节点快照为空 → Reconciler 进入 preserved/error 分支 → cleanup 发现这些 ID 仍在 proxy 表，未清理 → UI 或配置构建仍可能拿到损坏成员并调用 requireBean。无效 matchConfig 被跳过的组也应检查。

建议：只清理不能作为可用 Router 成员的引用并修正选择，不删除原 ProxyEntity 数据；真正的订阅失败/空刷新仍保留上次**有效**成员。不要把“清理 Router 引用”变成“导出或启动时删除用户节点”。

### B08 — P1：订阅组缺少 subscription，仍可导出无法回读的记录

位置：`database/ProxyGroup.kt:52`。

写端在 `type==SUBSCRIPTION` 时调用 `subscription?.serializeToBuffer()`，为空就跳过；读端却必然读取 SubscriptionBean。这样写出的流布局不匹配。

建议：备份/组序列化时对必需的 subscription 明确校验并报错，保留数据库原记录。新加入的 `ProxyGroupBackupTest.exportRejectsSubscriptionGroupWithNoSubscriptionData` 正等待实现。

### B09 — P1：导入仍缺少完整的路由/成员引用校验

位置：`ui/BackupFragment.kt:323-340`。

当前情况：

- Router members/sources 缺失引用时被静默 filter 掉，用户看到恢复成功但实际丢失关系。
- `routerRuleRefs` 写入 rules 后未检查其 Router 是否存在。
- 正数 legacy outbound 未检查目标 profile 是否存在。
- 仅恢复配置而保留现有规则时，配置替换可能让原有规则悬空。
- 仅恢复规则时，只按数值 ID 关联可能把来自另一份备份的 Router 误认成本机同 ID 的不同 Router；此项需进一步设计跨备份身份校验，不能猜测用户意图。

建议：先验证最终组合状态，再 reset/提交。Router 引用优先于旧 outbound；`0/-1/-2` 保持现有语义。对无法确定的跨备份引用给出明确错误，而非静默改去别组/直连。校验失败应保留原库。

`BackupIntegrityTest.importedRulesCannotReferenceMissingRoutersOrProfiles` 已写测试意图，但校验方法尚未实现。缺少旧版 Router 数组本身应继续兼容，不能一概当作损坏。

## 4. 已写入的修复：接手后保留、复审、补验收

下表是当前已有改动，不是让 Antigravity 再从头重写。

| 编号 | 原问题及触发 | 当前修改 | 证据/边界 |
|---|---|---|---|
| F01 | 导出发现缺失 bean 就直接删除数据库节点，还可能留下成员/规则引用 | 移除 doBackup 的 partition/deleteProxy，提取 `BackupSerializer.exportDatabase`；读取相关主库表在一个事务内；缺失 bean 报错，不输出假成功 | 源码已审；新增真实 Room 导出不删数据测试，Android-test 仅编译，未在设备执行 |
| F02 | ProxyEntity 缺失 bean 仍写零长度负载；Chain 缺失 bean 的报错拼接还依赖资源 | 序列化 requireBean；错误信息只含 type/id/groupId，不访问 displayType 资源 | 2 个失败回归已转绿；未恢复历史已丢失的数据 |
| F03 | 普通组备份遗漏 isSelector/frontProxy/landingProxy | 非分享记录版本升级为 1，并增加字段；读取版本 0 保持旧默认值；分享记录未改 | Kryo 直接读写回归已验证；Room schema 未改 |
| F04 | 空 Router 页面读取时再次 reconcile，回调又 reload，形成递归 | `ConfigurationFragment.reloadProfiles` 改为只读，不再同步成员 | 调用链已确认；需要设备验证空匹配组打开不会卡死/溢出 |
| F05 | 手动/自动模式编辑后页面仍持有旧 Router 对象，点击失效；删除组后页签滞留 | 刷新完整 Router 元数据，onResume 更新，最后一组删除后回普通视图 | 编译通过；需设备验收 |
| F06 | Pager reload 清空活页面注册，之后回调无法找到页面；旧页销毁可移除新页注册 | 保留当前仍存在的组注册；销毁时核对对象身份 | 编译通过；旋转/切换/删除需设备验收 |
| F07 | createProfile 先 reconcile 再 onAdd，同一 ID 可能再次插入卡片 | onAdd 防止重复插入同 ID | 源码时序确认；需导入/新增节点后实际验证 |
| F08 | UI 拖拽快照过期，成员数量相同却 ID 集合不同，DAO 排序出现重复/遗漏；重复输入也留空档 | `RouterMember.Dao.updateOrders` 按当前有效 ID 交集去重，再追加新增成员，连续编号 | 2 个排序回归从红到绿；使用内存 DAO 存储验证真实 default 方法，不代替 Room instrumentation |
| F09 | Router urltest 包含全局选中节点时接管 mainProxyTag/outbound=0，并可反向写全局选择 | ConfigBuilder 保持原主出口，Router 仅承接显式 Router 引用 | 符合项目“未匹配流量保持原默认行为”；新增 resolver 语义测试已通过，但不是完整 ConfigBuilder/native 集成测试 |
| F10 | 成员同步各表分步写，UI 可能读到部分结果 | reconcile 主库读/计算/写事务化，事务外发事件；新增匹配/预览/选择前校验 bean | 不等于已解决所有并发读写；B07 和第 5 节仍需检查 |
| F11 | 导出只把字节留在 lateinit 字段，文件选择器期间重建页面后失效；本地化日期可能包含文件名分隔符 | 缓存快照文件 + 保存待导出文件名；安全时间格式；导出/分享使用生命周期协程 | 编译通过；用户取消/进程恢复/缓存被清理等需设备验收 |
| F12 | 导入文件 provider 的 cursor 元数据查询在 try 外，空游标/缺失列会抛异常 | 查询移入异常处理，检查列及 moveToFirst，IO 离开主线程；提前捕获选项 | 编译通过；已知生命周期遗留 B05 |
| F13 | 设置解码晚于主库恢复，坏 settings 可以让主库先被替换 | 设置提前解码；PublicDatabase reset/insert 事务化 | 仅部分修复，仍有 B03/B06 |
| F14 | 私人配置可能误加入上传 | 补充 .gitignore 的备份 JSON/ZIP、中文“备份”、本地 A 数字配置等规则 | 现有根目录两份 nekobox JSON 均被 check-ignore 命中，tracked 路径检查没有这些备份；忽略规则无法阻止显式 git add -f/人工上传 |

## 5. 待进一步复现的风险，不要当成已确定修复

1. **选择与同步竞争：** `RouterGroupRepository.select():172` 读取 Router/成员，再以整个旧对象 update；这些操作没有与读取一起包事务。并发 reconcile/保存设置可能令校验过时，或旧 copy 覆盖新 mode/filter/selection。需要可控交错测试后做最小事务或字段更新修复。
2. **拖拽 UI 快照跨线程：** `ConfigurationFragment.commitMove():2249` 在 Default dispatcher 读取 `configurationIdList` 并清除 `routerOrderChanged`，而拖拽在主线程更新。DAO 修复不能证明 UI 快照安全；建议主线程取不可变快照再写库，验证快速连续拖拽与刷新交错。
3. **备份主库快照与订阅写入：** 导出读事务保证读者一致，但 RawUpdater 多次独立写入仍可能在某个中间状态被完整读到。审查订阅更新提交边界，不要误称导出事务单独解决整个更新过程的原子性。
4. **导出取消后的缓存文件：** 文件在后台生成后、返回主线程前页面被取消时，可能留下缓存快照；launch 文件选择器失败也可能遗留。低于数据库安全问题，后续可做严格限定到本功能创建文件的清理，不删除用户选择的导出目标。
5. **48 个 lint warning：** 没有逐项升级为 bug，不能直接全局 suppress。`app/build/lint.txt` 是本次完整清单。Room migration test 仍有旧 MigrationTestHelper 构造器警告，需要实际设备迁移测试确认，不以“编译过”替代。

## 6. 推荐接手顺序

1. 阅读父目录 AGENTS.md、原 `GPT6 handoff.md` 及本文，确认 git diff，不覆盖当前改动。
2. 先补 B01 所指测试契约；完成 B03/B04/B06/B08/B09 的导入导出完整性修复。让错误在任何 destructive reset 前报出，且不能泄露订阅/凭据。
3. 修复 B05 的确认后恢复生命周期，以及 B07 的旧损坏成员引用。
4. 对第 5 节并发风险建立实际复现，再改必要代码。
5. 处理 B02，证明真实产物升级到 1.4.7/250。保持 Room 10、单 arm64 和用户已接受框架。
6. 重新执行完整验证，最后检查差异/私密文件/产物。没有用户新授权不要提交、推送或发布。

建议最终验证（这些命令是交接建议，本轮暂停后没有继续执行修复）：

```powershell
$env:JAVA_HOME='C:/Users/renos/AppData/Local/CodexTools/jdk-17.0.20/jdk-17.0.20.1+1'
./gradlew.bat :app:clean :app:testOssDebugUnitTest :app:compileOssDebugAndroidTestKotlin :app:lintOssDebug :app:assembleOssDebug --offline --no-daemon --console=plain
git diff --check
```

SDK：`C:\Users\renos\Documents\Proxy\.router-groups-toolchain\android-sdk`，已由 local.properties 指定。使用 SDK `aapt dump badging` 核对 APK 的 `versionName=1.4.7`、`versionCode=250`、arm64-v8a；必要时用 `apksigner verify` 检查签名。不得将私有备份打入 assets/resources 或交付压缩包。

设备验收至少包括：

- 健康数据备份往返：普通组 selector/前置/落地、Router 模式/来源/顺序/选择、规则、设置一致。
- 恶意/损坏/截断/null/重复键/悬空引用备份失败，原数据库和设置不被改动。
- 缺失 bean 的导出有明确错误，节点原数据没有被偷偷删除。
- 文件选择器期间旋转/重建；确认恢复过程中旋转；恢复后保证完整重启。
- 空匹配组启动、自动与手动切换、删除最后一个 Router、添加节点不重复。
- 拖拽同时订阅刷新，原订阅顺序不被 Router 拖拽污染；选择只影响对应 Router。
- Google/AI、X/YouTube、Telegram 的既有已确认路由目标正确；AdBlock、`.invalid` 加载规则、App 分流、DNS/TUN 与普通默认出口保持。
- 订阅空/失败刷新不删除最后有效节点；成员全部无效时有可理解错误，不崩溃/悬空/静默改走其他组。

## 7. 当前改动文件

生产代码：

- `database/GroupManager.kt`
- `database/ProxyEntity.kt`
- `database/ProxyGroup.kt`
- `database/RouterGroupRepository.kt`
- `database/RouterMember.kt`
- `fmt/BackupSerializer.kt`
- `fmt/ConfigBuilder.kt`
- `ui/BackupFragment.kt`
- `ui/ConfigurationFragment.kt`

以上相对 `app/src/main/java/io/nekohasekai/sagernet/`。

测试：`ProxyEntityNullSafetyTest.kt`、新增 `ProxyGroupBackupTest.kt`、新增 `RouterMemberOrderTest.kt`、新增但尚未实现其契约的 `BackupIntegrityTest.kt`、`RouterRouteSemanticTest.kt`、Android 测试 `BackupSerializationTest.kt`。

其他：`.gitignore`、`nb4a.properties`、`docs/superpowers/plans/2026-09-05-1.4.7-repair.md` 和本文。计划中的复选框还未结项，不能据此宣称修复完成。

用户说“暂时先不修改”后，GPT-6 只做状态/编译/产物检查并写本文，没有继续更改生产代码或测试实现，也没有自动回滚之前的改动。
