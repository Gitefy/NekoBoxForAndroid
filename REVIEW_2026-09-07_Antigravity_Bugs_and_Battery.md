# NekoBox 1.4.7 复审：问题与耗电优化建议

日期：2026-09-07。接收方：Antigravity。

## 1. 结论与审查边界

**发现 5 项应处理的问题，其中 2 项 P1、3 项 P2；另有 1 项低优先级的序列化缺陷，以及 4 个省电优化方向。当前不能仅凭构建成功认定为收尾版本。**

用户要求只复审、提供 Markdown，由 Antigravity 后续修改。本轮没有修改生产代码、测试源码、构建配置或用户代理配置，没有提交、推送、切换分支或更新远端。

- 仓库：`C:\Users\renos\Documents\Proxy\NekoBoxForAndroid-router-groups`。
- 分支：`router-groups`。
- 审查 HEAD：`8104d9aacb8a735265d0f8b0b9acda468a1f3210`。
- 对照：上一轮 `c790efb` 及其问题报告；旧问题均重新对照当前源码，不直接照搬历史结论。
- 开始时：已跟踪文件无改动，仅有未跟踪的 `AG8/`；保留该目录，未将其作为本轮 App 审查对象。
- 未读取私人备份、A7、isA8 或同类配置内容，未提取订阅、节点凭据、签名密钥。
- 本报告针对本地提交；没有在线核对 GitHub 最新分支，因此不声明本地与当前远端一致。
- 主要检查范围：最近修复、Router 数据与运行时选择、备份恢复、流量统计、服务/通知/Doze、自动测速、订阅调度及 HEV 接入。不是对每个协议和整个依赖树的完整安全审计。

下文 App 路径均相对上述仓库；行号对应此 HEAD。核心源码另位于 `C:\Users\renos\Documents\Proxy\sing-box`，明确区分 App 源码、邻接核心源码和实际 AAR 证据。

P1：正常操作可破坏备份恢复能力或产生错误成员/选择。P2：特定正常配置下功能失效或统计不正确。

## 2. 必须处理的问题

### R01 / P1：默认分组名称合法为空，新恢复校验却一律拒绝

位置：

- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt:340`。
- `app/src/main/java/io/nekohasekai/sagernet/database/ProxyGroup.kt:17-18,102-104`。
- `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt:60,74`。
- `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt:1428`。

**触发：** 新安装使用应用自动创建的默认分组，正常导出包含“配置”的备份，再恢复该备份。默认分组通过 `ProxyGroup(ungrouped = true)` 创建，`name` 默认为 null；显示名称由 `displayName()` 回退到本地化默认名称。默认分组没有节点也仍会随 `allGroups()` 导出。

最新提交新增 `groupsList.all { it.id > 0L && !it.name.isNullOrBlank() }`，因此正常记录也会得到 `Backup contains invalid or blank proxy groups`，包括全量恢复和只恢复配置。这是修补损坏数据校验引入的兼容性回归。

**本轮验证：** 用当前编译的生产 `ProxyGroup`、`KryoConverters` 构造人工默认分组，严格序列化/反序列化成功，读回 `id=1, ungrouped=true, name=null`；代入当前导入条件为 false。未执行 Android Parcel → Room 完整导入。

**最小修复方向：** 按真实数据模型校验身份与负载，允许原本合法的默认分组空名称。不要为了通过校验给用户所有分组强制改名，也不要放开 null/截断记录的严格解码。

**验收：** 新安装默认分组、包含节点的默认分组、旧版合法备份、普通命名组均可往返；损坏记录仍在 reset 前拒绝。测试必须覆盖真实恢复校验，而不仅是 `ProxyGroup` 单独往返。

### R02 / P1：Router 的锁只保护部分写入，过期成员与跨进程整行覆盖仍存在

位置：

- `app/src/main/java/io/nekohasekai/sagernet/database/GroupManager.kt:148-241`，尤其 `151,161-165,198,209-239`。
- `app/src/main/java/io/nekohasekai/sagernet/database/RouterGroupRepository.kt:132-164,177-198`。
- `app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt:236-275`。
- `app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt:83-109`。
- `app/src/main/AndroidManifest.xml:234-256`：代理服务运行于 `:bg` 进程。

**第一条可确定的交错：**

1. 订阅同步 A 读取旧 Router 过滤条件 US、旧 source，计算出 US 成员；暂停在第 198 行之后。
2. 用户保存新条件 JP，同步 B 完成，数据库保存 JP 条件、JP 成员和对应选择。
3. A 恢复，取得锁，把步骤 1 的 US 成员写入；随后才读取 `freshRouter`。
4. JP 选择不在过期 US 成员中，于是回退到 A 计算出的旧选择。结果是名称/过滤条件为 JP，实际成员与持久化选择却是 US。

重新读取 Router 只能保护部分字段，不能使此前的成员计算自动变成最新。`ConfigBuilder` 后续使用持久化成员生成 outbound，并不会重新按过滤条件计算，错误可以进入下一次启动配置。

**第二条仍未封闭的路径：** 服务热切换与 native 回调仍执行“读整行 → `router.copy(...)` → 整行 update”，未加入共同事务；错误分支 `GroupManager.kt:170,202,254` 也有同类写法。UI 的 `routerSyncLock` 是进程内对象，不能为 `:bg` 服务进程提供跨进程互斥。因此只给 repository 增加 `synchronized` 无法关闭旧 R01。

**证据等级：** 当前源码确定的交错推演；本轮没有 Android Room 并发/多进程复现，不能表述为已在手机上抓到错误路由。

**最小修复方向：** 让“读取过滤条件/来源/节点 → 计算 → 提交成员与选择”具有一致性，或在提交时验证相关版本，过期结果重新计算；将选择、错误状态等独立更新收敛为合适的字段更新与数据库事务。网络请求不要放进长事务。覆盖所有写入入口，不能仅锁一个 UI 函数。

**验收：** 用可控暂停点覆盖上述 A/B 交错；同时覆盖 UI 与 `:bg` 选择回调、刷新失败写错误、删除 Router、更新节点期间的交错。最终成员满足最新条件，模式/来源/选择不被旧快照覆盖。

现有 `BackupIdentityAndChainTest.kt:200` 只是局部列表选择表达式测试，没有调用 repository、Room 或上述交错，因此其通过不能关闭本项。

### R03 / P2：只导出规则没有 legacy 节点身份信息，自己的规则备份也无法恢复

位置：

- `app/src/main/java/io/nekohasekai/sagernet/fmt/BackupSerializer.kt:24-58`。
- `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt:451-487`。

**触发：** 创建一条 `routerGroupId=0, outbound=101` 的普通指定节点规则；仅勾选“规则”导出，在本机节点未改变的情况下导入这个文件。

`profiles=false` 时导出不包含 `profiles`；新增的 `routerRuleRefs` 也只描述 Router 引用。导入却从 `profiles` 构建 `backupProxies`，因此集合必为空；遇到正数 outbound 时，`expectedStableId` 为 null，必然拒绝。Router 规则及 `0/-1/-2` 不属于这个问题。

这不是“拒绝身份不明的外来旧备份”本身有错，而是当前导出器没有生成当前导入器必需的信息，导致正常功能失去往返能力。

**证据等级：** 已核对完整导出字段与导入条件；未执行手机 UI 恢复。现有 `BackupIdentityAndChainTest.kt:179` 仅比较人工 Map 中的字符串，不验证实际导出/导入闭环。

**最小修复方向：** 规则单独导出时附带必要的 legacy outbound 稳定身份元数据，导入据此验证；保留“不能证明身份时拒绝”的边界，不要退回仅比较数字 ID。避免为此强制导出完整节点凭据。

**验收：** 同机正常规则单独往返成功；同数字 ID、不同身份的节点仍在写库前拒绝；Router 规则、完整备份及旧哨兵出口保持原有语义。

### R04 / P2：测速间隔可设置超过核心默认 idle_timeout，省电设置会生成不可启动配置

位置：

- `app/src/main/java/io/nekohasekai/sagernet/database/RouterGroupRepository.kt:77-79`。
- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt:140-148`。
- `app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java:4449-4459`。
- 邻接核心：`sing-box/protocol/group/urltest.go:198-210`、`sing-box/constant/timeout.go:14`。

**触发：** 将自动测速间隔设为 3600 秒以降低测试频率。App 仅校验最小 10 秒，接受 3600；生成 `interval: "3600s"`，不写 `idle_timeout`。本地核心默认空闲超时为 30 分钟，并检查 `interval > idleTimeout` 后返回错误。

**本轮验证：**

- 直接调用当前生产 `RouterGroupDraft.validate()`，3600 秒通过。
- 直接调用当前生产 `buildRouterOutbounds()`，生成的 JSON 确实缺少 `idle_timeout`。
- 当前 `app/libs/libcore.aar` 内 arm64 `libgojni.so` 包含错误字符串 `interval must be less or equal than idle_timeout`。
- `go version -m` 确认该二进制使用本机 `C:\Users\renos\Documents\Proxy\sing-box` 替换依赖，目标为 android/arm64。

**边界：** 邻接源码与 AAR 不能仅凭路径证明逐字一致；本轮没有在 Android 原生核心运行 3600 秒配置。上述为生产 App 生成结果、AAR 字符串和本地核心契约相互支持的证据，不冒充真机报错复现。

**最小修复方向：** 明确支持的间隔范围，并与 `idle_timeout` 保持一致。可以限制输入并清晰提示，或正确生成/透传匹配的超时；若目标是支持低频省电模式，优先评估后一种。不要只放宽界面，也不要意外让闲置组永远测试。

**验收：** 300、1800、1801、3600 秒分别验证 UI、生成 JSON 和打包核心加载结果；不支持的值保存前报错，支持的值可启动。长间隔下还需测闲置停止与恢复使用后的可用性。

### R05 / P2：旧 selector 开启时，独立 Router 节点被一起标记 ignore，流量统计漏计

位置：

- `app/src/main/java/io/nekohasekai/sagernet/bg/proto/TrafficLooper.kt:224-257,99-120`。
- `app/src/main/java/io/nekohasekai/sagernet/bg/proto/TrafficUpdater.kt:61-66`。
- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt:709-730`。

**触发：** 主节点所属普通组启用旧 `isSelector`；另一个 Router（例如 SG）通过规则承接流量，其节点不是当前主 selector 选中节点。

`dynamicMain=true` 后，代码对整个 `trafficMap` 的所有节点设置 `ignore=true`，包括独立 Router/指定规则加载的节点。初始化只对主选中节点调用 `selectMainLocked()` 解除 ignore。`TrafficUpdater.updateAll()` 直接跳过其余节点，停止时的最终采样也复用相同逻辑。

**影响：** 独立 Router 有真实流量时，对应节点的统计可能保持不变，合计速率/流量也不完整。漏计不能作为“Router 没活动”或“耗电低”的依据。此项不表示代理流量本身没有转发。

**本轮验证：** 以当前初始化策略构造人工主节点和被忽略的 SG 节点，调用生产 `TrafficUpdater`，为 SG 设置非零模拟计数；查询记录仅为 `[proxy, proxy]`，SG 接收统计仍为 0。没有启动完整 Android TrafficLooper 或产生真实网络流量。

**最小修复方向：** 区分主 selector 的计数归属与独立路由出口统计，避免把所有非主节点一并忽略；同时明确共享节点/链式节点如何去重，不能简单取消所有 ignore 后造成重复累计。

**验收：** 主 selector + 两个独立 Router 并行传输，逐个核对节点和总量；主节点切换前后、同一节点被多个组引用、链式节点、停止时最终持久化均不漏计/重计。

## 3. 耗电：已有效的措施与优先优化方向

**本轮没有测到真实耗电数值，不判断这份 APK 比原版多耗多少电，也不承诺任何省电百分比。以下是针对当前实现的优化候选。**

已经存在的措施应保留：

- `TrafficLoopPolicy.kt:4-15`：后台可见通知最短 5 秒，隐藏通知最短 30 秒；前台按用户间隔。不要重复开一个轮询器实现同样功能。
- `ServiceNotification.kt:62-63,215-222`：通知测速与交互状态有关；VPN 的构造默认不展示速度。不能将当前版本描述成“锁屏每秒刷新通知”。
- `BaseService.kt:68-78` → `libcore/box.go:171-181`：已有设备 Doze 的 sleep/wake 接入；邻接核心 urltest 使用 pause ticker 和闲置停止机制。不能声称完全没有休眠机制。
- `DataStore.kt:135` 及设置页：WakeLock 是显式选项，默认不是无条件获取；`VpnService.kt:55-57` 在开启该选项时才获取持续锁。
- Router 默认测试间隔是 300 秒；核心每组测试并发上限为 10，并有历史缓存和组内并发防重入。不能简单把“节点数 × 所有组数”当成每轮实际请求数。

### E01 / 优先：关闭统计且后台无人展示时，避免仍做完整节点统计循环

位置：`TrafficLooper.kt:182-184,220-275,312-326`；`TrafficUpdater.kt:33-58`；`libcore/box.go:189-207`。

在 `speedInterval>0` 下，即使 `profileTrafficStatistics=false`、应用不在前台、通知也不显示速度，代码仍建立节点统计项、注册 native stats tracker、循环调用 `updateAll()`；该开关主要影响流量广播与最终保存，没有阻止取数和对象分配。

建议先处理“统计关闭 + 无展示消费者”的分支：避免不必要的全节点读取/快照；前台重新订阅时通过已有 `requestUpdate()` 恢复。若保留总速率，评估最少必要计数，不要把关闭节点累计等同于关闭所有 UI 速度。

若需要进一步减少 native 每条连接上的 tracker 成本，需结合核心生命周期设计；当前 `setV2rayStats` 只支持第一次注册，不能随意重复调用或假定 tracker 可动态移除。

验证：不同节点规模下统计 JNI 调用数、分配量和 CPU 时间，再做真机耗电 A/B。协程每 30 秒轮询不等于每 30 秒发生一次能唤醒深度睡眠的硬件闹钟，二者必须区分。

### E02 / 优先：控制真正的网络测速成本，先补齐长间隔契约

位置：`ConfigBuilder.kt:279-308,731-753`；`RouterFilter.kt:19-21`；邻接核心 `urltest.go:236-241,316-340,343-397`。

当前会构建所有启用且可用的 Router。自动组启动会发起首轮检查，即使暂时没有规则流量；使用后还有周期检查，闲置后停止。候选节点多、自动组多、间隔短，会增加代理连接和测速请求。

建议：

1. 先修 R04，使低频测试配置在核心层真实可用。
2. 在同等可用性目标下评估 300/600/900 秒与不同候选规模；仅作为待测方案，不擅自替用户改组成员或自动/手动模式。
3. 对未使用备用组评估延迟首次测试/按需激活，但必须保持用户显式测速和打开组查看状态的语义，不能仅按“有没有规则引用”直接删除该组。

粗略预算可用“每个活跃组实际待测候选数 × 每小时执行轮数”，但必须扣除缓存命中、闲置暂停、重复目标及 Doze，最终以实际请求计数为准。不要为了省电改成很长间隔后忽略故障发现变慢的代价。

### E03 / 次优先：网络切换触发的强制测速可以合并短时间重复事件

位置：`BaseService.kt:346-386`；`libcore/box.go:259-276`；邻接核心 `urltest.go:116-118,345-365`。

有效网络发生变化时，会对全部运行中的自动组调用 `refreshURLTestFor()`，核心进入 `CheckOutbounds(true)`，跳过新鲜历史缓存。现有 `checking` 能挡住同一组重叠中的测试，但挡不住刚测完又收到切网事件的连续重复轮次。

优化候选是事件合并或短冷却窗口，并保留真实新网络的及时重测。先记录 Wi-Fi/移动网络切换和弱网抖动下的测试轮数，再判断收益；不要取消切网重测来换取表面低开销。

### E04 / 测量后决定：WakeLock、HEV 和协议保活分别做 A/B，避免全局盲调

WakeLock 开启后的持续持有是显式行为，不应未经用户选择自动关闭；应测其是否确实解决该设备的休眠断流，记录锁持有时间。启动/停止、启动失败后需验证释放行为。

`moe/matsuri/nb4a/hevtun/HevTunRuntime.kt` 的路径为 TUN → HEV → loopback SOCKS → sing-box。多一层转发不能直接证明更耗电，native 实现也可能在某些负载更有效；保持相同节点、MTU、DNS、路由、流量模型测试后再定默认。

本轮没有用户手机上的协议、保活参数或真实流量信息，因此不推荐全局修改 QUIC/TCP keepalive、DNS/TUN 或连接重置选项。日志默认配置也不等于开启 debug/trace，不能先归因于日志。

## 4. 低优先级已复现缺陷：StringSet 写字符数、读字节数

位置：`app/src/main/java/io/nekohasekai/sagernet/database/preference/KeyValuePair.kt:157-165`。

写入长度使用 `v.length`，写入内容却是 UTF-8 `v.toByteArray()`；读取和 validate 都将前者当字节数。生产类 `put(setOf("中文"))` 随后 `validate()` 失败，`stringSet` 返回 null；ASCII 对照正常。

这是已有 writer 的编码错误，与此次新增严格验证共同表现为拒绝恢复合法来源值。**本轮未找到当前持久化配置直接写入非 ASCII StringSet 的实际调用场景，故不升级成主要发布阻断问题。** 不应宣称普通中文名称设置必然损坏：普通字符串与 StringSet 不同。

后续处理时使用实际编码字节数，并评估历史错误编码数据兼容性；补中文、emoji、混合与空字符串测试，避免只测 ASCII。

## 5. 本轮验证记录

按完成前验证流程，实际执行命令并检查退出状态；验证产生的 build 输出不属于源代码修改。

| 项目 | 结果与边界 |
|---|---|
| Gradle | `:app:testOssDebugUnitTest :app:writeReviewClasspath :app:compileOssDebugAndroidTestKotlin :app:lintOssDebug :app:assembleOssDebug --offline --no-daemon --console=plain --init-script <临时脚本>`，退出 0，约 2 分钟 |
| JVM | 临时 init script 设置 test outputs 不使用 up-to-date 跳过；实际执行 19 suites / 82 tests，0 failures / 0 errors / 0 skipped |
| Android tests | Kotlin 编译检查通过；没有运行 instrumentation |
| lint | 0 errors / 30 warnings；部分 lint 报告任务为 UP-TO-DATE，不将此描述为零告警 |
| APK | debug assembly 通过；部分构建步骤复用已有输出，不是 clean 全量重建 |
| APK 元数据 | `aapt dump badging`：`com.nb4a.debug`，versionName `1.4.7`，versionCode `250`，`arm64-v8a` |
| 独立 JVM 探针 | 当前生产类的默认组 Kryo 往返、interval 校验/生成、统计 ignore 行为、Unicode StringSet；最终编译/运行退出 0 |
| AAR | 检查内嵌 arm64 ELF 的 Go build info 与错误字符串；没有在电脑执行 Android arm64 核心 |
| 设备 | `adb devices` 无设备；真机恢复、Room 并发、代理运行、实际测速与电量均未验证 |
| 源码保护 | 报告生成前 `git diff --quiet` 退出 0；只新增本文，原未跟踪 `AG8/` 保留 |

本轮 APK：`app/build/outputs/apk/oss/debug/NekoBox-1.4.7-arm64-v8a-debug.apk`。

- APK SHA-256：`7F573F693B0CE120B0CA204C67E0904FA327EE40E0BF53E5BC4FBCCF85A03776`。
- AAR SHA-256：`E828AFDF27F61291D31D291B84978C122B4181280BEDAB935510321523F82A15`。

临时证据目录：`C:\Users\renos\AppData\Local\Temp\nekobox-review-20260907`，含 `verification.log`、`probe.log`、`ReviewProbe.java`、`review.init.gradle`。这些是本机辅助材料，不会随仓库同步；关键结果已收录本文。探针不读取真实数据库、不请求测速网址、不写用户设置。

关键输出：

```text
DEFAULT_GROUP_STRICT_ROUNDTRIP id=1 ungrouped=true name=null importGuard=false
INTERVAL_3600_DRAFT_VALIDATED=true
GENERATED_URLTEST={"outbounds":["node.synthetic"],"url":"https://example.invalid/probe","interval":"3600s","tolerance":50,"type":"urltest","tag":"router.synthetic"}
SELECTOR_WITH_ROUTER_STATS queried=[proxy, proxy] routedNodeRx=0
ASCII_STRINGSET=[tag]
UNICODE_STRINGSET_VALIDATE=Setting synthetic (stringSet) entry truncated or invalid length: -1377397113, remaining: 0
UNICODE_STRINGSET_READ=null
AAR_HAS_INTERVAL_IDLE_TIMEOUT_ERROR=True
```

辅助探针初次 Java argfile 使用 Windows 反斜杠导致 classpath 解析错误，修正临时参数文件为正斜杠后运行成功；不是产品失败。aapt 最初尝试的 35.0.0 不存在，定位实际 35.0.1 后元数据检查成功。

## 6. Antigravity 修复与真机验收顺序

1. 先修 R01；它影响正常备份可恢复性。使用人工数据及独立测试库，勿拿用户现有配置做破坏性恢复实验。
2. 修 R02 的整体一致性，而非继续在局部叠加进程内锁。
3. 闭合 R03 的导出/导入契约，保留跨身份拒绝能力。
4. 修 R04，再测试省电长间隔；处理 R05 后才能用流量统计辅助判定 Router 活动状态。
5. E01/E02 优先试验，E03/E04 有设备数据后再决定。优化建议不授权重构所有协议或改变默认路由。

已有测试中部分逻辑是测试文件内复制算法或比较常量。新增回归应调用生产路径；尤其备份恢复与数据库并发需要 Android/Room 层覆盖，不能仅把本报告的条件再次写进一个纯函数测试。

最小耗电验收矩阵：

| 场景 | 保持相同的条件 | 观察指标 |
|---|---|---|
| 待机：手动组 vs 自动组 | 同一设备/网络/节点，屏幕关闭，相同同步应用 | 实际测速次数、进程 CPU、网络请求、WakeLock、进入 Doze 后行为 |
| 统计开 vs 关 | 相同流量与候选规模，分别前台和后台 | JNI 查询次数、对象分配/GC、UI 及时性、节点累计完整性 |
| 300/600/900 秒 | 相同候选组和可用节点 | 测试开销、断点故障发现时间、切网恢复速度 |
| HEV vs 现有另一 TUN 路径（若可用） | 相同 MTU、DNS、协议、路由与数据量 | 吞吐、CPU 时间、温升、电量变化、UDP/IPv6 正确性 |

每次只改一个变量，待机建议至少 60 分钟、重复多轮；记录起始温度、屏幕/充电状态、网络和其他应用流量。桌面 adb 连线/充电可能改变休眠与电量观察条件，应在方案中明确控制。Battery Historian/Perfetto 或设备可用的 batterystats 数据可辅助分析，但本轮没有取得这些证据。

交付时逐项给出修复位置、真实验证结果与未验证项。保留 Room 10、arm64、旧出口语义、AdBlock、加载节点规则、App 分流、DNS/TUN 及用户已有配置；源码审查和构建通过不能代替真机验收。提交、推送、发布按用户后续明确授权执行。
