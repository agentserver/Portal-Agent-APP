# HomeFragment Stream-JSON Long-Lived Claude Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert HomeFragment's per-message `claude -p` subprocess pattern to one long-lived `claude --input-format stream-json --output-format stream-json` per conversation, owned by an Application-level `ClaudeStreamSession` singleton, while adding collapsible tool-call / tool-result bubbles.

**Architecture:** New `ClaudeStreamSession` singleton owns the subprocess lifecycle, stdin writer thread, stdout reader thread, and event dispatch via a `Listener` interface. HomeFragment becomes a listener subscriber—no longer holding `Process` references. Subprocess survives between turns for prompt-cache hits; explicit `interrupt() / resetForNewConversation() / startWithResume()` are the only triggers that kill it. `CLAUDE_DIRECT=1` env stays set so `injectClaudeWrapper` passes through without writing the agentserver pipe.

**Tech Stack:** Android Java, ProcessBuilder + BufferedReader/Writer threads, RecyclerView Adapter, SharedPreferences for session_id persistence (via existing `SessionStore`). No new dependencies. Validation via ADB + logcat + on-device UI inspection (no JUnit/Robolectric infra exists in this project).

**Reference spec:** `docs/superpowers/specs/2026-06-02-stream-json-home-claude-design.md`

**Out of scope (DO NOT TOUCH in this plan):**
- `app/src/main/java/com/termux/app/autotasks/AutoUbuntuManager.java` (has unrelated uncommitted cpu_arch fix)
- The current `HomeFragment.java` uncommitted `--resume` fix (lines 502-537 area)—your Phase 4 rewrite will subsume it
- `AgentServerFragment.java` and AgentServer pipe protocol
- `wrapper` script in `injectClaudeWrapper` (still keyed off `CLAUDE_DIRECT=1` and `--input-format stream-json`, both already supported)

---

## File Structure

**New files:**
- `app/src/main/java/com/termux/app/ClaudeStreamSession.java` — singleton subprocess wrapper (~280 lines)
- `app/src/main/res/layout/item_msg_tool_use.xml` — tool_use bubble
- `app/src/main/res/layout/item_msg_tool_result.xml` — tool_result bubble

**Modified files:**
- `app/src/main/java/com/termux/app/TermuxApplication.java` — `onCreate()` line +5: `ClaudeStreamSession.init(this)`
- `app/src/main/java/com/termux/app/ChatMessage.java` — add 2 enum values, 3 fields, 2 factory methods
- `app/src/main/java/com/termux/app/ChatAdapter.java` — add 2 viewTypes, 2 ViewHolders, helper methods for tool bubbles + `updateLastAssistantText` / `updateLastAssistantThinking` (split of existing `updateLastAssistant`)
- `app/src/main/java/com/termux/app/HomeFragment.java` — rip out ProcessBuilder path (~120 lines deleted), add Listener + Manager wiring (~40 lines added)

**Phases:** Each phase is independently shippable / inspectable.
- Phase 1: Data layer (ChatMessage / layouts / ChatAdapter) — verifiable by hardcoded test bubbles
- Phase 2: Manager skeleton + parser
- Phase 3: Application wiring
- Phase 4: HomeFragment swap
- Phase 5: Cleanup + logging

---

# PHASE 1 — Data Layer (Tool Bubble Plumbing)

These tasks land WITHOUT removing existing functionality. After Phase 1, the app still uses `claude -p`; tool bubbles are wired but unused until Phase 4.

## Task 1.1: Extend `ChatMessage` with TOOL_USE / TOOL_RESULT types

**Files:**
- Modify: `app/src/main/java/com/termux/app/ChatMessage.java`

- [ ] **Step 1: Replace the entire file contents**

```java
package com.termux.app;

/** 聊天消息数据类，用于简化 UI 的聊天视图。 */
public class ChatMessage {

    public enum Type { USER, ASSISTANT, SYSTEM, TOOL_USE, TOOL_RESULT }

    public final Type type;
    public String content;            // 主气泡正文（USER/ASSISTANT/SYSTEM）或标题行（TOOL_USE/TOOL_RESULT）
    public String thinking;           // 思考过程原文（null = 无思考内容）
    public boolean thinkingCollapsed; // true = 折叠显示（回复完成后）

    // TOOL_USE / TOOL_RESULT 专用字段
    public String  toolName;            // 工具名，例如 "Bash"
    public String  toolDetail;          // 折叠区域内容（input JSON 或完整 output）
    public boolean toolDetailCollapsed = true;

    public ChatMessage(Type type, String content) {
        this.type = type;
        this.content = content;
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Type.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Type.ASSISTANT, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Type.SYSTEM, content);
    }

    /** 工具调用气泡，标题行显示 "🔧 调用 {name}"，点击展开 input JSON。 */
    public static ChatMessage toolUse(String name, String inputJson) {
        ChatMessage m = new ChatMessage(Type.TOOL_USE, "🔧 调用 " + name);
        m.toolName   = name;
        m.toolDetail = inputJson != null ? inputJson : "";
        return m;
    }

    /** 工具返回气泡，标题行显示 "📥 {name}: {summary}"，点击展开完整内容。 */
    public static ChatMessage toolResult(String name, String summary, String full) {
        String title = (summary == null || summary.isEmpty())
                ? "📥 " + name
                : "📥 " + name + ": " + summary;
        ChatMessage m = new ChatMessage(Type.TOOL_RESULT, title);
        m.toolName   = name;
        m.toolDetail = full != null ? full : "";
        return m;
    }
}
```

- [ ] **Step 2: Verify compiles**

Run from project root:
```bash
./gradlew :app:compileDebugJava 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL or only warnings (no errors). Any `cannot find symbol` is a typo in Step 1—reread.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/termux/app/ChatMessage.java
git commit -m "feat(chat): add TOOL_USE / TOOL_RESULT message types

Adds two new enum variants to ChatMessage plus toolName / toolDetail /
toolDetailCollapsed fields and matching factory methods. Renderer in
ChatAdapter follows in next commit. No callers added yet — the new
types are inert until HomeFragment switches to Stream-JSON in Phase 4.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 1.2: Create `item_msg_tool_use.xml` layout

**Files:**
- Create: `app/src/main/res/layout/item_msg_tool_use.xml`

- [ ] **Step 1: Create the file**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 工具调用：灰色气泡，标题行 + 可折叠详情 -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="start"
    android:paddingStart="12dp"
    android:paddingEnd="56dp"
    android:paddingTop="2dp"
    android:paddingBottom="2dp">

    <LinearLayout
        android:id="@+id/tool_container"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:background="@drawable/bubble_thinking"
        android:clickable="true"
        android:focusable="true">

        <TextView
            android:id="@+id/tool_header"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:paddingStart="12dp"
            android:paddingEnd="12dp"
            android:paddingTop="6dp"
            android:paddingBottom="6dp"
            android:textColor="#666666"
            android:textSize="12sp"
            android:fontFamily="monospace" />

        <TextView
            android:id="@+id/tool_detail"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:paddingStart="12dp"
            android:paddingEnd="12dp"
            android:paddingBottom="8dp"
            android:textColor="#777777"
            android:textSize="11sp"
            android:fontFamily="monospace"
            android:lineSpacingMultiplier="1.3"
            android:textIsSelectable="true"
            android:visibility="gone" />

    </LinearLayout>

</LinearLayout>
```

- [ ] **Step 2: Verify the resource compiles**

```bash
./gradlew :app:processDebugResources 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/item_msg_tool_use.xml
git commit -m "feat(chat): add item_msg_tool_use.xml layout

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 1.3: Create `item_msg_tool_result.xml` layout

**Files:**
- Create: `app/src/main/res/layout/item_msg_tool_result.xml`

- [ ] **Step 1: Create the file**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 工具返回：灰色气泡，标题行（含摘要） + 可折叠完整内容 -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="start"
    android:paddingStart="12dp"
    android:paddingEnd="56dp"
    android:paddingTop="2dp"
    android:paddingBottom="2dp">

    <LinearLayout
        android:id="@+id/tool_container"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:background="@drawable/bubble_thinking"
        android:clickable="true"
        android:focusable="true">

        <TextView
            android:id="@+id/tool_header"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:paddingStart="12dp"
            android:paddingEnd="12dp"
            android:paddingTop="6dp"
            android:paddingBottom="6dp"
            android:textColor="#666666"
            android:textSize="12sp"
            android:fontFamily="monospace"
            android:maxLines="3"
            android:ellipsize="end" />

        <TextView
            android:id="@+id/tool_detail"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:paddingStart="12dp"
            android:paddingEnd="12dp"
            android:paddingBottom="8dp"
            android:textColor="#777777"
            android:textSize="11sp"
            android:fontFamily="monospace"
            android:lineSpacingMultiplier="1.3"
            android:textIsSelectable="true"
            android:visibility="gone" />

    </LinearLayout>

</LinearLayout>
```

- [ ] **Step 2: Verify the resource compiles**

```bash
./gradlew :app:processDebugResources 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/item_msg_tool_result.xml
git commit -m "feat(chat): add item_msg_tool_result.xml layout

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 1.4: Extend `ChatAdapter` with tool ViewHolders and split assistant updaters

**Files:**
- Modify: `app/src/main/java/com/termux/app/ChatAdapter.java`

- [ ] **Step 1: Add the two new view-type constants**

In `ChatAdapter.java`, find:
```java
    private static final int TYPE_USER = 0;
    private static final int TYPE_ASSISTANT = 1;
    private static final int TYPE_SYSTEM = 2;
```

Replace with:
```java
    private static final int TYPE_USER         = 0;
    private static final int TYPE_ASSISTANT    = 1;
    private static final int TYPE_SYSTEM       = 2;
    private static final int TYPE_TOOL_USE     = 3;
    private static final int TYPE_TOOL_RESULT  = 4;
```

- [ ] **Step 2: Update `getItemViewType`**

Find the existing `getItemViewType`:
```java
    @Override
    public int getItemViewType(int position) {
        switch (mMessages.get(position).type) {
            case USER:      return TYPE_USER;
            case SYSTEM:    return TYPE_SYSTEM;
            default:        return TYPE_ASSISTANT;
        }
    }
```

Replace with:
```java
    @Override
    public int getItemViewType(int position) {
        switch (mMessages.get(position).type) {
            case USER:         return TYPE_USER;
            case SYSTEM:       return TYPE_SYSTEM;
            case TOOL_USE:     return TYPE_TOOL_USE;
            case TOOL_RESULT:  return TYPE_TOOL_RESULT;
            default:           return TYPE_ASSISTANT;
        }
    }
```

- [ ] **Step 3: Update `onCreateViewHolder`**

Find the existing method body:
```java
        if (viewType == TYPE_USER) {
            return new UserViewHolder(inflater.inflate(R.layout.item_msg_user, parent, false));
        } else if (viewType == TYPE_SYSTEM) {
            return new SystemViewHolder(inflater.inflate(R.layout.item_msg_system, parent, false));
        } else {
            return new AssistantViewHolder(inflater.inflate(R.layout.item_msg_assistant, parent, false));
        }
```

Replace with:
```java
        if (viewType == TYPE_USER) {
            return new UserViewHolder(inflater.inflate(R.layout.item_msg_user, parent, false));
        } else if (viewType == TYPE_SYSTEM) {
            return new SystemViewHolder(inflater.inflate(R.layout.item_msg_system, parent, false));
        } else if (viewType == TYPE_TOOL_USE) {
            return new ToolViewHolder(inflater.inflate(R.layout.item_msg_tool_use, parent, false));
        } else if (viewType == TYPE_TOOL_RESULT) {
            return new ToolViewHolder(inflater.inflate(R.layout.item_msg_tool_result, parent, false));
        } else {
            return new AssistantViewHolder(inflater.inflate(R.layout.item_msg_assistant, parent, false));
        }
```

- [ ] **Step 4: Update `onBindViewHolder`**

Find:
```java
        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).bind(msg.content);
        } else if (holder instanceof SystemViewHolder) {
            ((SystemViewHolder) holder).bind(msg.content);
        } else {
            ((AssistantViewHolder) holder).bind(msg);
        }
```

Replace with:
```java
        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).bind(msg.content);
        } else if (holder instanceof SystemViewHolder) {
            ((SystemViewHolder) holder).bind(msg.content);
        } else if (holder instanceof ToolViewHolder) {
            ((ToolViewHolder) holder).bind(msg, position, ChatAdapter.this);
        } else {
            ((AssistantViewHolder) holder).bind(msg);
        }
```

- [ ] **Step 5: Add separate `updateLastAssistantText` / `updateLastAssistantThinking` methods**

After the existing `updateLastAssistant(String content)` 1-arg method (around line 113), insert:

```java
    /** 仅更新最后一条 ASSISTANT 的正文（thinking 字段保留不动）。 */
    public void updateLastAssistantText(String text) {
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            ChatMessage msg = mMessages.get(i);
            if (msg.type == ChatMessage.Type.ASSISTANT) {
                if (!java.util.Objects.equals(msg.content, text)) {
                    msg.content = text;
                    notifyItemChanged(i);
                }
                return;
            }
        }
        // 没有 ASSISTANT 占位 → 创建一条
        addMessage(ChatMessage.assistant(text));
    }

    /** 仅更新最后一条 ASSISTANT 的 thinking 字段。 */
    public void updateLastAssistantThinking(String thinking) {
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            ChatMessage msg = mMessages.get(i);
            if (msg.type == ChatMessage.Type.ASSISTANT) {
                if (!java.util.Objects.equals(msg.thinking, thinking)) {
                    msg.thinking = thinking;
                    notifyItemChanged(i);
                }
                return;
            }
        }
        // 没有 ASSISTANT 占位 → 用空 content 创建一条
        ChatMessage m = ChatMessage.assistant("");
        m.thinking = thinking;
        addMessage(m);
    }

    /** 折叠当前 turn 内所有 TOOL_USE / TOOL_RESULT 气泡的详情区。 */
    public void collapseAllToolDetailsInLastTurn() {
        // 从末尾向前，直到遇到 USER 消息为止
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            ChatMessage msg = mMessages.get(i);
            if (msg.type == ChatMessage.Type.USER) break;
            if ((msg.type == ChatMessage.Type.TOOL_USE
                    || msg.type == ChatMessage.Type.TOOL_RESULT)
                    && !msg.toolDetailCollapsed) {
                msg.toolDetailCollapsed = true;
                notifyItemChanged(i);
            }
        }
    }
```

- [ ] **Step 6: Add the `ToolViewHolder` inner class**

At the end of `ChatAdapter` (after `SystemViewHolder` static class, before the final `}`), insert:

```java
    static class ToolViewHolder extends RecyclerView.ViewHolder {
        private final TextView mHeader;
        private final TextView mDetail;
        private final View     mContainer;

        ToolViewHolder(View itemView) {
            super(itemView);
            mContainer = itemView.findViewById(R.id.tool_container);
            mHeader    = itemView.findViewById(R.id.tool_header);
            mDetail    = itemView.findViewById(R.id.tool_detail);
            itemView.setOnLongClickListener(v -> {
                copyToClipboard(v, mHeader.getText() + "\n" + mDetail.getText());
                return true;
            });
        }

        void bind(ChatMessage msg, int position, ChatAdapter adapter) {
            String triangle = msg.toolDetailCollapsed ? " ▶" : " ▼";
            mHeader.setText(msg.content + triangle);
            if (msg.toolDetail == null || msg.toolDetail.isEmpty()) {
                mDetail.setVisibility(View.GONE);
                mContainer.setOnClickListener(null);  // 无内容则不可点
                return;
            }
            mDetail.setText(msg.toolDetail);
            mDetail.setVisibility(msg.toolDetailCollapsed ? View.GONE : View.VISIBLE);
            mContainer.setOnClickListener(v -> {
                msg.toolDetailCollapsed = !msg.toolDetailCollapsed;
                adapter.notifyItemChanged(position);
            });
        }
    }
```

- [ ] **Step 7: Verify compiles**

```bash
./gradlew :app:compileDebugJava 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Verify with a hardcoded sample on device**

In `HomeFragment.onViewCreated`, immediately after `mAdapter = new ChatAdapter(mMessages);` and `mRecycler.setAdapter(mAdapter);`, **temporarily** add:

```java
        // === TEMPORARY Phase 1 visual test — REMOVE in Task 1.5 ===
        mAdapter.addMessage(ChatMessage.system("--- Phase 1 visual test ---"));
        mAdapter.addMessage(ChatMessage.toolUse("Bash", "{\"command\":\"ls -la /home/claude\"}"));
        mAdapter.addMessage(ChatMessage.toolResult("Bash",
                "total 64\ndrwx... claude...",
                "total 64\ndrwxr-xr-x 5 claude claude 4096 May 25 12:34 .\ndrwxr-xr-x 3 root root 4096 May 1 10:00 ..\n[truncated]"));
        // === END temp ===
```

Build + install + open the app:
```bash
./gradlew :app:installDebug
adb shell am start -n com.termux/com.termux.app.TermuxActivity
```

Inspect on the device:
- A `--- Phase 1 visual test ---` gray system bubble appears
- A `🔧 调用 Bash ▶` row appears below it; tap it → expands to show the JSON; tap again → collapses
- A `📥 Bash: total 64...` row with truncated summary on the header; tap → expands to show the full multi-line output

If anything looks broken (clipping, wrong colors, no expand reaction), fix it before continuing. Long-press a tool bubble copies "header + detail" to clipboard.

- [ ] **Step 9: REMOVE the temporary code**

Delete the `// === TEMPORARY Phase 1 visual test ===` block you added in Step 8.

Rebuild and verify the bubbles are gone:
```bash
./gradlew :app:compileDebugJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/termux/app/ChatAdapter.java
git commit -m "feat(chat): render TOOL_USE / TOOL_RESULT bubbles with collapse

- Adds TYPE_TOOL_USE / TYPE_TOOL_RESULT viewTypes wired to the new
  item_msg_tool_use.xml and item_msg_tool_result.xml layouts.
- Adds ToolViewHolder rendering '🔧 Tool ▶/▼' headers; tap toggles the
  monospace detail panel (input JSON for tool_use, full output for
  tool_result).
- Splits updateLastAssistant() into updateLastAssistantText() and
  updateLastAssistantThinking() so the upcoming Stream-JSON callbacks
  can update text and thinking independently without overwriting each
  other. The old 1-arg / 2-arg updateLastAssistant remain for the legacy
  ProcessBuilder path until Phase 4.
- Adds collapseAllToolDetailsInLastTurn() — used by onResult in Phase 4.

Manually verified bubbles render + collapse on device (R1LM45S11867DC).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# PHASE 2 — ClaudeStreamSession Manager

The Manager has no UI dependencies. Validation in this phase is "compiles and singleton initializes"; real wire-protocol validation happens after Phase 4 hooks it up.

## Task 2.1: Create `ClaudeStreamSession.java` skeleton

**Files:**
- Create: `app/src/main/java/com/termux/app/ClaudeStreamSession.java`

- [ ] **Step 1: Create the file with full skeleton**

```java
package com.termux.app;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application-level singleton wrapping ONE long-lived
 * `claude --input-format stream-json --output-format stream-json`
 * subprocess per conversation.
 *
 * 设计原则：
 *   - 不依赖 Android UI 类（除了 Log）。
 *   - 所有 Listener 回调在子进程 stdout reader 线程触发，调用方负责切回 UI 线程。
 *   - 状态机三态：IDLE / STARTING / WAITING_TURN（详见 spec §状态机）。
 *   - 子进程在 IDLE 默认存活；只在 interrupt() / reset / startWithResume
 *     时显式 SIGTERM。OS 后台清理子进程是合法的边界，下一次 send() 会
 *     带 --resume currentSid 自动恢复。
 */
public final class ClaudeStreamSession {

    private static final String TAG = "ClaudeStream";

    private static final String PREFIX  = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
    private static final String PROOT_D = PREFIX + "/bin/proot-distro";

    public enum State { IDLE, STARTING, WAITING_TURN }

    public interface Listener {
        void onSystem(String info);
        void onAssistantText(String text);
        void onAssistantThinking(String thinking);
        void onToolUse(String name, String inputJson);
        void onToolResult(String name, String summary, String full);
        void onResult(String sid, boolean isError, String errMsg);
        void onProcessDied(String reason);
    }

    // ── Singleton ────────────────────────────────────────────────────────────
    private static volatile ClaudeStreamSession sInstance;
    public static synchronized void init(Context appCtx) {
        if (sInstance == null) sInstance = new ClaudeStreamSession(appCtx.getApplicationContext());
    }
    public static ClaudeStreamSession get(Context ctx) {
        if (sInstance == null) init(ctx);
        return sInstance;
    }

    private final Context mAppCtx;

    private ClaudeStreamSession(Context appCtx) {
        this.mAppCtx = appCtx;
    }

    // ── State ────────────────────────────────────────────────────────────────
    private volatile State    mState           = State.IDLE;
    private volatile Process  mProcess;
    private volatile Thread   mStdoutThread;
    private volatile Thread   mStderrThread;
    private volatile BufferedWriter mStdinWriter;
    private volatile String   mCurrentSid;
    private volatile int      mGeneration      = 0;   // bumps on every kill/spawn

    // Turn-local buffers (cleared on each new WAITING_TURN entry / process death)
    private final Set<String> mEmittedToolUseIds = new HashSet<>();
    /** tool_use id → 工具名（如 "Bash"）。用于把 tool_result 的不透明 tool_use_id
     *  反查成可读名字，传给 onToolResult 显示。turn 边界清空。 */
    private final java.util.Map<String, String> mToolNameById = new java.util.HashMap<>();

    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();

    // ── Public API ───────────────────────────────────────────────────────────

    public State getState() { return mState; }
    public boolean isWaitingResponse() { return mState == State.WAITING_TURN; }
    @Nullable public String getCurrentSessionId() { return mCurrentSid; }

    public void addListener(Listener l)    { if (l != null) mListeners.add(l); }
    public void removeListener(Listener l) { mListeners.remove(l); }

    /** 发送一条 user 消息。subprocess 不存活时自动 spawn。 */
    public synchronized void send(String userText, String apiKey, String baseUrl) {
        if (mState == State.WAITING_TURN) {
            Log.w(TAG, "send() ignored: state=" + mState);
            return;
        }
        if (mProcess == null || !mProcess.isAlive()) {
            // Need to spawn
            if (!spawn(apiKey, baseUrl, mCurrentSid)) return; // spawn() emitted onProcessDied
        }
        mState = State.WAITING_TURN;
        mEmittedToolUseIds.clear();
        mToolNameById.clear();
        writeUserMessage(userText);
    }

    /** SIGTERM 当前子进程；currentSid 保留。下条 send 自动 --resume。 */
    public synchronized void interrupt() {
        killProcess("user interrupt");
    }

    /** SIGTERM + 清 currentSid。下条 send 起全新 session。 */
    public synchronized void resetForNewConversation() {
        mCurrentSid = null;
        killProcess("reset");
    }

    /** SIGTERM + currentSid = sid。下条 send 起新进程并 --resume sid。 */
    public synchronized void startWithResume(String sid) {
        mCurrentSid = sid;
        killProcess("resume " + sid);
    }

    // ── Spawn / kill ─────────────────────────────────────────────────────────

    private boolean spawn(String apiKey, String baseUrl, @Nullable String resumeSid) {
        mState = State.STARTING;
        mGeneration++;
        final int gen = mGeneration;
        try {
            String escapedKey = apiKey.replace("'", "'\\''");
            String escapedUrl = (baseUrl == null ? "" : baseUrl).replace("'", "'\\''");
            String resumeFlag = (resumeSid != null && !resumeSid.isEmpty())
                    ? " --resume '" + resumeSid.replace("'", "'\\''") + "'" : "";
            // stream-json 模式不带 -p / --continue
            String claudeCmd =
                    "ANTHROPIC_API_KEY='" + escapedKey + "'"
                  + (escapedUrl.isEmpty() ? "" : " ANTHROPIC_BASE_URL='" + escapedUrl + "'")
                  + " CLAUDE_DIRECT=1"
                  + " claude --input-format stream-json --output-format stream-json"
                  + " --verbose --dangerously-skip-permissions" + resumeFlag
                  + " 2>/dev/null";

            ProcessBuilder pb = new ProcessBuilder(PROOT_D, "login", "ubuntu",
                    "--user", "claude", "--", "sh", "-c", claudeCmd);
            setupEnv(pb.environment(), apiKey, baseUrl);
            pb.redirectErrorStream(false);
            mProcess = pb.start();
            mStdinWriter = new BufferedWriter(new OutputStreamWriter(mProcess.getOutputStream()));
            Log.i(TAG, "[gen " + gen + "] spawned"
                    + (resumeSid != null ? " --resume " + resumeSid : " (new session)"));
            startStdoutThread(gen);
            startStderrThread(gen);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "spawn failed", e);
            mState = State.IDLE;
            mProcess = null;
            mStdinWriter = null;
            emitProcessDied("spawn failed: " + e.getMessage());
            return false;
        }
    }

    /** SIGTERM 然后 1 秒后升级 SIGKILL；清状态。 */
    private void killProcess(String reason) {
        Process p = mProcess;
        if (p == null) {
            mState = State.IDLE;
            return;
        }
        mGeneration++;  // invalidate stale stdout events
        try {
            p.destroy();
            // 等 1 秒；不阻塞 UI 线程（killProcess 在已经被 synchronized 包住的调用里，
            // 通常来自 UI 线程，但 1s 等待对 UI 影响可接受；后续可改为 worker 线程）
            if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "SIGTERM didn't kill, escalating to SIGKILL");
                p.destroyForcibly();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        mProcess = null;
        mStdinWriter = null;
        mState = State.IDLE;
        mEmittedToolUseIds.clear();
        mToolNameById.clear();
        Log.i(TAG, "killed: " + reason);
    }

    // ── Stdin ────────────────────────────────────────────────────────────────

    private void writeUserMessage(String text) {
        BufferedWriter w = mStdinWriter;
        if (w == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("type", "user");
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            JSONArray content = new JSONArray();
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            content.put(textBlock);
            msg.put("content", content);
            root.put("message", msg);
            w.write(root.toString());
            w.write('\n');
            w.flush();
        } catch (Exception e) {
            Log.e(TAG, "writeUserMessage failed", e);
            emitProcessDied("stdin write failed: " + e.getMessage());
        }
    }

    // ── Stdout / stderr ──────────────────────────────────────────────────────

    private void startStdoutThread(final int gen) {
        mStdoutThread = new Thread(() -> {
            BufferedReader r = new BufferedReader(new InputStreamReader(mProcess.getInputStream()));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (gen != mGeneration) {
                        Log.d(TAG, "[gen " + gen + "] dropping stale line (cur=" + mGeneration + ")");
                        continue;
                    }
                    line = line.trim();
                    if (!line.startsWith("{")) continue;
                    handleStdoutLine(line);
                }
            } catch (Exception e) {
                if (gen == mGeneration) Log.w(TAG, "stdout reader exited", e);
            }
            // Reader EOF → process died
            if (gen == mGeneration) {
                try {
                    int code = mProcess != null ? mProcess.waitFor() : -1;
                    Log.i(TAG, "[gen " + gen + "] process exited code=" + code);
                    mState = State.IDLE;
                    mProcess = null;
                    mStdinWriter = null;
                    emitProcessDied("exit " + code);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "ClaudeStream-stdout");
        mStdoutThread.setDaemon(true);
        mStdoutThread.start();
    }

    private void startStderrThread(final int gen) {
        mStderrThread = new Thread(() -> {
            BufferedReader r = new BufferedReader(new InputStreamReader(mProcess.getErrorStream()));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (gen != mGeneration) continue;
                    // proot 噪音过滤
                    String t = line.trim();
                    if (t.isEmpty()
                            || t.startsWith("proot warning:")
                            || t.startsWith("proot info:")) continue;
                    Log.d(TAG, "stderr: " + t);
                }
            } catch (Exception ignored) {}
        }, "ClaudeStream-stderr");
        mStderrThread.setDaemon(true);
        mStderrThread.start();
    }

    private void handleStdoutLine(String line) {
        try {
            JSONObject obj = new JSONObject(line);
            String type = obj.optString("type");
            switch (type) {
                case "system":    handleSystem(obj);    break;
                case "assistant": handleAssistant(obj); break;
                case "user":      handleUser(obj);      break;
                case "result":    handleResult(obj);    break;
                default: break;
            }
        } catch (Exception e) {
            Log.w(TAG, "JSON parse failed, line=" + line, e);
        }
    }

    private void handleSystem(JSONObject obj) {
        if (!"init".equals(obj.optString("subtype"))) return;
        String cwd   = obj.optString("cwd", "");
        String model = obj.optString("model", "");
        if (cwd.isEmpty() && model.isEmpty()) return;
        StringBuilder sb = new StringBuilder("工作区：").append(cwd.isEmpty() ? "/" : cwd);
        if (!model.isEmpty()) sb.append("  |  模型：").append(model);
        emitSystem(sb.toString());
    }

    private void handleAssistant(JSONObject obj) {
        JSONObject msg = obj.optJSONObject("message");
        if (msg == null) return;
        JSONArray content = msg.optJSONArray("content");
        if (content == null) return;
        StringBuilder textSb = new StringBuilder();
        StringBuilder thinkSb = new StringBuilder();
        List<JSONObject> toolUses = new ArrayList<>();
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null) continue;
            String t = item.optString("type");
            if ("text".equals(t)) {
                String s = item.optString("text", "");
                if (!s.isEmpty()) { if (textSb.length() > 0) textSb.append("\n"); textSb.append(s); }
            } else if ("thinking".equals(t)) {
                String s = item.optString("thinking", "");
                if (!s.isEmpty()) { if (thinkSb.length() > 0) thinkSb.append("\n"); thinkSb.append(s); }
            } else if ("tool_use".equals(t)) {
                toolUses.add(item);
            }
        }
        if (textSb.length() > 0)  emitAssistantText(textSb.toString().trim());
        if (thinkSb.length() > 0) emitAssistantThinking(thinkSb.toString().trim());
        for (JSONObject tu : toolUses) {
            String id = tu.optString("id", "");
            if (id.isEmpty() || mEmittedToolUseIds.contains(id)) continue;
            mEmittedToolUseIds.add(id);
            String name  = tu.optString("name", "?");
            mToolNameById.put(id, name);
            JSONObject in = tu.optJSONObject("input");
            String inputJson = in != null ? in.toString(2) : "";
            emitToolUse(name, inputJson);
        }
    }

    private void handleUser(JSONObject obj) {
        // 来自子进程 stdout 的 type=user 事件通常是 tool_result 回执
        JSONObject msg = obj.optJSONObject("message");
        if (msg == null) return;
        Object content = msg.opt("content");
        if (!(content instanceof JSONArray)) return;
        JSONArray arr = (JSONArray) content;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            if (!"tool_result".equals(item.optString("type"))) continue;
            // tool_use_id 是不透明 id（toolu_01...），从 mToolNameById 反查显示名
            String tuId = item.optString("tool_use_id", "");
            String toolName = mToolNameById.getOrDefault(tuId, "tool");
            String[] sf = summarizeToolResult(item.opt("content"));
            if (sf == null) continue;
            emitToolResult(toolName, sf[0], sf[1]);
        }
    }

    /** 返回 [summary≤200字, full]，无内容返回 null。 */
    private String[] summarizeToolResult(Object content) {
        try {
            String raw;
            if (content instanceof String) {
                raw = (String) content;
            } else if (content instanceof JSONArray) {
                StringBuilder sb = new StringBuilder();
                JSONArray arr = (JSONArray) content;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item == null) continue;
                    String t = item.optString("type");
                    if ("text".equals(t)) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(item.optString("text", ""));
                    } else if ("image".equals(t)) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append("[图片]");
                    }
                }
                raw = sb.toString();
            } else {
                return null;
            }
            raw = raw.trim();
            if (raw.isEmpty()) return null;
            String summary = raw.length() > 200
                    ? raw.substring(0, 197) + "…"
                    : raw;
            // 摘要去多行只取首行
            int nl = summary.indexOf('\n');
            if (nl > 0) summary = summary.substring(0, nl) + "…";
            return new String[]{summary, raw};
        } catch (Exception e) {
            return null;
        }
    }

    private void handleResult(JSONObject obj) {
        if (!"result".equals(obj.optString("type"))) return;
        boolean isError = obj.optBoolean("is_error", false);
        String sid = obj.optString("session_id", "");
        String errMsg = isError ? obj.optString("result", "Claude 返回错误") : "";
        if (!sid.isEmpty()) mCurrentSid = sid;
        mState = State.IDLE;
        mEmittedToolUseIds.clear();
        mToolNameById.clear();
        emitResult(sid.isEmpty() ? null : sid, isError, errMsg);
    }

    // ── Emit helpers (each wraps every listener in try/catch) ────────────────

    private void emitSystem(String info) {
        for (Listener l : mListeners) try { l.onSystem(info); } catch (Throwable t) { Log.e(TAG, "listener.onSystem", t); }
    }
    private void emitAssistantText(String s) {
        for (Listener l : mListeners) try { l.onAssistantText(s); } catch (Throwable t) { Log.e(TAG, "listener.onAssistantText", t); }
    }
    private void emitAssistantThinking(String s) {
        for (Listener l : mListeners) try { l.onAssistantThinking(s); } catch (Throwable t) { Log.e(TAG, "listener.onAssistantThinking", t); }
    }
    private void emitToolUse(String name, String inputJson) {
        for (Listener l : mListeners) try { l.onToolUse(name, inputJson); } catch (Throwable t) { Log.e(TAG, "listener.onToolUse", t); }
    }
    private void emitToolResult(String name, String summary, String full) {
        for (Listener l : mListeners) try { l.onToolResult(name, summary, full); } catch (Throwable t) { Log.e(TAG, "listener.onToolResult", t); }
    }
    private void emitResult(String sid, boolean isError, String errMsg) {
        for (Listener l : mListeners) try { l.onResult(sid, isError, errMsg); } catch (Throwable t) { Log.e(TAG, "listener.onResult", t); }
    }
    private void emitProcessDied(String reason) {
        for (Listener l : mListeners) try { l.onProcessDied(reason); } catch (Throwable t) { Log.e(TAG, "listener.onProcessDied", t); }
    }

    // ── Env (mirrors HomeFragment.setupEnv) ───────────────────────────────────

    private static void setupEnv(Map<String, String> env, String apiKey, String baseUrl) {
        env.put("PREFIX",            PREFIX);
        env.put("HOME",              TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("TMPDIR",            TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        env.put("PATH",              TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                                    + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
        env.put("LD_LIBRARY_PATH",   TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        env.put("LANG",              "en_US.UTF-8");
        env.put("ANTHROPIC_API_KEY", apiKey);
        if (baseUrl != null && !baseUrl.isEmpty()) {
            env.put("ANTHROPIC_BASE_URL", baseUrl);
        }
    }
}
```

- [ ] **Step 2: Verify compiles**

```bash
./gradlew :app:compileDebugJava 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL.

Common errors:
- `cannot find symbol: TermuxConstants` → add import (already in template, double check)
- `cannot find symbol: Base64` → leftover unused import; safe to remove or leave

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/termux/app/ClaudeStreamSession.java
git commit -m "feat: introduce ClaudeStreamSession singleton

Application-level wrapper around one long-lived
'claude --input-format stream-json --output-format stream-json'
subprocess per conversation.

State machine: IDLE / STARTING / WAITING_TURN. Process survives between
turns for prompt-cache hits; only interrupt() / resetForNewConversation()
/ startWithResume() explicitly SIGTERM. On unexpected exit, next send()
spawns again with --resume currentSid for transparent recovery.

Listener interface emits 7 event types (system / assistant text /
thinking / tool_use / tool_result / result / process_died). All
callbacks fire on the stdout reader thread; callers must marshal to UI
thread (HomeFragment does this in Phase 4).

Generation counter (mGeneration) discards stale stdout events from
killed processes, preventing them from polluting the new turn's UI.

Singleton initialised in TermuxApplication.onCreate in the next task;
HomeFragment subscribes in Phase 4. No callers yet — safe to land.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# PHASE 3 — Application Wiring

## Task 3.1: Initialize `ClaudeStreamSession` in `TermuxApplication.onCreate`

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxApplication.java`

- [ ] **Step 1: Add the init call**

Find the existing line:
```java
        TermuxShellEnvironment.init(this);
```
(around line 79)

Insert immediately AFTER it:
```java

        // Initialise the singleton subprocess wrapper for HomeFragment chat.
        // No subprocess is spawned here; lazy on first send().
        ClaudeStreamSession.init(this);
```

- [ ] **Step 2: Verify compiles + installs + boots**

```bash
./gradlew :app:installDebug && \
adb shell am force-stop com.termux && \
adb shell am start -n com.termux/com.termux.app.TermuxActivity && \
sleep 3 && \
adb logcat -d -t 100 | grep -E 'TermuxApplication|ClaudeStream' | tail -10
```
Expected: logcat shows "Starting Application" and no crash. No `ClaudeStream` log appears yet (no send invoked).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/termux/app/TermuxApplication.java
git commit -m "feat: initialise ClaudeStreamSession singleton at app startup

Lazy mode — no subprocess spawned until the first send() arrives from
HomeFragment in Phase 4.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# PHASE 4 — HomeFragment Swap

This is the meaty phase. The plan is: write the new `mListener` + new `sendOrConfirm` first, BEHIND a flag (don't delete the old path yet); then once verified working, delete the old code.

To keep the diff minimal and easy to revert, we do it as ONE atomic rewrite + commit at the end.

## Task 4.1: Rewrite `HomeFragment` to use `ClaudeStreamSession`

**Files:**
- Modify: `app/src/main/java/com/termux/app/HomeFragment.java`

This task touches a lot of lines. The plan groups them into surgical edits. Read all steps before executing — they refer back to each other.

- [ ] **Step 1: Remove unused imports + fields**

In `HomeFragment.java`, find the fields block:
```java
    // ── Claude 子进程 ──────────────────────────────────────────────────────
    private Process mClaudeProcess;
    private Thread  mClaudeThread;
```

Replace with:
```java
    // ── Claude 流式会话（singleton，长驻进程） ─────────────────────────────
    private ClaudeStreamSession             mClaudeSession;
    private ClaudeStreamSession.Listener    mClaudeListener;
    /** 最近一条用户消息文字，onResult 写 SessionStore preview 时用。 */
    private String mLastSentText = "";
```

- [ ] **Step 2: Remove the `mSessionStarted` field (no longer needed in stream mode)**

Find:
```java
    private boolean mSessionStarted  = false;   // 是否已有对话可 --continue
```

Delete this line entirely. (`mCurrentSessionId` stays — it's still tracked for SessionStore.)

- [ ] **Step 3: Initialise the singleton + register listener in `onViewCreated`**

Find this line near the top of `onViewCreated`:
```java
        mDrawerLayout = view.findViewById(R.id.home_drawer_layout);
```

Insert immediately BEFORE it:
```java
        mClaudeSession = ClaudeStreamSession.get(requireContext());
        mClaudeListener = buildClaudeListener();
        mClaudeSession.addListener(mClaudeListener);
```

- [ ] **Step 4: Add `onDestroyView` to unregister listener**

Find the existing `onDestroy` method (which calls `stopAgentPipeWatcher`):
```java
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAgentPipeWatcher();
    }
```

Insert immediately BEFORE it:
```java
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mClaudeSession != null && mClaudeListener != null) {
            mClaudeSession.removeListener(mClaudeListener);
        }
    }
```

- [ ] **Step 5: Add `buildClaudeListener` helper at the bottom of the class**

Find the closing `}` of the `HomeFragment` class (the very last line of the file). Insert immediately BEFORE that `}`:

```java
    // =========================================================================
    // ClaudeStreamSession listener — all callbacks fire on stdout reader
    // thread; we marshal each to the UI thread via mHandler.post.
    // =========================================================================

    private ClaudeStreamSession.Listener buildClaudeListener() {
        return new ClaudeStreamSession.Listener() {
            @Override public void onSystem(String info) {
                mHandler.post(() -> {
                    mAdapter.addMessage(ChatMessage.system(info));
                    scrollToBottom();
                });
            }
            @Override public void onAssistantText(String text) {
                mHandler.post(() -> {
                    mAdapter.updateLastAssistantText(text);
                    scrollToBottom();
                });
            }
            @Override public void onAssistantThinking(String thinking) {
                mHandler.post(() -> {
                    mAdapter.updateLastAssistantThinking(thinking);
                    scrollToBottom();
                });
            }
            @Override public void onToolUse(String name, String inputJson) {
                mHandler.post(() -> {
                    mAdapter.addMessage(ChatMessage.toolUse(name, inputJson));
                    scrollToBottom();
                });
            }
            @Override public void onToolResult(String name, String summary, String full) {
                mHandler.post(() -> {
                    mAdapter.addMessage(ChatMessage.toolResult(name, summary, full));
                    scrollToBottom();
                });
            }
            @Override public void onResult(String sid, boolean isError, String errMsg) {
                mHandler.post(() -> {
                    if (isError) {
                        mAdapter.updateLastAssistantText("⚠ " + errMsg);
                    } else {
                        // 占位 "…" 被流式 text 已经覆盖；若没有任何 text（极少见，全 tool 调用），
                        // 去掉占位避免气泡留空
                        dropPlaceholder();
                    }
                    mAdapter.collapseLastAssistantThinking();
                    mAdapter.collapseAllToolDetailsInLastTurn();
                    mWaitingResponse = false;

                    // 捕获 session_id 并写入 SessionStore（首次或变化时）
                    if (sid != null && !sid.equals(mCurrentSessionId)) {
                        mCurrentSessionId = sid;
                        mUploadStore.commitPending(sid);
                        mSessionStore.add(sid, System.currentTimeMillis(), mLastSentText);
                        refreshSessionDrawer();
                    }
                    updateStatus("● 就绪", 0xFF2E7D32);
                    FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
                    // 写聊天日志（沿用旧路径，让 Termux tab 仍能 tail -f）
                    ChatMessage last = mAdapter.getLastAssistantMessage();
                    if (last != null && last.content != null && !last.content.isEmpty()) {
                        appendChatLog("Claude", last.content);
                    }
                });
            }
            @Override public void onProcessDied(String reason) {
                mHandler.post(() -> {
                    mAdapter.addMessage(ChatMessage.system(
                            "● 进程已退出 (" + reason + ")，下条消息将自动恢复"));
                    mWaitingResponse = false;
                    updateStatus("● 就绪", 0xFF888888);
                    FloatingStatusService.updateStatus("● 就绪", 0xFF888888, "", false);
                    scrollToBottom();
                });
            }
        };
    }
```

- [ ] **Step 6: Replace `sendOrConfirm` with the new stream-json path**

Find the entire `sendOrConfirm()` method (starts around line 452, ends around line 647 — the whole method including the `mClaudeThread = new Thread(() -> { ... }).start();` block).

Replace the **entire method** with:

```java
    private void sendOrConfirm() {
        if (mWaitingResponse || mClaudeSession.isWaitingResponse()) return;

        String text = mInputEdit.getText().toString().trim();
        if (text.isEmpty() && mAttachmentPath == null) { terminal("\r"); return; }

        // 有附件时拼接文件引用（附件路径 + 用户文字）
        final String pendingPath = mAttachmentPath;
        final String pendingName = mAttachmentName;
        if (pendingPath != null) {
            text = "[附件: " + pendingPath + "]\n" + text;
        }
        clearAttachment();

        // 检查 API Key
        ApiKeyStore store = new ApiKeyStore(requireContext());
        String activeId = store.getActiveId();
        String apiKey   = null;
        String baseUrl  = "";
        if (activeId != null) {
            for (ApiKeyStore.Entry e : store.loadAll()) {
                if (e.id.equals(activeId)) { apiKey = e.value; baseUrl = e.baseUrl; break; }
            }
        }
        // 用于 UI 显示的简洁文本（不含路径）
        String typed = mInputEdit.getText().toString().trim();
        String displayText = (typed.isEmpty() && pendingName != null)
                ? "[📎 " + pendingName + "]"
                : typed;
        if (pendingPath != null && !typed.isEmpty()) {
            displayText = "[📎 " + pendingName + "] " + typed;
        }
        if (apiKey == null || apiKey.isEmpty()) {
            mAdapter.addMessage(ChatMessage.user(displayText));
            mInputEdit.setText("");
            mAdapter.addMessage(ChatMessage.assistant("⚠ 请先在「API Key」页面添加并激活一个 API Key。"));
            scrollToBottom();
            return;
        }

        mAdapter.addMessage(ChatMessage.user(displayText));
        scrollToBottom();
        mInputEdit.setText("");
        mAdapter.addMessage(ChatMessage.assistant("…"));
        scrollToBottom();

        mWaitingResponse = true;
        updateStatus("● 运行中", 0xFF1565C0);
        FloatingStatusService.updateStatus("● 运行中", 0xFF1565C0, displayText, true);

        // 写日志（与旧路径一致：每条 user 立即写）
        mLastSentText = text;
        appendChatLog("你", text);

        // 投入 Stream-JSON 进程
        mClaudeSession.send(text, apiKey, baseUrl);
    }
```

- [ ] **Step 7: Replace `stopClaudeProcess` body**

Find:
```java
    private void stopClaudeProcess() {
        if (mClaudeProcess != null) {
            mClaudeProcess.destroy();
            mClaudeProcess = null;
        }
        if (mClaudeThread != null) {
            mClaudeThread.interrupt();
            mClaudeThread = null;
        }
        mWaitingResponse = false;
        updateStatus("● 就绪", 0xFF2E7D32);
        FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
    }
```

Replace with:
```java
    /** "打断" 按钮调用：SIGTERM 当前 turn，currentSid 保留，下条消息 --resume 续接。 */
    private void stopClaudeProcess() {
        if (mClaudeSession != null) mClaudeSession.interrupt();
        mWaitingResponse = false;
        updateStatus("● 就绪", 0xFF2E7D32);
        FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
        // 给最后一条 assistant 气泡追加打断标记（沿用 SYSTEM 灰条提示）
        mAdapter.addMessage(ChatMessage.system("● 已打断"));
        scrollToBottom();
    }
```

- [ ] **Step 8: Update "新建对话" button to use `resetForNewConversation`**

Find the existing handler:
```java
        // "新建对话"：清空所有状态 + 清空 UI，开始全新对话
        btnNewSession.setOnClickListener(v -> {
            stopClaudeProcess();
            mSessionStarted   = false;
            mResumeSessionId  = null;
            mCurrentSessionId = null;
            mMessages.clear();
            mAdapter.notifyDataSetChanged();
            updateSessionTitle(null);
        });
```

Replace with:
```java
        // "新建对话"：杀进程 + 清状态 + 清 UI，开始全新对话
        btnNewSession.setOnClickListener(v -> {
            mClaudeSession.resetForNewConversation();
            mWaitingResponse  = false;
            mResumeSessionId  = null;
            mCurrentSessionId = null;
            mMessages.clear();
            mAdapter.notifyDataSetChanged();
            updateSessionTitle(null);
            updateStatus("● 就绪", 0xFF2E7D32);
            FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
        });
```

- [ ] **Step 9: Update `resumeSession` to call `startWithResume`**

Find:
```java
    private void resumeSession(SessionStore.Entry entry) {
        stopClaudeProcess();
        mMessages.clear();
        mAdapter.notifyDataSetChanged();
        mResumeSessionId  = entry.id;
        mCurrentSessionId = entry.id;
        mSessionStarted   = false;
        mAdapter.addMessage(ChatMessage.system("已切换到历史对话：" + entry.formatTime() + "（加载中…）"));
        ...
```

Replace ONLY the first ~7 lines (up to and including the `mAdapter.addMessage(...)` line) with:

```java
    private void resumeSession(SessionStore.Entry entry) {
        mClaudeSession.startWithResume(entry.id);   // kill 当前 + 标记下条 --resume entry.id
        mMessages.clear();
        mAdapter.notifyDataSetChanged();
        mResumeSessionId  = entry.id;       // 仍保留：本 Fragment 不再使用，但传给 mLastSentText 路径无害
        mCurrentSessionId = entry.id;
        mWaitingResponse  = false;
        updateStatus("● 就绪", 0xFF2E7D32);
        mAdapter.addMessage(ChatMessage.system("已切换到历史对话：" + entry.formatTime() + "（加载中…）"));
```

(Leave the rest of `resumeSession` — the history-loading thread block — unchanged.)

- [ ] **Step 10: Delete now-unused private methods**

The new path no longer calls these methods. Delete them entirely:

1. `extractSessionId(String jsonLine)` — replaced by Manager.handleResult
2. `extractSystemInfo(String jsonLine)` — replaced by Manager.handleSystem
3. `extractAssistant(String jsonLine)` — replaced by Manager.handleAssistant

Also check `summarizeToolResult` — if it's only called from the old removed code paths, remove it; if still used elsewhere (search for callers first), keep it.

```bash
grep -n 'extractAssistant\|extractSessionId\|extractSystemInfo\|summarizeToolResult' app/src/main/java/com/termux/app/HomeFragment.java
```

Delete every method definition that has no remaining callers (besides itself). Keep `parseHistUserEvent` / `parseHistAssistantEvent` — those are used by `loadSessionHistory` for the history-replay path, completely separate.

- [ ] **Step 11: Verify compiles**

```bash
./gradlew :app:compileDebugJava 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL.

Most likely errors and fixes:
- `cannot find symbol mClaudeProcess / mClaudeThread / mSessionStarted` → remaining stale reference; grep and delete.
- `cannot find symbol summarizeToolResult` if you removed it but a caller remains → restore the method or remove the caller.
- `cannot find symbol setupEnv` if you removed it because the new `sendOrConfirm` doesn't use it → check `copyFileAsync` (it still calls `setupEnv`); if so, **keep** `setupEnv`.

- [ ] **Step 12: End-to-end smoke on device**

```bash
./gradlew :app:installDebug
adb shell am force-stop com.termux
adb shell am start -n com.termux/com.termux.app.TermuxActivity
adb logcat -c
```

In the app: open the chat tab and send "hello, who are you?".

Watch logcat:
```bash
adb logcat -v time | grep -E 'ClaudeStream|TermuxApplication'
```

Expected pattern:
1. Within 5-10s: `ClaudeStream: [gen 1] spawned (new session)`
2. Streaming: occasional Java callbacks (no log line per event, listener fires silently)
3. Within 30-60s: `ClaudeStream: [gen 1] process exited code=0` is NOT expected — process should stay alive after result. If you see it, that's a regression to debug before continuing.
4. UI: user bubble, assistant bubble streams text, "● 就绪" appears

Send a SECOND message immediately ("are you still there?"). Confirm:
- No new `spawned` log line (process reused)
- Quick first-token latency vs the first message (prompt cache hit)

- [ ] **Step 13: Commit**

```bash
git add app/src/main/java/com/termux/app/HomeFragment.java
git commit -m "feat: rewrite HomeFragment chat path on top of ClaudeStreamSession

Removes the per-message 'claude -p' ProcessBuilder pattern (~120 lines:
mClaudeProcess / mClaudeThread fields, full sendOrConfirm body,
extractAssistant / extractSessionId / extractSystemInfo parsers).
sendOrConfirm now: validates + adds user bubble + adds '…' placeholder
+ calls mClaudeSession.send(text, apiKey, baseUrl) and returns.

Listener (~40 lines) subscribes in onViewCreated and unsubscribes in
onDestroyView; each callback marshals to UI thread via mHandler.post.
onResult writes SessionStore + chat_history.log + commits pending
uploads, matching the legacy path's persistence semantics.

Button rewires:
  - 打断  → mClaudeSession.interrupt() + '● 已打断' gray bubble
  - 新建  → mClaudeSession.resetForNewConversation()
  - 历史抽屉条目 → mClaudeSession.startWithResume(id)

mSessionStarted field removed (--continue no longer used; session is
held in-process between turns).

Manually verified on device R1LM45S11867DC: cold spawn on first msg,
process reuse on second msg, no spurious exit after onResult.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# PHASE 5 — Verification Matrix + Logging Polish

## Task 5.1: Run the end-to-end test matrix from spec

For each row, perform the action on device, observe outcome, check the box. If a row fails, stop, file it as a bug (open a NOTES.md if needed), don't continue.

**Setup:**
```bash
adb logcat -c
adb logcat -v time | grep -E 'ClaudeStream|TermuxApplication' &
```

- [ ] **Test 1: Cold spawn** — fresh install, open chat, send "hi". Expected:
  - First log line: `[gen 1] spawned (new session)`
  - UI: gray system bubble (cwd + model), then streaming assistant reply
  - Final state: "● 就绪", send button enabled

- [ ] **Test 2: Process reuse** — immediately send "what's 2+2?". Expected:
  - **No** new `spawned` log line
  - Faster first token than Test 1
  - Reply appears, "● 就绪"

- [ ] **Test 3: Interrupt + resume** — send "count to 100 slowly". While streaming, tap 打断. Then immediately send "actually just say hi". Expected:
  - On 打断: `[gen 1] killed: user interrupt`, `[gen 1] process exited code=...`
  - On next send: `[gen 2] spawned --resume <sid>`
  - "actually just say hi" gets a normal reply
  - The new reply's session_id (in logcat / SessionStore) matches the original

- [ ] **Test 4: New conversation** — tap 新建对话. Chat clears. Send "hello fresh". Expected:
  - `killed: reset`, `[gen N] spawned (new session)` — note "(new session)" not "--resume"
  - The new session_id (visible after first message in History drawer or logcat) is DIFFERENT from Test 3's

- [ ] **Test 5: Resume from history drawer** — open drawer, tap an older history entry. Send a follow-up. Expected:
  - `killed: resume <sid>`
  - `[gen N] spawned --resume <sid>`
  - Claude responds with full context of the prior session

- [ ] **Test 6: Tool call rendering** — send "list files in /home". Claude calls Bash. Expected:
  - A `🔧 调用 Bash ▶` bubble appears
  - Tap → expands to show `{"command": "ls /home"}` (or similar)
  - A `📥 Bash: ...summary...` bubble appears after
  - Tap → expands to show full ls output
  - After result: thinking, tool bubbles all auto-collapse

- [ ] **Test 7: Tab switch survives** — start a multi-tool task ("explore this repo"), while running switch to AgentServer tab, wait 5s, switch back. Expected:
  - HomeFragment still shows running task
  - New events (additional tool calls, final reply) still arrive after returning
  - No `process_died` callback

- [ ] **Test 8: OS background kill** — start a chat, send a message, switch app to background (home button), wait 1-2 min, then re-open. Run `adb shell pidof claude` to check if the process is still alive. If alive: skip this test (need a more aggressive OS to trigger). If dead: send a new message — expect "● 进程已退出..." gray bubble + auto `[gen N] spawned --resume <sid>`.

- [ ] **Test 9: AgentServer concurrency** — connect AgentServer (if not connected), have an upstream task send some prompts. Simultaneously chat in HomeFragment. Expected:
  - Two `pidof claude` processes running concurrently (one each)
  - HomeFragment's SessionStore entries and AgentServer's task list stay separate
  - No cross-talk: HomeFragment replies don't reference AgentServer's task content and vice versa

- [ ] **Step 11: Commit a test log if anything was tweaked**

If you ended up making small fixes during testing (string changes, log level tweaks), commit them now with:
```bash
git diff
git add <files>
git commit -m "fix: <specific fix found during e2e test>

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5.2: Optional — clean up unused legacy fields

After everything works, do a final grep pass to find leftover legacy symbols:

- [ ] **Step 1: Find dead references**

```bash
cd C:/ZRS_Works/Claude_code_test_app
grep -n 'mResumeSessionId\|mSessionStarted\|doContinue' app/src/main/java/com/termux/app/HomeFragment.java
```

- `mResumeSessionId` may still be set in `resumeSession` and read in nowhere — safe to delete if so
- `mSessionStarted` should be gone (deleted in Task 4.1 Step 2). If grep still finds it: delete remaining references.
- `doContinue` should not appear at all.

- [ ] **Step 2: If any dead fields found, delete them and recompile**

```bash
./gradlew :app:compileDebugJava 2>&1 | tail -10
```

- [ ] **Step 3: Commit cleanup**

```bash
git add app/src/main/java/com/termux/app/HomeFragment.java
git commit -m "chore: remove unused session-flag fields after Stream-JSON migration

mSessionStarted / doContinue / mResumeSessionId only had meaning in the
old --continue/-p path; ClaudeStreamSession holds session state
in-process now.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Done

After all phases pass, the working tree should also still contain the unrelated `--resume` cross-talk fix in HomeFragment and `cpu_arch` fix in AutoUbuntuManager that were already pending before this plan started. Those are separate commits and not touched here.

**Ship checklist before any release:**
- [ ] All Phase 5 tests passed on device
- [ ] `git log --oneline` shows ~9 commits from this plan (3 in Phase 1, 1 in Phase 2, 1 in Phase 3, 1-2 in Phase 4, 0-2 in Phase 5)
- [ ] No `mClaudeProcess` / `mClaudeThread` references remain in source
- [ ] `grep -rn 'claude -p' app/src/main` shows only documentation strings, not active call sites in HomeFragment
