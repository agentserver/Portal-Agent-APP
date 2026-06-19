# Claude Code Android App — 架构图

> 本文档反映当前代码状态（最近更新：见 git log）。架构以 **Termux** 为底，叠加 **Ubuntu proot** 容器跑 Claude Code，并通过 **MCP HTTP 服务**和 **API HTTP 桥**让 Claude Code 直接调用 Android 系统能力。

---

## 1. 总览

```
┌────────────────────────────────────────────────────────────────────────────┐
│                            Android App (com.termux)                        │
│                                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                          TermuxActivity                             │  │
│  │                                                                     │  │
│  │  ┌─────────────────────────────────────────────────────────────┐    │  │
│  │  │  Fragment 容器（home_fragment_container, 单 FrameLayout）   │    │  │
│  │  │  ├ HomeFragment             ← 主聊天 UI                     │    │  │
│  │  │  ├ ApiKeyFragment           ← API Key 管理                  │    │  │
│  │  │  ├ AgentServerFragment      ← AgentServer 配置/连接         │    │  │
│  │  │  └ AgentTaskDetailFragment  ← AgentServer 任务详情（全屏）  │    │  │
│  │  │                                                             │    │  │
│  │  │  Activity show*Mode() 方法负责 Fragment add/show/hide/replace │  │
│  │  └─────────────────────────────────────────────────────────────┘    │  │
│  │                                                                     │  │
│  │  ┌─────────────────────────────────────────────────────────────┐    │  │
│  │  │              AutoTaskCoordinator（启动协调器）                │    │  │
│  │  │  串联各后台 Manager / 自检 / HTTP 服务，幂等启动              │    │  │
│  │  └────────┬──────────────────────────┬────────────────────────┘    │  │
│  │           │                          │                              │  │
│  └───────────┼──────────────────────────┼──────────────────────────────┘  │
│              │                          │                                 │
│   ┌──────────┴──────────────┐  ┌────────┴───────────┐                     │
│   │  自动安装 / 维护链路    │  │ HTTP 服务 / 桥接   │                     │
│   │                         │  │                    │                     │
│   │  AutoUbuntuManager      │  │  McpHttpServer     │  ← port 8765        │
│   │  ├ UbuntuSnapshotMgr   │  │  (Streamable HTTP) │                     │
│   │  └ injectClaudeWrapper │  │                    │                     │
│   │  AutoClaudeManager      │  │  ApiHttpBridgeSrv  │  ← port 17681       │
│   │  AutoAgentServerManager │  │  /battery /wifi    │                     │
│   │  ApiSelfCheckManager    │  │  /sensors /camera  │                     │
│   │  CapabilitiesManager    │  │  /clipboard        │                     │
│   └─────────────────────────┘  └────────────────────┘                     │
│                                                                            │
│   ┌─────────────────────────────────────────────────────────────────┐     │
│   │            持久化层（SharedPreferences / 私有文件）              │     │
│   │  SessionStore       ← 历史对话元数据（ID + 预览 + 时间）         │     │
│   │  UploadStore        ← 上传附件追踪（绑定 sessionId，方便清理）   │     │
│   │  AgentTaskStore     ← AgentServer 任务（prompt + 消息流 + 状态） │     │
│   │  ApiKeyStore        ← API key + base URL（支持第三方端点）      │     │
│   │  agent-tasks/<id>.jsonl  ← 每任务 pipe 归档（getFilesDir）       │     │
│   └─────────────────────────────────────────────────────────────────┘     │
│                                                                            │
│   ┌─────────────────────────────────────────────────────────────────┐     │
│   │                          后台 Service                            │     │
│   │  TermuxService              ← 终端 session（Termux 原生）        │     │
│   │  ScreenCaptureService       ← MediaProjection 截屏              │     │
│   │  McpAccessibilityService    ← UI 控制（tap/swipe/input）         │     │
│   │  FloatingStatusService      ← 后台悬浮窗（busy 时显示）          │     │
│   │  RunCommandService          ← TermuxRunCommand IPC（Termux 原生）│     │
│   └─────────────────────────────────────────────────────────────────┘     │
└────────────────────────────────────────────────────────────────────────────┘

       ┌─────────────────────────────────────────────────────────────┐
       │            MCP 工具 (android-mcp，13 个，HTTP 8765)           │
       │                                                             │
       │  UI 控制（5）                  截屏与摄像头（2）              │
       │  ├ ui.tap                      ├ screen.capture             │
       │  ├ ui.swipe                    └ camera.take_photo          │
       │  ├ ui.click_text                                            │
       │  ├ ui.input_text               文件（3）                     │
       │  └ ui.get_accessibility_tree   ├ file.read                  │
       │                                ├ file.list                  │
       │  App 控制（2）                  └ file.check_exists          │
       │  ├ app.open                                                 │
       │  └ app.get_current_activity    状态（1）                     │
       │                                └ android.get_status         │
       └─────────────────────────────────────────────────────────────┘
```

**关键路径常量**（HomeFragment.java:82）：

```
UBUNTU_CLAUDE_HOME = $PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu/home/claude
                         (Termux PREFIX = /data/data/com.termux/files/usr)
```

所有 Ubuntu 内的工作都以 **`claude` 用户**身份运行，HOME = `/home/claude`（不是 root）。

---

## 2. 主聊天数据流（HomeFragment Chat）

```
用户输入文字 / 附件
        │
        ▼
HomeFragment.sendOrConfirm()
        │
        │  附件已通过 copyFileAsync 提前复制进 ~/uploads/<name>
        │  发送时 prompt 拼成：[附件: ~/uploads/<name>]\n<文字>
        │
        ▼
ProcessBuilder 起子进程：
  bash proot-distro login --user claude ubuntu -- bash -c
    'CLAUDE_DIRECT=1 ANTHROPIC_API_KEY="..." [ANTHROPIC_BASE_URL="..."]
     printf "%s" "<prompt>" |
     claude -p --output-format stream-json --verbose
            --dangerously-skip-permissions
            [--continue | --resume <session_id>]'
        │
        │  CLAUDE_DIRECT=1 → 透传给 wrapper，让它直接 exec 真实 claude
        │  （wrapper 不会拦截这种调用，只拦截 agentserver 的）
        │
        │  stdout: JSONL 逐行输出
        ▼
后台线程 ClaudeProcess 读 stdout，逐行解析 JSON：
  type=system/init   → 显示工作区 + 模型信息
  type=assistant     → 流式更新最后一个 ASSISTANT 气泡（累积快照）
  type=result        → 提取 session_id；持久化到 SessionStore
        │
        ▼
mHandler.post → 主线程更新 ChatAdapter（item_msg_user/assistant/system 复合气泡）
        │
        ▼
完成后 mSessionStarted=true（下次 --continue 续上）
       FloatingStatusService.updateStatus("● 就绪", busy=false)
```

**主页底部按钮：**
- `打断`：杀当前 claude 子进程；mSessionStarted 不变 → 下条消息 `--continue`
- `新建对话`：杀子进程 + 清 mMessages + 清 mResumeSessionId/mCurrentSessionId

---

## 3. 侧栏抽屉（5 Tab，单 ViewFlipper）

```
┌──── tab_history ───── tab_memory ───── tab_skills ───── tab_agent_task ───── tab_uploads ────┐
│                                                                                              │
│  Panel 0：历史对话                                Panel 3：AgentServer 任务                  │
│  • SessionStore.loadAll()                        • AgentTaskListAdapter（列表）              │
│  • 列表项：时间 + prompt 预览                     • 列表项：时间 + 预览 + 运行/完成徽章       │
│  • 点击：resumeSession(entry)                     • 点击：showAgentTaskDetailMode(taskId)    │
│       1) 设 mResumeSessionId                      • 长按：deleteAgentTaskFile + Store delete │
│       2) 后台读 ~/.claude/projects/                                                           │
│          -home-claude/<id>.jsonl                  Panel 4：上传附件                          │
│       3) 解析 user/assistant/tool_use/            • UploadStore：sessionId → [filenames]      │
│          tool_result 还原成 ChatMessage           • __pending__ 桶：未关联 session 的临时文件 │
│       4) 注入 mMessages 显示                      • 长按：删除 Ubuntu 内文件 + Store          │
│  • 长按：删除 SessionStore + Claude jsonl         • 清空：全删 + 同步删 Ubuntu                │
│         + 关联的上传文件                                                                      │
│                                                                                              │
│  Panel 1：记忆库                                  Panel 2：技能（slash commands）            │
│  • 扫 ~/.claude/memory/*.md                       • 扫 ~/.claude/commands/*.md              │
│  • DrawerFileAdapter 显示                         • 同上 + ＋按钮新建技能                    │
│                                                                                              │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

**自洁机制**：HomeFragment.onViewCreated 启动时跑 `cleanupOrphanClaudeJsonl()` 后台线程，扫 `-home-claude/*.jsonl`，比对 SessionStore，删除孤儿。

---

## 4. AgentServer 任务流
AgentServer派发的任务以dangerously-skip-permissions 运行，不需人工确认。
### 4.1 数据通路

```
┌─ 上游 AgentServer 服务派发任务
│
▼
agentserver claudecode --server ... （Ubuntu 内长驻进程，由 AgentServerFragment 启动）
│
│  spawn 子进程：
│  /home/claude/.local/bin/claude --print
│         --output-format stream-json --input-format stream-json
│         --verbose --permission-mode bypassPermissions
│
▼  ★ 这个 claude 实际上是 wrapper 脚本（PATH 里 .local/bin 在 /usr/local/bin 之前）★
   wrapper 检测到 stream-json 输入模式，做：
        1) 写一行 {"type":"as_stream_session_start","ts":...} 到 ~/.agentserver-pipe.jsonl
        2) 把 stdin 每行 base64 包装写入 pipe（{"type":"as_stream_in","b64":"..."}）
        3) 执行真实 /usr/local/bin/claude 接管 stdin
        4) tee stdout 到 pipe（claude 的 JSONL 响应原样进 pipe）
│
▼
~/.agentserver-pipe.jsonl  ← 单一 append-only 文件，混合了 stdin + stdout 的事件流
│
│  HomeFragment.processNewPipeLines() 每 600ms 增量读取（持久化 offset）
│  按字节读取 + UTF-8 解码（避免 RandomAccessFile.readLine 的 Latin-1 bug）
│
▼
HomeFragment.handleAgentPipeLine(line)：
  as_stream_session_start  → finishActiveTaskIfAny()
  as_stream_in (b64)       → 解码 → 内层若是 user/text → startNewAgentTask(prompt)
  user (tool_result)       → appendToActiveTask(SYSTEM "📥 工具返回: <摘要>")
  assistant (text)         → appendToActiveTask(ASSISTANT text)
  assistant (thinking)     → appendToActiveTask(ASSISTANT 含 thinking)
  assistant (tool_use)     → appendToActiveTask(SYSTEM "📞 调用工具: <name>")
  result                   → finishActiveTaskIfAny()
│
│  每次 active task 变更 → mAgentTaskStore.upsert(task)
│  同时把原始 pipe 行写入 getFilesDir()/agent-tasks/<task_id>.jsonl
│
▼
侧栏「任务」Tab 列表条目自动刷新（运行中/已完成徽章）
点击 → AgentTaskDetailFragment 全屏显示完整对话
```

### 4.2 主 pipe 文件的清理

- AgentServerFragment.doConnect 每次重连时 `> $PIPE_FILE` 截断
- HomeFragment.processNewPipeLines 检测到 `file.length() < mAgentPipeOffset` 时自动重置 offset 并 finish 当前任务
- 任务真实数据全部归档在 **per-task 文件**里，主 pipe 仅作运行时缓冲

### 4.3 任务删除时的资源回收

```
长按列表项删除：
  AgentTaskStore.delete(id)  ← SharedPrefs 元数据
  + new File(getFilesDir/agent-tasks/<id>.jsonl).delete()  ← 释放 base64 截图等占用
```

---

## 5. MCP 工具调用链路（android-mcp）

```
Claude Code（Ubuntu proot 内）
        │  HTTP POST http://127.0.0.1:8765/mcp
        │  Body: JSON-RPC 2.0 { method: "tools/call", params: { name, arguments } }
        ▼
McpHttpServer（Android 进程，port 8765）
        │  路由到 McpTool.handle(args)
        │
        ├─ ui.tap / ui.swipe / ui.click_text / ui.input_text
        │      ▼  UiTool → McpAccessibilityService.dispatchGesture / setText
        │
        ├─ ui.get_accessibility_tree
        │      ▼  UiTreeTool → AccessibilityNodeInfo 树 → JSON
        │
        ├─ screen.capture
        │      ▼  ScreenCaptureTool → ScreenCaptureService（MediaProjection）→ base64 JPEG
        │
        ├─ camera.take_photo
        │      ▼  CameraTool → CameraX → base64 JPEG
        │
        ├─ file.read / file.list / file.check_exists
        │      ▼  FileTool → 直接文件 IO（Android 进程权限范围内）
        │
        ├─ app.open / app.get_current_activity
        │      ▼  AppTool → Intent / AccessibilityService 查询
        │
        └─ android.get_status
               ▼  AndroidStatusTool → 权限状态 + 工具可用性清单
```

**所有调用经 AuditLogger 写入 ~/mcp-audit.log**，AgentServerFragment 实时监控按钮可 tail。

---

## 6. Android 传感器桥（ApiHttpBridgeServer，port 17681）

为什么不用 termux-api？因为 termux-* 二进制是 bionic，proot 内的 Ubuntu 是 glibc，互不兼容。HTTP 桥让 Ubuntu 内任何 curl/wget 都能查到 Android 状态：

```
curl http://127.0.0.1:17681/battery     # 电池
curl http://127.0.0.1:17681/wifi        # WiFi
curl http://127.0.0.1:17681/sensors     # 传感器列表
curl http://127.0.0.1:17681/camera      # 摄像头能力
curl http://127.0.0.1:17681/clipboard   # 剪贴板（只读）
```

返回兼容 termux-api 格式的 JSON，方便 Claude Code shell 直接消费。

---

## 7. 文件附件流程

```
用户点击 📎 → 系统文件选择器（ACTION_GET_CONTENT）
        │
        ▼
HomeFragment.copyFileAsync(uri)
  Step 1：URI → app cache 目录（FileOutputStream）
          /data/data/com.termux/cache/upload_src
  Step 2：proot-distro login --user claude ubuntu
            --bind <cache>/upload_src:/tmp/.upload_src
            -- sh -c "mkdir -p ~/uploads && cp /tmp/.upload_src ~/uploads/<name>
                      && echo ~/uploads/<name>"
        │
        ▼
回显路径 = /home/claude/uploads/<name> → mAttachmentPath
        │
        │  同时记入 UploadStore：
        │    若 mCurrentSessionId != null → 写入该 session 桶
        │    否则写入 "__pending__" 桶（待 sendOrConfirm 拿到 sessionId 后 commitPending）
        │
        ▼
发送时 prompt 拼成：[附件: /home/claude/uploads/<name>]\n<用户文字>
        │
        ▼
Claude Code 接到 prompt 后用原生 Read 工具读该路径
        │
        ▼
（删除会话时）SessionStore.delete + UploadStore.deleteSession
        + deleteUbuntuFiles 异步清理 ~/uploads 内对应文件
```

---

## 8. 自动安装与维护流程

```
TermuxActivity 启动
        │
        ▼
AutoTaskCoordinator.start()
        │
        ├─ AutoUbuntuManager
        │     ├─ 检查 Ubuntu rootfs 是否存在 + claude --version 健康检查
        │     │      ┌── 已就绪 ──┐
        │     │   未就绪/缺失     │
        │     │      │             │
        │     │      ▼             │
        │     │   UbuntuSnapshotManager 优先级：
        │     │      1) APK 内置快照（ubuntu-snapshot/*.tar.xz，离线可用）
        │     │      2) GitHub Release 下载快照
        │     │      3) proot-distro install ubuntu（最后兜底）
        │     │      ▼
        │     │   注入 Claude 安装 hook 到 /home/claude/.bashrc（一次性）
        │     │      └─────────────┘
        │     │
        │     ├─ injectClaudeMd  → /home/claude/CLAUDE.md（每次启动覆盖）
        │     │     ├ MCP 工具速查
        │     │     ├ HTTP 桥端点说明
        │     │     ├ 截屏硬规则（不允许尝试 scrot/screencap 等）
        │     │     └ 记忆系统规则（写入 ~/.claude/memory/*.md）
        │     │
        │     ├─ injectPhoneSkill → /home/claude/.claude/commands/phone.md
        │     ├─ injectMcpSettings → 注册 android-mcp + dangerouslySkipPermissions
        │     └─ injectClaudeWrapper → /home/claude/.local/bin/claude
        │           （拦截 agentserver 调用，捕获 stream-json IO 到 pipe 文件）
        │
        ├─ AutoClaudeManager
        │     └─ 写 .bashrc hook：npm install -g @anthropic-ai/claude-code
        │
        ├─ AutoAgentServerManager
        │     └─ 提取 assets/agentserver-linux-arm64.tgz → /usr/local/bin/agentserver
        │
        ├─ ApiSelfCheckManager
        │     └─ 启动后跑一次自检：MCP 端点连通、ApiHttpBridge 可达
        │
        └─ CapabilitiesManager
              └─ 持久化 capabilities.json（设备能力清单，供上游展示）
```

### 三层组件 & 联网回退矩阵

```
Layer 1: Termux 端工具（proot, proot-distro, libtalloc, file）
         ✓ 全部 APK 静态打包到 assets/termux-tools/  → 永不联网
         （已脱离 Termux apt 仓库，避免上游 4.x→5.x Python 重写这种破坏性变更）

Layer 2: Ubuntu rootfs（约 200 MB 压缩）
         ① APK 内置快照（assets/ubuntu-snapshot/，含预装 Claude+AgentServer）✓ 离线
         ② GitHub Release 快照下载（snapshot-v2 tag）  ← 联网
         ③ proot-distro install ubuntu（裸 Ubuntu base）  ← 联网

Layer 3: 容器内组件
         · Claude Code（npm install）  ← 联网（除非快照内已预装）
         · AgentServer（APK 内置 .tgz）  ✓ 离线（dpkg/extract）
```

**幂等设计**：每个 .bashrc hook 入口先 `command -v <bin>` 检测，已装则自删 hook + 退出。这样组件互不耦合：

| 组件 | 检测方式 | 已安装时行为 |
|------|---------|-----------|
| proot-distro / proot / libtalloc / file | `command -v ...` | 跳过 dpkg/install.sh |
| Ubuntu rootfs | `[ -d $PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu ]` | 跳过 proot-distro install |
| Claude Code | `command -v claude` | 跳过 npm install |
| AgentServer | `command -v agentserver` | 跳过 dpkg + tar |

### 常见场景表现

| 场景 | 网络 | 时延 |
|------|------|------|
| 全新装 + 快照 OK | 不需要 | ~1 分钟（解压快照） |
| 全新装 + 快照下载失败 | 需要 | ~5-10 分钟（裸 Ubuntu + npm install claude） |
| 重启已部署 App | 不需要 | 立即（所有 hook skip） |
| 手动删了 claude | 需要 | ~3-5 分钟（仅补 claude） |

**唯一未完全解耦点**：Claude Code 版本绑定在 Ubuntu 快照里。升级 Claude → 必须重打快照 + 发新 snapshot-v3 release（或用户进 Ubuntu 终端手动 `npm install -g @anthropic-ai/claude-code`）。

---

## 9. 悬浮窗（FloatingStatusService）

```
显示条件 = !TermuxActivity.sActivityForeground   （Activity 在后台）
        && sIsBusy                                 （Claude 正在执行任务）
        && !sUserClosed                            （用户没手动点 ×）

触发：
  HomeFragment.sendOrConfirm    → updateStatus("● 运行中", color, preview, busy=true)
  AgentServer 任务到达          → updateStatus("● 执行任务", color, preview, busy=true)
  完成时                        → updateStatus("● 就绪", color, "", busy=false)

边沿：
  busy 从 false→true 时重置 sUserClosed → 新任务能再次弹出
```

---

## 10. 持久化层细节

| 类 | SharedPreferences key | 内容 |
|----|----------------------|------|
| `SessionStore` | `claude_sessions` | 历史会话元数据 `[{id, ts, preview}]`，最多 50 条 |
| `UploadStore` | `upload_index` | `{sessionId: [filename, ...], "__pending__": [...]}` |
| `AgentTaskStore` | `agent_tasks_v2` | `[AgentTask]`，每个含 prompt + status + ChatMessage 列表，最多 50 条 |
| `ApiKeyStore` | `claude_api_keys` | `[{id, alias, value, baseUrl}]` + activeId |

**App 私有文件目录**：
- `getFilesDir()/agent-tasks/<id>.jsonl` — 每个 AgentServer 任务的原始 pipe 事件归档（含截图 base64），随任务删除而释放

**Ubuntu rootfs 内的关键文件**：
- `/home/claude/CLAUDE.md` — Claude Code 系统提示（每次启动覆盖）
- `/home/claude/.claude/memory/*.md` — 用户记忆（侧栏「记忆」Tab）
- `/home/claude/.claude/commands/*.md` — 自定义 skill（侧栏「技能」Tab）
- `/home/claude/.claude/projects/-home-claude/<sessId>.jsonl` — Claude Code 自身的 session 历史（点击「历史」时回放）
- `/home/claude/uploads/<name>` — 用户上传附件
- `/home/claude/.agentserver-pipe.jsonl` — agentserver wrapper 写入的运行时 pipe
- `/home/claude/.local/bin/claude` — 我们注入的 wrapper 脚本

---

## 11. 权限依赖

| 功能 | 所需权限 |
|------|---------|
| ui.tap / swipe / click_text / input_text / get_accessibility_tree | Android 无障碍服务 |
| screen.capture | MediaProjection（Home 页点「授权截图」一次性授予） |
| camera.take_photo | CAMERA 权限 |
| file.read / list / check_exists | 无（App 进程权限内） |
| app.open / get_current_activity | 无障碍（仅查询当前 Activity） |
| /battery /wifi /sensors /clipboard | 由 ApiHttpBridge 在 App 进程内处理 |
| 后台悬浮窗 | SYSTEM_ALERT_WINDOW（首次需手动授予） |

---

## 12. 关键端口与协议

| 端口 | 服务 | 协议 |
|------|------|------|
| **8765** | McpHttpServer | MCP Streamable HTTP（Claude Code 注册的 android-mcp） |
| **17681** | ApiHttpBridgeServer | 普通 HTTP GET（Android 状态/传感器） |

两个端口都只监听 `127.0.0.1`，仅 Ubuntu proot 内（同进程网络命名空间）可达。

---

## 13. 与上游 AgentServer 的关系

```
              ┌────────────────────────────┐
              │   上游 AgentServer 服务    │
              │   （用户配置的地址）       │
              └─────────────┬──────────────┘
                            │  WebSocket tunnel
                            │  wss://.../api/tunnel/<sandbox>
                            ▼
            ┌─────────────────────────────────┐
            │  Ubuntu proot 内的             │
            │  agentserver claudecode 进程   │
            └────────┬────────────────────────┘
                     │  spawn (PATH 里 wrapper 优先)
                     ▼
            ┌─────────────────────────────────┐
            │  /home/claude/.local/bin/claude │  ← app的拦截 wrapper，用于呈现对话记录
            │  - 流式 JSON 模式 → tee stdin/  │
            │    stdout 到 pipe 文件          │
            │  - 透传给 /usr/local/bin/claude │
            └────────┬────────────────────────┘
                     │  exec
                     ▼
            ┌─────────────────────────────────┐
            │  /usr/local/bin/claude          │  ← 真实 Claude Code
            │  使用 mcp__agentserver__*       │
            │  和 mcp__android-mcp__* 工具    │
            └─────────────────────────────────┘
```
