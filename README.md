# Claude Code Android App（Termux Fork）

一个基于 Termux 的 Android 应用：在手机上自动安装 Ubuntu（`proot-distro`），并在 Ubuntu 内自动部署 **Claude Code**。同时提供 Chat 风格 UI、API Key 管理、AgentServer 集成，以及 **Android API → localhost HTTP** 桥接，方便 Claude Code 在 Ubuntu 内获取电量/传感器/剪贴板等实时信息。

- 上游基础：`termux/termux-app`（本项目是其 Fork）
- AgentServer：`agentserver/agentserver`
- License：继承 Termux，**GPLv3-only**（见 `LICENSE.md`）

## 功能概览

- **Home**：Chat UI（通过 `claude -p --output-format stream-json` 运行并渲染流式输出）
- **Terminal**：完整 Termux 终端（默认 Tab）
- **API Key**：管理 `ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL`（可保存多组并切换当前）
- **AgentServer**：自动安装并后台启动 `agentserver claudecode`，支持查看日志与停止
- **Android API 桥接**：App 内置 HTTP 服务 `127.0.0.1:17681`，供 Ubuntu 内用 `curl`/wrapper 调用电量、相机信息、传感器、Wi‑Fi、剪贴板

## 使用方法（首次运行）

1. 安装 APK（Android Studio 运行，或自行构建）
2. 打开 App → 进入 **Terminal**
   - App 会自动执行 `~/.ubuntu-setup.sh` 来安装 `proot-distro` + Ubuntu，并自动登录
   - 如果没有自动开始，可手动运行：`bash ~/.ubuntu-setup.sh`
3. 首次进入 Ubuntu 后会自动触发安装向导（写在 `/root/.bashrc`）
   - `~/.claude-setup.sh`：安装 Node.js + Claude Code，并可按提示写入 API Key（也可跳过，改在 App 的 **API Key** Tab 配置）
   - `~/.agentserver-setup.sh`：安装 AgentServer（arm64 优先使用离线包；否则从 GitHub Release 下载）
4. 在 **API Key** Tab 添加并“设为当前”
   - Base URL 默认 `https://code.ai.cs.ac.cn`，可清空以使用官方 `https://api.anthropic.com`
5. 在 **Home** Tab 输入消息开始使用；也可在 Terminal 里运行
   - `proot-distro login ubuntu`
   - `claude`

## AgentServer

- 打开 **AgentServer** Tab
  - 填写 `Server URL`（例如 `https://agent.cs.ac.cn`）与 `Device Name`（可选）
  - 点击“连接”后，后台会运行：`agentserver claudecode --skip-open-browser ...`
  - 如日志出现 OAuth URL，请复制到浏览器完成授权
  - 日志文件：`~/agentserver-agent.log`
- 也可在 Ubuntu 里手动运行
  - `agentserver login --server <URL>`
  - `agentserver claudecode --server <URL>`

## Android API 桥接（Ubuntu 内）

App 进程内启动本地 HTTP 服务：`127.0.0.1:17681`（仅 loopback）。

- 直接调用
  - `curl -s http://127.0.0.1:17681/battery`
  - `curl -s http://127.0.0.1:17681/clipboard`
- Ubuntu 内自动生成 wrapper（`/usr/local/bin/termux-*`）
  - `termux-battery-status`
  - `termux-camera-info`
  - `termux-sensor`
  - `termux-wifi-connectioninfo`
  - `termux-clipboard-get`

> 需要相关 Android 权限（相机/定位/传感器等）。App 被系统后台杀死时，HTTP 桥接也会停止。

## 构建

- 环境：Android Studio（建议 JDK 17+）
- 命令：`./gradlew assembleDebug`
- 产物：`app/build/outputs/apk/`

## FAQ

- Ubuntu 登录出现 `signal 11` / 崩溃：尝试 `proot-distro login --kernel 5.4.0 ubuntu`
- `claude native binary not installed`：在 Ubuntu 内运行 `npm install -g @anthropic-ai/claude-code --include=optional`

## 致谢与声明

- 本项目基于 `termux/termux-app` 修改，不是 Termux 官方版本。
- Claude / Claude Code 为 Anthropic 产品，本项目不隶属 Anthropic。
- License：见 `LICENSE.md`（GPLv3-only）与上游各模块例外条款。
