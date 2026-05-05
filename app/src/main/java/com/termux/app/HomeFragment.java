package com.termux.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 简化 UI Fragment（ChatGPT 风格）。
 *
 * ── 架构 ────────────────────────────────────────────────────────────────────
 *
 * Chat UI 通过 ProcessBuilder 直接启动独立子进程运行 claude -p，
 * 完全不依赖任何 TerminalSession，终端 Tab 可自由使用。
 *
 * 子进程命令：
 *   bash proot-distro login ubuntu -- sh -c
 *     'printf "%s" "PROMPT" | claude -p --output-format stream-json --verbose
 *      --dangerously-skip-permissions [--continue]'
 *
 * 子进程 stdout 输出纯 JSONL，后台线程逐行解析，主线程更新 UI。
 * type=assistant 事件是累积快照，只取最后一条。
 * type=result 表示 Claude 已完成。
 *
 * ── 会话控制 ─────────────────────────────────────────────────────────────────
 * "启动"   → 在新 TerminalSession 中运行交互式 Claude（供用户手动操作）
 * "停止"   → 终止当前 claude -p 子进程
 * "新建"   → 清除 --continue 标志，下次发送开启新对话
 */
public class HomeFragment extends Fragment {

    // ── Termux 路径常量 ────────────────────────────────────────────────────
    private static final String PREFIX  = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
    private static final String BASH    = PREFIX + "/bin/bash";
    private static final String PROOT_D = PREFIX + "/bin/proot-distro";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private DrawerLayout  mDrawerLayout;
    private RecyclerView  mRecycler;
    private ChatAdapter   mAdapter;
    private final List<ChatMessage> mMessages = new ArrayList<>();

    private TextView mStatusText;
    private TextView mSessionTitle;
    private EditText mInputEdit;
    private TextView mScreenCaptureStatus;
    private TextView mAccessibilityStatus;

    // ── 历史会话抽屉 ──────────────────────────────────────────────────────
    private SessionStore               mSessionStore;
    private List<SessionStore.Entry>   mSessionEntries;
    private SessionAdapter             mSessionAdapter;

    // ── Claude 子进程 ──────────────────────────────────────────────────────
    private Process mClaudeProcess;
    private Thread  mClaudeThread;

    // ── 对话状态 ───────────────────────────────────────────────────────────
    private boolean mWaitingResponse = false;
    private boolean mSessionStarted  = false;   // 是否已有对话可 --continue

    /** 当前捕获到的 Claude session ID（从 type=result 事件提取）。 */
    private String  mCurrentSessionId = null;

    /** 恢复特定会话时设置；null = 使用 --continue 或新建。 */
    private String  mResumeSessionId  = null;

    /** 已写入日志文件的条目数（每次 appendChatLog 时自增），用于同步时跳过已知内容。 */
    private int mSyncedLogEntries = 0;

    // =========================================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mDrawerLayout = view.findViewById(R.id.home_drawer_layout);

        mRecycler = view.findViewById(R.id.chat_recycler);
        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        lm.setStackFromEnd(true);
        mRecycler.setLayoutManager(lm);
        mAdapter = new ChatAdapter(mMessages);
        mRecycler.setAdapter(mAdapter);

        mStatusText          = view.findViewById(R.id.home_status_text);
        mSessionTitle        = view.findViewById(R.id.home_session_title);
        mInputEdit           = view.findViewById(R.id.home_input_edit);
        mScreenCaptureStatus = view.findViewById(R.id.screen_capture_status);
        mAccessibilityStatus = view.findViewById(R.id.accessibility_status);

        // ── 历史会话抽屉 ──────────────────────────────────────────────────
        mSessionStore   = new SessionStore(requireContext());
        mSessionEntries = mSessionStore.loadAll();
        mSessionAdapter = new SessionAdapter(mSessionEntries, mCurrentSessionId, entry -> {
            resumeSession(entry);
            mDrawerLayout.closeDrawers();
        });
        RecyclerView sessionRecycler = view.findViewById(R.id.session_recycler);
        sessionRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        sessionRecycler.setAdapter(mSessionAdapter);

        TextView emptyHint = view.findViewById(R.id.session_empty_hint);
        emptyHint.setVisibility(mSessionEntries.isEmpty() ? View.VISIBLE : View.GONE);

        MaterialButton btnHistory    = view.findViewById(R.id.btn_history);
        MaterialButton btnSend       = view.findViewById(R.id.home_send_btn);
        MaterialButton btnEnter      = view.findViewById(R.id.btn_enter);
        MaterialButton btnStart      = view.findViewById(R.id.btn_start_claude);
        MaterialButton btnStop       = view.findViewById(R.id.btn_stop_claude);
        MaterialButton btnRestart    = view.findViewById(R.id.btn_restart_claude);
        MaterialButton btnNewSession = view.findViewById(R.id.btn_new_session);

        btnHistory.setOnClickListener(v -> mDrawerLayout.openDrawer(android.view.Gravity.START));

        btnSend.setOnClickListener(v -> sendOrConfirm());

        // ⏎ 向当前可见 session 发送回车
        btnEnter.setOnClickListener(v -> terminal("\r"));

        // "启动"：新建 session，运行交互式 Claude（inline 注入 API Key，避免 .bashrc 早返回问题）
        btnStart.setOnClickListener(v -> {
            TermuxActivity a = act();
            if (a != null) {
                // 读取当前激活的 Key
                ApiKeyStore store2   = new ApiKeyStore(requireContext());
                String activeId2     = store2.getActiveId();
                String startKey      = "";
                String startBaseUrl  = "";
                if (activeId2 != null) {
                    for (ApiKeyStore.Entry e : store2.loadAll()) {
                        if (e.id.equals(activeId2)) { startKey = e.value; startBaseUrl = e.baseUrl; break; }
                    }
                }
                String keyEsc = startKey.replace("'", "'\\''");
                String urlEsc = startBaseUrl.replace("'", "'\\''");
                String startCmd = "ANTHROPIC_API_KEY='" + keyEsc + "'"
                        + (startBaseUrl.isEmpty() ? "" : " ANTHROPIC_BASE_URL='" + urlEsc + "'")
                        + " claude";
                a.addNewSessionFromHome();
                terminal("proot-distro login ubuntu -- sh -c '" + startCmd + "'\r");
            }
        });

        // "停止"：终止 claude -p 子进程
        btnStop.setOnClickListener(v -> stopClaudeProcess());

        // "重启"：停止子进程，清除对话历史标志
        btnRestart.setOnClickListener(v -> {
            stopClaudeProcess();
            mSessionStarted = false;
            mAdapter.addMessage(ChatMessage.assistant("— 对话已重置 —"));
            scrollToBottom();
        });

        // "新建会话"：清除所有会话状态，下次发送开启全新对话
        btnNewSession.setOnClickListener(v -> {
            stopClaudeProcess();
            mSessionStarted   = false;
            mResumeSessionId  = null;
            mCurrentSessionId = null;
            mMessages.clear();
            mAdapter.notifyDataSetChanged();
            updateSessionTitle(null);
        });

        // 无障碍权限按钮：跳转系统无障碍设置页
        MaterialButton btnAccessibility = view.findViewById(R.id.btn_accessibility);
        btnAccessibility.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(
                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            requireContext().startActivity(intent);
        });

        // 截图授权按钮：已授权时点击撤销，未授权时弹系统对话框
        MaterialButton btnScreenCapture = view.findViewById(R.id.btn_screen_capture);
        btnScreenCapture.setOnClickListener(v -> {
            TermuxActivity a = act();
            if (a == null) return;
            if (com.termux.app.mcp.ScreenCaptureService.isRunning()) {
                a.stopScreenCapture();
                updateScreenCaptureStatus(false);
            } else {
                a.requestScreenCapturePermission();
            }
        });
    }

    @Override public void onResume() { super.onResume(); startStatusPolling(); }
    @Override public void onPause()  { super.onPause();  stopStatusPolling();  }

    // =========================================================================
    // 发送 — ProcessBuilder 独立进程
    // =========================================================================

    private void sendOrConfirm() {
        if (mWaitingResponse) return;

        String text = mInputEdit.getText().toString().trim();
        if (text.isEmpty()) { terminal("\r"); return; }

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
        if (apiKey == null || apiKey.isEmpty()) {
            mAdapter.addMessage(ChatMessage.user(text));
            mInputEdit.setText("");
            mAdapter.addMessage(ChatMessage.assistant("⚠ 请先在「API Key」页面添加并激活一个 API Key。"));
            scrollToBottom();
            return;
        }

        mAdapter.addMessage(ChatMessage.user(text));
        scrollToBottom();
        mInputEdit.setText("");
        mAdapter.addMessage(ChatMessage.assistant("…"));
        scrollToBottom();

        mWaitingResponse = true;
        updateStatus("● 运行中", 0xFF1565C0);

        final String finalKey        = apiKey;
        final String finalBaseUrl    = baseUrl;
        final String finalText       = text;
        final boolean doContinue     = mSessionStarted;
        final String finalResumeId   = mResumeSessionId;

        // resume 只用一次：第一条消息发出后切换为 --continue
        if (mResumeSessionId != null) mResumeSessionId = null;

        mClaudeThread = new Thread(() -> {
            String lastSnapshot     = "";
            String capturedSessId   = "";
            try {
                // 单引号转义：' → '\''
                String escaped    = finalText.replace("'", "'\\''");
                String escapedKey = finalKey.replace("'", "'\\''");
                String escapedUrl = finalBaseUrl.replace("'", "'\\''");

                String sessionFlag = (finalResumeId != null)
                        ? " --resume " + finalResumeId
                        : (doContinue ? " --continue" : "");

                String claudeCmd = "printf '%s' '" + escaped + "'"
                        + " | ANTHROPIC_API_KEY='" + escapedKey + "'"
                        + (finalBaseUrl.isEmpty() ? "" : " ANTHROPIC_BASE_URL='" + escapedUrl + "'")
                        + " claude -p --output-format stream-json --verbose"
                        + sessionFlag;

                ProcessBuilder pb = new ProcessBuilder(BASH, PROOT_D, "login", "ubuntu", "--", "sh", "-c", claudeCmd);
                setupEnv(pb.environment(), finalKey, finalBaseUrl);
                pb.redirectErrorStream(false);

                mClaudeProcess = pb.start();

                // 先把用户消息追加到日志文件
                appendChatLog("你", finalText);

                // 独立线程读取 stderr，剥离 ANSI 转义码后显示为 SYSTEM 消息（过滤 proot 噪声）
                Process capturedProcess = mClaudeProcess;
                Thread stderrThread = new Thread(() -> {
                    try {
                        BufferedReader err = new BufferedReader(
                                new InputStreamReader(capturedProcess.getErrorStream()));
                        StringBuilder block = new StringBuilder();
                        String errLine;
                        while ((errLine = err.readLine()) != null) {
                            String stripped = stripAnsi(errLine).trim();
                            // 过滤 proot-distro 自身的底层警告（对用户无意义）
                            if (stripped.startsWith("proot warning:")
                                    || stripped.startsWith("proot info:")
                                    || stripped.isEmpty()) {
                                if (stripped.isEmpty() && block.length() > 0) {
                                    final String msg = block.toString().trim();
                                    block.setLength(0);
                                    mHandler.post(() -> {
                                        mAdapter.addMessage(ChatMessage.system(msg));
                                        scrollToBottom();
                                    });
                                }
                                continue;
                            }
                            if (block.length() > 0) block.append("\n");
                            block.append(stripped);
                        }
                        if (block.length() > 0) {
                            final String msg = block.toString().trim();
                            mHandler.post(() -> {
                                mAdapter.addMessage(ChatMessage.system(msg));
                                scrollToBottom();
                            });
                        }
                    } catch (Exception ignored) {}
                }, "ClaudeStderr");
                stderrThread.setDaemon(true);
                stderrThread.start();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(mClaudeProcess.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (!t.startsWith("{")) continue;
                    // type=system init：显示工作区 / 模型信息
                    String sysInfo = extractSystemInfo(t);
                    if (sysInfo != null) {
                        final String info = sysInfo;
                        mHandler.post(() -> { mAdapter.addMessage(ChatMessage.system(info)); scrollToBottom(); });
                        continue;
                    }
                    // type=result：捕获 session_id
                    String sid = extractSessionId(t);
                    if (sid != null) { capturedSessId = sid; continue; }
                    // type=assistant：更新回复气泡
                    String snap = extractText(t);
                    if (snap != null) {
                        lastSnapshot = snap;
                        final String ui = lastSnapshot;
                        mHandler.post(() -> { mAdapter.updateLastAssistant(ui); scrollToBottom(); });
                    }
                }
                mClaudeProcess.waitFor();

            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                final String err = e.getMessage();
                mHandler.post(() -> mAdapter.updateLastAssistant("⚠ 进程错误：" + err));
            }

            final String finalSnap    = lastSnapshot;
            final String finalSessId  = capturedSessId;
            if (!finalSnap.isEmpty()) appendChatLog("Claude", finalSnap);
            // 保存会话到历史记录
            if (!finalSessId.isEmpty()) {
                mSessionStore.add(finalSessId, System.currentTimeMillis(), finalText);
            }
            mHandler.post(() -> {
                if (finalSnap.isEmpty()) dropPlaceholder();
                mWaitingResponse  = false;
                mSessionStarted   = true;
                // 更新当前 session ID 并刷新抽屉
                if (!finalSessId.isEmpty() && !finalSessId.equals(mCurrentSessionId)) {
                    mCurrentSessionId = finalSessId;
                    refreshSessionDrawer();
                }
                updateStatus("● 就绪", 0xFF2E7D32);
            });
        }, "ClaudeProcess");
        mClaudeThread.setDaemon(true);
        mClaudeThread.start();
    }

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
    }

    /** 设置子进程所需的 Termux + ubuntu 环境变量。 */
    private void setupEnv(Map<String, String> env, String apiKey, String baseUrl) {
        env.put("PREFIX",           PREFIX);
        env.put("HOME",             TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("TMPDIR",           TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        env.put("PATH",             TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                                    + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
        env.put("LD_LIBRARY_PATH",  TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        env.put("LANG",             "en_US.UTF-8");
        env.put("ANTHROPIC_API_KEY", apiKey);
        if (baseUrl != null && !baseUrl.isEmpty()) {
            env.put("ANTHROPIC_BASE_URL", baseUrl);
        }
    }

    // =========================================================================
    // JSONL：解析 type=system 初始化事件
    // =========================================================================

    /**
     * 从 type=system subtype=init 事件中提取工作区和模型信息，
     * 替代 TTY 模式下的 workspace trust 对话框。
     * 返回 null 表示不是 system init 事件。
     */
    /** 从 type=result 事件中提取 session_id，供 --resume 使用。 */
    @Nullable
    private String extractSessionId(String jsonLine) {
        if (!jsonLine.contains("\"type\":\"result\"")) return null;
        try {
            JSONObject obj = new JSONObject(jsonLine);
            if (!"result".equals(obj.optString("type"))) return null;
            String sid = obj.optString("session_id", "");
            return sid.isEmpty() ? null : sid;
        } catch (Exception ignored) { return null; }
    }

    private String extractSystemInfo(String jsonLine) {
        if (!jsonLine.contains("\"type\":\"system\"")) return null;
        try {
            JSONObject obj = new JSONObject(jsonLine);
            if (!"system".equals(obj.optString("type"))) return null;
            if (!"init".equals(obj.optString("subtype"))) return null;
            String cwd   = obj.optString("cwd", "");
            String model = obj.optString("model", "");
            if (cwd.isEmpty() && model.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("工作区：").append(cwd.isEmpty() ? "/" : cwd);
            if (!model.isEmpty()) sb.append("  |  模型：").append(model);
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    // =========================================================================
    // 对话日志（~/chat_history.log，终端里可 tail -f 查看）
    // =========================================================================

    private void appendChatLog(String role, String content) {
        try {
            String logPath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/chat_history.log";
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String entry = "[" + ts + "] " + role + "\n" + content + "\n\n";
            BufferedWriter bw = new BufferedWriter(new FileWriter(logPath, true));
            bw.write(entry);
            bw.close();
            mSyncedLogEntries++; // 记录已知条目数，同步时跳过它
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // 工具：剥离 ANSI/VT100 转义序列
    // =========================================================================

    /** 去掉终端控制码，只保留可打印文字。 */
    private static String stripAnsi(String s) {
        // ESC [ ... 参数 最终字母  (CSI 序列)
        s = s.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
        // ESC 单字母序列（如 ESC c, ESC M 等）
        s = s.replaceAll("\u001B[^\\[\\]]", "");
        // OSC 序列 ESC ] ... BEL/ST
        s = s.replaceAll("\u001B][^\u0007\u001B]*(\u0007|\u001B\\\\)", "");
        // 其余控制字符（CR、BEL 等），保留 \n
        s = s.replaceAll("[\u0000-\u0008\u000B-\u001A\u001C-\u001F\u007F]", "");
        return s;
    }

    // =========================================================================
    // JSONL 解析（在后台线程调用）
    // =========================================================================

    /**
     * 从单行 JSONL 中提取 type=assistant 的文字快照。
     * stream-json 格式下每个 assistant 事件是累积快照，取最后一个即可。
     * 返回 null 表示本行不是 assistant 事件或无文字内容。
     */
    @Nullable
    private String extractText(String jsonLine) {
        if (!jsonLine.contains("\"type\":\"assistant\"")) return null;
        try {
            JSONObject obj = new JSONObject(jsonLine);
            if (!"assistant".equals(obj.optString("type"))) return null;
            JSONObject msg = obj.optJSONObject("message");
            if (msg == null) return null;
            JSONArray content = msg.optJSONArray("content");
            if (content == null) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject item = content.getJSONObject(i);
                if ("text".equals(item.optString("type"))) {
                    String txt = item.optString("text");
                    if (!txt.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(txt);
                    }
                }
            }
            return sb.length() > 0 ? sb.toString().trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    // =========================================================================
    // 状态轮询（仅用于显示终端 session 活跃状态）
    // =========================================================================

    private Runnable mStatusPoller;

    private void startStatusPolling() {
        mStatusPoller = new Runnable() {
            @Override public void run() {
                if (!mWaitingResponse) {
                    TermuxActivity a = act();
                    boolean active = a != null && a.hasActiveSession();
                    updateStatus(active ? "● 会话活跃" : "● 就绪",
                                 active ? 0xFF2E7D32    : 0xFF888888);
                }
                updateScreenCaptureStatus(com.termux.app.mcp.ScreenCaptureService.isRunning());
                updateAccessibilityStatus(com.termux.app.mcp.McpAccessibilityService.isRunning());
                mHandler.postDelayed(this, 2000);
            }
        };
        mHandler.post(mStatusPoller);
    }

    private void stopStatusPolling() {
        if (mStatusPoller != null) { mHandler.removeCallbacks(mStatusPoller); mStatusPoller = null; }
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    /** 从历史列表恢复指定会话：清空 UI、设置 resume ID、更新标题。 */
    private void resumeSession(SessionStore.Entry entry) {
        stopClaudeProcess();
        mMessages.clear();
        mAdapter.notifyDataSetChanged();
        mResumeSessionId  = entry.id;
        mCurrentSessionId = entry.id;
        mSessionStarted   = false;
        mAdapter.addMessage(ChatMessage.system("已切换到历史对话：" + entry.formatTime()));
        scrollToBottom();
        updateSessionTitle(entry.preview);
        mSessionAdapter.setActiveId(entry.id);
    }

    /** 重新从 SessionStore 加载列表，刷新抽屉。 */
    private void refreshSessionDrawer() {
        mSessionEntries.clear();
        mSessionEntries.addAll(mSessionStore.loadAll());
        mSessionAdapter.setActiveId(mCurrentSessionId);
    }

    /** 更新顶栏标题；preview 为 null 时显示默认 "Claude Code"。 */
    private void updateSessionTitle(@Nullable String preview) {
        if (mSessionTitle == null) return;
        if (preview == null || preview.isEmpty()) {
            mSessionTitle.setText("Claude Code");
        } else {
            mSessionTitle.setText(preview.length() > 30 ? preview.substring(0, 30) + "…" : preview);
        }
    }

    private void updateStatus(String text, int color) {
        if (mStatusText != null) {
            mStatusText.setText(text);
            mStatusText.setTextColor(color);
        }
    }

    private void updateAccessibilityStatus(boolean enabled) {
        if (mAccessibilityStatus == null) return;
        if (enabled) {
            mAccessibilityStatus.setText("● 无障碍: 已启用");
            mAccessibilityStatus.setTextColor(0xFF2E7D32);
        } else {
            mAccessibilityStatus.setText("● 无障碍: 未启用");
            mAccessibilityStatus.setTextColor(0xFF888888);
        }
        View root = getView();
        if (root != null) {
            MaterialButton b = root.findViewById(R.id.btn_accessibility);
            if (b != null) b.setText(enabled ? "无障碍设置" : "开启无障碍");
        }
    }

    private void updateScreenCaptureStatus(boolean granted) {
        if (mScreenCaptureStatus == null) return;
        if (granted) {
            mScreenCaptureStatus.setText("● 截图: 已授权");
            mScreenCaptureStatus.setTextColor(0xFF2E7D32);
        } else {
            mScreenCaptureStatus.setText("● 截图: 未授权");
            mScreenCaptureStatus.setTextColor(0xFF888888);
        }
        // Update button label
        View btn = getView();
        if (btn != null) {
            MaterialButton b = btn.findViewById(R.id.btn_screen_capture);
            if (b != null) b.setText(granted ? "撤销截图" : "授权截图");
        }
    }

    /** 向当前可见 TerminalSession 写入（供"启动"和"⏎"按钮使用）。 */
    private void terminal(String text) {
        TermuxActivity a = act();
        if (a != null) a.sendTerminalInput(text);
    }

    public void scrollToBottom() {
        mRecycler.post(() -> {
            int last = mAdapter.getItemCount() - 1;
            if (last >= 0) mRecycler.smoothScrollToPosition(last);
        });
    }

    /**
     * 由 TermuxActivity.syncChatLogToHome() 在主线程调用。
     * 传入日志文件解析后的所有条目，只把第 mSyncedLogEntries 条之后的新内容加入 UI。
     * entries: 每项为 [header, body]，header 形如 "[14:32:01] 你"。
     */
    public void syncFromLog(java.util.List<String[]> entries) {
        int start = mSyncedLogEntries; // 跳过本 Fragment 自己写过的条目
        for (int i = start; i < entries.size(); i++) {
            String header = entries.get(i)[0];
            String body   = entries.get(i)[1];
            if (header.contains("] 你")) {
                mAdapter.addMessage(ChatMessage.user(body));
            } else if (header.contains("] Claude")) {
                mAdapter.addMessage(ChatMessage.assistant(body));
            } else {
                mAdapter.addMessage(ChatMessage.system(body));
            }
            mSyncedLogEntries++;
        }
    }

    private void dropPlaceholder() {
        if (!mMessages.isEmpty()) {
            ChatMessage last = mMessages.get(mMessages.size() - 1);
            if (last.type == ChatMessage.Type.ASSISTANT
                    && (last.content.equals("…") || last.content.trim().isEmpty())) {
                int idx = mMessages.size() - 1;
                mMessages.remove(idx);
                mAdapter.notifyItemRemoved(idx);
            }
        }
    }

    @Nullable
    private TermuxActivity act() {
        return (getActivity() instanceof TermuxActivity)
            ? (TermuxActivity) getActivity() : null;
    }
}
