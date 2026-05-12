# Claude Code Android App — 架构图

## 总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Android App (com.termux)                           │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                         TermuxActivity                               │  │
│  │  ┌───────────────┐  ┌───────────────┐  ┌───────────────────────┐   │  │
│  │  │ HomeFragment  │  │ApiKeyFragment │  │ AgentServerFragment   │   │  │
│  │  │  (Chat UI)    │  │  (Key 管理)   │  │ (AgentServer 配置)    │   │  │
│  │  └──────┬────────┘  └───────────────┘  └───────────────────────┘   │  │
│  │         │                                                            │  │
│  │  ┌──────▼──────────────────────────────────────────────────────┐   │  │
│  │  │               AutoTaskCoordinator（启动协调器）               │   │  │
│  │  │  初始化并串联所有后台管理器与服务器                             │   │  │
│  │  └──────┬──────────────────┬──────────────────┬───────────────┘   │  │
│  │         │                  │                  │                    │  │
│  └─────────┼──────────────────┼──────────────────┼────────────────────┘  │
│            │                  │                  │                         │
│     ┌──────▼──────┐   ┌───────▼──────┐   ┌──────▼──────┐                │
│     │ AutoUbuntu  │   │  McpHttp     │   │ ApiHttpBridge│                │
│     │  Manager   │   │   Server     │   │   Server     │                │
│     │(Ubuntu安装) │   │ port: 8765   │   │ port: 17681  │                │
│     └──────┬──────┘   └───────┬──────┘   └──────┬──────┘                │
│            │                  │                  │                         │
│     ┌──────▼──────┐           │           ┌──────▼─────────────────────┐ │
│     │ AutoClaude  │           │           │  Android API 桥接端点        │ │
│     │  Manager   │           │           │  GET /battery  → 电池状态    │ │
│     │(Claude安装) │           │           │  GET /camera   → 摄像头信息  │ │
│     └──────┬──────┘           │           │  GET /sensors  → 传感器列表  │ │
│            │                  │           │  GET /wifi     → WiFi信息    │ │
│     ┌──────▼──────┐           │           │  GET /clipboard→ 剪贴板内容  │ │
│     │  AutoAgent  │           │           └────────────────────────────┘ │
│     │ServerManager│           │                                           │
│     │(AgentServer │           │                                           │
│     │    安装)    │           │                                           │
│     └─────────────┘           │                                           │
│                                │                                           │
└────────────────────────────────┼───────────────────────────────────────────┘
                                 │
              ┌──────────────────┼──────────────────────────────┐
              │         MCP Tools (14个工具)                     │
              │                                                  │
              │  ┌─────────────────────┐  ┌─────────────────┐  │
              │  │    UI 控制工具       │  │   文件工具       │  │
              │  │  ui.tap             │  │  file.read       │  │
              │  │  ui.swipe           │  │  file.list       │  │
              │  │  ui.click_text      │  │  file.check_     │  │
              │  │  ui.input_text      │  │      exists      │  │
              │  │  ui.get_            │  └─────────────────┘  │
              │  │    accessibility_   │  ┌─────────────────┐  │
              │  │    tree             │  │   屏幕与摄像头   │  │
              │  └─────────────────────┘  │  screen.capture  │  │
              │  ┌─────────────────────┐  │  camera.take_    │  │
              │  │    App 控制工具      │  │      photo       │  │
              │  │  app.open           │  └─────────────────┘  │
              │  │  app.get_current_   │  ┌─────────────────┐  │
              │  │      activity       │  │   状态查询       │  │
              │  └─────────────────────┘  │  android.get_    │  │
              │                           │      status      │  │
              │                           └─────────────────┘  │
              └──────────────────────────────────────────────────┘
```

---

## 核心数据流

### 1. 用户发送消息（Chat UI → Claude Code）

```
用户输入文字 / 附件
        │
        ▼
HomeFragment.sendOrConfirm()
        │
        │  可选：附件文件经 proot-distro --bind 复制进 Ubuntu rootfs
        │        /root/uploads/<文件名>
        │
        ▼
ProcessBuilder 启动子进程：
  bash proot-distro login ubuntu -- sh -c
    'printf "%s" "<prompt>" |
     ANTHROPIC_API_KEY="..." claude -p
     --output-format stream-json --verbose
     [--continue | --resume <session_id>]'
        │
        │  stdout: JSONL 逐行输出
        ▼
后台线程解析 JSONL：
  type=system/init  → 显示工作区 + 模型信息
  type=assistant    → 更新气泡（流式累积快照）
  type=result       → 捕获 session_id，存入历史记录
        │
        ▼
主线程更新 RecyclerView（ChatAdapter）
```

### 2. Claude Code 调用 MCP 工具

```
Claude Code（Ubuntu proot 内）
        │  HTTP POST http://127.0.0.1:8765/mcp
        │  Body: JSON-RPC 2.0 { method: "tools/call", params: { name, arguments } }
        ▼
McpHttpServer（Android 进程，port 8765）
        │
        ├─ ui.tap / ui.swipe / ui.click_text / ui.input_text
        │        │
        │        ▼
        │   McpAccessibilityService
        │   （Accessibility Service 实例）
        │   • dispatchGesture → 触控注入
        │   • getRootInActiveWindow → 遍历 UI 树
        │   • ACTION_SET_TEXT → 文字输入
        │
        ├─ ui.get_accessibility_tree
        │        │
        │        ▼
        │   UiTreeTool → 遍历 AccessibilityNodeInfo，返回 XML/JSON 树
        │
        ├─ screen.capture
        │        │
        │        ▼
        │   ScreenCaptureService（MediaProjection）
        │   → 截图 Bitmap → base64 JPEG
        │
        ├─ camera.take_photo
        │        │
        │        ▼
        │   CameraX（ProcessLifecycleOwner）
        │   → 拍照 → base64 JPEG
        │
        ├─ file.read / file.list / file.check_exists
        │        │
        │        ▼
        │   FileTool → 直接读取 Android 文件系统
        │
        ├─ app.open / app.get_current_activity
        │        │
        │        ▼
        │   AppTool → Intent 启动 / AccessibilityService 查询当前 Activity
        │
        └─ android.get_status
                 │
                 ▼
            AndroidStatusTool → 返回权限状态 + 工具列表
```

### 3. Claude Code 获取 Android 传感器数据

```
Claude Code（Ubuntu proot 内）
        │  curl http://127.0.0.1:17681/battery
        │  curl http://127.0.0.1:17681/wifi
        │  ...（其余端点）
        ▼
ApiHttpBridgeServer（Android 进程，port 17681）
        │  调用 Termux-API Java 接口（BatteryStatusAPI、WifiAPI 等）
        │  绕过 bionic/glibc 不兼容问题（termux-* 二进制不能在 proot 内执行）
        ▼
返回兼容 termux-api 格式的 JSON
```

---

## 文件附件流程

```
用户点击 📎 → 系统文件选择器（ACTION_GET_CONTENT）
        │
        ▼
onActivityResult → copyFileAsync(uri)
        │
        │ Step 1：URI → App cache（Java FileOutputStream）
        │         /data/data/com.termux/cache/upload_src
        │
        ▼
proot-distro login ubuntu
  --bind <cache>/upload_src:/tmp/.upload_src
  -- sh -c "mkdir -p ~/uploads && cp /tmp/.upload_src ~/uploads/<name>
            && echo ~/uploads/<name>"
        │
        │  ~/uploads/ = /root/uploads/（proot root 用户的 home）
        │  与 claude -p 运行环境一致，在受信目录树内
        │
        ▼
回显路径存入 mAttachmentPath（如 /root/uploads/photo.png）
        │
        ▼
发送时拼入 prompt：
  "[附件: /root/uploads/photo.png]\n<用户文字>"
        │
        ▼
Claude Code 用 Read 工具直接读取该路径
```

---

## 自动安装流程（首次运行）

```
TermuxActivity 启动
        │
        ▼
AutoTaskCoordinator
        ├─ AutoClaudeManager   → 写 ~/.claude-inner-setup.sh（后台）
        ├─ AutoAgentServerManager → 从 assets 提取 agentserver tar.gz（后台）
        └─ AutoUbuntuManager
                │
                ▼
           检查 proot-distro 是否已安装 ubuntu
                │
          ┌─────┴─────┐
        已安装        未安装
          │              │
          │              ▼
          │        安装 ubuntu rootfs
          │        （优先使用 assets 内置，否则从 CDN 下载）
          │              │
          └──────┬───────┘
                 ▼
          注入 Claude 安装脚本到 /root/.bashrc（hook 一次性执行）
          注入 AgentServer 安装脚本
                 │
                 ▼
          用户首次登录 ubuntu → .bashrc 触发：
            1. 安装 Claude Code（npm install -g @anthropic-ai/claude-code）
            2. 安装 AgentServer
            3. 注册 MCP 服务端（claude mcp add android-mcp http://127.0.0.1:8765/mcp）
            4. 自我清除 hook
```

---

## 权限依赖关系

| 功能 | 所需权限 |
|------|---------|
| ui.tap / ui.swipe / ui.click_text / ui.input_text / ui.get_accessibility_tree | Android 无障碍服务（Accessibility Service） |
| screen.capture | MediaProjection（用户在 Home 页点击"授权截图"） |
| camera.take_photo | CAMERA 权限 |
| file.read / file.list | 无（读取 app 自身数据目录） |
| app.open | 无 |
| /battery /wifi /sensors /clipboard | 由 ApiHttpBridgeServer 在 Android 进程内处理，无需额外权限 |

---

## 关键端口

| 端口 | 用途 |
|------|------|
| **8765** | MCP Streamable HTTP Server（Claude Code 注册的 MCP 工具入口） |
| **17681** | Android API HTTP 桥（传感器、电池、WiFi、剪贴板等只读数据） |
