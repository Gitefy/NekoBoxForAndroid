# sing-box 1.15 / Go TUN stack 升级说明

## 目标

将 NekoBox for Android 的 sing-box 核心升级到包含新 **Go TUN stack** 的版本，
以改善 Android VPN 的 TUN 性能、CPU/内存效率和长期运行功耗。

本次升级**不删除**原有 gVisor / System / Mixed 回退能力；Go stack 仅作为新安装默认项。

## 当前 pin（v1.15.0-alpha.12）

| 组件 | 版本 / Commit |
|---|---|
| sing-box | `25b0d5ea3e466d246c708ac0f20d0448110e566c`（官方 tag `v1.15.0-alpha.12`） |
| sing | `v0.9.7-0.20260929150544-6f21f2425a95` |
| sing-tun | `v0.9.7-0.20261009022811-5c2edb183cc9` |
| gvisor | `v0.0.0-20260727.0-sing-box-mod.1`（保留显式固定和旧栈回退） |
| Go / NDK | `1.25.5` / `25.0.8775105` |

升级适配集中在 libcore：生命周期 `Start(stage, scope)`、Juicity scope 清理、策略组选中项 `Selected(network)`、GeoIP `Prefixable`。Android/JNI 对外方法与既有 P3、URLT-F1/F2 语义保持兼容；新增订阅 HTTP 响应大小限制方法。

### 可重现原生依赖

- 核心与 libneko 使用本项目忽略目录 `.deps/`，不切换或覆盖 `../sing-box`、`../libneko` 的用户工作区。
- `buildScript/lib/core/get_source.sh` 固定 revision，并幂等应用 `patches/egox-core.patch` 和 `egox-core-tests.patch`。保留旧缓存缺失游标恢复、FakeIP 嗅探目的地修正及原有自定义 sniff 字段；上游已经修复的元数据读取删除问题不重复补丁。
- `scripts/native-provenance.py` 在构建前检查依赖 revision 和补丁一致性，构建后记录输入摘要与 AAR SHA256。Gradle `verifyLibcore` 拒绝来源缺失、过期或被替换的 AAR。输入摘要规范化 CRLF/LF；源码、pin、补丁或构建脚本变化需要重新构建。
- CI、Build、Release 固定同一 Go 版本，原生缓存同时保存 AAR 与来源记录，并纳入补丁和校验脚本。
- 原生补丁的干净重放、FakeIP IPv4-only/双栈旧缓存恢复，以及普通/完整 Release 标签 libcore 测试均在 Windows 主机验证。Android 真机导入、VPN、切网、UDP/QUIC 和休眠恢复须单独验收。

### 2026-10-13 主机验收

- libcore 普通和完整 Release build tags 全量测试通过；HTTP 超限后重读、UDP-only URLTest 选中项回退均有先失败后通过的回归。
- Debug / Release JVM 各 440 项测试通过，失败、错误、跳过均为 0；新导入安全测试 8 项通过，原生来源/获取测试 7 项通过。
- Debug lint 0 错误 / 61 警告，Release lint 0 错误 / 44 警告；AndroidTest Kotlin 编译通过。lint 使用离线模式，未查询 Maven 最新版本。
- Debug / Release APK 构建通过；包内 libgojni.so 与本次 AAR 字节一致，包含 alpha.12、固定 revision 和 Go 1.25.5；ELF LOAD 和 APK zipalign 16 KB 校验通过。
- 正式 APK 保持 `com.egox`、4.3.2 / 305，签名证书与原 4.3.2 一致。交付文件：`app/build/outputs/verified/EgoX-4.3.2-sing-box-alpha.12-arm64-v8a.apk`。
- APK SHA256：`d2a8cadfe58d279f554718f40dc03df9d5a96db52b3b8fd2d32678dea18d0c4e`；详细记录：`app/build/outputs/verified/verification.json`。
- 未连接 Android 设备，未做真机验收；Windows 缺少宿主 Cgo 编译器，未运行 `-race`，已将 FakeIP 竞态回归加入 Linux CI。以上为提交前本地主机验收，远端 CI 结果单独核对。

## 2026-10-13 通用可靠性迁移

参考 `Gitefy/vela` 的 `EgoX` 分支固定快照 `de4d8cc17a57a44a24a9bb08c756d99a604ba464`，
逐项迁移到本仓库；参考仓库和原有 sibling core 工作区均不修改。
未引入 Vela 协议、Rust 客户端、协议 Bean 或导入入口。包名、版本、alpha.12 pin、
Router 不可用时的既有回退行为和备份 Parcel 格式保留。

### 迁移内容

- 隐藏节点分享链接与插件命令中的敏感参数；日志页面后台线程读取、分类并保留原始诊断。
- 清理缓存保留 FakeIP 数据库、日志及运行中的插件配置；独立测速使用临时缓存，
  TCP ping 工作任务上限 16，取消与异常路径释放资源。
- 测速只写入 `status/ping/error`，不覆盖节点编辑、订阅刷新或流量计数。
- 恢复采用持久化准备阶段、原子日志替换和结果明确的双数据库回滚；
  回滚失败保留快照，恢复失败阻止启动。Windows JVM 跳过不支持的目录 fsync，
  Android/Linux 保留目录持久化；失败不降级成非原子覆盖。
- 订阅提交与恢复共用文件锁和恢复代次；数据库事务内读取最新节点和分组，
  拒绝恢复前发起或设置已经变化的下载结果；后台更新增加联网约束和有界重试。
- socket 保护错误传播，并绑定捕获的底层网络；网络变更同步到 native interface monitor，
  取消待取网络请求，避免本地 DNS 回退到 VPN 自身。
- START 在连接过程中等待旧实例清理；停止尝试释放所有资源；
  请求和流量发布使用独立运行代次，拒绝已替换实例的迟到数据。
- 共享 UID 保留全部包名，Requests 显示应用、包名及 UID，创建应用规则时保持完整集合。
- 本地 Clash API 使用事务生成的随机密钥，内置面板在本地 origin 初始化认证；
  密钥不进入 URL、外部页面或配置导出；释放 WebView 与列表视图资源。
- alpha.12 叠加失败 URLTest 节点重选、完整 UDP 嗅探报文和流量关闭元数据副本补丁。
  URLTest 重选和元数据补丁用原始 alpha.12 overlay 先复现失败，再验证通过。

### 构建和验收

- 固定 Matsuri gomobile 源码 `17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f`；
  绑定工具在 `.deps/tool-bin` 构建，保持 Go 1.25.5，不安装浮动 `gobind@latest`。
  Android arm64 绑定不使用 OpenAL，只准备所需工作目录。
- 原生来源 diff 固定完整哈希、上下文及算法；避免不同克隆对象数量和 Git 偏好导致误报。
  在与 CI 相同的新克隆上复现了 7/8 位缩写差异，修复后与原工作区补丁表示完全一致。
- 普通和完整 Release 标签 Go 测试、核心补丁回归、干净 alpha.12 补丁重放及原生来源验证通过。
- Debug / Release JVM 各 504 项测试通过（包含面板认证与导出隔离）；原生来源/获取测试 8 项通过。
- Debug / Release lint 均为 0 错误，分别保留 61 / 44 项已有警告；两种 APK 构建通过。
- AAR 与两种 APK 的 native 字节相同，alpha.12 / revision / Go 版本正确；ELF 与 APK 16 KB 对齐通过，
  Release v2 签名证书与原版本一致。新交付文件：
  `app/build/outputs/verified/EgoX-4.3.2-alpha.12-reference-hardening-arm64-v8a.apk`，
  SHA256 `6b7febc7be2a14419b245181506fd3d89ab7484f25bccaa0c2b267e2d79f1b82`。
- AndroidTest Kotlin 编译通过，新增 Room 延迟测速写回用例尚未在设备执行。
- 无连接设备；VPN、切网、UDP/QUIC、休眠及数据库并发设备验收仍待完成。
  Windows 未执行 Go `-race`；Linux CI 执行 libcore 与核心补丁的竞态回归。

## 先前 pin（v1.15.0-alpha.6）

| 组件 | 版本 / Commit |
|---|---|
| sing-box | `8330820fa62505f9574e4c35cd969d9af6eb7769`（官方 tag `v1.15.0-alpha.6`） |
| sing | `v0.9.5-0.20260917164122-8fc5da509c10` |
| sing-tun | `v0.9.4-0.20260917142847-fbc0c3dff312` |
| gvisor | `v0.0.0-20260727.0-sing-box-mod.1`（显式固定，见下方说明） |
| Go | `1.25.5` |
| Android NDK | `25.0.8775105` |

相对上一 pin 的产品选择：`TunImplementation.GO` **省略** `stack`，让核心使用 1.15 新默认 TCP/IP 栈；gVisor / System / Mixed 仍写出旧值。`stack` 将在 sing-box 1.17 删除。不跟踪浮动 `testing` HEAD。

## 先前 pin（05f7bc1）

| 组件 | 版本 / Commit |
|---|---|
| sing-box | `05f7bc1aac0cc7297259249142481f8a6c1a0d73` (官方 SagerNet/sing-box，tree `0260b326f9919fc8a6a52c43f7f545a677f7d7b2`，等价原 starifly commit) |
| sing | `v0.9.4-0.20260908053243-3e69d072c058` |
| sing-tun | `v0.9.1-0.20260908051822-65ba32b917d5` |
| gvisor | `v0.0.0-20260727.0-sing-box-mod.1`（显式固定，见下方说明） |
| Go | `1.25.5` |
| Android NDK | `25.0.8775105` |

> 该 commit pin 用于接入 Go TUN。之后改为官方 `v1.15.0-alpha.6` tag，见上一节。

## 核心变更

1. **TUN stack 默认省略（1.15 新 TCP/IP 栈）**
   - `Constants.kt` 增加 `TunImplementation.GO = 3`。
   - `DataStore.kt` 默认 `tunImplementation → GO`。
   - `ConfigBuilder` 对 `GO` **不写** `stack`；对 gVisor / System / Mixed 仍写 `"gvisor"` / `"system"` / `"mixed"`。
   - `arrays.xml` 增加 `tun_implementation_go` 文案与 `int_array_4` 枚举。

2. **TUN schema 迁移到 sing-box 1.15**
   - `Inbound_TunOptions` 使用新的 `address` 字段替代已移除的 `inet4_address` / `inet6_address`。
   - 移除 `endpoint_independent_nat` 等已废弃字段；保留等价的 `udp_mapping` / `udp_filtering` 默认行为。
   - 镜像 sing-box 1.15 新增字段：`interface_name`、`dns_mode`、`dns_address`、`udp_mapping` 等。

3. **DNS schema 迁移到 sing-box 1.15**
   - sing-box 1.14 起移除 DNS 服务器旧 `address` 字段。
   - `ConfigBuilder` 新增 `buildDnsServerOptions()`，将用户设置中的地址字符串转换为新的 typed DNS server 格式：
     - `local` → `{"type":"local"}`
     - `fakeip` → `{"type":"fakeip", "inet4_range":"...", "inet6_range":"..."}`
     - `rcode://success` → `{"type":"rcode", "rcode":"success"}`
     - `https://dns.google/dns-query` → `{"type":"https", "server":"dns.google", "path":"/dns-query", "tls":{...}}`
     - `tls://...`、`tcp://...`、`udp://...` 等分别映射到对应类型。
   - 移除已废弃的 `dns.fakeip` 配置节，FakeIP 范围现在直接写在 fakeip DNS server 上。

4. **WireGuard 迁移到 endpoint**
   - sing-box 1.15 不再将 WireGuard 注册为 outbound，仅作为 endpoint。
   - `ConfigBuilder` 现在把 WireGuard 节点写入 `endpoints` 而非 `outbounds`。
   - 弃用旧字段 `server` / `server_port` / `local_address` / `peer_public_key`，改用新的 `peers[]` 结构。
   - `urlTestDetour()` 增加 endpoint 回退，确保 URL Test 仍然经过 WireGuard 节点。

5. **恢复被升级破坏的平台功能**
   - **URL Test**：改用上游 `common/urltest.URLTest`，请求通过目标 outbound 拨号，不再使用 `http.DefaultClient` 直连。
   - **流量统计**：使用 `experimental/v2rayapi.StatsService` 替代已移除的 `boxapi`。
   - **ResetAllConnections**：调用 `ConnectionManager.CloseAll()` 真正关闭连接，而非仅打印日志。

6. **协议兼容性处理**
   - **SSR**：sing-box 1.15 上游已彻底移除 `protocol/shadowsocksr`。当前分支保留 UI/数据库代码，
     但使用 SSR 的节点在启动时会得到明确的 "unknown outbound type: shadowsocksr" 错误。
     后续恢复方案：像 `protocol/juicity` 一样在 `libcore/` 内引入一个兼容 sing-box 1.15 的 SSR 实现。
   - **Snell**：sing-box 1.15 出站仅支持版本 `4` / `6`；`quic_proxy_mode` 是 reF1nd fork 扩展，上游已不支持。
     `SnellBuildConfig` 不再发出 `quic_proxy_mode`，避免 strict parsing 报错。
   - **VMess / Juicity**：使用上游 `sing-vmess` v0.2.8 与项目内置 juicity 实现；编译与 schema 校验通过。

7. **清理仓库污染**
   - 删除并忽略 `scratch/`，避免开发 checkpoint / 真实配置 dump 进入仓库。
   - 回退与本次升级无关的优化（BootReceiver、Room cache、Gson 单例、RouterGroupListFragment 等）到基线。

## 构建方式

```bash
# 环境：JDK 17, Go 1.25.5, Android SDK (platform 36, build-tools 36.0.0), NDK 25.0.8775105
./run lib core
./gradlew app:assembleOssDebug
```

## 已知临时兼容项

- `libcore/go.mod` 中显式 `require github.com/sagernet/gvisor v0.0.0-20260727.0-sing-box-mod.1`。
  由于该 pseudo-version 字符串在 semver 预发布比较中排序低于旧版 `20250325...`，
  不加显式固定时 MVS 会错误地选择旧版 gvisor，导致 `WritePacketDirect` 编译失败。
- `libcore/` 中新增 `config_parse_test.go` 作为最低限度自动验证；
  Windows 宿主上 WireGuard endpoint 相关 case 跳过（需要 `with_gvisor` 标签），在 Android 构建中会自动运行。

## gVisor fallback

保留 `with_gvisor` build tag；`libcore/build.sh` 仍然编译 gVisor 栈。
用户可在设置中将 `TUN implementation` 从 `Go` 切换为 `gVisor` / `System` / `Mixed`。
这三项依赖已废弃的 `stack` 字段，将在 sing-box 1.17 失效。

## URLT-F1 + URLT-F2 FREEZE

```text
Status:
COMPLETE — FROZEN

F1:
Fast RTT manual connectivity test

F2:
Shared temporary sing-box Batch Box for multi-profile URL Test

Review:
URLT-F1 REVIEW PASS
URLT-F2 REVIEW PASS

Compatibility:
GOOD

Hardening:
COMPLETE
```

### 永久不变量 (Invariants)
- **INV-URLT-01 Target correctness**: 每个 profile 必须测试自己的 outbound/endpoint；禁止静默回退到 direct / 默认 outbound / 第一个 outbound / 其他 profile 目标。
- **INV-URLT-02 Batch isolation**: 正常可批处理节点统一走 `N profiles → 1 Batch Box`；禁止无证据回退为 `N profiles → N BoxInstances`。
- **INV-URLT-03 Fast RTT**: 默认手动 Connectivity Test 保留 F1 Fast RTT（warm-up + same-node connection reuse），不得无理由恢复为完整冷启动延迟语义。
- **INV-URLT-04 HTTP transport isolation**: 不同 profile 严禁共享 Fast RTT HTTP transport / connection pool，仅允许同一 profile 的 warm-up 与 measured request 复用连接。
- **INV-URLT-05 Namespace isolation**: Batch 内所有节点私有 tag 必须 namespaced，`customOutboundJson` 严禁覆盖 Batch identity tag。
- **INV-URLT-06 Main VPN isolation**: Batch Test Box 不得修改/关闭 main Box、改动 selected outbound、重置主连接或污染主状态/历史。
- **INV-URLT-07 Failure isolation**: 单个节点失败不得导致整个 Batch 失败；Whole Batch 无法启动时保留安全降级 fallback。
- **INV-URLT-08 Cancellation**: 用户取消时立即 cancel active probes、stop queued probes、close Batch Box 并终止 fallback，不得等待全部超时。
- **INV-URLT-09 sing-box compatibility boundary**: 未来 sing-box 升级时优先修改 compatibility/adapter 层，禁止在 Android/UI 各处散落新增对 sing-box Go concrete internals 的直接依赖。

