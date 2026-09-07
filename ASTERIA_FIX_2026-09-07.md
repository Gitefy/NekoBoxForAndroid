# Asteria 1.0 定向修复记录（2026-09-07）

## 修复
- App：`VpnService.startVpn` 原先丢弃核心传来的 TUN 参数，配置 MTU=1420 时 Android VPN 仍使用全局默认 9000。现读取 Go `tun.Options` 的 `MTU` 字段并传入 VPN Builder；普通配置继续使用核心生成的 MTU，字段缺失时保留全局默认。
- 配置：`geosite`/`geoip` 已被当前核心移除，替换为本项目已有的本地 `rule_set` 格式，使用 App 的同一份地理数据库；`geoip:private` 由前面的 `ip_is_private` 规则承接。
- 配置：组播拦截由来源与目标同时命中改为逻辑 OR，保留两个原有网段条件。
- 配置：旧 Clash 缓存字段迁移到 `experimental.cache_file`，保留缓存路径及控制器设置，使选择持久化使用当前核心的配置入口。

- App：lint 复现两处 MissingPermission 错误；通知更新调用捕获 SecurityException，避免通知权限拒绝/撤销中断 VPN 通知刷新或订阅任务。未压制 lint 检查。

## 文件与保护
- 原件 `asteria_v1.0.json`，19746 字节，SHA256 `1880064e010fef4628369ebaac2be63b13fd90096273c8ca8d4296d0ec4841d1`。
- 新件 `asteria_v1.0.1.json`，20500 字节，SHA256 `83797318928d65b3eb1726c50c441ba92355eb72f2e794f287f7bd71135289f2`。
- 修改前副本：`scratch/codex-20260907-172930/`，含原配置、VpnService.kt、ServiceNotification.kt、SubscriptionUpdater.kt。
- 原件哈希与副本一致；节点、订阅信息、分组、DNS/TUN 参数、规则数量、非目标规则均保持不变；outbound 引用检查通过。未提交、推送或覆盖既有版本。

## 验证
- MTU 回归：修复前 2 项中 1 项失败，修复后通过。
- JVM：86 项，0 失败。
- Debug APK 已构建：`app/build/outputs/apk/oss/debug/Asteria-1.0-arm64-v8a-debug.apk`。
- 最终离线测试/构建成功，日志：`scratch/codex-final.log`；修复前 lint 日志：`scratch/codex-lint.log`。
- 当前核心原生选项解码：新版退出 0；原版因旧地理字段检查退出 1。日志：`scratch/codex-native.log`、`scratch/codex-native-before.log`。仅验证解码及已移除字段，不等于完整服务加载或地理数据库可用性。
- 未进行 Android 安装、导入、启动、实际 MTU、分流、测速及重启持久化验收。此配置是完整 sing-box 自定义配置，不能在“恢复备份”入口使用；地理规则依赖 App 已有的 GeoIP/Geosite 资源。

本次仅针对发现并能证实的问题修复，不代表对整个 App 做了全面审计。



最终验证：离线 JVM 测试、Debug APK 构建、lint 联合命令退出 0（BUILD SUCCESSFUL）。lint 原有非阻断警告保留，未为消除警告扩大修改。
