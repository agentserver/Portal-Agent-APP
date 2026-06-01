# HomeFragment Claude 调用切换为 stream-json 长驻模式

## 目标

把 HomeFragment 与 Claude Code 的交互从"每条消息起一个 `claude -p` 子进程"改造为"一个会话长驻一个 `claude --input-format stream-json --output-format stream-json` 子进程"。

UI 行为保持不变(气泡列表、抽屉、附件流程都不动),但额外支持工具调用 / 工具返回的可折叠展示。该改造同时为后续的"真打断"、"prompt cache 高命中"等优化打底。

源文件要求保持不变:
- `~/.claude/projects/-home-claude/<session_id>.jsonl` —— Claude CLI 自管的 session 历史,只读不写
- `SessionStore`、`UploadStore`、`AgentTaskStore` —— 已有的 SharedPreferences 不动 schema
- `AgentServer` 一侧的 wrapper、Fragment、pipe 协议 —— 不动

## 当前状态

HomeFragment 现在每次发消息都启动一个独立的 `proot-distro login ubuntu --user claude -- sh -c 'printf "..." | claude -p ... [--continue|--resume <id>]'` 子进程,处理完一轮就退出。

这套路径存在三个限制:
1. **打断粒度粗**: SIGTERM 是唯一打断手段。打断后 Claude 写在 session jsonl 里的状态是半成品,下条 `--continue` 容易让模型把上一轮的 plan 继续推进
2. **prompt cache 命中低**: 每条消息冷启动一个新进程,Claude 本地缓存命中率上不去
3. **`--continue` 容易抢错 session**: 已通过 `--resume <captured_sid>` 修复 (见 commit history),但根本性的"进程内持有会话"才是更清晰的模型

`AgentServer` 那一侧由 wrapper 拦截已经走 stream-json 长驻模式,可以参考其线协议处理代码。HomeFragment 这次改造采用同样的进程拉起方式,但**事件流走 stdout 直读**(不写入 `.agentserver-pipe.jsonl`,继续靠 `CLAUDE_DIRECT=1` env 让 wrapper 透传)。

## 设计

### 组件边界

新增一个 Application-level singleton `ClaudeStreamSession`,封装子进程生命周期、stdin 写入、stdout 解析、Listener 分发。HomeFragment 注册 listener 接收事件,**不再持有子进程引用**。

| 文件 | 操作 | 角色 |
|------|------|------|
| `ClaudeStreamSession.java` | 新建 | 进程封装 + 状态机 + 事件分发,**不依赖 Android UI 类** |
| `TermuxApplication.java` | 改 | `onCreate()` 调用 `ClaudeStreamSession.init(this)` 创建 singleton(懒模式,不预热进程) |
| `ChatMessage.java` | 改 | 新增 `TOOL_USE` / `TOOL_RESULT` 两个 enum + `toolName/toolDetail/toolDetailCollapsed` 字段 + 工厂方法 |
| `ChatAdapter.java` | 改 | 新增 2 个 viewType 渲染工具气泡(灰底 + 折叠详情) |
| `item_msg_tool_use.xml` | 新建 | 工具调用气泡布局 |
| `item_msg_tool_result.xml` | 新建 | 工具返回气泡布局 |
| `HomeFragment.java` | 改 | 删除 ProcessBuilder 路径,改成 Manager.send + Listener |

不动: AgentServer 侧代码、`AutoUbuntuManager.injectClaudeWrapper`、SessionStore、UploadStore、AgentTaskStore、TermuxActivity 路由、抽屉布局。

### Manager 公开接口

```java
public final class ClaudeStreamSession {
    public static ClaudeStreamSession get(Context appCtx);

    /** 发送一条 user 消息(异步)。被禁止则静默忽略(UI 应禁用按钮)。 */
    public void send(String userText);

    /** SIGTERM 当前子进程;清空状态字段;currentSid 保留。下条 send 会用 --resume currentSid 起新进程。 */
    public void interrupt();

    /** SIGTERM + 清 currentSid。下条 send 起全新 session。 */
    public void resetForNewConversation();

    /** SIGTERM + currentSid = sid。下条 send 起新进程并 --resume sid。 */
    public void startWithResume(String sid);

    public boolean isWaitingResponse();
    public String  getCurrentSessionId();

    public void addListener(Listener l);
    public void removeListener(Listener l);

    public interface Listener {
        void onSystem(String info);                                 // type=system init
        void onAssistantText(String text);                          // 累积快照
        void onAssistantThinking(String thinking);                  // 累积快照
        void onToolUse(String name, String inputJson);              // 单次触发(按 id 去重)
        void onToolResult(String name, String summary, String full);
        void onResult(String sid, boolean isError, String errMsg);  // 一轮结束
        void onProcessDied(String reason);                          // 子进程异常退出
    }
}
```

### 状态机

```
            ┌─────────────────────────────────────────────────┐
            │                                                 │
            │   send()                                        │
   IDLE ─────────────────────► STARTING ──ready──► WAITING_TURN
    ▲                              │                    │
    │                              │                    │
    │   onResult / onProcessDied   │  spawn fail        │
    └──────────────────────────────┴────────────────────┘
                                                         │
                                       send() in WAITING ──► 忽略
                                       interrupt() ──► kill ──► IDLE
                                       reset/startWithResume ──► kill ──► STARTING
```

| 状态 | 含义 | 子进程 |
|------|------|--------|
| `IDLE` | 没有 turn 在进行,或上一轮已结束 | 可能存活也可能不存活 |
| `STARTING` | 进程刚 spawn,正在等 init event | 存活 |
| `WAITING_TURN` | 已写入 user message,等待 `result` 事件 | 存活 |

子进程在 IDLE 状态下**默认保留存活**:Claude `--input-format stream-json` 模式在 result 之后会持续等下一条 user event。保留进程让下一条消息几乎零延迟、prompt cache 满命中。OS 后台杀掉进程是 acceptable 的边界情况(见下节)。

例外:`interrupt()` / `resetForNewConversation()` / `startWithResume()` 三个方法显式 SIGTERM 进程后,IDLE 时进程不存活;下条 `send()` 会触发 spawn。这是"显式杀进程"与"自然空闲"两种 IDLE 形态。

`isWaitingResponse()` = `state == WAITING_TURN`,UI 的发送按钮 disable 逻辑用这个。

### 进程拉起的命令行

```bash
proot-distro login ubuntu --user claude -- sh -c '
  ANTHROPIC_API_KEY=... [ANTHROPIC_BASE_URL=...] CLAUDE_DIRECT=1 \
  claude --input-format stream-json --output-format stream-json \
         --verbose --dangerously-skip-permissions \
         [--resume <sid>] 2>/dev/null
'
```

与现有 `claude -p` 调用的差异:
- 去掉 `-p`
- 新增 `--input-format stream-json`
- 不再传 `--continue`,只在 spawn 新进程时按需带 `--resume <sid>`
- `CLAUDE_DIRECT=1` 保留 —— 让 `injectClaudeWrapper` 走透传分支,**不写入 `.agentserver-pipe.jsonl`**(与现有隔离策略一致)

### stdin / stdout 线协议

**写入 stdin**(每行一个 JSON 对象 + `\n`,write 后 flush):

```json
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"<userText>"}]}}
```

带附件时 `text` 字段以 `[附件: /home/claude/uploads/<uuid>/<filename>]\n` 前缀拼接 —— 跟当前 HomeFragment 的做法一致,Claude 看到路径会自动 Read。

**从 stdout 读到的事件**(已知存在,沿用 HomeFragment 现有 extract* 函数搬到 Manager):

| `type` | 解析 | Listener 回调 |
|--------|------|--------------|
| `system` (subtype=init) | 取 cwd / model | `onSystem(info)` |
| `assistant` | 遍历 `message.content[*]` | text 块 → `onAssistantText` ; thinking 块 → `onAssistantThinking` ; tool_use 块 → `onToolUse`(按 `id` 去重) |
| `user` | 遍历 `message.content[*]`,只关心 tool_result | `onToolResult(name, summary≤200字, full)` |
| `result` | `is_error` / `session_id` / `result` | `onResult` |
| 其他 | 忽略 | — |

**累积快照规则**: `assistant` 事件是累积快照,Listener 每次收到的 text/thinking 都是**完整内容**(不是增量),UI 用最新覆盖当前气泡的对应字段。`tool_use` 不会重复 emit,Manager 内部 `Set<String> mEmittedToolUseIds` 去重。

### 错误恢复

| # | 异常 | 检测 | 处理 |
|---|------|-----|------|
| 1 | spawn 失败(Ubuntu 未装) | `ProcessBuilder.start()` IOException | `onProcessDied("spawn failed: " + msg)` → IDLE。下次 send 仍尝试 spawn,不退避 |
| 2 | 子进程异常退出 | stdout reader 读到 EOF + `waitFor() != 0` | `onProcessDied("exit " + code)` → IDLE。下次 send 自动 spawn(若 currentSid 非空则带 --resume) |
| 3 | OS 后台清理进程 | 同 #2(exitCode 通常 137) | 同 #2,文案统一"进程已退出,下条消息将自动恢复" |
| 4 | JSON 行解析失败 | `new JSONObject(line)` 抛异常 | 静默 skip,logcat warning 一行 |
| 5 | stdin 写入阻塞 | 专用 stdin-writer 线程,不在 UI 线程 | 不影响 UI 可用性;状态机保证 send 队列容量 = 1 |
| 6 | interrupt 后子进程未死 | 1 秒后 SIGKILL | 仍存活则标记 zombie + spawn 新进程顶上 |
| 7 | listener 抛异常 | 每个回调 try/catch 包裹 | 不影响其他 listener |

**"代际"过滤 token**: interrupt() / resetForNewConversation() / startWithResume() / `onProcessDied` 触发时 `mInterruptToken++`,stdout reader 处理事件前先比对 token,小于当前的事件丢弃。防止 SIGTERM 后残留事件污染新 turn 的 UI。同时清空 `mEmittedToolUseIds`、`mLastTextSnapshot`、`mLastThinkingSnapshot` 三个 turn-local 缓冲。新 turn 的 `WAITING_TURN` 进入时再清一次,双保险。

**currentSid 何时持久化**: 仅在 `onResult` 收到非空 `session_id` 时调 listener,**Listener(HomeFragment)负责写入 SessionStore**。Manager 不碰持久化,保持纯数据层。

**App 重启后的残留 claude 进程**: 不主动清理。理由:stream-json 模式下空闲 claude 几乎不耗 CPU,等不到 user message 就一直等;主动扫并杀有误杀 AgentServer 派生 claude 的风险。

### UI 集成

#### HomeFragment 改造

**删除**(~120 行): `mClaudeProcess` / `mClaudeThread` 字段、`sendOrConfirm` 里整个 ProcessBuilder + stderr 线程 + stdout reader 大块、`extractAssistant`/`extractSessionId`/`extractSystemInfo`/`summarizeToolResult`(搬到 Manager)。

**新增**(~40 行): `mListener` inner class、`onViewCreated` 里 addListener、`onDestroyView` 里 removeListener。

**改造**:
- `sendOrConfirm` 主体: 校验 → 加 user 气泡 → 加 "…" 占位气泡 → `mgr.send(text)` → `mWaitingResponse = true` + UI 锁定
- "打断" → `mgr.interrupt()`
- "新建对话" → `mgr.resetForNewConversation()` + 清气泡 + 清 mCurrentSessionId
- 历史抽屉打开 → `mgr.startWithResume(entry.id)` + UI 加载历史回放

**移除字段**: `mSessionStarted`、`doContinue`(--continue 在 stream-json 模式下完全不需要,会话由进程内持有)

**保留 in-Fragment**: `appendChatLog` 调用(在 mListener.onResult 里写),`FloatingStatusService.updateStatus` 触发(也在 listener 回调里)。

#### Listener 回调到 UI 的映射

`mListener` 的所有回调用 `mHandler.post` 切回 UI 线程后调 `mAdapter` 对应方法:
- `onSystem` → `addMessage(ChatMessage.system(info))`
- `onAssistantText` → `updateLastAssistantText(text)` (覆盖)
- `onAssistantThinking` → `updateLastAssistantThinking(thinking)` (覆盖)
- `onToolUse` → `addMessage(ChatMessage.toolUse(name, inputJson))`
- `onToolResult` → `addMessage(ChatMessage.toolResult(name, summary, full))`
- `onResult` → 折叠最后一条 assistant 的 thinking / 所有 tool 详情 + 解锁按钮 + 捕获 session_id 写 SessionStore + 写 chat_history.log + commitPending
- `onProcessDied` → 灰色 SYSTEM 气泡"● 进程已退出 (reason),下条消息将自动恢复" + 解锁按钮

#### 气泡布局

ChatMessage 新增字段:
```java
public enum Type { USER, ASSISTANT, SYSTEM, TOOL_USE, TOOL_RESULT }
public String  toolName;
public String  toolDetail;
public boolean toolDetailCollapsed = true;
```

工厂方法:
```java
public static ChatMessage toolUse(String name, String detail);
public static ChatMessage toolResult(String name, String summary, String full);
```

布局复用 SYSTEM 气泡灰底圆角 monospace 风格,加 ▶/▼ 折叠三角 + 默认 `visibility=GONE` 的详情区域。点击三角切换:
```
┌────────────────────────────────────────┐
│ ▶ 🔧 调用 Bash                        │   折叠态
└────────────────────────────────────────┘

┌────────────────────────────────────────┐
│ ▼ 🔧 调用 Bash                        │   展开态
│ ────────────────────────────────       │
│ { "command": "ls -la /home" }          │
└────────────────────────────────────────┘

┌────────────────────────────────────────┐
│ ▶ 📥 Bash: total 64...                │   TOOL_RESULT 折叠态(显示 ≤200 字摘要)
└────────────────────────────────────────┘
```

thinking 折叠继续复用 ASSISTANT 气泡现有折叠组件,不动。

## 测试

**单元级**(可选):用 mock stdin/stdout pipe 跑 Manager 的发-收-result 路径。

**端到端验证清单**:

- [ ] 冷启动后第一条消息: spawn → init event → 流式输出 → result → 解锁
- [ ] 连续 3 条消息: 进程不重启;Claude 端 prompt cache 命中(看 stream-json 里 cache_read_input_tokens > 0)
- [ ] 打断: 流出一半时打断 → "● 已打断"灰条 → 立刻发新消息 → session_id 与之前一致(--resume 续接成功)
- [ ] 新建对话: kill + 清气泡 → 下条消息 session_id 不同
- [ ] 历史抽屉打开旧 session: kill + UI 加载历史 → 下条消息 --resume 该 session
- [ ] 工具调用气泡: text 中夹 tool_use → 灰色"🔧 调用 X" → 点 ▶ 看 input JSON;工具返回 → "📥 X: 摘要" → 点 ▶ 看完整输出
- [ ] thinking: 流式累积 → result 后自动折叠 → 可点击展开
- [ ] 切到 AgentServer Tab 再回来: 进程仍存活,对话状态完整
- [ ] App 切后台被 OS 杀进程: 回前台后下条消息触发 --resume 自动恢复,UI 提示"进程已退出..."灰条
- [ ] AgentServer 并发: 同时跑 HomeFragment 聊天 + 一个 AgentServer 任务,两侧 jsonl 与 session_id 完全不串

## 非目标

- 不动 AgentServer 一侧的代码或线协议
- 不引入流式输入侧的"控制事件"(如运行中切 session) —— 只在 spawn 时通过命令行决定 --resume
- 不处理多个并发 user message 排队(状态机保证单 turn)
- 不解决 App 重启时残留 claude 进程的清理(YAGNI,见错误恢复 §)
- 不改 wrapper 脚本、AgentServer Fragment、UploadStore schema、AgentTaskStore schema
- 不实现 "真·打断后下条消息加 [previous interrupted] 前缀" 的语义补丁(这是后续独立 spec)
- 不重构 ChatAdapter 通用样式系统 —— 只追加 2 个 viewType
