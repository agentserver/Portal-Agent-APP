# Android MCP Mecha Agent 开发说明

## 1. 项目目标

构建一套 Android 本地执行机制：

```text
上层 Agent / 远程控制端
  → AgentServer
  → Termux / proot Linux 中的 Claude Code
  → MCP Client
  → Android MCP Server App
  → Android SDK / 系统服务
  → 手机原生能力
```

目标：让 Claude Code 在 Android 手机上通过 MCP 调用原生能力，完成拍照、截图、UI 感知、点击、输入、打开 App、读取状态等任务。

项目定位：**Claude Code 驾驶 Android 执行体；AgentServer 提供上层调度和远程连接。**

---

## 2. 总体架构

```text
┌──────────────────────────────────────────────┐
│ 上层 Agent / 浏览器 / 远程控制端               │
│ - 下发任务                                    │
│ - 查看执行状态                                │
│ - 接收结果                                    │
└───────────────────────┬──────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────┐
│ AgentServer                                   │
│ - 远程访问入口                                │
│ - 本地 Agent 注册                             │
│ - WebSocket tunnel                            │
│ - 会话管理                                    │
│ - 上下游消息中转                              │
└───────────────────────┬──────────────────────┘
                        │ WebSocket tunnel
                        ▼
┌──────────────────────────────────────────────┐
│ Termux / proot Linux                          │
│                                              │
│  agentserver connect                          │
│  Claude Code                                  │
│    - 接收上层任务                              │
│    - 规划执行步骤                              │
│    - 调用 MCP 工具                             │
│    - 读取工具返回结果                          │
│    - 向上层返回执行报告                        │
│                                              │
│  MCP Client                                   │
└───────────────────────┬──────────────────────┘
                        │ MCP over localhost HTTP
                        ▼
┌──────────────────────────────────────────────┐
│ Android MCP Server App                        │
│ - Tool Registry                               │
│ - Permission Gate                             │
│ - Audit Log                                   │
│ - User Confirmation UI                        │
│ - Android Capability Adapters                 │
└───────────────────────┬──────────────────────┘
                        │ Android SDK / System Service
                        ▼
┌──────────────────────────────────────────────┐
│ Android 原生能力                              │
│ - CameraX / Camera2                           │
│ - MediaProjection                             │
│ - AccessibilityService                        │
│ - NotificationListenerService                 │
│ - Location API                                │
│ - SensorManager                               │
│ - MediaStore / File API                       │
│ - Intent API                                  │
└──────────────────────────────────────────────┘
```

---

## 3. 组件职责

| 组件 | 职责 |
|---|---|
| 上层 Agent / 远程控制端 | 下发任务、查看进度、接收结果 |
| AgentServer | 连接上层与本地 Claude Code，提供 tunnel、注册、会话管理 |
| Termux / proot Linux | 承载 Claude Code、agentserver connect、脚本、MCP client |
| Claude Code | 本地执行 Agent，负责规划、调用工具、验证结果、汇报 |
| Android MCP Server App | 将 Android 原生能力封装为 MCP tools |
| Android SDK / 系统服务 | 执行拍照、截图、点击、输入、通知、定位等动作 |

---

## 4. AgentServer 加入后的机制变化

### 4.1 新增上层调度链路

原链路：

```text
用户 → Claude Code → Android MCP Server App → Android SDK
```

加入 AgentServer 后：

```text
上层 Agent / 浏览器
  → AgentServer
  → Termux 本地 Agent 连接
  → Claude Code
  → Android MCP Server App
  → Android SDK
```

AgentServer 增加了远程入口和上层调度能力。Android 能力桥接层不变。

---

### 4.2 新增安全边界

新增风险面：

```text
- AgentServer 注册凭据
- WebSocket tunnel
- 远程任务注入
- 多用户访问
- 上层 Agent 下发高风险任务
- 本地 Claude Code 对 Android MCP tools 的间接调用
```

强制要求：

```text
1. Android MCP Server 默认只监听 127.0.0.1
2. AgentServer 不直接访问 Android MCP Server
3. 只有本地 Claude Code 可以调用 Android MCP tools
4. 高风险 Android tools 默认 ask
5. 所有 AgentServer 下发任务写入本地执行日志
6. 所有 MCP tool call 写入 Android App 审计日志
7. 上层任务必须包含 allowed_tools 与 stop_condition
```

---

### 4.3 新增任务协议

上层 Agent 向本地 Claude Code 下发任务时，使用结构化任务描述。

建议格式：

```yaml
task_id: "task-001"
objective: "拍一张照片并确认文件已保存"
allowed_tools:
  - android.get_status
  - camera.take_photo
  - file.check_exists
risk_level: "medium"
requires_user_confirmation: false
stop_condition:
  - "photo_saved == true"
  - "local_path returned"
report_required:
  - summary
  - tool_calls
  - artifacts
  - errors
```

本地 Claude Code 返回报告：

```yaml
task_id: "task-001"
status: "success"
summary: "已调用 camera.take_photo 并保存照片。"
tool_calls:
  - tool: android.get_status
    result: ok
  - tool: camera.take_photo
    result: ok
artifacts:
  - type: image
    path: "/storage/emulated/0/Android/data/<pkg>/files/photo_001.jpg"
errors: []
```

---

### 4.4 并发控制要求

AgentServer 可能带来多个上层任务。Android 执行体必须串行化高风险动作。

规则：

```text
1. 同一时间只允许一个 Android control task 执行
2. camera / screen / ui / input 类工具互斥
3. 新任务进入队列，不抢占当前任务
4. emergency_stop 可中断当前任务
5. Android App 维护当前 task_id
6. 每个 tool call 绑定 task_id
```

---

## 5. MCP Tool 分层

### 5.1 状态工具

```text
android.get_status()
permission.get_status()
app.get_current_activity()
task.get_last_result()
```

### 5.2 感知工具

```text
screen.capture()
ui.get_accessibility_tree()
notification.list()
location.get()
sensor.read()
file.list_recent()
```

### 5.3 执行动作工具

```text
camera.take_photo()
app.open(package_name)
ui.tap(x, y)
ui.click_text(text)
ui.input_text(text)
ui.swipe(x1, y1, x2, y2, duration_ms)
```

### 5.4 受限开发工具

```text
intent.send_template(template_id, args)
logcat.read_filtered(filter_id)
shell.run_allowlisted(command_id)
file.read_allowed(path)
file.write_allowed(path, content)
```

默认禁用任意代码执行、任意 shell、任意 Intent、任意 SDK 调用。

---

## 6. 第一版 MVP

### 6.1 MVP 目标

完成上层到手机原生能力的端到端链路：

```text
上层 Agent / 浏览器
  → AgentServer
  → Termux Claude Code
  → MCP camera.take_photo
  → Android CameraX
  → 返回图片路径
  → Claude Code 汇报结果
```

---

### 6.2 MVP 工具

第一版只实现以下工具：

```text
android.get_status()
camera.take_photo()
file.check_exists()
task.get_last_result()
```

第二步加入：

```text
screen.capture()
app.get_current_activity()
app.open(package_name)
ui.get_accessibility_tree()
ui.tap(x, y)
ui.click_text(text)
ui.input_text(text)
```

---

### 6.3 MVP 验收标准

```text
1. Termux 中 agentserver connect 能连接上层 AgentServer
2. Claude Code 能在 Termux / proot Linux 中运行
3. Claude Code 能识别 Android MCP Server tools
4. android.get_status() 调用成功
5. camera.take_photo() 调用成功
6. Android App 使用 CameraX 保存照片
7. MCP 返回 JSON 结果
8. Claude Code 向上层返回任务报告
9. Android App 审计日志记录本次 tool call
10. 上层任务与本地 tool call 通过 task_id 关联
```

---

## 7. Android MCP Server App 模块

建议目录：

```text
app/
  server/
    McpHttpServer.kt
    McpRouter.kt
    ToolRegistry.kt
    ToolCallHandler.kt

  security/
    PermissionGate.kt
    TokenAuth.kt
    ToolPolicy.kt
    AuditLogger.kt
    TaskLock.kt

  protocol/
    ToolCallRequest.kt
    ToolCallResponse.kt
    ToolSchema.kt
    ErrorCode.kt
    TaskContext.kt

  tools/
    AndroidStatusTools.kt
    CameraTools.kt
    FileTools.kt
    ScreenTools.kt
    UiTools.kt
    AppTools.kt
    NotificationTools.kt
    LocationTools.kt
    SensorTools.kt

  adapters/
    CameraAdapter.kt
    FileAdapter.kt
    MediaProjectionAdapter.kt
    AccessibilityAdapter.kt
    IntentAdapter.kt
    NotificationAdapter.kt
    LocationAdapter.kt
    SensorAdapter.kt

  ui/
    MainActivity.kt
    PermissionActivity.kt
    ConfirmActionDialog.kt
    AuditLogActivity.kt
```

---

## 8. Tool Schema 草案

### 8.1 android.get_status

输入：

```json
{}
```

输出：

```json
{
  "ok": true,
  "server_version": "0.1.0",
  "device": "android",
  "enabled_tools": [
    "android.get_status",
    "camera.take_photo",
    "file.check_exists"
  ],
  "permissions": {
    "camera": true,
    "accessibility": false,
    "media_projection": false,
    "notification_listener": false,
    "location": false
  },
  "current_task_id": null
}
```

---

### 8.2 camera.take_photo

输入：

```json
{
  "task_id": "task-001",
  "camera": "back",
  "flash": "auto",
  "resolution": "default",
  "return_type": "path"
}
```

输出：

```json
{
  "ok": true,
  "task_id": "task-001",
  "uri": "content://media/external/images/media/123",
  "local_path": "/storage/emulated/0/Android/data/<pkg>/files/photo_001.jpg",
  "width": 3024,
  "height": 4032,
  "camera": "back",
  "timestamp": 1777548120
}
```

失败输出：

```json
{
  "ok": false,
  "task_id": "task-001",
  "error_code": "CAMERA_PERMISSION_DENIED",
  "message": "Camera permission is not granted."
}
```

---

### 8.3 file.check_exists

输入：

```json
{
  "task_id": "task-001",
  "path": "/storage/emulated/0/Android/data/<pkg>/files/photo_001.jpg"
}
```

输出：

```json
{
  "ok": true,
  "task_id": "task-001",
  "exists": true,
  "size_bytes": 1842230
}
```

---

### 8.4 screen.capture

输入：

```json
{
  "task_id": "task-002",
  "return_type": "path",
  "max_width": 1080
}
```

输出：

```json
{
  "ok": true,
  "task_id": "task-002",
  "image_path": "/storage/emulated/0/Android/data/<pkg>/files/screens/screen_001.png",
  "width": 1080,
  "height": 2400,
  "timestamp": 1777548121
}
```

---

### 8.5 ui.get_accessibility_tree

输入：

```json
{
  "task_id": "task-003",
  "include_bounds": true,
  "include_text": true,
  "include_clickable": true
}
```

输出：

```json
{
  "ok": true,
  "task_id": "task-003",
  "package": "com.example.app",
  "activity": "com.example.app.MainActivity",
  "nodes": [
    {
      "id": "node_1",
      "text": "确定",
      "content_description": null,
      "bounds": [100, 1800, 980, 1920],
      "clickable": true,
      "enabled": true
    }
  ]
}
```

---

### 8.6 ui.click_text

输入：

```json
{
  "task_id": "task-003",
  "text": "确定",
  "match_mode": "exact"
}
```

输出：

```json
{
  "ok": true,
  "task_id": "task-003",
  "matched_node": {
    "text": "确定",
    "bounds": [100, 1800, 980, 1920]
  }
}
```

---

## 9. Termux / AgentServer / Claude Code 启动链路

### 9.1 首次注册本地 Agent

```bash
agentserver connect \
  --server <AGENTSERVER_URL> \
  --code <REGISTRATION_CODE> \
  --name "android-phone-agent"
```

### 9.2 后续自动重连

```bash
agentserver connect
```

### 9.3 Claude Code MCP 注册

```bash
claude mcp add --transport http android-phone http://127.0.0.1:8765/mcp
```

### 9.4 检查 MCP

```bash
claude mcp list
```

Claude Code 内部检查：

```text
/mcp
```

---

## 10. 安全策略

### 10.1 网络边界

```text
- Android MCP Server 默认绑定 127.0.0.1
- 禁止默认监听 0.0.0.0
- 局域网调试必须开启 token
- AgentServer tunnel 只连接 Claude Code 层
- 上层 Agent 不直接连接 Android MCP Server
```

---

### 10.2 工具权限

| 风险等级 | 工具 | 默认策略 |
|---|---|---|
| 低 | android.get_status, file.check_exists | allow |
| 中 | camera.take_photo, screen.capture, location.get | ask |
| 高 | ui.tap, ui.input_text, notification.list | ask + audit |
| 极高 | send_message, delete_file, install_apk | deny |

---

### 10.3 禁止项

默认禁止：

```text
run_shell(command)
execute_kotlin(code)
call_android_sdk(method, args)
send_raw_intent(intent)
read_all_files()
delete_arbitrary_file(path)
send_message_without_confirm()
make_payment()
unlock_screen()
record_audio_silently()
take_photo_silently()
```

---

## 11. 开发路线图

### Phase 0：AgentServer 链路验证

任务：

```text
1. Termux / proot Linux 安装 agentserver
2. 使用 registration code 完成本地 Agent 注册
3. 验证 agentserver connect 自动重连
4. 确认上层可以看到 android-phone-agent
5. 确认上层任务能到达本地 Claude Code
```

验收：

```text
上层 Agent 可以向 Termux 中的 Claude Code 下发文本任务，并收到文本回复。
```

---

### Phase 1：Android MCP Server 空壳

任务：

```text
1. Android App 启动本地 HTTP MCP Server
2. 实现 tools/list
3. 实现 android.get_status()
4. 实现基础 token 校验
5. 实现审计日志
```

验收：

```text
Claude Code 能通过 MCP 调用 android.get_status()。
```

---

### Phase 2：CameraX 拍照闭环

任务：

```text
1. 集成 CameraX
2. 实现 camera.take_photo()
3. 保存图片到 App 私有目录
4. 返回 local_path、uri、width、height、timestamp
5. 实现 file.check_exists()
6. 将 task_id 写入审计日志
```

验收：

```text
上层 Agent 下发“拍照”任务，本地 Claude Code 调用 camera.take_photo()，Android App 保存图片，Claude Code 返回结果报告。
```

---

### Phase 3：屏幕感知

任务：

```text
1. 集成 MediaProjection
2. 实现 screen.capture()
3. 返回截图路径和元数据
4. 处理用户授权状态
```

验收：

```text
Claude Code 能获取当前屏幕截图，并向上层返回截图路径。
```

---

### Phase 4：UI 自动化

任务：

```text
1. 集成 AccessibilityService
2. 实现 ui.get_accessibility_tree()
3. 实现 ui.tap()
4. 实现 ui.click_text()
5. 实现 ui.input_text()
6. 实现 ui.swipe()
```

验收：

```text
Claude Code 能完成：打开 App → 获取 UI 树 → 点击指定文本 → 验证页面变化。
```

---

### Phase 5：任务队列与并发控制

任务：

```text
1. Android App 增加 TaskLock
2. 每个 tool call 绑定 task_id
3. 高风险工具串行执行
4. 增加 emergency_stop
5. 增加 task.get_last_result()
```

验收：

```text
多个上层任务不会同时争用 camera、screen、ui、input 工具。
```

---

## 12. 端到端 Demo

### Demo 1：远程拍照

输入任务：

```text
拍一张后置摄像头照片，确认文件存在，并返回保存路径。
```

预期流程：

```text
上层 Agent
  → AgentServer
  → Claude Code
  → android.get_status()
  → camera.take_photo(camera="back")
  → file.check_exists(path)
  → 返回报告
```

预期报告：

```yaml
status: success
summary: "照片已保存。"
artifacts:
  - type: image
    path: "/storage/emulated/0/Android/data/<pkg>/files/photo_001.jpg"
tool_calls:
  - android.get_status: ok
  - camera.take_photo: ok
  - file.check_exists: ok
```

---

### Demo 2：打开 App 并点击按钮

输入任务：

```text
打开目标 App，点击文本为“确定”的按钮，并确认页面发生变化。
```

预期流程：

```text
app.open(package_name)
app.get_current_activity()
ui.get_accessibility_tree()
ui.click_text("确定")
ui.get_accessibility_tree()
返回报告
```

---

## 13. 失败模式检查表

```text
[ ] AgentServer 能连接，但 Claude Code 没有收到任务
[ ] Claude Code 收到任务，但无法访问 MCP tools
[ ] MCP tools 可见，但 Android App 没有权限
[ ] camera.take_photo 返回成功，但文件不存在
[ ] 上层任务没有 task_id，无法关联审计日志
[ ] 多个任务同时调用相机或 UI 工具
[ ] Android MCP Server 误监听 0.0.0.0
[ ] 高风险工具没有 ask / audit
[ ] Claude Code 尝试调用未授权工具
[ ] 上层 Agent 下发了超出 allowed_tools 的任务
```

---

## 14. 第一版交付物

```text
1. Android MCP Server App
2. Termux / proot Linux 环境说明
3. agentserver connect 配置说明
4. Claude Code MCP 配置说明
5. android.get_status 工具
6. camera.take_photo 工具
7. file.check_exists 工具
8. 审计日志模块
9. task_id 贯穿链路
10. 远程拍照端到端 Demo
```

---

## 15. 当前开发优先级

优先级从高到低：

```text
P0: AgentServer → Termux Claude Code 文本任务链路
P0: Claude Code → Android MCP Server 连接
P1: android.get_status()
P1: camera.take_photo()
P1: file.check_exists()
P1: 审计日志
P2: screen.capture()
P2: ui.get_accessibility_tree()
P2: ui.click_text()
P3: notification / location / sensor / file 扩展
```

当前只实现远程拍照闭环。完成后再扩展屏幕感知和 UI 自动化。

