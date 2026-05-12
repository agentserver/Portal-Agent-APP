# Claude Code Android App

把 **Claude Code** 跑在你的 Android 手机上。基于 Termux 二次开发，用 **proot-distro** 在容器里跑 Ubuntu + Claude Code，再通过 MCP 把手机能力（截屏、点击、输入、相机、传感器）暴露给 Claude，让 Claude 真正能「操作手机」。

> 仓库默认面向 **arm64** 设备，自带离线 Ubuntu 快照。
> 实现细节记录在 → [app架构.md](./app架构.md)

---

## 核心特性

| 能力             | 说明                                                    |
|----------------|-------------------------------------------------------|
| 完整 Claude Code | 支持 skills、plan mode、自定义 slash commands、记忆系统、MCP 工具    |
| Claude 操作手机    | 截屏、点击、滑动、输入文字、读取 UI 树、查询权限/电池/WiFi/传感器                |
| 兼容第三方 API      | 通过 `ANTHROPIC_BASE_URL` 接入 DeepSeek、各种 Anthropic 兼容代理 |
| ChatGPT 风格 UI  | 流式气泡、思考过程折叠、附件上传、对话历史                                 |
| AgentServer 接入 | 把手机做成"远程沙盒"，从 PC/Web 端的上游 Agent 派发任务                  |
| 后台悬浮窗          | App 切到后台且 Claude 在执行任务时显示状态条，不挡视线                     |
| 可离线安装（但依旧建议联网） | 内置已配置好的Ubuntu快照，可直接离线部署                               |

---

## 安装

### 系统要求

- Android 7.0+ 的 **arm64** 设备
- 推荐预留 3GB+ 可用存储（Ubuntu rootfs ~ 1GB，Claude Code 安装 ~ 500MB）

### 安装说明

首次打开 App，**保持前台 + 联网**，等 Termux 终端跑完自动安装脚本（约30秒到1分钟）

安装完成的标志是终端最后输出 `[*] Setup complete`，以及进入以cLaude为用户名的Ubuntu环境。

---

## 首次配置

### 1. 配置 API Key

打开底部导航 **API Key** 页：

- 点击 **添加新 Key**
- 别名：随便取（如 `Anthropic` 或 `DeepSeek`）
- API Key：你的 `sk-ant-xxx`（Anthropic）或 `sk-xxx`（DeepSeek/其他兼容服务）
- Base URL：
  - 留空 = 走CLaude官方 Anthropic API
  - DeepSeek 填 `https://api.deepseek.com/anthropic`
  - 其他第三方填它们提供的 Anthropic 兼容地址
- 点击保存后**长按该条 → 设为当前使用**

### 2. 授予运行时权限

打开主页（**Home** 页），看底部两个状态按键：

| 徽章 | 含义 | 怎么开 |
|------|------|--------|
|  截屏 | MediaProjection 权限 | 点徽章右边的「授权截图」按钮，每次 App 启动后需点一次 |
|  无障碍 | UI 控制权限 | 点徽章后跳系统设置 → 找到本 App 启用 |

不开权限不影响聊天，只影响 Claude 的"操作手机"能力。
其他权限需去系统设置 应用管理界面开启。

### 3. 连接 AgentServer

默认上游地址为 `https://agent.cs.ac.cn`，可把手机当作"远程沙盒"派发任务：

底部导航 **AgentServer** 页：
- Server URL：上游服务器地址
- 沙盒 ID：留空 = 自动新建一个；填值 = 复用某个已有沙盒
- 设备名称：随便取，会显示在上游 Web UI 的沙盒列表
- 点击「连接」

连接成功后日志区会出现 `tunnel connected (sandbox: ...)`。

---

## 使用说明

### 主页

主页是聊天区，底部输入框旁的几个按钮：

| 按钮 | 作用 |
|------|------|
| 📎 | 选文件作为附件，会自动复制进 Ubuntu 给 Claude 读取 |
| **发送** | 发出消息，开始流式输出 |
| **打断** | 停止当前生成（保留会话上下文） |
| **新建对话** | 完全开始新对话（清屏 + 新 session ID） |

### 侧拉抽屉（左侧滑出）

5 个 Tab：

| Tab | 内容                                                      |
|------|---------------------------------------------------------|
| **历史** | 所有过往对话，点击恢复（自动加载完整对话）(删除会同步删掉当前对话上传的相关文件)               |
| **记忆** | Claude 的记忆库（`.md` 文件），点开可看可编辑                           |
| **技能** | 自定义 slash commands（`.md` 文件），点 ＋ 新建                     |
| **任务** | AgentServer 派发的任务列表，点击进入详情页看完整对话（删除会同步删掉当前任务执行时创建的临时文件） |
| **上传** | 你上传给 Claude 看的所有附件，长按可删（同步删 Ubuntu 内文件）                 |

**长按**列表项几乎都能弹出删除确认。删除会同步清理对应的 Ubuntu 文件 / Claude session jsonl，避免磁盘 leak。

### AgentServer 任务详情页

点侧栏「任务」Tab 里的某条 → 全屏聊天页：
- 顶部：返回按钮 + 任务标题 + 运行/完成徽章
- 内容：完整对话流（用户 prompt + Claude 回复 + 工具调用 + 工具返回）
- 任务还在跑时每秒自动刷新，跑完停止刷新

### 后台悬浮窗

- App 切到后台 + Claude 正在执行任务时，屏幕右上角出现一个紧凑状态条
- 显示当前状态（运行中/执行任务）+ prompt 预览
- 右侧 `×` 可手动关闭，下次新任务开始时会再次出现
- Claude 空闲时不显示

### 文件附件

主页输入框左边的 📎 → 系统文件选择器 → 选文件 → 输入框上方出现 `📎 文件名` 提示。

发送时 prompt 自动拼成 `[附件: /home/claude/uploads/<name>]\n<你的文字>`，Claude 用原生 Read 工具读取。

---

## 接入第三方 API（DeepSeek 等）

只要服务端**兼容 Anthropic API 协议**就行。

### DeepSeek

DeepSeek 官方提供 Anthropic 兼容端点：

- Base URL: `https://api.deepseek.com/anthropic`
- API Key: 你的 DeepSeek `sk-xxx`
- 模型映射由 DeepSeek 自动处理

### 其他兼容服务

- OpenRouter 的 anthropic models
- OneAPI / NewAPI / One-Hub 等聚合代理
- 自建 claude-code-router

填它们提供的 base URL + key 即可。**纯 OpenAI 格式**（如 `api.openai.com`）目前没有翻译代理。

---

## 常见问题

### Claude 说"截屏权限未授予"

去主页点顶部「授权截图」按钮。每次 App 重启都要重新点一次。

### Claude 说"无障碍服务未启用"

系统设置 → 无障碍 → 找到本 App → 启用。这是 Claude 调用 `ui.tap` 等所必需。
。

### 提示 402报错 / Insufficient Balance

是 API 账户余额耗尽了。去对应平台充值。

### App 越用越占空间

每次对话和每个 AgentServer 任务都会保存历史（含截图 base64）。清理方式：
- 侧栏「历史」Tab 长按 → 删除 → 自动清 Claude session jsonl
- 侧栏「任务」Tab 长按 → 删除 → 自动清归档文件
- 侧栏「上传」Tab 长按 → 删除 → 自动清 Ubuntu 内文件

App 启动时还会自动扫描并清理"孤儿 jsonl"（删过会话但磁盘上还残留的文件）。

---

## 文件位置（排查用）

| 路径 | 内容 |
|------|------|
| `/data/data/com.termux/files/home/agentserver-agent.log` | AgentServer 运行日志 |
| `/data/data/com.termux/files/home/mcp-audit.log` | Claude 调用 MCP 工具的审计日志 |
| `/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu/home/claude/CLAUDE.md` | Claude 系统提示（每次启动重写） |
| `~/.claude/memory/*.md`（Ubuntu 内） | 记忆库 |
| `~/.claude/projects/-home-claude/<id>.jsonl`（Ubuntu 内） | Claude 自身的 session 历史 |
| `~/.agentserver-pipe.jsonl`（Ubuntu 内） | agentserver wrapper 的运行时缓冲 |
| App 私有 `getFilesDir()/agent-tasks/<id>.jsonl` | 每个 AgentServer 任务的归档 |

---

## 构建

需要 **JDK 17** + **Android SDK 36**。

```bash
# Debug 构建
./gradlew :app:assembleDebug

# 输出位置
app/build/outputs/apk/debug/claude-code-test-app_apt-android-7-debug_universal.apk

# 直接装到连接的设备
adb install -r app/build/outputs/apk/debug/claude-code-test-app_apt-android-7-debug_universal.apk
```

**构建说明：**
- `app/src/main/assets/ubuntu-snapshot/` 下的 tar.xz 是 Ubuntu 快照，决定了 APK 大小（默认 ~230MB）
- `gradle.properties` 已设置 `-Xmx12288M`，旧版 8GB heap 会在打包时 OOM
- 已配置 `noCompress 'xz', 'tar', ...` 跳过对预压缩文件的二次压缩，省内存且加快安装

---

## 实现细节

完整的架构图、数据流、组件职责拆解请看：

**→ [app架构.md](./app架构.md)**

涵盖 Fragment 路由、MCP 工具实现、AgentServer 接入的 wrapper 拦截机制、悬浮窗显隐三条件、4 个 Store 的数据组织、自动安装链路等。

---

## License

继承自 Termux 上游协议。
