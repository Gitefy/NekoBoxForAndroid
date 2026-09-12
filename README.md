# NekoBox for Android

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Releases](https://img.shields.io/github/v/release/MatsuriDayo/NekoBoxForAndroid)](https://github.com/MatsuriDayo/NekoBoxForAndroid/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Contributors](https://img.shields.io/github/contributors/starifly/NekoBoxForAndroid)](https://github.com/starifly/NekoBoxForAndroid/graphs/contributors)

## 免责声明

> 免责声明：本项目仅用于技术研究与代码学习之目的，不提供任何形式的网络代理服务。请勿将本项目用于违反当地法律法规的任何活动。请勿在生产环境中使用本项目，使用者应自行承担使用本项目可能带来的全部风险。若您下载或引用本项目，请在 24 小时内自行删除相关内容，并避免长期存储、分享或传播本项目的任何部分。**作者保留随时修改、更新或移除本项目及其内容的权利，恕不另行通知。**
> 
> Disclaimer: This project is intended solely for technical research and code learning purposes and does not provide any form of network proxy service. Please do not use this project for any activities that violate local laws and regulations. Do not use this project in production environments. Users are fully responsible for any risks that may arise from using this project. If you download or reference this project, please delete all related content within 24 hours and avoid long-term storage, distribution, or dissemination of any part of this project. **The author reserves the right to modify, update, or remove any part of this project or its contents at any time without prior notice.**
# EgoX

> A personal-use engineering fork of NekoBox for Android, focused on policy-based routing, runtime observability, state consistency, and long-running stability.

EgoX 是基于 [NekoBox for Android](https://github.com/starifly/NekoBoxForAndroid) 持续演进的个人使用向分支。

本项目不以“增加尽可能多的功能”为主要目标，而更关注：

- 策略组与复杂路由管理
- VPN runtime 的状态一致性
- 实时连接可观测性
- 多进程与并发场景下的可靠性
- Backup / Restore 的可预测性
- Android 长时间运行稳定性
- 降低无效 CPU、IPC、数据库与 UI 工作

核心开发优先级：

```text
Correctness
    ↓
Stability
    ↓
Observability
    ↓
Performance
```

项目目标可以概括为：

> 装着就能用，不需要频繁确认它是否处于正确状态。

---

## Project Status

当前主线：

```text
EgoX 4.2.0
main
```

EgoX 已经相对 fork 基线进行了较大规模修改。

与上游相比，以下行为已经存在明显差异：

- routing model
- VPN runtime control
- preference/database synchronization
- backup/restore coordination
- process communication
- UI behavior
- connection diagnostics

因此，请不要假设 EgoX 与 NekoBox for Android 在所有内部行为上完全一致。

---

## Why EgoX?

原始 NekoBox for Android 已经提供了完善的 Android 代理客户端基础。

EgoX 的主要工作不是重新实现代理协议，而是在此基础上继续解决几个长期使用中更容易遇到的问题：

### 1. 路由规则不应该长期绑定某一个具体节点

节点可能因为订阅更新、延迟变化、临时故障或服务商调整而发生变化。

因此 EgoX 增加 Router Groups / 策略组，让规则更多指向一个稳定的逻辑目标。

### 2. UI 显示的状态应该尽量等于 Core 实际执行的状态

```text
用户点击启动
↓
数据库写入
↓
后台进程收到命令
↓
Core 真正启动
↓
UI 得到明确结果
```

而不是简单地：

```text
发送命令
↓
假设已经成功
```

### 3. 出现路由问题时，应该能够看到流量到底去了哪里

因此 EgoX 增加 Requests 实时连接观察器。

### 4. 长时间运行比短时间 benchmark 更重要

移动端代理应用通常会运行数小时甚至数天，因此 EgoX 更关注：

- race condition
- stale state
- main-thread database access
- excessive refresh
- unnecessary GC
- unnecessary connection reset
- lifecycle correctness

---

# Major Differences from Upstream

## 1. Router Groups / 策略组

EgoX 增加了独立的 Router Group 系统。

节点可以先组织为逻辑策略组，再让路由规则指向策略组。

例如：

```text
US-Core
├── US-LA-01
├── US-LA-02
└── US-SJC-01

US-Media
├── US-LA-03
└── US-Seattle-01

SG-TG
├── SG-01
└── SG-02
```

运行时可以表现为：

```text
US-Media → US-LA-03
```

其中：

- `US-Media` 是逻辑 Router Group
- `US-LA-03` 是实际最终使用的物理节点

支持：

- 手动选择节点
- URLTest 自动选择
- 独立成员排序
- Router Group 作为路由目标
- Router Group 稳定标识
- Router member 独立顺序
- Router Group 管理界面
- Router Group restore / cleanup
- runtime selection
- group deletion cleanup

这样可以避免大量规则直接绑定到某一个具体节点。

---

## 2. sing-box / Go TUN Integration

EgoX 对 sing-box 集成和 Android TUN 路径进行了较大调整。

主要方向包括：

- newer sing-box integration
- Go TUN path
- Router Group runtime integration
- outbound selection coordination
- URLTest integration
- network change handling
- connection reset throttling
- protocol compatibility adjustments
- Android foreground/background runtime handling

部分旧兼容路径已经被重新整理或移除。

这里的重点不是单纯追求极限吞吐，而是让 Android 长时间运行更加可预测。

---

## 3. Runtime State Consistency

EgoX 对设置、数据库和 VPN Core 之间的状态同步做了较大规模重构。

当前整体设计逐步收敛为：

```text
User Intent
    ↓
Desired State
    ↓
Persisted State
    ↓
Config Snapshot
    ↓
Runtime Apply
    ↓
Applied State
    ↓
Network
```

### Preference / Database

主要改动包括：

- asynchronous store readiness
- in-memory preference cache
- serialized write queue
- stale ACK protection
- stale snapshot protection
- generation fencing
- durability barrier
- failure tracking
- restore/reset fencing
- defensive copy
- duplicate ACK idempotency

同时尽量避免 UI 可达路径同步访问 SQLite / Room。

---

## 4. Cross-process Apply Protocol

Android 主进程与 VPN `:bg` 进程之间增加了显式 Apply 协议。

请求包含：

```text
requestId
commandGeneration
START / RELOAD / STOP
target
```

后台进程执行完成后返回明确结果。

主要用于处理：

- stale command
- duplicate command
- START / STOP race
- reload acknowledgement
- invalid explicit target
- cleanup completion
- process timing differences

EgoX 尽量区分：

```text
Command Sent
```

和：

```text
Command Actually Applied
```

这两个状态。

---

## 5. Config Snapshot

配置构建逐渐从“构建过程中不断读取数据库”转向：

```text
Capture State
    ↓
Immutable Snapshot
    ↓
Compile Config
```

这样可以降低配置构建过程中状态变化导致的不一致风险。

---

## 6. Backup / Restore Reliability

Backup / Restore 流程增加了独立协调机制。

主要包括：

- restore coordination
- boot recovery
- user restore / boot recovery distinction
- restore locking
- restore/start exclusion
- crash recovery
- restore fencing
- post-restore cleanup
- Router state cleanup

目的是减少 Restore、VPN Start 与后台进程 bootstrap 同时发生时产生的不确定状态。

---

## 7. Requests — Realtime Connection Inspector

EgoX 新增 `请求 / Requests` 页面。

它用于观察 sing-box 当前连接，而不是抓取网络 payload。

可以查看：

- App
- Domain
- Destination IP
- Port
- TCP / UDP
- Logical Router Group
- Actual outbound node
- Upload
- Download
- Active / Closed state

例如：

```text
YouTube
googlevideo.com:443
TCP
US-Media → US-LA-03
```

这可以直接回答一个实际问题：

> 这个域名现在到底走了哪个策略组，又最终用了哪个节点？

### Requests History

Requests 历史：

- memory only
- bounded records
- bounded lifetime
- runtime restart clears history
- not persisted to Room
- not included in backup

Requests 不是：

- packet capture
- MITM
- HTTPS decryption
- payload analyzer

---

## 8. Create Routing Rules from Requests

可以直接从某条连接生成现有 `RuleEntity` 格式的规则。

支持匹配：

- Exact Domain
- Domain Suffix
- Current App
- Destination IP

支持目标：

- DIRECT
- REJECT
- Default Proxy
- Existing Router Group

规则支持：

```text
仅保存
```

和：

```text
保存并应用
```

因此可以：

```text
保存规则 A
保存规则 B
保存规则 C
↓
最后统一应用
```

避免每添加一条规则都立即 reload 并清空 Requests runtime history。

---

## 9. LAN Mixed Proxy

EgoX 保留并增强了本地 Mixed Proxy 的 LAN 使用能力。

路径：

```text
设置
→ 入站设置
→ 本地代理
```

支持：

- Allow LAN
- HTTP / SOCKS mixed inbound
- custom port
- username
- password
- optional LAN authentication

### LAN Authentication

默认建议开启认证。

在可信家庭局域网中，也可以关闭：

```text
Require authentication for LAN proxy
```

此时其他设备只需要：

```text
PHONE_IP:PORT
```

即可使用 EgoX 作为显式 HTTP / SOCKS 代理入口。

> 无认证 LAN Proxy 只建议用于可信家庭网络。不要在公共 Wi-Fi 或不受信任网络开放。

注意：EgoX LAN Proxy 是显式代理，不是完整透明旁路由。客户端必须支持并实际使用 HTTP / SOCKS Proxy。

---

## 10. Logging & Diagnostics

EgoX 对日志生命周期进行了调整。

主要包括：

- logging policy independent from early store loading
- main / background process synchronization
- logging enable state recovery
- Disabled / Empty state distinction
- lower noise for successful routine restore operations
- preserve Warning / Error diagnostics

解决了部分情况下应用运行较长时间后日志页面为空的问题。

---

## 11. Theme / UI

EgoX 对部分 UI 和主题行为进行了调整。

包括：

- EgoX branding
- launcher icon
- Dark theme fixes
- Black theme contrast fixes
- cold-start night mode handling
- lifecycle-safe group UI updates
- Router Group management UX
- Requests page
- Local Proxy settings

部分术语也进行了调整，例如：

```text
代理组 → 策略组
```

---

## 12. Performance & Long-running Stability

EgoX 的性能优化重点不是单次 benchmark，而是降低长期后台运行成本。

主要包括：

- elimination of main-thread Room queries
- reduced repeated database access
- request history indexing improvements
- UI list diff updates
- PackageManager label cache
- observer coalescing
- publisher coalescing
- connection reset debounce
- package scan debounce
- native GC control
- database WAL
- TrafficLooper throttling
- bounded TUN worker count
- reduced unnecessary UI refresh
- reduced unnecessary IPC
- reduced unnecessary reconnect/reload

目标是降低：

```text
CPU wakeups
GC pressure
database contention
UI jank
IPC churn
connection churn
```

---

# Reliability Work

EgoX 内部增加了大量针对并发和生命周期问题的回归测试。

覆盖内容包括：

- preference linearizability
- write queue durability
- stale snapshot handling
- async store readiness
- Apply handshake
- START / STOP race
- restore race
- config snapshot
- cross-process behavior
- Requests observer
- Requests store
- routing rule generation
- logging policy
- theme cold start
- Router UI lifecycle
- connection reset debounce

很多内部改动不是单独修改实现，而是同时加入针对具体故障场景的 regression test。

---

# Supported Proxy Protocols

EgoX 继承 NekoBox for Android 的协议基础。

包括但不限于：

- SOCKS (4/4a/5)
- HTTP(S)
- SSH
- Shadowsocks
- VMess
- Trojan
- VLESS
- AnyTLS / AnyReality
- Snell
- ShadowTLS
- TUIC
- Juicity
- Hysteria 1 / 2
- WireGuard
- Trojan-Go
- NaïveProxy
- Mieru

具体支持能力取决于当前集成的 sing-box 与插件版本。

---

# Supported Subscription Formats

继承上游订阅解析能力，包括常见格式，例如：

- Shadowsocks
- ClashMeta
- v2rayN
- sing-box outbound

主要解析 outbound / 节点信息。

订阅中的其他配置并不保证完整映射到 EgoX。

---

# Build

推荐使用正式主线：

```bash
git clone https://github.com/Gitefy/NekoBoxForAndroid.git
cd NekoBoxForAndroid
```

Debug：

```bash
./gradlew :app:assembleOssDebug
```

Release：

```bash
./gradlew :app:assembleOssRelease
```

部分本地构建依赖可能需要：

- Android SDK
- Android NDK
- Go
- gomobile
- libcore build dependencies
- sing-box source/dependencies

Release signing 需要自行配置签名环境。

不要使用未知来源的签名文件。

---

# Release Signing

正式 APK 应使用稳定且受控的 Android signing key。

如果新 APK 与手机当前已安装版本使用不同签名，Android 会拒绝原地覆盖升级。

不要把 unsigned APK 当作正式发行 APK。

---

# Security Notes

## LAN Proxy

如果开启：

```text
Allow LAN
```

EgoX 可能监听局域网可访问地址。

请根据实际环境决定是否开启认证。

## Requests

Requests 仅记录连接 metadata，不记录 payload。

## Backup

备份文件可能包含：

- proxy configuration
- subscription configuration
- routing rules
- application settings

请妥善保管。

---

# Upstream

EgoX 建立在以下项目基础上：

## Direct Upstream

[NekoBoxForAndroid — starifly](https://github.com/starifly/NekoBoxForAndroid)

## Original Project

[NekoBoxForAndroid — MatsuriDayo](https://github.com/MatsuriDayo/NekoBoxForAndroid)

EgoX 保留并感谢上游项目及其所有贡献者的工作。

---

# Credits

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

Android GUI heritage:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)
- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [starifly/NekoBoxForAndroid](https://github.com/starifly/NekoBoxForAndroid)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)

感谢所有原项目、fork、依赖库及相关开源贡献者。

---

# License

This project follows the license included in this repository.

The code is distributed under the GNU General Public License, version 3 or later, where applicable.

See `LICENSE` for details.

---

# Disclaimer

本项目仅用于技术研究、个人使用和代码学习。

本项目本身不提供任何代理服务器或网络服务。

使用者应自行确认其使用方式符合所在国家或地区的法律法规、网络政策以及服务提供商条款。

作者及贡献者不对以下情况承担责任：

- 错误配置
- 数据丢失
- 网络中断
- 隐私泄露
- 第三方服务封禁
- 违反当地法律法规造成的后果
- 自行修改、编译或重新分发产生的问题

本项目属于高度修改的 fork，不保证与上游 NekoBox for Android 的行为完全一致。

使用前请自行评估风险。

---

# Development Philosophy

EgoX 不追求无限增加功能。

如果一个改动不能明显改善以下至少一项：

```text
Correctness
Stability
Observability
Performance
```

通常不会被优先考虑。

对于性能优化，也优先避免 speculative optimization，而倾向于：

```text
measure
↓
identify bottleneck
↓
make focused change
↓
verify
```

项目更重视可预测行为，而不是复杂度本身。

---

# Current Direction

后续开发优先处理：

- reproducible real-device defects
- routing correctness
- runtime state consistency
- Android lifecycle problems
- connection observability
- long-running stability

除非有明确收益，否则不会为了代码“看起来更现代”而进行大规模重构。

请到[这里](https://matsuridayo.github.io/nb4a-plugin/)下载插件以获得完整的代理支持.

Please visit [here](https://matsuridayo.github.io/nb4a-plugin/) to download plugins for full proxy
supports.

## 支持的订阅格式 / Supported Subscription Format

* 一些广泛使用的格式 (如 Shadowsocks, ClashMeta 和 v2rayN)
* sing-box 出站

仅支持解析出站，即节点。分流规则等信息会被忽略。

* Some widely used formats (like Shadowsocks, ClashMeta and v2rayN)
* sing-box outbound

Only resolving outbound, i.e. nodes, is supported. Information such as diversion rules are ignored.

## 捐助 / Donate

<details>

如果这个项目对您有帮助, 可以通过捐赠的方式帮助我们维持这个项目.

捐赠满等额 50 USD 可以在「[捐赠榜](https://mtrdnt.pages.dev/donation_list)」显示头像, 如果您未被添加到这里,
欢迎联系我们补充.

Donations of 50 USD or more can display your avatar on
the [Donation List](https://mtrdnt.pages.dev/donation_list). If you are not added here, please
contact us to add it.

USDT TRC20

`TFVcx36pVLuCWLbWiMdT5KP2PsfQ2SJVEZ`

</details>

## Credits

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)

## Contributors

![Contributors](https://contrib.rocks/image?repo=starifly/NekoBoxForAndroid)
