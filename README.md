# Claude Code Test App

一个运行在 Android 手机上的本机 Agent 运行时。项目基于 Termux 二次开发，在 App 私有目录中部署 Ubuntu proot 环境，同时支持 Codex 和 Claude，并把手机能力通过 Android MCP 暴露给模型使用。

当前项目已经不只是“在手机上运行 Claude Code”。它包含：

- Codex / Claude 双后端聊天与独立配置。
- 截图、无障碍、ADB、应用、文件、系统状态等 Android MCP 工具。
- AgentServer / Loom 协作运行时，支持 Driver 绑定、Slave 管理和多设备协作。
- 工作目录限制、自动化 Boost、密钥管理、终端和调试页面。
- 离线优先的 Ubuntu、AgentServer、Loom 分包安装。

> 当前主路径以 Codex 为主，Claude 保留兼容。

## 当前定位

App 的核心目标是让 Android 设备成为一个可交互、可协作、可被远端调度的 Agent 节点：

| 模块 | 作用 |
| --- | --- |
| 主页 | 本机 Codex / Claude 对话，支持流式输出、思考折叠、工具调用排序和对话历史 |
| 密钥 | 分别管理 Codex 和 Claude 的 API Key、环境变量和配置文件 |
| 协作 | 管理 Driver 工作区绑定、本机运行时、Slave 列表、Loom 编排和 AgentServer 连接 |
| 终端 | 进入 Termux / Ubuntu 环境排查运行状态 |
| 设置 | 自动化 Boost、工作目录限制、权限和调试入口 |

## 运行架构

### Android 层

Android App 负责 UI、权限、MCP 工具和安装编排：

- `HomeFragment`：聊天主页，支持 Codex / Claude 切换。
- `ApiKeyFragment`：按 provider 隔离管理密钥和配置。
- `CollaborationFragment`：统一协作运行时控制台。
- `AgentServerFragment`：AgentServer 工作空间连接的高级页面。
- `LoomFragment`：Loom Driver / Slave / Observer 的高级配置页面。
- `WorkspaceAccessSettingsFragment`：工作目录限制和应用目录权限。
- `McpHttpServer`：向 Ubuntu 内的 Agent 暴露 Android 工具。

### Ubuntu 层

App 内置一个共享 Ubuntu proot 环境，但 provider 状态拆到不同 Linux 用户下：

| Provider | Linux 用户 | Home | 主配置 | Key 环境变量 |
| --- | --- | --- | --- | --- |
| Codex | `codex` | `/home/codex` | `/home/codex/AGENTS.md`, `/home/codex/.codex/config.toml` | `OPENAI_API_KEY` |
| Claude | `claude` | `/home/claude` | `/home/claude/CLAUDE.md`, `/home/claude/.claude/settings.json` | `ANTHROPIC_API_KEY` |

Ubuntu 基础环境、Node.js、AgentServer 和 Loom 二进制共用同一个 rootfs，避免重复打包导致 APK 体积失控。

### 分包资产

当前 APK 内置这些离线资产：

| 资产 | 路径 | 作用 |
| --- | --- | --- |
| Ubuntu 快照 | `app/src/main/assets/ubuntu-snapshot/ubuntu-claude-aarch64-20260512.tar.xz` | Android 内 Ubuntu rootfs |
| AgentServer addon | `app/src/main/assets/agentserver-linux-arm64.tgz` | AgentServer CLI / 连接能力 |
| Loom addon | `app/src/main/assets/loom-linux-arm64.tgz` | `driver-agent`, `slave-agent`, `observer-server`, skills 和 prompt |

安装优先使用 APK 内置包；内置包缺失或损坏时，安装脚本可以按模块走联网回退下载。

## 安装要求

- Android 7.0+。
- arm64 设备。
- 建议预留 4GB 以上可用空间。
- 首次部署建议联网，便于包缺失或设备环境异常时走回退安装。
- 需要手动授权截图和无障碍权限，ADB 能力需要设备侧允许调试。

首次启动时请保持 App 在前台，等待终端安装脚本完成。安装完成后，Ubuntu、Codex/Claude 用户、Android MCP 配置、AgentServer addon 和 Loom addon 会被写入 App 私有目录。

## 首次配置

### 1. 选择当前助手

主页顶部的“切换 Agent”按钮用于在 Codex 和 Claude 之间切换。切换只影响新的本机对话和后续协作配置生成，不会删除另一个 provider 的历史数据。

### 2. 配置密钥

进入底部“密钥”页：

- 顶部切换 Codex / Claude。
- 先配置 Agent 相关设置，再添加 API Key。
- Codex 写入 `OPENAI_API_KEY` 和 Codex 配置。
- Claude 写入 `ANTHROPIC_API_KEY`，可选写入 `ANTHROPIC_BASE_URL`。

两个 provider 的密钥、历史、技能和配置相互隔离。

### 3. 授权手机能力

主页和设置页会显示关键权限状态：

| 权限 | 用途 |
| --- | --- |
| 截图 | 让 Agent 观察屏幕内容 |
| 无障碍 | 读取 UI 树、点击、滑动、输入 |
| ADB | 在无障碍不稳定或不可用时提供候选操作通道 |

部分 App 的无障碍节点可能不完整，项目会优先使用可验证的 UI 树操作，必要时补充 ADB 和截图识别方案。

## 主页

主页用于本机对话：

- 顶部可切换 Codex / Claude。
- 左侧抽屉按 provider 独立显示历史、技能、记忆和上传文件。
- 输出流会把思考过程、工具调用和最终回答分层展示。
- Markdown 中的加粗等基础格式会在气泡内渲染。
- 输出时页面不会强制跳到底部，便于用户从回答开头自然阅读。

Codex 当前是主路径；Claude 兼容原有 Claude Code 使用方式。

## 协作运行时

协作页把 AgentServer 和 Loom 统一成一个运行时 Dashboard，而不是两个割裂入口。

### Driver 工作区绑定

Driver 是当前推荐的协作入口。点击“Driver 工作区绑定”后扫码登录，把本机 Driver 绑定到当前 AgentServer workspace。绑定成功后，Codex 或 Claude 可以通过 Loom driver MCP 查询和调度同 workspace 下的 Slave。

如果旧 token 失效，App 会重新要求绑定，避免出现远端显示 Slave 在线但本机 Driver 查询不到的情况。

### 本机运行时

本机运行时展示当前设备可承担的协作角色：

- Observer：Loom 遥测与能力仓库。
- Driver：在当前 Agent 中提供编排 MCP。
- Slave：被 Driver 调度执行任务的工作节点。

协作首页支持创建、启动、暂停和删除本机 Slave。每个 Slave 可以指定工作目录、provider 和名称。

### Loom 编排能力

Loom 运行在 AgentServer workspace 之上，负责多 Agent 编排：

- 查询在线 agent / slave。
- 检查能力。
- 派发任务。
- 调用 slave 的 bash、文件和动态 MCP 能力。

高级配置入口保留在协作页中，用于查看日志、处理注册、调试 Observer / Driver / Slave。

### AgentServer 连接

AgentServer 是远端 workspace/control plane。当前 UI 中，AgentServer 连接保留为独立高级入口：

- 使用 Loom 编排时，优先通过 Driver 工作区绑定进入协作能力。
- 不使用 Loom 编排时，可以从 AgentServer 页面连接传统 AgentServer 工作空间。

## 工作目录限制

工作目录限制用于控制 Agent 可以访问哪些目录和应用数据：

- Ubuntu 中当前 provider 用户目录默认可用。
- Android 基础公共目录可作为默认可选目录。
- 应用目录按应用列表勾选授权。
- 协作页只保留跳转入口，完整配置在设置页中完成。

这个功能用于减少 Agent 对无关文件和应用数据的访问面。

## 自动化 Boost

自动化 Boost 用于沉淀可复用的低风险手机操作路径：

- 第一次由 Agent 正常观察和操作。
- 成功后从 MCP 调用轨迹生成候选动作配方。
- 用户审核后加入白名单。
- 后续相似任务可直接执行配方，失败时清除 boosting 状态并回退给 Agent。

第一阶段只适合打开 App、进入固定页面、点击稳定按钮等低风险操作；发送、删除、支付、授权、密码和验证码等高风险动作不会自动 Boost。

## Android MCP 能力

项目内置的 Android 工具会暴露给 Codex / Claude：

- 截图观察。
- 无障碍 UI 树读取。
- 点击、滑动、输入、按键。
- 打开应用和读取当前 Activity。
- 文件读取和目录管理。
- 设备状态、网络、电量、传感器等查询。
- ADB 候选操作能力。

这些能力通过本机 MCP 服务提供，模型不需要直接调用 Android SDK。

## 构建

需要 JDK 17 和 Android SDK 36。

```powershell
cd C:\ZRS_Works\Claude_code_test_app
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出位置：

```text
app\build\outputs\apk\debug\claude-code-test-app_apt-android-7-debug_universal.apk
```

安装到已连接设备：

```powershell
adb install -r app\build\outputs\apk\debug\claude-code-test-app_apt-android-7-debug_universal.apk
```

当前 APK 包含 Ubuntu 快照和 addon，体积约 400MB 以上。发布时应把 APK 上传为 GitHub Release 资产，不应直接提交到 git 历史。

## 常用验证命令

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
adb devices
adb install -r app\build\outputs\apk\debug\claude-code-test-app_apt-android-7-debug_universal.apk
```

查看设备上安装版本：

```powershell
adb shell dumpsys package com.termux | findstr /i "versionName versionCode firstInstallTime lastUpdateTime"
```

## 排查路径

| 路径 | 内容 |
| --- | --- |
| `/data/data/com.termux/files/home/agentserver-agent.log` | AgentServer 运行日志 |
| `/data/data/com.termux/files/home/loom-driver-register.log` | Driver 注册日志 |
| `/data/data/com.termux/files/home/loom-slave.log` | Slave 运行日志 |
| `/data/data/com.termux/files/home/mcp-audit.log` | Android MCP 调用审计 |
| `/home/codex/AGENTS.md` | Codex 侧 Android 能力提示 |
| `/home/codex/.codex/config.toml` | Codex 配置 |
| `/home/claude/CLAUDE.md` | Claude 侧 Android 能力提示 |
| `/home/claude/.claude/settings.json` | Claude 配置 |

## 常见问题

### 进入 App 后对话区为空或历史突然恢复

通常是 provider 历史恢复、会话缓存和 UI 状态同步问题。先确认当前顶部显示的是 Codex 还是 Claude，再打开左侧抽屉查看对应 provider 的历史。

### Driver 已绑定，但查询不到 Slave

优先检查 Driver token 是否过期。App 会在复用 Driver 配置前调用 `/api/agent/whoami` 校验凭据；如果校验失败，需要重新扫码绑定 Driver。

### AgentServer 页面已连接，但 Driver 仍要求扫码

这是正常边界。AgentServer 工作空间连接和 Loom Driver 绑定不是同一个凭据。当前推荐路径是以 Driver 绑定作为主要协作入口。

### 截图或无障碍显示未授权

截图权限每次 App 重启后可能需要重新授权。无障碍权限需要进入系统设置打开本 App 的无障碍服务。部分系统会在应用更新后重置权限状态。

### Android Studio 编译出来像旧版本

先确认 Android Studio 打开的目录是：

```text
C:\ZRS_Works\Claude_code_test_app
```

然后执行 Gradle Sync 或 Clean/Rebuild。当前新版代码和 Loom/Codex 相关页面都在这个目录下。

## 设计文档

主要设计记录在 `docs/superpowers/specs/` 下：

- `2026-06-04-codex-provider-support-design.md`
- `2026-06-03-loom-offline-addon-integration-design.md`
- `2026-06-10-automation-boost-design.md`
- `2026-06-16-agentserver-loom-connection-boundary-design.md`
- `2026-06-16-agentserver-loom-unified-collaboration-design.md`

旧的总体架构说明仍可参考：

- `app架构.md`

## License

本项目基于 Termux 上游代码继续开发，继承对应开源协议。新增的 AgentServer / Loom 集成遵循各自上游项目协议。
