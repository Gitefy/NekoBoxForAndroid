# NekoBoxForAndroid (`router-groups` 分支) 完整接力开发说明文档 (GPT-6 Handoff)

## 一、项目概况与版本基准

| 配置项 | 当前状态 / 规范 |
| :--- | :--- |
| **代码仓库** | `Gitefy/NekoBoxForAndroid` |
| **当前工作分支** | `router-groups`（已与远程 `origin/router-groups` 完全同步） |
| **本地 Git 提交身份** | `Gitefy <lubbers_0betting@icloud.com>` |
| **应用版本** | `1.4.6`（`VERSION_CODE: 245`，定义于 `nb4a.properties`） |
| **Room 数据库版本** | **Version 10**（`SagerDatabase.kt`，严禁随意破坏已有迁移链） |
| **支持设备架构** | **仅保留 `arm64-v8a`**（已彻底裁剪 `armeabi-v7a`、`x86`、`x86_64`） |
| **离线测试命令** | `.\gradlew.bat testOssDebugUnitTest --offline --console=plain` |
| **APK 组装命令** | `.\gradlew.bat assembleOssDebug --offline --console=plain` |
| **输出 APK 路径** | `app/build/outputs/apk/oss/debug/NekoBox-1.4.6-arm64-v8a-debug.apk` (约 25.2 MB) |

---

## 二、最近关键 Commit 历史链（自底向上）

```text
439a9d2 (HEAD -> router-groups, origin/router-groups) 
        fix: prevent NPE during backup export by hardening ProxyEntity displayType and filtering corrupted profiles
94707b8 feat: prune app architectures, tools network module, webdav backup, and drawer router group
6c2ecb6 fix: ensure node groups statically display nodes on homepage upon app open
5230562 feat: prepare 1.4.6, optimize router group drag reordering, and repair lifecycle defects
```

---

## 三、修改详情与核心技术方案

### 1. 修复备份导出闪退问题 (Commit: `439a9d2`)
- **问题现象**：在设置 -> 备份页面点击“导出文件”或“分享”时，应用崩溃闪退，抛出 `CrashHandler` 日志（崩溃堆栈见 `NB4A Crash 7551409928519854559.log`）。
- **崩溃根因**：
  1. `ProxyEntity.kt` 的 `displayType()` 曾使用强拆操作符 `!!`（如 `socksBean!!.protocolName()`、`httpBean!!.isTLS()` 等）。
  2. 当 SQLite 中存在因订阅更新异常或历史遗留的残缺/空配置节点（bean 为 null）时，`requireBean()` 触发错误描述拼接，调用 `displayType()` 引发了连锁的 `NullPointerException`。
  3. `BackupFragment` 内的 `actionExport` 与 `actionShare` 缺少协程异常捕获，导致未捕获异常直接杀死进程。
- **修复方案**：
  1. **安全调用与兜底**：`ProxyEntity.displayType()` 中所有 bean 属性访问全部改为安全调用 `?.` 与安全回退文本（例如 `socksBean?.protocolName() ?: "SOCKS"`），彻底杜绝 NPE。
  2. **序列化保护**：`ProxyEntity.serializeToBuffer()` 对 `requireBean()` 增加了 `runCatching` 保护，若 bean 为空则写入空字节数组，防止底层 Kryo 序列化异常。
  3. **数据健康自愈**：`BackupFragment.doBackup()` 从数据库读取节点后使用 `partition` 过滤出有效节点，若检测到损坏节点自动执行 `proxyDao.deleteProxy(corruptedProfiles)` 进行清理，确保导出的备份文件 100% 合规。
  4. **外层全局防护**：在 `actionExport` 与 `actionShare` 中增加 `try-catch`，遇到任何意外读写错误均通过底部 Snackbar 友好提示，绝不闪退。
  5. **测试覆盖**：新增 `ProxyEntityNullSafetyTest.kt`，覆盖 19 种协议类型在 bean 为 null 时的安全性。

---

### 2. 应用瘦身与精简优化 (Commit: `94707b8`)
- **去除除 `arm64-v8a` 外的设备支持**：
  - `Helpers.kt`：`splits.abi` 仅保留 `include("arm64-v8a")`，清理 Fdroid 任务。
  - `app/build.gradle.kts`：`buildHevTun` 仅构建 `arm64-v8a`；`verifyLibcore` 仅校验 `jni/arm64-v8a/libgojni.so`；避免在 `defaultConfig` 与 `splits.abi` 冲突配置 `abiFilters`。
  - `compile-hevtun.sh`：`ABIS="arm64-v8a"`。
  - 最终编译只输出单架构 APK，体积约 25 MB。
- **工具页网络模块去除**：
  - 修改 `ToolsFragment.kt`：移除 `NetworkFragment`，仅保留 `BackupFragment`。
  - 设置 `binding.toolsTab.isVisible = false` 隐藏顶部单一 Tab，备份页面直接铺满工具页。
  - 删除文件：`NetworkFragment.kt`、`layout_network.xml`。
- **备份页面 WebDAV 备份模式去除**：
  - 修改 `layout_backup.xml`：移除整张 WebDAV 卡片。
  - 修改 `BackupFragment.kt`：删除 WebDAV 上传/下载/测试等网络逻辑与 OkHttp 依赖；`doBackup` 简化为直接输出 UTF-8 JSON 字节数组（本地导出/导入/分享完好保留）。
  - 删除文件与配置：`WebDAVSettingsActivity.kt`、`layout_webdav_settings.xml`、`webdav_preferences.xml`；从 `AndroidManifest.xml` 注销；清理 `Constants.kt` 与 `DataStore.kt` 中的 `webdav*` 常量及属性。
- **侧边栏去除“代理组”条目**：
  - `main_drawer_menu.xml`：移除 `nav_router_group` 菜单项。
  - `MainActivity.kt`：移除 `R.id.nav_router_group` 分发分支。
  - 路由分组管理页面（`RouterGroupListActivity`）依然可通过分组页顶部的卡片点击直接进入。

---

### 3. 首页节点组静态持久显示修复 (Commit: `6c2ecb6`)
- **问题现象**：过去每次打开 App，首页各节点组下的节点不可见，必须手动刷新一次订阅才会出现。
- **根本原因**：
  1. `ConfigurationFragment` 在路由分组模式下依赖订阅更新或主动刷新事件，在页面初次创建（`onViewCreated`）时没有主动去对齐与分发成员快照。
  2. `onResume()` 中使用了脆弱的 `configurationListView.size == 0` 判断，当存在占位或空头时，阻止了刷新逻辑。
  3. `onAdd` / `onUpdated` / `onRemoved` 事件分发时，按 `proxy.groupId == currentGroup.id` 做了硬过滤；而在路由分组模式下，节点来自多个不同订阅分组（跨订阅），导致动态事件被过滤掉。
- **修复方案**：
  1. `GroupManager.kt`：在 `GroupManager.Listener` 接口中增加 `suspend fun routerGroupsUpdated() = Unit`，在 `reconcileRouterMembers()` 对齐完成后向所有监听器广播更新。
  2. `ConfigurationFragment.kt`：
     - 在 `GroupFragment.onViewCreated()` 时立即调用 `loadProfiles()`；
     - 优化 `onResume()` 中的刷新判定，确保回到页面时能重新评估并刷新成员列表；
     - 监听 `routerGroupsUpdated()` 回调，收到通知后在主线程重新拉取节点；
     - 在路由分组模式下，重构 `onAdd`、`onUpdated`、`onRemoved` 事件处理：只要节点属于当前路由组的成员列表，即触发 UI 局部更新或重新加载。
  3. `MainActivity.kt`：冷启动在 IO 协程中执行一次全局对齐（`reconcileRouterMembers`），确保冷启动时数据库中的 `RouterMember` 与实体节点完全同步。

---

### 4. 路由分组跨订阅拖拽改序与排序持久化 (Commit: `5230562`)
- **核心需求**：用户需要在路由分组视图下支持拖拽任意节点调整顺序，且该顺序必须独立于订阅、持久化保存在数据库中。
- **架构实现**：
  - **数据表设计（Room Schema 10）**：
    - `router_groups`：存储路由分组元数据（名称、分组类型 selector/urltest、测速配置等）。
    - `router_group_sources`：存储该路由分组包含的数据源（按订阅分组绑定）。
    - `router_members`：存储每个具体节点的映射关系，包含 `routerId`, `proxyId`, `userOrder`, `enabled` 等。
  - **排序独立性**：跨订阅拖拽改序仅修改 `router_members.userOrder`，绝不污染或修改 `proxy_entities.userOrder`（普通节点在其原本订阅分组内的顺序保持不变）。
  - **对齐算法保证（`RouterReconciler.kt`）**：
    - 当订阅刷新拉取到新增节点时，新节点自动追加到该路由组现有最大 `userOrder + 1` 之后，绝不打乱用户之前已经手动拖拽好的节点顺序。
    - 当订阅删除了节点时，自动清理对应的 `router_members` 孤立记录。

---

## 四、给 GPT-6 的后续接力注意事项

1. **Room 数据库版本保持**：
   - 当前 Room 版本为 **10**，且所有测试用例对版本 10 的约束校验均处于通过状态。如需增改数据表结构，必须编写合规的 Migration 并更新 `app/schemas` 下的 JSON 文件，严禁直接改动破坏向下兼容。
2. **架构过滤与构建**：
   - 现已全面启用 `splits.abi` 单架构构建模式（仅 `arm64-v8a`）。
   - 切勿在 `app/build.gradle.kts` 的 `defaultConfig` 中同时声明 `ndk.abiFilters` 与 `splits.abi`（Android Gradle Plugin 会抛出 `Conflicting configuration` 异常）。
3. **节点空安全防线**：
   - `ProxyEntity` 在从 SQLite 读取时，其特定协议 bean 可能因解析或损坏为 null。任何涉及 `ProxyEntity` 协议类型的访问均建议调用 `displayNameOrFallback()` 或使用 `?.` 安全调用，避免使用 `!!` 强拆。
4. **验证命令（本地已配置好离线依赖环境）**：
   - 测试：`.\gradlew.bat testOssDebugUnitTest --offline --console=plain`
   - 打包：`.\gradlew.bat assembleOssDebug --offline --console=plain`

---
*当前工作区状态：Clean，所有测试与打包 100% 验证通过，已全部推送到 GitHub `router-groups` 分支。*
