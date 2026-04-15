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
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
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

    private RecyclerView mRecycler;
    private ChatAdapter  mAdapter;
    private final List<ChatMessage> mMessages = new ArrayList<>();

    private TextView mStatusText;
    private EditText mInputEdit;

    // ── Claude 子进程 ──────────────────────────────────────────────────────
    private Process mClaudeProcess;
    private Thread  mClaudeThread;

    // ── 对话状态 ───────────────────────────────────────────────────────────
    private boolean mWaitingResponse = false;
    private boolean mSessionStarted  = false;   // 是否已有对话可 --continue

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

        mRecycler = view.findViewById(R.id.chat_recycler);
        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        lm.setStackFromEnd(true);
        mRecycler.setLayoutManager(lm);
        mAdapter = new ChatAdapter(mMessages);
        mRecycler.setAdapter(mAdapter);

        mStatusText = view.findViewById(R.id.home_status_text);
        mInputEdit  = view.findViewById(R.id.home_input_edit);

        MaterialButton btnSend       = view.findViewById(R.id.home_send_btn);
        MaterialButton btnEnter      = view.findViewById(R.id.btn_enter);
        MaterialButton btnStart      = view.findViewById(R.id.btn_start_claude);
        MaterialButton btnStop       = view.findViewById(R.id.btn_stop_claude);
        MaterialButton btnRestart    = view.findViewById(R.id.btn_restart_claude);
        MaterialButton btnNewSession = view.findViewById(R.id.btn_new_session);

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

        // "新建会话"：清除 --continue 标志（下次发送开启全新对话）
        btnNewSession.setOnClickListener(v -> {
            stopClaudeProcess();
            mSessionStarted = false;
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

        final String finalKey     = apiKey;
        final String finalBaseUrl = baseUrl;
        final String finalText    = text;
        final boolean doContinue  = mSessionStarted;

        mClaudeThread = new Thread(() -> {
            String lastSnapshot = "";
            try {
                // 单引号转义：' → '\''
                String escaped    = finalText.replace("'", "'\\''");
                String escapedKey = finalKey.replace("'", "'\\''");
                String escapedUrl = finalBaseUrl.replace("'", "'\\''");

                String claudeCmd = "printf '%s' '" + escaped + "'"
                        + " | ANTHROPIC_API_KEY='" + escapedKey + "'"
                        + (finalBaseUrl.isEmpty() ? "" : " ANTHROPIC_BASE_URL='" + escapedUrl + "'")
                        + " claude -p --output-format stream-json --verbose"
                        + (doContinue ? " --continue" : "")
                        + " 2>/dev/null";

                ProcessBuilder pb = new ProcessBuilder(BASH, PROOT_D, "login", "ubuntu", "--", "sh", "-c", claudeCmd);
                setupEnv(pb.environment(), finalKey, finalBaseUrl);
                pb.redirectErrorStream(false);

                mClaudeProcess = pb.start();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(mClaudeProcess.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (!t.startsWith("{")) continue;
                    String snap = extractText(t);
                    if (snap != null) {
                        lastSnapshot = snap;
                        final String ui = lastSnapshot;
                        mHandler.post(() -> { mAdapter.updateLastAssistant(ui); scrollToBottom(); });
                    }
                }
                mClaudeProcess.waitFor();

            } catch (InterruptedException ignored) {
                // 被 stopClaudeProcess() 中断，正常退出
            } catch (Exception e) {
                final String err = e.getMessage();
                mHandler.post(() -> mAdapter.updateLastAssistant("⚠ 进程错误：" + err));
            }

            final String finalSnap = lastSnapshot;
            mHandler.post(() -> {
                if (finalSnap.isEmpty()) dropPlaceholder();
                mWaitingResponse = false;
                mSessionStarted  = true;
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

    private void updateStatus(String text, int color) {
        if (mStatusText != null) {
            mStatusText.setText(text);
            mStatusText.setTextColor(color);
        }
    }

    /** 向当前可见 TerminalSession 写入（供"启动"和"⏎"按钮使用）。 */
    private void terminal(String text) {
        TermuxActivity a = act();
        if (a != null) a.sendTerminalInput(text);
    }

    private void scrollToBottom() {
        mRecycler.post(() -> {
            int last = mAdapter.getItemCount() - 1;
            if (last >= 0) mRecycler.smoothScrollToPosition(last);
        });
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
