# AgentServer / Loom 统一协作页设计

Date: 2026-06-16
Project: `Claude_code_test_app`
Reference repos:

- `https://github.com/agentserver/agentserver`
- `https://github.com/agentserver/loom`
- `C:\ZRS_Works\GitPillwork\Agentserver_app` at `6a21559`

## Goal

把当前 Android App 的“协作”页面从两个并列入口：

```text
AgentServer
Loom
```

调整为一个统一的“协作运行时”控制台：

```text
AgentServer 工作空间
  -> 本机作为 Connector / Browser / Driver / Slave 的运行状态
  -> Loom 作为运行在 AgentServer workspace 上的多 agent 编排层
```

这次设计文档只定义信息架构、状态模型、命令边界和分阶段实施路径。代码实现需要在用户确认后再开始。

## Upstream Findings

### `agentserver/agentserver`

最新上游 README 的定位已经从早期的 `agentserver claudecode` 连接工具，演进为：

- Personal Compute Network。
- 一个 workspace 中注册多台设备。
- 每台设备是 Connector。
- 实际发起控制的客户端是 Browser。
- 主推 Codex-native 路径：Connector 使用 `codex exec-server --remote`，Browser 使用 `codex --remote`。
- 平台还包含 Web、IM、Jupyter、credential proxy、LLM proxy、audit log、sandbox 等控制面能力。

仓库根目录没有直接合入 Loom 源码目录。也就是说，代码仓库仍然分开。

### `agentserver/loom`

Loom README 明确写明：

- Loom 是基于 AgentServer 平台的一组自定义 agent。
- 用户本地运行一个 driver。
- driver 在 Claude Code 或 Codex CLI 里作为 stdio MCP server。
- 多台 slave 注册到同一个 AgentServer workspace。
- observer 独立收集遥测。
- driver 和 slave 都支持 `--agent claude|codex`，可以混合机队。
- Loom 的关键能力是 `inspect_capabilities`、`list_agents`、任务 contract、`run_slave_bash`、`register_slave_mcp`、`unregister_slave_mcp`、dynamic MCP。

Loom 仓库依赖 `github.com/agentserver/agentserver v0.48.1`，说明它不是 AgentServer 的替代品，而是建立在 AgentServer 注册、workspace、任务通道和 agent discovery 上的编排层。

### `Agentserver_app` Reference

`C:\ZRS_Works\GitPillwork\Agentserver_app` 已经更新到 `6a21559`。这个版本新增了 Linux headless server 设计和实现，值得作为 Android 侧参考：

- 新增 `cmd/agentserver/main.go`，用户面对一个统一的 `agentserver` 二进制。
- 默认运行 `agentserver` 时，把当前目录作为 foreground slave。
- `agentserver install-driver` 配置 Codex 全局 MCP driver。
- `agentserver switch-workspace` 切换远端 workspace。
- `agentserver serve-driver-mcp` 作为 Codex MCP 入口，内部再调用 `driver-agent serve-mcp`。
- `internal/headless` 把 `driver-agent`、`slave-agent`、Codex runtime、modelserver access、AgentServer device auth 收束到一个用户可理解的运行时。

这说明 `Agentserver_app` 的产品方向也是：把 Loom 的 driver/slave 细节包装成 AgentServer app 的本机运行时，而不是让用户直接管理两套系统。

## Current Android App Context

当前 Android App 已经有以下能力：

- 单个共享 Ubuntu proot 环境。
- Claude 和 Codex 两个 Ubuntu 用户：`/home/claude`、`/home/codex`。
- Provider switch：Codex / Claude。
- `AgentServerFragment`：配置 server URL、device name、connect/stop/status/log。
- `LoomFragment`：配置 observer、workspace、driver/slave 名称、role mode、start/stop/register/status/log。
- `CollaborationFragment`：协作页目前显示 provider、driver 绑定、AgentServer 入口、Loom 入口、本机 Agent/Slave、更新区。
- `AutoAgentServerManager` 和 `AutoLoomManager`：仍按独立 addon 安装 AgentServer 与 Loom。

当前问题：

- 协作页把 AgentServer 和 Loom 视觉上当成两个平级系统，和上游新定位不一致。
- AgentServer 页面仍偏旧式 `agentserver claudecode` / `agentserver codex` 兼容路径。
- Loom 页面已经有很多细配置，适合保留为高级页，但不应在协作首页上成为和 AgentServer 平级的第二套入口。
- 用户真正需要看到的是“这台手机如何接入工作空间、当前承担什么角色、哪些能力在线”。

## Product Decision

仓库关系按事实描述：

```text
AgentServer 仓库没有合并 Loom 仓库。
Loom 功能已经深度建立在 AgentServer workspace 之上。
Android App 的协作页应按功能融合来设计，而不是按 Git 仓库边界来设计。
```

因此协作页采用：

```text
统一协作运行时 Dashboard
  AgentServer 工作空间是顶层上下文
  Loom 是该 workspace 下的多 agent 编排能力
  AgentServer 详情页和 Loom 详情页保留为二级高级配置页
```

## Terminology

为了降低用户理解成本，Android UI 使用中文主文案，括号中保留必要英文名：

- 工作空间：AgentServer workspace。
- 本机运行时：当前手机上可启动的协作进程集合。
- 指挥端：Browser / Driver，负责发起任务和编排。
- 执行端：Connector / Slave，负责被调度执行任务。
- Driver：Loom driver MCP。
- Slave：Loom worker。
- Observer：Loom 遥测与能力仓库。
- All-in-one：本机同时承担 Observer、Driver、Slave。

不在主 UI 中直接暴露 `master-agent`。它作为兼容路径，后续放入高级 Loom 配置。

## Information Architecture

### Top Area: Workspace And Provider

第一屏顶部显示：

- 当前助手：Codex / Claude。
- 当前 AgentServer workspace：名称、短 ID、连接状态。
- 本机身份摘要：例如 `All-in-one · Codex`、`Driver · Claude`、`Slave · Codex`。
- 一个明显的“切换”按钮，用于切换 provider 或本机身份。

这里不显示大段说明文字。状态卡本身要可点击。

### Card 1: AgentServer 工作空间

用途：展示远端控制面是否可用。

状态字段：

- Server URL。
- Workspace name / workspace id。
- 账号或授权状态。
- 当前设备注册状态。
- 兼容模式：旧 AgentServer CLI / 新 headless wrapper / Codex remote。

主要按钮：

- `连接工作空间`：进入 AgentServer 设置或触发连接。
- `停止连接`：停止当前 AgentServer 相关后台进程。
- `连接设置`：进入 AgentServerFragment。

第一阶段不删除旧 AgentServerFragment，只把协作页入口文案从“AgentServer”改为“工作空间连接设置”。

### Card 2: 本机运行时

用途：展示这台手机当前承担的协作角色。

状态字段：

- Provider：Codex / Claude。
- 角色：All-in-one / Driver / Slave / Observer。
- Driver MCP：未配置 / 已配置 / 认证中 / 可用 / 错误。
- Slave：停止 / 启动中 / 认证中 / 运行中 / 错误。
- Observer：停止 / 运行中 / 错误。

主要按钮：

- `切换身份`。
- `绑定 Driver`。
- `启动本机运行时`。
- `停止本机运行时`。

按钮根据当前角色动态变化：

| Role | 主操作 |
| --- | --- |
| All-in-one | 写配置、启动 observer、启动 slave、绑定 driver |
| Driver | 写 driver 配置、绑定 driver、检测 MCP |
| Slave | 写 slave 配置、启动 slave、处理认证 |
| Observer | 写 observer 配置、启动 observer |

### Card 3: Loom 编排能力

用途：把 Loom 从“另一个入口”改成“workspace 下的编排层”。

状态字段：

- `list_agents` 是否可用。
- `inspect_capabilities` 是否可用。
- dynamic MCP 是否启用。
- userspace 是否安装。
- skills 是否安装到当前 provider。

主要按钮：

- `高级配置`：进入 LoomFragment。
- `查看日志`：进入 LoomFragment 并定位日志区域。
- `刷新状态`：运行 Loom status script。

第一阶段只显示摘要，不直接在 Dashboard 上做完整 Loom 配置表单。

### Card 4: 本机能力与权限

用途：把 Android MCP 能力也放进协作上下文，避免用户不知道为什么 Agent 操作手机失败。

状态字段：

- 无障碍：已授权 / 未授权。
- 截图：已授权 / 未授权。
- ADB companion：在线 / 离线 / 未配置。
- Android MCP HTTP server：运行中 / 未运行。

操作：

- 点击授权状态进入对应权限页。
- 点击 ADB 状态进入自动化/调试页。

这张卡和 Loom/AgentServer 不强绑定，但它决定手机能否作为有效 slave/connector。

### Card 5: 更新区

更新区保留，但文案按包拆分：

- Ubuntu 基础环境。
- AgentServer addon。
- Loom addon。
- Provider runtime：Claude / Codex。

第一阶段只显示安装策略和版本摘要，不实现在线版本检查。

## Navigation

底部导航保留当前五项：

```text
主页 | 协作 | 终端 | 密钥 | 设置
```

协作页作为 AgentServer + Loom 的一级入口。

二级页：

- AgentServerFragment：改名语义为“工作空间连接设置”。
- LoomFragment：改名语义为“Loom 高级配置”。

二级页必须有返回按钮，返回协作页，而不是退出应用。

## Runtime State Model

新增一个概念层，不一定第一阶段就单独成类：

```text
CollaborationRuntimeState
  provider
  workspace
  localRole
  agentServer
  loom
  androidCapabilities
  packages
```

建议后续落地为：

```text
app/src/main/java/com/termux/app/collaboration/CollaborationRuntimeState.java
app/src/main/java/com/termux/app/collaboration/CollaborationRuntimeReader.java
app/src/main/java/com/termux/app/collaboration/CollaborationActionController.java
```

第一阶段可以先在 `CollaborationFragment` 内读取现有 prefs 和简单状态。等 UI 稳定后再抽类。

状态枚举：

```text
NOT_CONFIGURED
STOPPED
STARTING
AUTH_REQUIRED
RUNNING
ERROR
UNKNOWN
```

状态来源：

- Provider：`ProviderSettingsStore`。
- AgentServer：`agentserver_config` prefs、进程 grep、日志摘要。
- Loom：`loom_config` prefs、`LoomCommandBuilder.statusScript`、进程 grep、日志摘要。
- Android permissions：已有 MCP permission state。
- Package：Auto*Manager 或文件存在性检查。

## Command Strategy

### Phase 1: UI Fusion Without Runtime Rewrite

保持现有命令路径：

- AgentServer 继续使用 `AgentServerCommandBuilder`。
- Loom 继续使用 `LoomCommandBuilder`。
- Driver 绑定继续调用 `driver-agent register --config ...`。
- AgentServer 和 Loom addon 继续分包安装。

只改协作页信息架构、文案、入口关系和状态摘要。

原因：

- 风险小。
- 不影响当前已经能跑的 Codex/Loom 调试结果。
- 可以先验证用户是否理解新的 Dashboard。

### Phase 2: Introduce Android Headless Wrapper

参考 `Agentserver_app` 的 Linux headless 设计，Android 侧可以新增一个统一 wrapper 层，但不要求上游 AgentServer 立刻改包：

```text
android-agentserver-runtime
  install-driver
  switch-workspace
  serve-driver-mcp
  start-slave
  status
```

它可以先由 Java 生成 shell script，而不是马上引入 Go wrapper。

目标：

- 将 Driver 配置、Slave 配置、Codex MCP 注册、device-code URL 捕获收束成统一命令。
- 为未来替换为上游 `cmd/agentserver` headless binary 留接口。

### Phase 3: Align With New AgentServer Connector Model

当 Android 侧 packaged AgentServer 或 Codex runtime 支持新 remote 模式后，AgentServer 连接页应从旧兼容命令迁移到 capability detection：

```text
1. 如果存在新 headless wrapper:
   agentserver install-driver
   agentserver serve-driver-mcp
   agentserver default slave

2. 如果 Codex 支持 remote connector:
   codex exec-server --remote ...
   codex --remote ...

3. 如果只存在旧 AgentServer CLI:
   agentserver claudecode
   agentserver codex / codexcode
```

UI 上不要暴露这三个技术分支，只显示：

```text
连接工作空间
本机作为执行端在线
本机作为指挥端可用
```

## Package Strategy

保持之前已经确认的分包策略：

```text
Ubuntu snapshot
AgentServer addon
Loom addon
Codex runtime / npm install path
Claude runtime
```

短期不把 Loom 二进制并入 AgentServer addon。

原因：

- 上游仓库仍分离。
- Loom release 仍独立发布 driver/slave/observer/userspace。
- Android APK 需要避免重复 rootfs。
- 独立 addon 便于只更新 Loom。

但安装脚本必须支持未来去重：

- 如果 AgentServer addon 已经包含 `driver-agent` / `slave-agent`，Loom addon 安装时可以跳过相同版本。
- 如果 Loom addon 包含更新版本，按 Loom manifest 覆盖。
- `/usr/local/bin` 中的最终二进制来源写入 manifest，便于诊断。

建议 manifest：

```json
{
  "components": {
    "agentserver": {"version": "...", "source": "agentserver-addon"},
    "driver-agent": {"version": "...", "source": "loom-addon"},
    "slave-agent": {"version": "...", "source": "loom-addon"},
    "observer-server": {"version": "...", "source": "loom-addon"}
  }
}
```

## Provider Compatibility

Codex 是当前主路径，Claude 保留兼容。

协作页所有文案和状态都必须显示当前 provider：

- Driver MCP 配置写入 `.codex/config.toml` 或 `.mcp.json`。
- Loom skills 写入 `.codex/skills/loom-driver` 或 `.claude/skills`。
- 进程在 `/home/codex` 或 `/home/claude` 下运行。
- 切换 provider 后，Dashboard 标记需要重新绑定或重启运行时。

Provider 切换时不自动杀进程。协作页显示：

```text
当前运行时使用 Claude，当前选择为 Codex。需要重新绑定/重启后生效。
```

## Error Handling

Dashboard 只显示短状态，详情页显示完整日志。

需要覆盖的错误：

- AgentServer workspace 未连接。
- AgentServer device auth 过期。
- AgentServer 旧 CLI 不支持 Codex。
- Codex remote / headless wrapper 未安装。
- Loom driver 未绑定。
- Loom slave 首次启动需要授权。
- Observer URL 不可达。
- Skills 未安装到当前 provider。
- Android 无障碍或截图未授权。
- 当前 provider 和运行中 provider 不一致。

OAuth/device-code URL 继续复用 QR dialog。

## Testing Strategy

### Documented Unit Tests For Implementation

第一阶段 UI 融合：

- `CollaborationNavigationStructureTest`
  - 不再要求 AgentServer 和 Loom 是两个平级入口文案。
  - 要求存在“工作空间”、“本机运行时”、“Loom 编排能力”、“本机能力与权限”、“更新区”。
  - 要求 AgentServer 和 Loom 详情页入口仍存在。
- `CollaborationFragment` source-level tests
  - provider switch 仍会触发 driver bind。
  - role switch 仍会保存 `LoomSettings.KEY_ROLE_MODE`。
  - AgentServer detail route 使用 `showAgentServerMode()`。
  - Loom detail route 使用 `showLoomMode()`。

第二阶段 runtime controller：

- 状态聚合测试：prefs + process result -> Dashboard state。
- provider mismatch 测试。
- auth URL parsing 测试。
- no-op stop 测试。

### Build Verification

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

### Manual Checks

- 打开协作页，第一屏能看出当前 workspace、provider、role。
- 点击“切换身份”不会和状态文字重叠。
- 点击“连接设置”进入 AgentServerFragment，返回后回协作页。
- 点击“高级配置”进入 LoomFragment，返回后回协作页。
- Codex provider 下 Driver MCP 摘要显示 Codex。
- Claude provider 下 Driver MCP 摘要显示 Claude。

## Implementation Phases

### Phase 1: Dashboard Fusion

Scope:

- 修改 `fragment_collaboration.xml`。
- 修改 `CollaborationFragment.java`。
- 更新 `CollaborationNavigationStructureTest`。
- 不改 `AgentServerFragment` 和 `LoomFragment` 的运行逻辑。
- 不改安装包结构。

Deliverable:

- 协作页按统一 Dashboard 呈现。
- AgentServer 与 Loom 进入二级设置。
- 文案统一为中文。

### Phase 2: Runtime State Extraction

Scope:

- 新增 `collaboration` package。
- 抽出 runtime state reader。
- 抽出 action controller。
- Dashboard 刷新从状态对象渲染。

Deliverable:

- UI 和命令读取解耦。
- 后续支持 headless wrapper 不需要重写 Fragment。

### Phase 3: Headless Wrapper Compatibility

Scope:

- 参考 `Agentserver_app/internal/headless`。
- 在 Android shell 层或 packaged binary 层提供统一命令。
- 添加 capability detection。

Deliverable:

- 旧 AgentServer CLI、新 AgentServer Connector、Loom driver/slave 都可以从统一入口启动。

### Phase 4: Package Manifest And Dedup

Scope:

- AgentServer addon 与 Loom addon 写入 component manifest。
- 安装时识别已有同版本 binary。
- 日志显示每个 binary 来源。

Deliverable:

- 为未来上游包合并或二进制重叠做准备。

## Non-Goals

第一阶段不做：

- 不删除 AgentServerFragment。
- 不删除 LoomFragment。
- 不迁移到 `codex exec-server --remote`。
- 不重打 AgentServer/Loom addon。
- 不改变 Ubuntu rootfs。
- 不实现版本在线检查。
- 不引入 `Agentserver_app` 的 Go 代码到 Android 项目。

## Acceptance Criteria

设计实现后应满足：

- 用户在协作页看到的是一个统一协作运行时，而不是两个割裂系统。
- AgentServer 被表达为 workspace/control plane。
- Loom 被表达为 workspace 下的多 agent 编排能力。
- 本机角色切换、provider 切换、driver 绑定仍可用。
- AgentServer 和 Loom 高级配置仍能进入。
- 当前 Codex 主路径不被破坏。
- 当前 Claude 兼容路径不被破坏。
- 后续可以平滑接入最新 AgentServer Connector/Browser 模型。

