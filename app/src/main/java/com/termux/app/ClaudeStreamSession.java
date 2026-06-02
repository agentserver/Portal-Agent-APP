package com.termux.app;

import android.content.Context;
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
    public static synchronized ClaudeStreamSession get(Context ctx) {
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
    private final Set<String> mEmittedToolUseIds =
            java.util.Collections.synchronizedSet(new HashSet<>());
    /** tool_use id → 工具名（如 "Bash"）。用于把 tool_result 的不透明 tool_use_id
     *  反查成可读名字，传给 onToolResult 显示。turn 边界清空。 */
    private final java.util.Map<String, String> mToolNameById =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

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
            Process orphan = mProcess;   // capture before null
            mState = State.IDLE;
            mProcess = null;
            mStdinWriter = null;
            if (orphan != null) {
                try { orphan.destroyForcibly(); } catch (Throwable ignored) {}
            }
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
            String inputJson = "";
            if (in != null) {
                try { inputJson = in.toString(2); } catch (Exception ex) { inputJson = in.toString(); }
            }
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
