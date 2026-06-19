# Android MCP 机甲驾驶 — 实施路线图

> 参考设计：`android_mcp_claude_mecha_design.md`
> 最后更新：2026-04-30（proot 验证结论已更新）

---

## 当前状态

| 组件 | 状态 | 说明 |
|------|------|------|
| Termux + proot Ubuntu 自动安装 | ✅ 完成 | AutoUbuntuManager |
| Claude Code 自动部署 | ✅ 完成 | AutoClaudeManager |
| agentserver claudecode 后台连接 | ✅ 完成 | AgentServerFragment |
| ApiHttpBridgeServer :17681（只读） | ✅ 完成 | 5 个 GET 端点 |
| Android MCP Server | ❌ 未开始 | 核心新增工作 |
| CameraX 拍照 | ❌ 未开始 | |
| MediaProjection 截图 | ❌ 未开始 | |
| AccessibilityService UI 控制 | ❌ 未开始 | |
| Claude Code MCP 自动配置 | ❌ 未开始 | |

---

## 已验证的前置问题

### ~~P0：验证 Claude Code 是否可在 Termux 原生（无 proot）运行~~ — 已验证，结论确定

**验证过程（2026-04-30 实测）：**
```bash
pkg install nodejs          # ✅ nodejs 25.8.2 + npm 11.12.1 安装成功
npm install -g @anthropic-ai/claude-code  # ✅ 包安装成功
claude --version            # ❌ Error: claude native binary not installed.
node install.cjs            # ❌ Unsupported platform: android arm64
                            #    Supported: linux-arm64, linux-arm64-musl ...（无 android）
```

**结论：proot + Ubuntu 必须保留。**

原因：Claude Code 的原生二进制明确将 `android` 平台排除在支持列表之外（`process.platform` 在 Termux Node.js 下返回 `android` 而非 `linux`）。即使强制下载 `linux-arm64` 二进制，其动态链接 glibc 的特性也会导致在 Termux bionic 环境下崩溃。这是 Anthropic 的刻意设计，无法绕过。

**架构影响：**
- proot + Ubuntu 层保留，不做精简
- MCP 工具产生的文件必须写到 Termux HOME（`/data/data/com.termux/files/home/`），该路径在 proot 内可见（`/root/` 或 `/home/claude/`）
- 或 MCP 工具直接返回 base64 数据，完全绕过跨层文件路径问题

---

## proot 保留时的文件路径规则（如果无法去掉）

```
Android App（MCP Server）可写路径：
  getFilesDir()         → /data/data/com.termux/files/       （proot 内可见为 /）
  Termux HOME 等价路径  → /data/data/com.termux/files/home/  （proot 内 = /root/ 或 /home/claude/）

规则：所有 MCP 工具产生的文件必须写到 Termux HOME 路径，而非外部存储。
返回给 Claude 的路径：使用 proot 内的路径（/root/photos/xxx.jpg）
或：MCP 响应直接返回 base64，完全绕过路径问题。
```

---

## 开发阶段

### Phase 1 — Android MCP Server 骨架

目标：Claude Code 能通过 MCP 调用 `android.get_status()`

任务：
- [ ] 新增 `McpHttpServer.java`，监听 `127.0.0.1:8765`
- [ ] 实现 MCP Streamable HTTP 协议：`POST /mcp`（JSON-RPC 2.0）
- [ ] 实现 `initialize` 握手、`tools/list`、`tools/call` 分发
- [ ] 实现 `android.get_status()` 工具（返回权限状态、已启用工具列表）
- [ ] 实现基础 AuditLogger（追加写文件，记录每次 tool call）
- [ ] AutoClaudeManager 安装完成后自动运行：
  `claude mcp add --transport http android-mcp http://127.0.0.1:8765/mcp`

验收：`claude mcp list` 能看到 android-mcp；`/mcp` 斜杠命令能列出工具。

---

### Phase 2 — CameraX 拍照闭环

目标：上层下发"拍照"任务 → Claude 调 MCP → Android 拍照 → 返回结果

任务：
- [ ] 集成 CameraX（ImageCapture，无需预览 UI）
- [ ] 实现 `camera.take_photo(camera, flash, return_type)`
  - `return_type=base64`：图片内容直接在响应里（推荐，无路径问题）
  - `return_type=path`：写到 Termux HOME，返回 proot 内可见路径
- [ ] 实现 `file.check_exists(path)`
- [ ] tool call 绑定 task_id，写入审计日志

验收：上层下发拍照任务，Claude Code 调 MCP，Android 拍照，Claude 返回任务报告。

---

### Phase 3 — MediaProjection 截图

目标：Claude 能"看到"当前手机屏幕

任务：
- [ ] 新增 `ScreenCaptureService`（前台 Service + 持久通知，避免每次重授权）
- [ ] 首次请求时弹 MediaProjection 授权对话框，之后 Session 保活
- [ ] 实现 `screen.capture(max_width, return_type)`
  - 压缩为 JPEG，`return_type=base64` 返回（避免路径问题）
- [ ] App UI 增加"截图权限"状态显示

验收：Claude Code 调用 `screen.capture()`，能在响应中分析图片内容。

---

### Phase 4 — AccessibilityService UI 控制

目标：Claude 能操作手机界面

任务：
- [ ] 声明并实现 `AndroidMcpAccessibilityService`
- [ ] App 启动时检测 Accessibility 是否启用，未启用时引导用户跳转设置页
- [ ] 实现 `ui.get_accessibility_tree(include_bounds, include_text, include_clickable)`
- [ ] 实现 `ui.tap(x, y)`
- [ ] 实现 `ui.click_text(text, match_mode)`
- [ ] 实现 `ui.input_text(text)`（向焦点输入框注入）
- [ ] 实现 `ui.swipe(x1, y1, x2, y2, duration_ms)`
- [ ] 实现 `app.open(package_name)`（Intent 启动）
- [ ] 实现 `app.get_current_activity()`

验收：Claude Code 能完成：打开 App → 获取 UI 树 → 点击指定元素 → 验证变化。

---

### Phase 5 — 任务队列与并发控制

目标：多个上层任务不会同时争用 camera / screen / ui 工具

任务：
- [ ] 实现 `TaskLock`（ReentrantLock，串行执行高风险工具）
- [ ] 每个 tool call 必须携带 task_id，写入审计日志
- [ ] 实现 `task.get_last_result()`
- [ ] 实现 `emergency_stop`（中断当前正在执行的 tool call）
- [ ] `android.get_status()` 返回 `current_task_id`

验收：并发两个拍照任务，第二个进入等待队列，不抢占第一个。

---

## MCP 工具总表

### 状态工具（低风险，默认 allow）
| 工具 | 说明 |
|------|------|
| `android.get_status()` | 设备状态、权限状态、已启用工具 |
| `app.get_current_activity()` | 当前前台 Activity 包名 |
| `task.get_last_result()` | 上次 tool call 结果摘要 |

### 感知工具（中风险，默认 ask）
| 工具 | 说明 |
|------|------|
| `screen.capture()` | 截图（base64 或路径） |
| `ui.get_accessibility_tree()` | 当前屏幕 UI 节点树 |
| `camera.take_photo()` | 拍照（base64 或路径） |
| `sensor.read()` | 传感器数据 |
| `location.get()` | 当前位置 |

### 执行工具（高风险，ask + audit）
| 工具 | 说明 |
|------|------|
| `app.open(package_name)` | 打开指定 App |
| `ui.tap(x, y)` | 坐标点击 |
| `ui.click_text(text)` | 按文字查找并点击 |
| `ui.input_text(text)` | 向焦点框注入文字 |
| `ui.swipe(x1,y1,x2,y2,duration_ms)` | 滑动 |

### 默认禁止（不实现或硬编码拒绝）
```
run_shell(arbitrary_command)
send_message_without_confirm()
make_payment()
delete_arbitrary_file()
record_audio_silently()
take_photo_silently()
```

---

## 安全约束（硬性要求）

1. MCP Server 只绑定 `127.0.0.1`，不监听 `0.0.0.0`
2. AgentServer 不直接访问 MCP Server；只有本地 Claude Code 可以调用
3. 所有 tool call 写入审计日志（工具名、task_id、参数摘要、时间戳、结果状态）
4. 高风险工具（ui.\*）执行时 Android App 展示通知或 Toast，用户可感知

---

## 端口规划

| 端口 | 用途 |
|------|------|
| 17681 | ApiHttpBridgeServer（现有，只读 GET 端点，保留） |
| 8765 | Android MCP Server（新增） |
