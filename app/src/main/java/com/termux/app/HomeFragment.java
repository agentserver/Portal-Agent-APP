package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.automation.AndroidMcpActionRunner;
import com.termux.app.automation.AutomationRuntime;
import com.termux.app.automation.AutomationStore;
import com.termux.app.automation.BoostExecutor;
import com.termux.app.automation.ToolTraceStore;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
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

    // Ubuntu rootfs 内 claude 用户目录
    private static final String UBUNTU_CLAUDE_HOME =
        PREFIX + "/var/lib/proot-distro/installed-rootfs/ubuntu/home/claude";
    private static final String UBUNTU_CODEX_HOME =
        PREFIX + "/var/lib/proot-distro/installed-rootfs/ubuntu/home/codex";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private DrawerLayout  mDrawerLayout;
    private RecyclerView  mRecycler;
    private ChatAdapter   mAdapter;
    private final List<ChatMessage> mMessages = new ArrayList<>();

    private TextView     mStatusText;
    private TextView     mSessionTitle;
    private EditText     mInputEdit;
    private TextView     mScreenCaptureStatus;
    private TextView     mAccessibilityStatus;
    private LinearLayout mAttachmentPreviewRow;
    private TextView     mAttachmentNameText;

    // ── 文件附件 ───────────────────────────────────────────────────────────
    private static final int REQUEST_CODE_PICK_FILE = 1002;
    private String mAttachmentPath; // 已复制到 Termux home 的绝对路径
    private String mAttachmentName; // 用于 UI 显示的文件名
    private AssistantProvider mAttachmentProvider;

    // ── 抽屉 Tab ──────────────────────────────────────────────────────────
    private ViewFlipper mDrawerFlipper;
    private TextView    mTabHistory, mTabMemory, mTabSkills, mTabAgentTask, mTabUploads;

    // ── 上传文件面板 ──────────────────────────────────────────────────────
    private UploadStore              mUploadStore;
    private RecyclerView             mUploadRecycler;
    private TextView                 mUploadEmptyHint;
    private final List<UploadItem>   mUploadItems = new ArrayList<>();
    private RecyclerView.Adapter<?>  mUploadAdapter;

    static class UploadItem {
        String sessionId;    // "__pending__" 或真实 sessionId
        String uuid;         // 物理目录 uuid（~/uploads/<uuid>/<filename>）
        String filename;
        String sessionLabel; // 会话时间字符串，或 "待关联"
        UploadItem(String sessionId, String uuid, String filename, String sessionLabel) {
            this.sessionId    = sessionId;
            this.uuid         = uuid;
            this.filename     = filename;
            this.sessionLabel = sessionLabel;
        }
    }

    // ── AgentServer 任务面板 ──────────────────────────────────────────────
    private static final String PREF_AGENT_PIPE_CLAUDE = "agent_pipe";
    private static final String PREF_AGENT_PIPE_CODEX  = "agent_pipe_codex";
    private static final String KEY_AGENT_PIPE_OFFSET = "offset";
    private RecyclerView          mAgentTaskRecycler;
    private TextView              mAgentTaskEmptyHint;
    private Thread                mAgentPipeWatcher;
    private long                  mAgentPipeOffset = 0;
    private AgentTaskStore        mAgentTaskStore;
    private final List<AgentTask> mAgentTasks      = new ArrayList<>();
    private AgentTaskListAdapter  mAgentTaskAdapter;
    private AgentTask             mActiveAgentTask;

    // ── 历史会话抽屉 ──────────────────────────────────────────────────────
    private SessionStore               mSessionStore;
    private ChatTranscriptStore        mTranscriptStore;
    private List<SessionStore.Entry>   mSessionEntries;
    private SessionAdapter             mSessionAdapter;
    private TextView                   mSessionEmptyHint;

    // ── 记忆库 ────────────────────────────────────────────────────────────
    private final List<DrawerFileAdapter.FileItem> mMemoryItems  = new ArrayList<>();
    private DrawerFileAdapter                       mMemoryAdapter;

    // ── 技能 ──────────────────────────────────────────────────────────────
    private final List<DrawerFileAdapter.FileItem> mSkillsItems  = new ArrayList<>();
    private DrawerFileAdapter                       mSkillsAdapter;

    // ── Claude 流式会话（singleton，长驻进程） ─────────────────────────────
    private ClaudeStreamSession             mClaudeSession;
    private ClaudeStreamSession.Listener    mClaudeListener;
    private ProviderSettingsStore           mProviderStore;
    private AssistantProvider               mProvider = AssistantProvider.CODEX;
    private CodexExecSession                mCodexSession;
    private CodexExecSession.Listener       mCodexListener;
    private volatile int                    mUiGeneration = 0;
    private volatile int                    mViewGeneration = 0;
    private volatile boolean                mViewActive = false;
    /** 最近一条用户消息文字，onResult 写 SessionStore preview 时用。 */
    private String mLastSentText = "";
    private com.termux.app.automation.AutomationRuntime mAutomationRuntime;
    private long mAutomationTurnStartMs = 0L;
    private volatile boolean mBoosting = false;
    private volatile int mBoostGeneration = 0;

    // ── 对话状态 ───────────────────────────────────────────────────────────
    private boolean mWaitingResponse = false;
    private boolean mTurnOutputAnchored = false;

    /** 当前捕获到的 Claude session ID（从 type=result 事件提取）。 */
    private String  mCurrentSessionId = null;
    private String  mClaudeCurrentSessionId = null;
    private String  mCodexCurrentSessionId = null;
    private static final String PREF_HOME_CURRENT_SESSIONS = "home_current_sessions";
    private static final String KEY_CURRENT_CLAUDE = "current_claude";
    private static final String KEY_CURRENT_CODEX  = "current_codex";

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

        mProviderStore = new ProviderSettingsStore(requireContext());
        mProvider = mProviderStore.getSelectedProvider();
        mClaudeCurrentSessionId = loadSavedSessionId(AssistantProvider.CLAUDE);
        mCodexCurrentSessionId = loadSavedSessionId(AssistantProvider.CODEX);
        mCurrentSessionId = mProvider == AssistantProvider.CODEX
            ? mCodexCurrentSessionId
            : mClaudeCurrentSessionId;

        mDrawerLayout = view.findViewById(R.id.home_drawer_layout);

        mRecycler = view.findViewById(R.id.chat_recycler);
        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        lm.setStackFromEnd(true);
        mRecycler.setLayoutManager(lm);
        mAdapter = new ChatAdapter(mMessages);
        updateAssistantLabel();
        mRecycler.setAdapter(mAdapter);

        mStatusText           = view.findViewById(R.id.home_status_text);
        mSessionTitle         = view.findViewById(R.id.home_session_title);
        mInputEdit            = view.findViewById(R.id.home_input_edit);
        mScreenCaptureStatus  = view.findViewById(R.id.screen_capture_status);
        mAccessibilityStatus  = view.findViewById(R.id.accessibility_status);
        mAttachmentPreviewRow = view.findViewById(R.id.attachment_preview_row);
        mAttachmentNameText   = view.findViewById(R.id.attachment_name_text);
        mSessionTitle.setOnClickListener(v -> showProviderDialog());
        View homeSettingsButton = view.findViewById(R.id.btn_home_settings);
        if (homeSettingsButton != null) {
            homeSettingsButton.setOnClickListener(v -> startActivity(new Intent(
                requireContext(), com.termux.app.activities.SettingsActivity.class)));
        }
        updateSessionTitle(null);

        // ── 抽屉 Tab 栏 ───────────────────────────────────────────────────
        mDrawerFlipper  = view.findViewById(R.id.drawer_flipper);
        mTabHistory     = view.findViewById(R.id.tab_history);
        mTabMemory      = view.findViewById(R.id.tab_memory);
        mTabSkills      = view.findViewById(R.id.tab_skills);
        mTabAgentTask   = view.findViewById(R.id.tab_agent_task);

        mTabUploads     = view.findViewById(R.id.tab_uploads);

        mTabHistory  .setOnClickListener(v -> switchDrawerTab(0));
        mTabMemory   .setOnClickListener(v -> { switchDrawerTab(1); loadMemoryFiles(); });
        mTabSkills   .setOnClickListener(v -> { switchDrawerTab(2); loadSkillsFiles(); });
        mTabAgentTask.setOnClickListener(v -> switchDrawerTab(3));
        mTabUploads  .setOnClickListener(v -> { switchDrawerTab(4); loadUploadFiles(); });

        // ── AgentServer 任务面板（列表形式）─────────────────────────────────
        mAgentTaskStore     = new AgentTaskStore(requireContext(), mProvider);
        mAgentTaskEmptyHint = view.findViewById(R.id.agent_task_empty_hint);
        mAgentTaskRecycler  = view.findViewById(R.id.agent_task_recycler);
        mAgentTasks.clear();
        mAgentTasks.addAll(mAgentTaskStore.loadAll());
        mAgentTaskAdapter = new AgentTaskListAdapter(mAgentTasks,
            new AgentTaskListAdapter.Listener() {
                @Override public void onTap(AgentTask task) {
                    if (act() != null) {
                        mDrawerLayout.closeDrawers();
                        act().showAgentTaskDetailMode(mProvider, task.id);
                    }
                }
                @Override public void onLongPress(AgentTask task) {
                    if (getContext() == null) return;
                    new AlertDialog.Builder(getContext())
                        .setTitle("删除该任务？")
                        .setMessage(task.previewLine().isEmpty() ? "（空任务）" : task.previewLine())
                        .setPositiveButton("删除", (d, w) -> {
                            mAgentTaskStore.delete(task.id);
                            deleteAgentTaskFile(task.id);
                            mAgentTasks.remove(task);
                            mAgentTaskAdapter.notifyDataSetChanged();
                            mAgentTaskEmptyHint.setVisibility(
                                mAgentTasks.isEmpty() ? View.VISIBLE : View.GONE);
                        })
                        .setNegativeButton("取消", null).show();
                }
            });
        mAgentTaskRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        mAgentTaskRecycler.setAdapter(mAgentTaskAdapter);
        mAgentTaskEmptyHint.setVisibility(mAgentTasks.isEmpty() ? View.VISIBLE : View.GONE);

        view.findViewById(R.id.btn_agent_task_clear).setOnClickListener(v -> {
            new AlertDialog.Builder(requireContext())
                .setTitle("清空所有任务记录？")
                .setPositiveButton("清空", (d, w) -> {
                    mAgentTaskStore.clear();
                    deleteAllAgentTaskFiles();
                    mAgentTasks.clear();
                    mAgentTaskAdapter.notifyDataSetChanged();
                    mAgentTaskEmptyHint.setVisibility(View.VISIBLE);
                })
                .setNegativeButton("取消", null).show();
        });

        // Pipe watcher：用持久化 offset 续读，否则只回放末尾一小段
        loadAgentPipeOffsetForCurrentProvider();
        processNewPipeLines();
        startAgentPipeWatcher();

        // ── 上传文件面板 ──────────────────────────────────────────────────
        mUploadStore     = new UploadStore(requireContext(), mProvider);
        mUploadRecycler  = view.findViewById(R.id.upload_recycler);
        mUploadEmptyHint = view.findViewById(R.id.upload_empty_hint);
        setupUploadRecycler();
        view.findViewById(R.id.btn_uploads_clear_all).setOnClickListener(v -> clearAllUploads());

        // ── 历史会话抽屉 ──────────────────────────────────────────────────
        mSessionStore   = new SessionStore(requireContext(), mProvider);
        mTranscriptStore = new ChatTranscriptStore(requireContext(), mProvider);
        mSessionEntries = mSessionStore.loadAll();
        // 启动自洁：删除 SessionStore 已不持有的 jsonl 孤儿（来自旧版 leak）
        cleanupOrphanClaudeJsonl();
        mSessionEmptyHint = view.findViewById(R.id.session_empty_hint);
        mSessionAdapter = new SessionAdapter(mSessionEntries, mCurrentSessionId,
            new SessionAdapter.Listener() {
                @Override public void onSessionSelected(SessionStore.Entry entry) {
                    resumeSession(entry);
                    mDrawerLayout.closeDrawers();
                }
                @Override public void onSessionLongPress(SessionStore.Entry entry) {
                    if (getContext() == null) return;
                    new AlertDialog.Builder(getContext())
                        .setTitle("删除这条历史记录？")
                        .setMessage(entry.preview.isEmpty() ? "（空对话）" : entry.preview)
                        .setPositiveButton("删除", (d, w) -> {
                            mSessionStore.delete(entry.id);
                            if (mProvider == AssistantProvider.CODEX && mTranscriptStore != null) {
                                mTranscriptStore.delete(entry.id);
                            }
                            mSessionEntries.remove(entry);
                            mSessionAdapter.notifyDataSetChanged();
                            mSessionEmptyHint.setVisibility(
                                mSessionEntries.isEmpty() ? View.VISIBLE : View.GONE);
                            // 同步删除该对话关联的上传文件（rm -rf 每个 UUID 目录）
                            List<UploadStore.Entry> toDelete = mUploadStore.deleteSession(entry.id);
                            if (!toDelete.isEmpty()) {
                                final List<String> uuids = new ArrayList<>();
                                for (UploadStore.Entry e : toDelete) uuids.add(e.uuid);
                                final AssistantProvider provider = mProvider;
                                new Thread(() -> deleteUbuntuUploadDirs(provider, uuids)).start();
                            }
                            // 同步删除 Claude session jsonl（释放磁盘里的图片/历史）
                            if (mProvider == AssistantProvider.CLAUDE) {
                                new Thread(() -> deleteClaudeSessionJsonl(entry.id)).start();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                }
            });
        RecyclerView sessionRecycler = view.findViewById(R.id.session_recycler);
        sessionRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        sessionRecycler.setAdapter(mSessionAdapter);
        mSessionEmptyHint.setVisibility(mSessionEntries.isEmpty() ? View.VISIBLE : View.GONE);

        // ── 记忆库 RecyclerView ───────────────────────────────────────────
        mMemoryAdapter = new DrawerFileAdapter(mMemoryItems, new DrawerFileAdapter.Listener() {
            @Override public void onTap(DrawerFileAdapter.FileItem item) {
                showFileContentDialog(item.name, item.fullPath, false);
            }
            @Override public void onLongPress(DrawerFileAdapter.FileItem item) {
                confirmDelete(item, mMemoryItems, mMemoryAdapter,
                    view.findViewById(R.id.memory_empty_hint));
            }
        });
        RecyclerView memoryRecycler = view.findViewById(R.id.memory_recycler);
        memoryRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        memoryRecycler.setAdapter(mMemoryAdapter);
        view.findViewById(R.id.btn_memory_refresh).setOnClickListener(v -> loadMemoryFiles());

        // ── 技能 RecyclerView ─────────────────────────────────────────────
        mSkillsAdapter = new DrawerFileAdapter(mSkillsItems, new DrawerFileAdapter.Listener() {
            @Override public void onTap(DrawerFileAdapter.FileItem item) {
                showFileContentDialog(item.name, item.fullPath, true);
            }
            @Override public void onLongPress(DrawerFileAdapter.FileItem item) {
                confirmDelete(item, mSkillsItems, mSkillsAdapter,
                    view.findViewById(R.id.skills_empty_hint));
            }
        });
        RecyclerView skillsRecycler = view.findViewById(R.id.skills_recycler);
        skillsRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        skillsRecycler.setAdapter(mSkillsAdapter);
        view.findViewById(R.id.btn_skill_add).setOnClickListener(v -> showNewSkillDialog());

        MaterialButton btnHistory      = view.findViewById(R.id.btn_history);
        MaterialButton btnSend         = view.findViewById(R.id.home_send_btn);
        MaterialButton btnEnter        = view.findViewById(R.id.btn_enter);
        MaterialButton btnStop         = view.findViewById(R.id.btn_stop_claude);
        MaterialButton btnNewSession   = view.findViewById(R.id.btn_new_session);
        MaterialButton btnAttach       = view.findViewById(R.id.btn_attach_file);
        MaterialButton btnClearAttach  = view.findViewById(R.id.btn_clear_attachment);

        btnHistory.setOnClickListener(v -> mDrawerLayout.openDrawer(android.view.Gravity.START));

        btnSend.setOnClickListener(v -> sendOrConfirm());

        btnAttach.setOnClickListener(v -> pickFile());
        btnClearAttach.setOnClickListener(v -> clearAttachment());

        // 向当前可见 session 发送回车
        btnEnter.setOnClickListener(v -> terminal("\r"));

        // "打断"：发送 SIGTERM 给当前 turn；currentSid 不变，下条消息自动 --resume 续接
        btnStop.setOnClickListener(v -> stopClaudeProcess());

        // "新建对话"：杀进程 + 清状态 + 清 UI，开始全新对话
        btnNewSession.setOnClickListener(v -> {
            mUiGeneration++;
            cancelAutomationBoost();
            if (mProvider == AssistantProvider.CODEX) {
                mCodexSession.resetForNewConversation();
            } else {
                mClaudeSession.resetForNewConversation();
            }
            mWaitingResponse  = false;
            mCurrentSessionId = null;
            if (mProvider == AssistantProvider.CODEX) {
                mCodexCurrentSessionId = null;
            } else {
                mClaudeCurrentSessionId = null;
            }
            saveSessionIdForProvider(mProvider, null);
            mTurnOutputAnchored = false;
            mMessages.clear();
            mAdapter.notifyDataSetChanged();
            if (mSessionAdapter != null) mSessionAdapter.setActiveId(null);
            updateSessionTitle(null);
            updateStatus("● 就绪", 0xFF2E7D32);
            FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
        });

        // 无障碍状态指示：点击跳转系统无障碍设置页
        View.OnClickListener accessibilityClick = v -> {
            android.content.Intent intent = new android.content.Intent(
                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            requireContext().startActivity(intent);
        };
        if (mAccessibilityStatus != null) {
            mAccessibilityStatus.setOnClickListener(accessibilityClick);
        }

        // 截图状态指示：已授权时点击撤销，未授权时弹系统对话框
        View.OnClickListener screenCaptureClick = v -> {
            TermuxActivity a = act();
            if (a == null) return;
            if (com.termux.app.mcp.ScreenCaptureService.isRunning()) {
                a.stopScreenCapture();
                updateScreenCaptureStatus(false);
            } else {
                a.requestScreenCapturePermission();
            }
        };
        if (mScreenCaptureStatus != null) {
            mScreenCaptureStatus.setOnClickListener(screenCaptureClick);
        }

        mViewGeneration++;
        mViewActive = true;
        mCodexSession = CodexExecSession.get(requireContext());
        mCodexListener = buildCodexListener();
        mCodexSession.addListener(mCodexListener);

        mClaudeSession = ClaudeStreamSession.get(requireContext());
        mClaudeListener = buildClaudeListener();
        mClaudeSession.addListener(mClaudeListener);
        AutomationStore automationStore = new AutomationStore(requireContext());
        ToolTraceStore traceStore = new ToolTraceStore(requireContext());
        BoostExecutor boostExecutor = new BoostExecutor(
            new AndroidMcpActionRunner(requireContext()), automationStore);
        mAutomationRuntime = new AutomationRuntime(
            requireContext(), automationStore, traceStore, boostExecutor);
        restoreCodexTranscriptIfNeeded();

        // 悬浮窗权限检查：未授权时点击状态栏跳转设置
        if (!android.provider.Settings.canDrawOverlays(requireContext())) {
            mStatusText.setText("● 点此授权悬浮窗");
            mStatusText.setTextColor(0xFFFF6F00);
            mStatusText.setClickable(true);
            mStatusText.setFocusable(true);
            mStatusText.setOnClickListener(v -> {
                Intent oi = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(oi);
                mStatusText.setOnClickListener(null);
                mStatusText.setClickable(false);
                mStatusText.setFocusable(false);
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startStatusPolling();
        // 回到界面后若已授权，清除授权提示
        if (android.provider.Settings.canDrawOverlays(requireContext()) && mStatusText != null) {
            mStatusText.setOnClickListener(null);
            mStatusText.setClickable(false);
            mStatusText.setFocusable(false);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopStatusPolling();
    }

    @Override
    public void onDestroyView() {
        cancelAutomationBoost();
        mViewActive = false;
        mUiGeneration++;
        mViewGeneration++;
        if (mCodexSession != null && mCodexListener != null) {
            mCodexSession.removeListener(mCodexListener);
        }
        if (mClaudeSession != null && mClaudeListener != null) {
            mClaudeSession.removeListener(mClaudeListener);
        }
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAgentPipeWatcher();
    }

    // =========================================================================
    // 发送 — Stream-JSON 长驻进程 (ClaudeStreamSession singleton)
    // =========================================================================

    private void sendOrConfirm() {
        if (isAnyProviderBusy()) {
            return;
        }

        String typed = mInputEdit.getText().toString().trim();
        String text = typed;
        if (text.isEmpty() && mAttachmentPath == null) { terminal("\r"); return; }
        if (mAttachmentPath != null && mAttachmentProvider != null
                && mAttachmentProvider != mProvider) {
            Toast.makeText(getContext(), "附件属于其他助手，请重新选择文件", Toast.LENGTH_SHORT).show();
            clearAttachment();
            return;
        }

        // 有附件时拼接文件引用（附件路径 + 用户文字）
        final String pendingPath = mAttachmentPath;
        final String pendingName = mAttachmentName;
        if (pendingPath != null) {
            text = "[附件: " + pendingPath + "]\n" + text;
        }

        // 用于 UI 显示的简洁文本（不含路径）
        String displayText = (typed.isEmpty() && pendingName != null)
                ? "[附件 " + pendingName + "]"
                : typed;
        if (pendingPath != null && !typed.isEmpty()) {
            displayText = "[附件 " + pendingName + "] " + typed;
        }
        clearAttachment();

        if (pendingPath == null && mAutomationRuntime != null && tryBoost(text, displayText)) {
            mInputEdit.setText("");
            return;
        }

        sendToProvider(text, displayText);
    }

    private void sendToProvider(String text, String displayText) {
        // 检查 API Key
        ProviderProfile profile = ProviderProfile.forProvider(mProvider);
        ApiKeyStore store = new ApiKeyStore(requireContext(), mProvider);
        String activeId = store.getActiveId();
        String apiKey   = null;
        String baseUrl  = "";
        if (activeId != null) {
            for (ApiKeyStore.Entry e : store.loadAll()) {
                if (e.id.equals(activeId)) { apiKey = e.value; baseUrl = e.baseUrl; break; }
            }
        }
        if (apiKey == null || apiKey.isEmpty()) {
            mAdapter.addMessage(ChatMessage.user(displayText));
            mInputEdit.setText("");
            mAdapter.addMessage(ChatMessage.assistant(
                "⚠ 请先在「API Key」页面添加并激活一个 " + profile.displayName + " API Key。"));
            scrollToBottom();
            return;
        }
        if (mProvider == AssistantProvider.CODEX) {
            try {
                ProviderConfigManager.writeCodexConfig(requireContext(),
                    new ProviderConfigManager.ProviderConfig(apiKey, baseUrl, ""));
            } catch (Exception ignored) {
            }
        }

        mTurnOutputAnchored = false;
        mAdapter.addMessage(ChatMessage.user(displayText));
        mInputEdit.setText("");
        mAdapter.addMessage(ChatMessage.assistant("…"));

        mWaitingResponse = true;
        updateStatus("● 运行中", 0xFF1565C0);
        FloatingStatusService.updateStatus("● 运行中", 0xFF1565C0, displayText, true);

        // 写日志（与旧路径一致：每条 user 立即写）
        mLastSentText = text;
        appendChatLog("你", text);

        mAutomationTurnStartMs = mAutomationRuntime != null ? mAutomationRuntime.markTurnStarted() : 0L;
        if (mProvider == AssistantProvider.CODEX) {
            ensureCodexSession(displayText);
            mCodexSession.send(text, apiKey, baseUrl);
        } else {
            mClaudeSession.send(text, apiKey, baseUrl);
        }
    }

    private void ensureCodexSession(String preview) {
        if (mCurrentSessionId == null || !mCurrentSessionId.startsWith("codex-")) {
            mCurrentSessionId = "codex-" + System.currentTimeMillis() + "-"
                + java.util.UUID.randomUUID().toString();
            mCodexCurrentSessionId = mCurrentSessionId;
            saveSessionIdForProvider(AssistantProvider.CODEX, mCurrentSessionId);
            if (mUploadStore != null) mUploadStore.commitPending(mCurrentSessionId);
            if (mSessionAdapter != null) mSessionAdapter.setActiveId(mCurrentSessionId);
            updateSessionTitle(preview);
        }
        if (mSessionStore != null) {
            mSessionStore.add(mCurrentSessionId, System.currentTimeMillis(),
                preview == null ? "" : preview);
            refreshSessionDrawer();
        }
    }

    private boolean tryBoost(String text, String displayText) {
        final int generation = ++mBoostGeneration;
        BoostExecutor.Callback callback = new BoostExecutor.Callback() {
            @Override
            public void onStep(String recipeName, int index, int total, String toolName) {
                if (generation != mBoostGeneration) return;
                mBoosting = true;
                mHandler.post(() -> {
                    if (!isCurrentAutomationBoost(generation)) return;
                    String status = "⚡ Boosting " + index + "/" + total;
                    updateStatus(status, 0xFF7B1FA2);
                    FloatingStatusService.updateBoostStatus(status, 0xFF7B1FA2, displayText, true);
                });
            }

            @Override
            public void onCompleted(String recipeName) {
                mHandler.post(() -> {
                    if (!isCurrentAutomationBoost(generation)) return;
                    mBoosting = false;
                    mTurnOutputAnchored = false;
                    mAdapter.addMessage(ChatMessage.user(displayText));
                    mAdapter.addMessage(ChatMessage.assistant("已通过 Automation Boost 完成：" + recipeName));
                    updateStatus("● 就绪", 0xFF2E7D32);
                    FloatingStatusService.updateBoostStatus("● Boost completed", 0xFF2E7D32, displayText, false);
                    scrollToCurrentTurnOutputStartOnce();
                });
            }

            @Override
            public void onFailed(String recipeName, String reason) {
                mHandler.post(() -> {
                    if (!isCurrentAutomationBoost(generation)) return;
                    mBoosting = false;
                    FloatingStatusService.updateBoostStatus("● Boost failed", 0xFFD32F2F, displayText, false);
                    sendToProvider(text, displayText);
                });
            }
        };
        boolean started = mAutomationRuntime.tryStartBoost(text, callback);
        if (started) {
            mBoosting = true;
        }
        return started;
    }

    private boolean isCurrentAutomationBoost(int generation) {
        return mBoosting
            && generation == mBoostGeneration
            && mViewActive
            && mAdapter != null;
    }

    private void cancelAutomationBoost() {
        mBoostGeneration++;
        mBoosting = false;
        clearAutomationTurnTracking();
        FloatingStatusService.updateBoostStatus("● 就绪", 0xFF2E7D32, "", false);
    }

    private void clearAutomationTurnTracking() {
        mAutomationTurnStartMs = 0L;
    }

    /** "打断" 按钮调用：SIGTERM 当前 turn，currentSid 保留，下条消息 --resume 续接。 */
    private void stopClaudeProcess() {
        cancelAutomationBoost();
        stopActiveProviderProcess();
        mWaitingResponse = false;
        updateStatus("● 就绪", 0xFF2E7D32);
        FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
        // 给最后一条 assistant 气泡追加打断标记（沿用 SYSTEM 灰条提示）
        mAdapter.addMessage(ChatMessage.system("● 已打断"));
        scrollToBottom();
    }

    private void showProviderDialog() {
        if (getContext() == null) return;
        String[] labels = {"Codex", "Claude Code"};
        int checked = mProvider == AssistantProvider.CLAUDE ? 1 : 0;
        new AlertDialog.Builder(getContext())
            .setTitle("选择提供商")
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                AssistantProvider next = which == 1
                    ? AssistantProvider.CLAUDE
                    : AssistantProvider.CODEX;
                dialog.dismiss();
                requestProviderSwitch(next);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void requestProviderSwitch(AssistantProvider next) {
        if (next == null || next == mProvider) return;
        if (!isAnyProviderBusy()) {
            switchProvider(next);
            return;
        }
        new AlertDialog.Builder(requireContext())
            .setTitle("切换提供商？")
            .setMessage("当前或后台提供商正在运行，切换会打断正在执行的任务。")
            .setPositiveButton("切换", (dialog, which) -> {
                switchProvider(next);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void switchProvider(AssistantProvider next) {
        if (next == null) next = AssistantProvider.CODEX;
        mUiGeneration++;
        cancelAutomationBoost();
        rememberCurrentProviderSessionId();
        if (isAnyProviderBusy()) stopActiveProviderProcess();
        mProvider = next;
        mCurrentSessionId = mProvider == AssistantProvider.CODEX
            ? mCodexCurrentSessionId
            : mClaudeCurrentSessionId;
        if (mProviderStore != null) mProviderStore.setSelectedProvider(next);
        configureProviderStores();
        updateAssistantLabel();
        mWaitingResponse = false;
        mTurnOutputAnchored = false;
        clearAttachment();
        mMessages.clear();
        mAdapter.notifyDataSetChanged();
        if (mSessionAdapter != null) mSessionAdapter.setActiveId(mCurrentSessionId);
        updateSessionTitle(null);
        updateStatus("● 就绪", 0xFF2E7D32);
        FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
        ProviderProfile profile = ProviderProfile.forProvider(mProvider);
        mAdapter.addMessage(ChatMessage.system("已切换到 " + profile.displayName));
        if (mProvider == AssistantProvider.CODEX) {
            restoreCodexTranscriptIfNeeded();
        }
        scrollToBottom();
    }

    private void rememberCurrentProviderSessionId() {
        if (mProvider == AssistantProvider.CODEX) {
            mCodexCurrentSessionId = mCurrentSessionId;
        } else {
            mClaudeCurrentSessionId = mCurrentSessionId;
        }
        saveSessionIdForProvider(mProvider, mCurrentSessionId);
    }

    private void configureProviderStores() {
        if (getContext() == null) return;
        mSessionStore = new SessionStore(requireContext(), mProvider);
        mTranscriptStore = new ChatTranscriptStore(requireContext(), mProvider);
        mUploadStore = new UploadStore(requireContext(), mProvider);
        mAgentTaskStore = new AgentTaskStore(requireContext(), mProvider);
        refreshSessionDrawer();
        refreshAgentTaskDrawer();
        loadAgentPipeOffsetForCurrentProvider();
        processNewPipeLines();
        mMemoryItems.clear();
        mSkillsItems.clear();
        mUploadItems.clear();
        if (mMemoryAdapter != null) mMemoryAdapter.notifyDataSetChanged();
        if (mSkillsAdapter != null) mSkillsAdapter.notifyDataSetChanged();
        if (mUploadAdapter != null) mUploadAdapter.notifyDataSetChanged();
        if (mUploadEmptyHint != null) mUploadEmptyHint.setVisibility(View.VISIBLE);
    }

    private void updateAssistantLabel() {
        ProviderProfile profile = ProviderProfile.forProvider(mProvider);
        FloatingStatusService.setDefaultTitle(profile.displayName);
        if (mAdapter != null) {
            mAdapter.setAssistantLabel(profile.displayName);
        }
    }

    private boolean isAnyProviderBusy() {
        return mBoosting
            || mWaitingResponse
            || (mClaudeSession != null && mClaudeSession.isWaitingResponse())
            || (mCodexSession != null && mCodexSession.isRunning());
    }

    private void stopActiveProviderProcess() {
        if (mProvider == AssistantProvider.CODEX) {
            if (mCodexSession != null) mCodexSession.interrupt();
        } else {
            if (mClaudeSession != null) mClaudeSession.interrupt();
        }
    }

    private void postProviderUi(AssistantProvider provider, int viewGeneration, Runnable action) {
        if (!mViewActive || mViewGeneration != viewGeneration) return;
        final int generation = mUiGeneration;
        mHandler.post(() -> {
            if (!mViewActive
                    || mViewGeneration != viewGeneration
                    || mProvider != provider
                    || mUiGeneration != generation
                    || mAdapter == null) {
                return;
            }
            action.run();
        });
    }

    private void restoreCodexTranscriptIfNeeded() {
        if (mProvider != AssistantProvider.CODEX || mCodexSession == null || mAdapter == null) return;
        List<ChatMessage> snapshot = mCodexSession.snapshotTranscript();
        boolean running = mCodexSession.isRunning();
        if (snapshot.isEmpty() && !running) {
            if (mCurrentSessionId == null && mSessionStore != null
                    && !hasSavedSessionKey(AssistantProvider.CODEX)) {
                List<SessionStore.Entry> entries = mSessionStore.loadAll();
                if (!entries.isEmpty()) {
                    mCurrentSessionId = entries.get(0).id;
                    mCodexCurrentSessionId = mCurrentSessionId;
                    saveSessionIdForProvider(AssistantProvider.CODEX, mCurrentSessionId);
                }
            }
            if (mCurrentSessionId != null && mTranscriptStore != null) {
                List<ChatMessage> stored = mTranscriptStore.load(mCurrentSessionId);
                if (!stored.isEmpty()) {
                    mCodexSession.loadTranscript(stored);
                    snapshot = stored;
                    if (mSessionAdapter != null) mSessionAdapter.setActiveId(mCurrentSessionId);
                }
            }
        }
        if (snapshot.isEmpty() && !running) return;

        mMessages.clear();
        mMessages.addAll(snapshot);
        if (running && needsCodexRunningPlaceholder(snapshot)) {
            mMessages.add(ChatMessage.assistant("…"));
        }
        mAdapter.notifyDataSetChanged();
        mWaitingResponse = running;
        mTurnOutputAnchored = false;
        if (running) {
            updateStatus("● 运行中", 0xFF1565C0);
            FloatingStatusService.updateStatus("● 运行中", 0xFF1565C0, "", true);
            scrollToCurrentTurnOutputStartOnce();
        } else {
            updateStatus("● 就绪", 0xFF2E7D32);
            FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
            scrollToBottom();
        }
    }

    private boolean needsCodexRunningPlaceholder(List<ChatMessage> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return true;
        ChatMessage last = snapshot.get(snapshot.size() - 1);
        return last == null || last.type != ChatMessage.Type.ASSISTANT;
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
    // 文件附件
    // =========================================================================

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(
            Intent.createChooser(intent, "选择文件或图片"),
            REQUEST_CODE_PICK_FILE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE
                && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            copyFileAsync(data.getData());
        }
    }

    private void copyFileAsync(Uri uri) {
        mAttachmentPreviewRow.setVisibility(View.VISIBLE);
        mAttachmentNameText.setText("附件准备中…");
        final AssistantProvider targetProvider = mProvider;
        final ProviderProfile targetProfile = ProviderProfile.forProvider(targetProvider);

        new Thread(() -> {
            try {
                String name = resolveDisplayName(uri);

                // Step 1: stream URI → app cache (Java has full access here)
                File cacheFile = new File(requireContext().getCacheDir(), "upload_src");
                try (InputStream in  = requireContext().getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(cacheFile)) {
                    if (in == null) throw new Exception("无法打开文件");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }

                // Step 2: 为本次上传生成独占 UUID 目录，cp 进 ~/uploads/<uuid>/<filename>
                // 这样不同对话的同名文件不会冲突，删除时 rm -rf <uuid> 也很干净
                final String finalName = name.replace("'", "_"); // simplify name for shell safety
                final String uploadUuid = java.util.UUID.randomUUID().toString();
                String shell = "mkdir -p ~/uploads/'" + uploadUuid + "'"
                    + " && cp /tmp/.upload_src ~/uploads/'" + uploadUuid + "'/'" + finalName + "'"
                    + " && echo ~/uploads/'" + uploadUuid + "'/'" + finalName + "'";

                // 与当前助手用户保持一致，保证附件路径能被对应 CLI 读取。
                ProcessBuilder pb = new ProcessBuilder(PROOT_D, "login", "ubuntu",
                    "--user", targetProfile.user,
                    "--bind", cacheFile.getAbsolutePath() + ":/tmp/.upload_src",
                    "--", "sh", "-c", shell);
                setupEnv(pb.environment(), "", "");
                pb.redirectErrorStream(false);
                Process proc = pb.start();

                String prootPath = new BufferedReader(
                    new InputStreamReader(proc.getInputStream())).readLine();
                int exit = proc.waitFor();
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();

                if (exit != 0 || prootPath == null || prootPath.trim().isEmpty()) {
                    throw new Exception("proot copy 失败 (exit " + exit + ")");
                }

                final String finalProotPath = prootPath.trim();
                // 追踪：若已有 session 直接写真桶，否则写 pending 桶（commitPending 时迁移元数据）
                String targetSessionId = targetProvider == AssistantProvider.CODEX
                    ? mCodexCurrentSessionId : mClaudeCurrentSessionId;
                String trackSid = targetSessionId != null ? targetSessionId : UploadStore.PENDING;
                new UploadStore(requireContext(), targetProvider).addFile(trackSid, uploadUuid, finalName);
                mHandler.post(() -> {
                    mAttachmentPath = finalProotPath; // proot-visible path passed to current assistant
                    mAttachmentName = finalName;
                    mAttachmentProvider = targetProvider;
                    mAttachmentNameText.setText("附件：" + finalName);
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mAttachmentPreviewRow.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "文件加载失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }, "file-copy").start();
    }

    private String resolveDisplayName(Uri uri) {
        String name = null;
        try (Cursor c = requireContext().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            String path = uri.getPath();
            if (path != null && path.contains("/"))
                name = path.substring(path.lastIndexOf('/') + 1);
        }
        return (name != null && !name.isEmpty()) ? name : "attachment_" + System.currentTimeMillis();
    }

    private void clearAttachment() {
        mAttachmentPath = null;
        mAttachmentName = null;
        mAttachmentProvider = null;
        if (mAttachmentPreviewRow != null)
            mAttachmentPreviewRow.setVisibility(View.GONE);
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
        } catch (Exception ignored) {}
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
                updateScreenCaptureStatus(com.termux.app.mcp.AndroidMcpPermissionState.isScreenCaptureRunning());
                updateAccessibilityStatus(com.termux.app.mcp.AndroidMcpPermissionState.isAccessibilityReady(requireContext()));
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

    /** 从历史列表恢复指定会话：清空 UI、设置 resume ID、更新标题，并异步回放历史对话气泡。 */
    private void resumeSession(SessionStore.Entry entry) {
        if (mProvider == AssistantProvider.CODEX) {
            resumeCodexSession(entry);
            return;
        }
        mUiGeneration++;
        mClaudeSession.startWithResume(entry.id);   // kill 当前 + 标记下条 --resume entry.id
        mMessages.clear();
        mAdapter.notifyDataSetChanged();
        mCurrentSessionId = entry.id;
        mClaudeCurrentSessionId = entry.id;
        saveSessionIdForProvider(AssistantProvider.CLAUDE, entry.id);
        mWaitingResponse  = false;
        updateStatus("● 就绪", 0xFF2E7D32);
        mAdapter.addMessage(ChatMessage.system("已切换到历史对话：" + entry.formatTime() + "（加载中…）"));
        scrollToBottom();
        updateSessionTitle(entry.preview);
        mSessionAdapter.setActiveId(entry.id);

        // 异步从 Claude session jsonl 解析历史对话并渲染
        final String sid = entry.id;
        final String header = "── 历史对话：" + entry.formatTime() + " ──";
        new Thread(() -> {
            List<ChatMessage> history = loadSessionHistory(sid);
            mHandler.post(() -> {
                // 仅当用户没有切到别的 session 才渲染（防快速点击竞态）
                if (!sid.equals(mCurrentSessionId)) return;
                mMessages.clear();
                mMessages.add(ChatMessage.system(header));
                if (history != null && !history.isEmpty()) {
                    mMessages.addAll(history);
                } else {
                    mMessages.add(ChatMessage.system("（该对话历史已被清理或无内容）"));
                }
                mAdapter.notifyDataSetChanged();
                scrollToBottom();
            });
        }, "load-session-history").start();
    }

    private void resumeCodexSession(SessionStore.Entry entry) {
        if (entry == null || mCodexSession == null || mTranscriptStore == null) return;
        if (mCodexSession.isRunning()) {
            Toast.makeText(getContext(), "Codex 正在运行，请先停止当前任务", Toast.LENGTH_SHORT).show();
            return;
        }
        mUiGeneration++;
        List<ChatMessage> history = mTranscriptStore.load(entry.id);
        mCodexSession.loadTranscript(history);
        mCurrentSessionId = entry.id;
        mCodexCurrentSessionId = entry.id;
        saveSessionIdForProvider(AssistantProvider.CODEX, entry.id);
        mWaitingResponse = false;
        mTurnOutputAnchored = false;
        mMessages.clear();
        mMessages.add(ChatMessage.system("── Codex 历史对话：" + entry.formatTime() + " ──"));
        if (history.isEmpty()) {
            mMessages.add(ChatMessage.system("（该对话历史已被清理或无内容）"));
        } else {
            mMessages.addAll(history);
        }
        mAdapter.notifyDataSetChanged();
        updateStatus("● 就绪", 0xFF2E7D32);
        updateSessionTitle(entry.preview);
        if (mSessionAdapter != null) mSessionAdapter.setActiveId(entry.id);
        scrollToBottom();
    }

    /** 解析 Claude 自身保存的 session jsonl，转换为 ChatMessage 列表用于回放。 */
    private List<ChatMessage> loadSessionHistory(String sessionId) {
        File f = new File(CLAUDE_PROJECTS_DIR + "/" + sessionId + ".jsonl");
        if (!f.exists()) return null;
        List<ChatMessage> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    JSONObject obj = new JSONObject(line);
                    String type = obj.optString("type");
                    if ("user".equals(type)) parseHistUserEvent(obj, result);
                    else if ("assistant".equals(type)) parseHistAssistantEvent(obj, result);
                    // 跳过 system / summary / control_response 等
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void parseHistUserEvent(JSONObject obj, List<ChatMessage> out) {
        JSONObject msg = obj.optJSONObject("message");
        if (msg == null) return;
        Object content = msg.opt("content");
        if (content instanceof String) {
            String s = ((String) content).trim();
            if (!s.isEmpty()) out.add(ChatMessage.user(s));
            return;
        }
        if (!(content instanceof JSONArray)) return;
        JSONArray arr = (JSONArray) content;
        StringBuilder textBuf = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String t = item.optString("type");
            if ("text".equals(t)) {
                if (textBuf.length() > 0) textBuf.append("\n");
                textBuf.append(item.optString("text", ""));
            } else if ("tool_result".equals(t)) {
                String summary = summarizeToolResult(item.opt("content"));
                if (summary != null) out.add(ChatMessage.system("📥 工具返回: " + summary));
            }
        }
        String txt = textBuf.toString().trim();
        if (!txt.isEmpty()) out.add(ChatMessage.user(txt));
    }

    private void parseHistAssistantEvent(JSONObject obj, List<ChatMessage> out) {
        JSONObject msg = obj.optJSONObject("message");
        if (msg == null) return;
        JSONArray content = msg.optJSONArray("content");
        if (content == null) return;
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null) continue;
            String t = item.optString("type");
            if ("text".equals(t)) {
                String txt = item.optString("text", "").trim();
                if (!txt.isEmpty()) out.add(ChatMessage.assistant(txt));
            } else if ("thinking".equals(t)) {
                String th = item.optString("thinking", "").trim();
                if (!th.isEmpty()) {
                    ChatMessage m = ChatMessage.assistant("");
                    m.thinking = th;
                    m.thinkingCollapsed = true;
                    out.add(m);
                }
            } else if ("tool_use".equals(t)) {
                String name = item.optString("name", "?");
                out.add(ChatMessage.system("📞 调用工具: " + name));
            }
        }
    }

    /** 重新从 SessionStore 加载列表，刷新抽屉。 */
    private void refreshSessionDrawer() {
        if (mSessionStore == null || mSessionEntries == null || mSessionAdapter == null) return;
        mSessionEntries.clear();
        mSessionEntries.addAll(mSessionStore.loadAll());
        mSessionAdapter.setActiveId(mCurrentSessionId);
        mSessionAdapter.notifyDataSetChanged();
        if (mSessionEmptyHint != null) {
            mSessionEmptyHint.setVisibility(mSessionEntries.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshAgentTaskDrawer() {
        if (mAgentTaskStore == null || mAgentTasks == null || mAgentTaskAdapter == null) return;
        mActiveAgentTask = null;
        mAgentTasks.clear();
        mAgentTasks.addAll(mAgentTaskStore.loadAll());
        mAgentTaskAdapter.notifyDataSetChanged();
        if (mAgentTaskEmptyHint != null) {
            mAgentTaskEmptyHint.setVisibility(mAgentTasks.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void loadAgentPipeOffsetForCurrentProvider() {
        if (getContext() == null) return;
        File pipeFile = new File(agentPipeFilePath(mProvider));
        SharedPreferences pipePrefs = requireContext()
            .getSharedPreferences(agentPipePrefsName(mProvider), Context.MODE_PRIVATE);
        boolean hasSavedOffset = pipePrefs.contains(KEY_AGENT_PIPE_OFFSET);
        long savedOffset = pipePrefs.getLong(KEY_AGENT_PIPE_OFFSET, 0);
        if (!hasSavedOffset && pipeFile.exists()) {
            savedOffset = Math.max(0, pipeFile.length() - 200_000);
        }
        mAgentPipeOffset = pipeFile.exists() ? Math.min(savedOffset, pipeFile.length()) : 0;
    }

    private static String agentPipePrefsName(AssistantProvider provider) {
        return provider == AssistantProvider.CODEX ? PREF_AGENT_PIPE_CODEX : PREF_AGENT_PIPE_CLAUDE;
    }

    private static String agentPipeFilePath(AssistantProvider provider) {
        return ubuntuHomeForProvider(provider) + "/.agentserver-pipe.jsonl";
    }

    private static String ubuntuHomeForProvider(AssistantProvider provider) {
        return provider == AssistantProvider.CODEX ? UBUNTU_CODEX_HOME : UBUNTU_CLAUDE_HOME;
    }

    private String loadSavedSessionId(AssistantProvider provider) {
        SharedPreferences prefs = requireContext()
            .getSharedPreferences(PREF_HOME_CURRENT_SESSIONS, Context.MODE_PRIVATE);
        String value = prefs.getString(currentSessionKey(provider), null);
        return value == null || value.isEmpty() ? null : value;
    }

    private boolean hasSavedSessionKey(AssistantProvider provider) {
        return requireContext()
            .getSharedPreferences(PREF_HOME_CURRENT_SESSIONS, Context.MODE_PRIVATE)
            .contains(currentSessionKey(provider));
    }

    private void saveSessionIdForProvider(AssistantProvider provider, String sessionId) {
        if (getContext() == null) return;
        requireContext()
            .getSharedPreferences(PREF_HOME_CURRENT_SESSIONS, Context.MODE_PRIVATE)
            .edit()
            .putString(currentSessionKey(provider), sessionId == null ? "" : sessionId)
            .apply();
    }

    private static String currentSessionKey(AssistantProvider provider) {
        return provider == AssistantProvider.CODEX ? KEY_CURRENT_CODEX : KEY_CURRENT_CLAUDE;
    }

    /** 更新顶栏提供商切换控件。 */
    private void updateSessionTitle(@Nullable String preview) {
        if (mSessionTitle == null) return;
        mSessionTitle.setText(ProviderProfile.forProvider(mProvider).displayName);
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
            mAccessibilityStatus.setTextColor(0xFF16A34A);
        } else {
            mAccessibilityStatus.setText("● 无障碍: 未启用");
            mAccessibilityStatus.setTextColor(0xFF64748B);
        }
    }

    private void updateScreenCaptureStatus(boolean granted) {
        if (mScreenCaptureStatus == null) return;
        if (granted) {
            mScreenCaptureStatus.setText("● 截图: 已授权");
            mScreenCaptureStatus.setTextColor(0xFF16A34A);
        } else {
            mScreenCaptureStatus.setText("● 截图: 未授权");
            mScreenCaptureStatus.setTextColor(0xFF64748B);
        }
    }

    /** 向当前可见 TerminalSession 写入（供启动和回车按钮使用）。 */
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

    private void scrollToCurrentTurnOutputStartOnce() {
        if (mTurnOutputAnchored || mRecycler == null || mAdapter == null) return;
        mRecycler.post(() -> {
            if (mTurnOutputAnchored || mRecycler == null || mAdapter == null) return;
            int index = firstGeneratedMessageIndexInCurrentTurn();
            if (index < 0) return;
            RecyclerView.LayoutManager layoutManager = mRecycler.getLayoutManager();
            if (layoutManager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(index, 0);
            } else {
                mRecycler.scrollToPosition(index);
            }
            mTurnOutputAnchored = true;
        });
    }

    private int firstGeneratedMessageIndexInCurrentTurn() {
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            ChatMessage message = mMessages.get(i);
            if (message != null && message.type == ChatMessage.Type.USER) {
                return i + 1 < mMessages.size() ? i + 1 : -1;
            }
        }
        return mMessages.isEmpty() ? -1 : 0;
    }

    /** 删除当前 turn 范围内（USER 之后的）所有空的 / "…" 占位 ASSISTANT 气泡。
     *  扫整个 turn 而不是只看末尾——打断时可能 system/tool_use 气泡已经追加到
     *  placeholder 后面，仅检查末尾会漏。 */
    private void dropPlaceholder() {
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            ChatMessage m = mMessages.get(i);
            if (m.type == ChatMessage.Type.USER) break;
            if (m.type == ChatMessage.Type.ASSISTANT
                    && (m.content == null
                        || m.content.equals("…")
                        || m.content.trim().isEmpty())
                    // 不删带有 thinking 内容的占位（流式 thinking 阶段 content 还空）
                    && (m.thinking == null || m.thinking.isEmpty())) {
                mMessages.remove(i);
                mAdapter.notifyItemRemoved(i);
            }
        }
    }

    @Nullable
    private ChatMessage getLastAssistantMessageInCurrentTurn() {
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            ChatMessage m = mMessages.get(i);
            if (m.type == ChatMessage.Type.USER) break;
            if (m.type == ChatMessage.Type.ASSISTANT) return m;
        }
        return null;
    }

    @Nullable
    private TermuxActivity act() {
        return (getActivity() instanceof TermuxActivity)
            ? (TermuxActivity) getActivity() : null;
    }

    // =========================================================================
    // 抽屉 Tab 切换
    // =========================================================================

    private void switchDrawerTab(int index) {
        mDrawerFlipper.setDisplayedChild(index);
        int activeColor   = getResources().getColor(R.color.app_primary, null);
        int inactiveColor = getResources().getColor(R.color.app_text_muted, null);
        int activeBg      = getResources().getColor(R.color.app_card_bg, null);
        int inactiveBg    = getResources().getColor(R.color.app_bg_tertiary, null);
        TextView[] tabs = { mTabHistory, mTabMemory, mTabSkills, mTabAgentTask, mTabUploads };
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == index ? activeColor : inactiveColor);
            tabs[i].setBackgroundColor(i == index ? activeBg : inactiveBg);
        }
    }

    // =========================================================================
    // 上传文件面板
    // =========================================================================

    private void setupUploadRecycler() {
        mUploadAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_upload_file, parent, false);
                return new RecyclerView.ViewHolder(v) {};
            }
            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                UploadItem item = mUploadItems.get(position);
                TextView badge   = holder.itemView.findViewById(R.id.upload_ext_badge);
                TextView nameTV  = holder.itemView.findViewById(R.id.upload_filename);
                TextView labelTV = holder.itemView.findViewById(R.id.upload_session_label);

                // 扩展名徽章
                int dot = item.filename.lastIndexOf('.');
                String ext = (dot >= 0 && dot < item.filename.length() - 1)
                    ? item.filename.substring(dot + 1).toUpperCase(Locale.US) : "FILE";
                if (ext.length() > 4) ext = ext.substring(0, 4);
                badge.setText(ext);
                badge.setBackgroundColor(extColor(ext));

                nameTV.setText(item.filename);
                labelTV.setText(item.sessionLabel);

                holder.itemView.setOnLongClickListener(v -> {
                    if (getContext() == null) return true;
                    new AlertDialog.Builder(getContext())
                        .setTitle("删除上传文件？")
                        .setMessage(item.filename)
                        .setPositiveButton("删除", (d, w) -> {
                            mUploadStore.deleteByUuid(item.sessionId, item.uuid);
                            final String uuid = item.uuid;
                            final AssistantProvider provider = mProvider;
                            new Thread(() -> deleteUbuntuUploadDirs(
                                provider, java.util.Collections.singletonList(uuid))).start();
                            mUploadItems.remove(item);
                            mUploadAdapter.notifyDataSetChanged();
                            mUploadEmptyHint.setVisibility(
                                mUploadItems.isEmpty() ? View.VISIBLE : View.GONE);
                        })
                        .setNegativeButton("取消", null).show();
                    return true;
                });
            }
            @Override public int getItemCount() { return mUploadItems.size(); }
        };
        mUploadRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        mUploadRecycler.setAdapter(mUploadAdapter);
    }

    private int extColor(String ext) {
        switch (ext) {
            case "JPG": case "JPEG": case "PNG": case "GIF": case "WEBP":
                return 0xFF4CAF50;
            case "PDF":
                return 0xFFF44336;
            case "ZIP": case "TAR": case "GZ": case "7Z": case "RAR":
                return 0xFFFF9800;
            case "TXT": case "MD": case "PY": case "JS": case "JAVA":
            case "KT": case "GO": case "TS": case "CSV": case "JSON":
            case "XML": case "HTML": case "RS": case "CPP": case "C": case "H":
                return 0xFF2196F3;
            default:
                return 0xFF9E9E9E;
        }
    }

    private void loadUploadFiles() {
        Map<String, List<UploadStore.Entry>> all = mUploadStore.getAll();
        List<SessionStore.Entry> sessions = mSessionStore.loadAll();
        Map<String, String> sessionLabels = new java.util.HashMap<>();
        for (SessionStore.Entry s : sessions) sessionLabels.put(s.id, s.formatTime());

        mUploadItems.clear();
        for (Map.Entry<String, List<UploadStore.Entry>> kv : all.entrySet()) {
            String sid = kv.getKey();
            String label = UploadStore.PENDING.equals(sid)
                ? "待关联" : sessionLabels.getOrDefault(sid, "已删除对话");
            for (UploadStore.Entry e : kv.getValue()) {
                mUploadItems.add(new UploadItem(sid, e.uuid, e.filename, label));
            }
        }
        mUploadAdapter.notifyDataSetChanged();
        mUploadEmptyHint.setVisibility(mUploadItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 清空整个 ~/uploads/ 内容（保留目录本身），用于"清空全部"按钮。 */
    private void clearUploadsRoot(AssistantProvider provider) {
        try {
            ProviderProfile profile = ProviderProfile.forProvider(provider);
            ProcessBuilder pb = new ProcessBuilder(PROOT_D, "login", "ubuntu",
                "--user", profile.user, "--", "sh", "-c",
                "rm -rf ~/uploads/* ~/uploads/.[!.]*");
            setupEnv(pb.environment(), "", "");
            pb.start().waitFor();
        } catch (Exception ignored) {}
    }

    /** 按 UUID 目录批量删除上传文件（rm -rf ~/uploads/&lt;uuid&gt;）。 */
    private void deleteUbuntuUploadDirs(AssistantProvider provider, List<String> uuids) {
        if (uuids.isEmpty()) return;
        StringBuilder sb = new StringBuilder("rm -rf");
        for (String u : uuids) {
            // uuid 是 App 生成的标准 UUID 形式（[a-z0-9-]），但仍做最小过滤
            String safe = u.replaceAll("[^a-zA-Z0-9-]", "");
            if (!safe.isEmpty()) sb.append(" ~/uploads/").append(safe);
        }
        try {
            ProviderProfile profile = ProviderProfile.forProvider(provider);
            ProcessBuilder pb = new ProcessBuilder(PROOT_D, "login", "ubuntu",
                "--user", profile.user, "--", "sh", "-c", sb.toString());
            setupEnv(pb.environment(), "", "");
            pb.start().waitFor();
        } catch (Exception ignored) {}
    }

    /** Claude Code 项目历史目录（含所有 session jsonl）。 */
    private static final String CLAUDE_PROJECTS_DIR =
        UBUNTU_CLAUDE_HOME + "/.claude/projects/-home-claude";

    /** 启动时扫描，删除 SessionStore 已不持有但磁盘上仍残留的 jsonl 文件。 */
    private void cleanupOrphanClaudeJsonl() {
        new Thread(() -> {
            File dir = new File(CLAUDE_PROJECTS_DIR);
            if (!dir.isDirectory()) return;
            File[] files = dir.listFiles((f, name) -> name.endsWith(".jsonl"));
            if (files == null || files.length == 0) return;
            java.util.Set<String> validIds = new java.util.HashSet<>();
            SessionStore claudeStore = new SessionStore(requireContext(), AssistantProvider.CLAUDE);
            for (SessionStore.Entry e : claudeStore.loadAll()) validIds.add(e.id);
            for (File f : files) {
                String name = f.getName();
                String id   = name.substring(0, name.length() - ".jsonl".length());
                if (!validIds.contains(id)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }, "orphan-jsonl-cleanup").start();
    }

    /** 删除 Claude Code 自身的 session 历史文件（包含 base64 截图，占用大），随对话历史删除一并清理。 */
    private void deleteClaudeSessionJsonl(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        // session id 由 Claude 生成，固定为 UUID 字符不含 shell 元字符，但仍做最小安全过滤
        String safe = sessionId.replaceAll("[^a-zA-Z0-9-]", "");
        if (safe.isEmpty()) return;
        String shell = "rm -f ~/.claude/projects/-home-claude/" + safe + ".jsonl";
        try {
            ProcessBuilder pb = new ProcessBuilder(PROOT_D, "login", "ubuntu",
                "--user", "claude", "--", "sh", "-c", shell);
            setupEnv(pb.environment(), "", "");
            pb.start().waitFor();
        } catch (Exception ignored) {}
    }

    private void clearAllUploads() {
        if (getContext() == null) return;
        new AlertDialog.Builder(getContext())
            .setTitle("清空全部上传文件？")
            .setMessage("将删除 Ubuntu ~/uploads/ 内所有文件，无法恢复。")
            .setPositiveButton("清空", (d, w) -> {
                final AssistantProvider provider = mProvider;
                mUploadStore.clearAll();
                // 不按 uuid 逐个删，直接清空整个 uploads 目录（兼容 orphan dirs）
                new Thread(() -> clearUploadsRoot(provider)).start();
                mUploadItems.clear();
                mUploadAdapter.notifyDataSetChanged();
                mUploadEmptyHint.setVisibility(View.VISIBLE);
            })
            .setNegativeButton("取消", null).show();
    }

    // =========================================================================
    // AgentServer 任务面板 — pipe 文件监听
    // =========================================================================

    /** 启动后台轮询线程，每 600ms 读取 pipe 文件新增行并渲染到第 4 面板。 */
    private void startAgentPipeWatcher() {
        if (mAgentPipeWatcher != null && mAgentPipeWatcher.isAlive()) return;
        mAgentPipeWatcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(600); } catch (InterruptedException e) { break; }
                processNewPipeLines();
            }
        }, "agent-pipe-watcher");
        mAgentPipeWatcher.setDaemon(true);
        mAgentPipeWatcher.start();
    }

    /** 停止 pipe watcher（Fragment 销毁时调用）。 */
    private void stopAgentPipeWatcher() {
        if (mAgentPipeWatcher != null) {
            mAgentPipeWatcher.interrupt();
            mAgentPipeWatcher = null;
        }
    }

    /** 读取 pipe 文件自上次 offset 以来的新行，逐行解析。 */
    private void processNewPipeLines() {
        AssistantProvider pipeProvider = mProvider;
        File f = new File(agentPipeFilePath(pipeProvider));
        if (!f.exists()) return;
        // pipe 被外部截断（AgentServer 重连）→ 重置 offset 从头读
        if (f.length() < mAgentPipeOffset) {
            mAgentPipeOffset = 0;
            // 截断同时意味着上一个 active task 的运行时缓冲也清空，标记完成
            mHandler.post(this::finishActiveTaskIfAny);
        }
        if (f.length() <= mAgentPipeOffset) return;
        long start = mAgentPipeOffset;
        // 注意：RandomAccessFile.readLine 用 Latin-1 解码字节（Java 历史遗留），中文 UTF-8
        // 字节会被解成乱码。这里手动按字节读取、按 \n 分行、用 UTF-8 显式解码。
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            raf.seek(mAgentPipeOffset);
            java.io.ByteArrayOutputStream lineBuf = new java.io.ByteArrayOutputStream();
            int b;
            while ((b = raf.read()) != -1) {
                if (b == '\n') {
                    String line = new String(lineBuf.toByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8);
                    lineBuf.reset();
                    mAgentPipeOffset = raf.getFilePointer();
                    if (!line.trim().isEmpty()) handleAgentPipeLine(line);
                } else if (b != '\r') {
                    lineBuf.write(b);
                }
            }
            // 末尾未结束的部分留到下次读取，offset 保持在最后一个完整行之后
        } catch (Exception ignored) {}
        if (mAgentPipeOffset != start && getContext() != null) {
            getContext()
                .getSharedPreferences(agentPipePrefsName(pipeProvider), Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_AGENT_PIPE_OFFSET, mAgentPipeOffset)
                .apply();
        }
    }

    /** 解析单行 pipe 内容：在 UI 线程统一处理事件 + 写入当前 active task 的归档文件。 */
    private void handleAgentPipeLine(String line) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(line);
            String type = obj.optString("type");
            final String rawLine = line;
            mHandler.post(() -> {
                processAgentEventOnUi(type, obj);
                // 处理后再写入任务文件，确保 as_stream_in 这类创建新任务的行落到新任务文件
                if (mActiveAgentTask != null) appendRawLineToTaskFile(mActiveAgentTask.id, rawLine);
            });
        } catch (Exception ignored) {}
    }

    /** UI 线程同步处理一个 pipe 事件（必要时切换 active task）。 */
    private void processAgentEventOnUi(String type, org.json.JSONObject obj) {
        switch (type) {
            case "as_stream_session_start":
                finishActiveTaskIfAny();
                break;
            case "as_stream_in": {
                String prompt = decodeStreamInPrompt(obj);
                if (prompt != null && !prompt.isEmpty()) startNewAgentTask(prompt);
                break;
            }
            case "user": {
                org.json.JSONObject msg = obj.optJSONObject("message");
                if (msg != null) {
                    String summary = summarizeToolResult(msg.opt("content"));
                    if (summary != null) appendToActiveTask(ChatMessage.system("📥 工具返回: " + summary));
                }
                break;
            }
            case "assistant":
                handleAssistantEvent(obj);
                break;
            case "result":
                finishActiveTaskIfAny();
                break;
            default:
                break;
        }
    }

    /** 解码 as_stream_in 的内层 JSON，若是 user prompt 则返回文本。 */
    private String decodeStreamInPrompt(org.json.JSONObject envelope) {
        try {
            String b64 = envelope.optString("b64", "");
            if (b64.isEmpty()) return null;
            byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            String inner = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (inner.isEmpty()) return null;
            org.json.JSONObject innerObj = new org.json.JSONObject(inner);
            if (!"user".equals(innerObj.optString("type"))) return null;
            return extractStreamUserPrompt(innerObj);
        } catch (Exception e) {
            return null;
        }
    }

    /** Assistant 事件：text/thinking 追加为 ASSISTANT 消息；tool_use 追加为 SYSTEM 消息。 */
    private void handleAssistantEvent(org.json.JSONObject obj) {
        try {
            org.json.JSONObject msg = obj.optJSONObject("message");
            if (msg == null) return;
            org.json.JSONArray content = msg.optJSONArray("content");
            if (content == null) return;
            final List<ChatMessage> toAppend = new ArrayList<>();
            for (int i = 0; i < content.length(); i++) {
                org.json.JSONObject item = content.optJSONObject(i);
                if (item == null) continue;
                String itemType = item.optString("type");
                if ("text".equals(itemType)) {
                    String txt = item.optString("text", "").trim();
                    if (!txt.isEmpty()) toAppend.add(ChatMessage.assistant(txt));
                } else if ("thinking".equals(itemType)) {
                    String th = item.optString("thinking", "").trim();
                    if (!th.isEmpty()) {
                        ChatMessage m = ChatMessage.assistant("");
                        m.thinking = th;
                        m.thinkingCollapsed = true;
                        toAppend.add(m);
                    }
                } else if ("tool_use".equals(itemType)) {
                    String name = item.optString("name", "?");
                    toAppend.add(ChatMessage.system("📞 调用工具: " + name));
                }
            }
            // 已经在 UI 线程，直接 append（由 processAgentEventOnUi 调用）
            for (ChatMessage m : toAppend) appendToActiveTask(m);
        } catch (Exception ignored) {}
    }

    /** 提取流式 user 消息中的纯文本 prompt（不含 tool_result）。 */
    private String extractStreamUserPrompt(org.json.JSONObject userEvent) {
        try {
            org.json.JSONObject msg = userEvent.optJSONObject("message");
            if (msg == null) return null;
            Object content = msg.opt("content");
            if (content instanceof String) {
                return ((String) content).trim();
            }
            if (content instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) content;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject item = arr.optJSONObject(i);
                    if (item == null) continue;
                    String itemType = item.optString("type");
                    if ("tool_result".equals(itemType)) return null;
                    if ("text".equals(itemType)) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(item.optString("text", ""));
                    }
                }
                String s = sb.toString().trim();
                return s.isEmpty() ? null : s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 把 tool_result 的 content 摘要成短字符串（最多 200 字）。 */
    private String summarizeToolResult(Object content) {
        try {
            String raw;
            if (content instanceof String) {
                raw = (String) content;
            } else if (content instanceof org.json.JSONArray) {
                StringBuilder sb = new StringBuilder();
                org.json.JSONArray arr = (org.json.JSONArray) content;
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject item = arr.optJSONObject(i);
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
            if (raw.length() > 200) raw = raw.substring(0, 197) + "…";
            return raw;
        } catch (Exception e) {
            return null;
        }
    }

    /** 创建新任务，写入 store，刷新侧栏。 */
    private void startNewAgentTask(String prompt) {
        // 上一个未完成的任务标记完成
        finishActiveTaskIfAny();
        AgentTask t = new AgentTask();
        t.prompt = prompt;
        mActiveAgentTask = t;
        mAgentTaskStore.upsert(t);
        // 头插到侧栏列表
        mAgentTasks.add(0, t);
        mAgentTaskAdapter.notifyDataSetChanged();
        mAgentTaskEmptyHint.setVisibility(View.GONE);
        FloatingStatusService.updateStatus("● 执行任务", 0xFFF57C00,
            prompt.length() > 40 ? prompt.substring(0, 40) + "…" : prompt, true);
    }

    /** 把消息追加到当前 active task（无 active 则忽略），并持久化。 */
    private void appendToActiveTask(ChatMessage m) {
        if (mActiveAgentTask == null) return;
        mActiveAgentTask.messages.add(m);
        mAgentTaskStore.upsert(mActiveAgentTask);
    }

    /** 把 active task 标记为 COMPLETED 并持久化；无 active 则空操作。 */
    private void finishActiveTaskIfAny() {
        if (mActiveAgentTask == null) return;
        mActiveAgentTask.status = AgentTask.Status.COMPLETED;
        mAgentTaskStore.upsert(mActiveAgentTask);
        // 刷新侧栏列表项的状态徽章
        for (int i = 0; i < mAgentTasks.size(); i++) {
            if (mAgentTasks.get(i).id.equals(mActiveAgentTask.id)) {
                mAgentTaskAdapter.notifyItemChanged(i);
                break;
            }
        }
        mActiveAgentTask = null;
        FloatingStatusService.updateStatus("● 就绪", 0xFF388E3C, "", false);
    }

    // ── 任务文件归档（每任务一个 .jsonl，方便随任务删除）─────────────────
    private File agentTaskFile(String taskId) {
        File dir = new File(requireContext().getFilesDir(), "agent-tasks");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, taskId + ".jsonl");
    }

    private void appendRawLineToTaskFile(String taskId, String line) {
        try (FileWriter fw = new FileWriter(agentTaskFile(taskId), true)) {
            fw.write(line);
            fw.write('\n');
        } catch (Exception ignored) {}
    }

    private void deleteAgentTaskFile(String taskId) {
        File f = agentTaskFile(taskId);
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private void deleteAllAgentTaskFiles() {
        File dir = new File(requireContext().getFilesDir(), "agent-tasks");
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // =========================================================================
    // 记忆库
    // =========================================================================

    private void loadMemoryFiles() {
        new Thread(() -> {
            List<DrawerFileAdapter.FileItem> items = new ArrayList<>();
            ProviderProfile profile = ProviderProfile.forProvider(mProvider);
            if (mProvider == AssistantProvider.CODEX) {
                File agents = new File(hostPathForProviderPath(profile.instructionsFile));
                if (agents.isFile()) {
                    items.add(new DrawerFileAdapter.FileItem(
                        "AGENTS.md", readFirstMeaningfulLine(agents), agents.getAbsolutePath()));
                }
            } else {
                File dir = new File(hostPathForProviderPath(profile.memoryDir));
                if (dir.isDirectory()) {
                File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".md"));
                if (files != null) {
                    for (File f : files) {
                        String name    = f.getName().replaceAll("\\.md$", "");
                        String preview = readPreview(f);
                        items.add(new DrawerFileAdapter.FileItem(name, preview, f.getAbsolutePath()));
                    }
                }
                }
            }
            mHandler.post(() -> {
                mMemoryItems.clear();
                mMemoryItems.addAll(items);
                mMemoryAdapter.notifyDataSetChanged();
                View root = getView();
                if (root != null) {
                    root.findViewById(R.id.memory_empty_hint)
                        .setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        }, "load-memory").start();
    }

    // =========================================================================
    // 技能
    // =========================================================================

    private void loadSkillsFiles() {
        new Thread(() -> {
            File dir = new File(hostPathForProviderPath(
                ProviderProfile.forProvider(mProvider).commandsDir));
            List<DrawerFileAdapter.FileItem> items = new ArrayList<>();
            if (dir.isDirectory()) {
                if (mProvider == AssistantProvider.CODEX) {
                    collectCodexSkills(dir, "", items, 2);
                } else {
                    File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".md"));
                    if (files != null) {
                        for (File f : files) {
                            String name    = f.getName().replaceAll("\\.md$", "");
                            String preview = readFirstMeaningfulLine(f);
                            items.add(new DrawerFileAdapter.FileItem(name, preview, f.getAbsolutePath()));
                        }
                    }
                }
            }
            mHandler.post(() -> {
                mSkillsItems.clear();
                mSkillsItems.addAll(items);
                mSkillsAdapter.notifyDataSetChanged();
                View root = getView();
                if (root != null) {
                    root.findViewById(R.id.skills_empty_hint)
                        .setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        }, "load-skills").start();
    }

    private void showNewSkillDialog() {
        if (getContext() == null) return;
        View dialogView = LayoutInflater.from(getContext())
            .inflate(android.R.layout.two_line_list_item, null);
        // 用两个 EditText 构建简单对话框
        LinearLayout ll = new LinearLayout(getContext());
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(48, 24, 48, 0);

        EditText etName = new EditText(getContext());
        etName.setHint("技能名称（如 phone）");
        etName.setSingleLine(true);
        ll.addView(etName);

        EditText etContent = new EditText(getContext());
        etContent.setHint("技能内容（Markdown）");
        etContent.setMinLines(6);
        etContent.setGravity(android.view.Gravity.TOP);
        ll.addView(etContent);

        new AlertDialog.Builder(getContext())
            .setTitle("新建技能")
            .setView(ll)
            .setPositiveButton("保存", (d, w) -> {
                String name    = etName.getText().toString().trim();
                String content = etContent.getText().toString();
                if (name.isEmpty()) { Toast.makeText(getContext(), "名称不能为空", Toast.LENGTH_SHORT).show(); return; }
                saveSkill(name, content);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void saveSkill(String name, String content) {
        new Thread(() -> {
            try {
                File dir  = new File(hostPathForProviderPath(
                    ProviderProfile.forProvider(mProvider).commandsDir));
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File file;
                if (mProvider == AssistantProvider.CODEX) {
                    File skillDir = new File(dir, safeFileName(name));
                    //noinspection ResultOfMethodCallIgnored
                    skillDir.mkdirs();
                    file = new File(skillDir, "SKILL.md");
                } else {
                    file = new File(dir, safeFileName(name) + ".md");
                }
                java.nio.file.Files.write(file.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                mHandler.post(() -> {
                    Toast.makeText(getContext(), "技能已保存", Toast.LENGTH_SHORT).show();
                    loadSkillsFiles();
                });
            } catch (Exception e) {
                mHandler.post(() -> Toast.makeText(getContext(), "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, "save-skill").start();
    }

    // =========================================================================
    // 通用文件操作
    // =========================================================================

    private void showFileContentDialog(String title, String path, boolean editable) {
        if (getContext() == null) return;
        new Thread(() -> {
            String content;
            try {
                content = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                content = "读取失败: " + e.getMessage();
            }
            final String text = content;
            mHandler.post(() -> {
                ScrollView sv = new ScrollView(getContext());
                EditText et  = new EditText(getContext());
                et.setText(text);
                et.setTextSize(12f);
                et.setFontVariationSettings(null);
                et.setTypeface(android.graphics.Typeface.MONOSPACE);
                et.setEnabled(editable);
                et.setPadding(32, 16, 32, 16);
                sv.addView(et);

                AlertDialog.Builder b = new AlertDialog.Builder(getContext())
                    .setTitle(title)
                    .setView(sv)
                    .setNegativeButton("关闭", null);
                if (editable) {
                    b.setPositiveButton("保存", (d, w) -> {
                        String newContent = et.getText().toString();
                        new Thread(() -> {
                            try {
                                java.nio.file.Files.write(java.nio.file.Paths.get(path),
                                    newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                mHandler.post(() -> {
                                    Toast.makeText(getContext(), "已保存", Toast.LENGTH_SHORT).show();
                                    loadSkillsFiles();
                                });
                            } catch (Exception e) {
                                mHandler.post(() -> Toast.makeText(getContext(),
                                    "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                            }
                        }, "save-file").start();
                    });
                }
                b.show();
            });
        }, "read-file").start();
    }

    private void confirmDelete(DrawerFileAdapter.FileItem item,
                               List<DrawerFileAdapter.FileItem> list,
                               DrawerFileAdapter adapter,
                               View emptyHint) {
        if (getContext() == null) return;
        new AlertDialog.Builder(getContext())
            .setTitle("删除 " + item.name + "?")
            .setMessage("此操作不可恢复")
            .setPositiveButton("删除", (d, w) -> {
                new Thread(() -> {
                    boolean ok = deleteDrawerItemPath(item);
                    mHandler.post(() -> {
                        if (ok) {
                            list.remove(item);
                            adapter.notifyDataSetChanged();
                            if (emptyHint != null)
                                emptyHint.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                        } else {
                            Toast.makeText(getContext(), "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }, "delete-file").start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private boolean deleteDrawerItemPath(DrawerFileAdapter.FileItem item) {
        if (item == null || item.fullPath == null) return false;
        File file = new File(item.fullPath);
        if (mProvider == AssistantProvider.CODEX && "SKILL.md".equals(file.getName())
                && file.getParentFile() != null) {
            return deleteRecursively(file.getParentFile());
        }
        return file.delete();
    }

    private void collectCodexSkills(
            File dir,
            String prefix,
            List<DrawerFileAdapter.FileItem> out,
            int depthRemaining) {
        if (dir == null || out == null || depthRemaining < 0 || !dir.isDirectory()) return;
        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) return;
        for (File child : children) {
            if (child.getName().startsWith(".")) continue;
            String name = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            File skillFile = new File(child, "SKILL.md");
            if (skillFile.isFile()) {
                out.add(new DrawerFileAdapter.FileItem(
                    name,
                    readFirstMeaningfulLine(skillFile),
                    skillFile.getAbsolutePath()));
            }
            collectCodexSkills(child, name, out, depthRemaining - 1);
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private static String safeFileName(String value) {
        String safe = (value == null ? "" : value.trim()).replaceAll("[^a-zA-Z0-9_.-]", "-");
        return safe.isEmpty() ? "skill" : safe;
    }

    private static String hostPathForProviderPath(String path) {
        if (path == null) return "";
        if (path.startsWith("/home/claude")) {
            return UBUNTU_CLAUDE_HOME + path.substring("/home/claude".length());
        }
        if (path.startsWith("/home/codex")) {
            return UBUNTU_CODEX_HOME + path.substring("/home/codex".length());
        }
        return path;
    }

    /** 读取文件前 120 字符作为预览（跳过 YAML frontmatter 横线）。 */
    private String readPreview(File f) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
            StringBuilder sb  = new StringBuilder();
            boolean inFront   = false;
            boolean frontDone = false;
            String line;
            while ((line = br.readLine()) != null) {
                if (!frontDone) {
                    if (line.equals("---")) { inFront = !inFront; if (!inFront) frontDone = true; continue; }
                    if (inFront) { if (line.startsWith("description:")) { return line.substring("description:".length()).trim(); } continue; }
                }
                if (!line.trim().isEmpty()) {
                    sb.append(line.trim()).append(" ");
                    if (sb.length() > 120) break;
                }
            }
            return sb.toString().trim();
        } catch (Exception e) { return ""; }
    }

    /** 读取文件第一个有意义的非标题行作为预览。 */
    private String readFirstMeaningfulLine(File f) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("---")) return line;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void generateAutomationCandidateForCompletedTurn(String sourceTaskId) {
        if (mAutomationRuntime == null || mAutomationTurnStartMs <= 0) return;
        final long startMs = mAutomationTurnStartMs;
        final long endMs = System.currentTimeMillis();
        final String prompt = mLastSentText;
        final String taskId = sourceTaskId == null ? "" : sourceTaskId;
        clearAutomationTurnTracking();
        new Thread(() -> mAutomationRuntime.generateCandidateForCompletedTurn(
            prompt, taskId, startMs, endMs), "automation-candidate").start();
    }

    // =========================================================================
    // ClaudeStreamSession listener — all callbacks fire on stdout reader
    // thread; we marshal each to the UI thread via mHandler.post.
    // =========================================================================

    private CodexExecSession.Listener buildCodexListener() {
        final int viewGeneration = mViewGeneration;
        return new CodexExecSession.Listener() {
            @Override public void onSystem(String info) {
                postProviderUi(AssistantProvider.CODEX, viewGeneration, () -> {
                    if (!CodexExecSession.isTransientSystemMessage(info)) {
                        mAdapter.addMessage(ChatMessage.system(info));
                        scrollToBottom();
                    }
                });
            }
            @Override public void onThinking(String thinking) {
                postProviderUi(AssistantProvider.CODEX, viewGeneration, () -> {
                    mAdapter.updateLastAssistantThinking(thinking);
                    scrollToCurrentTurnOutputStartOnce();
                });
            }
            @Override public void onAssistantText(String text) {
                postProviderUi(AssistantProvider.CODEX, viewGeneration, () -> {
                    mAdapter.updateLastAssistantText(text);
                    scrollToCurrentTurnOutputStartOnce();
                });
            }
            @Override public void onToolUse(String name, String inputJson) {
                postProviderUi(AssistantProvider.CODEX, viewGeneration, () -> {
                    mAdapter.addMessage(ChatMessage.toolUse(name, inputJson));
                    scrollToCurrentTurnOutputStartOnce();
                });
            }
            @Override public void onToolResult(String name, String summary, String full) {
                postProviderUi(AssistantProvider.CODEX, viewGeneration, () -> {
                    mAdapter.addMessage(ChatMessage.toolResult(name, summary, full));
                    scrollToCurrentTurnOutputStartOnce();
                });
            }
            @Override public void onResult(boolean isError, String errMsg) {
                postProviderUi(AssistantProvider.CODEX, viewGeneration, () -> {
                    if (isError) {
                        clearAutomationTurnTracking();
                        mAdapter.updateLastAssistantText("⚠ " + errMsg);
                    } else {
                        dropPlaceholder();
                        generateAutomationCandidateForCompletedTurn(
                            mCurrentSessionId != null ? mCurrentSessionId : "");
                    }
                    mAdapter.collapseLastAssistantThinking();
                    mWaitingResponse = false;
                    updateStatus("● 就绪", 0xFF2E7D32);
                    FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
                    ChatMessage last = getLastAssistantMessageInCurrentTurn();
                    if (!isError) {
                        persistCodexConversation();
                    }
                    if (last != null && last.content != null && !last.content.isEmpty()) {
                        appendChatLog("Codex", last.content);
                    }
                    scrollToCurrentTurnOutputStartOnce();
                });
            }
        };
    }

    private ClaudeStreamSession.Listener buildClaudeListener() {
        final int viewGeneration = mViewGeneration;
        return new ClaudeStreamSession.Listener() {
            @Override public void onSystem(String info) {
                postProviderUi(AssistantProvider.CLAUDE, viewGeneration, () -> {
                    mAdapter.addMessage(ChatMessage.system(info));
                    scrollToBottom();
                });
            }
            @Override public void onAssistantText(String text) {
                postProviderUi(AssistantProvider.CLAUDE, viewGeneration, () -> {
                    mAdapter.updateLastAssistantText(text);
                    scrollToCurrentTurnOutputStartOnce();
                });
            }
            @Override public void onAssistantThinking(String thinking) {
                postProviderUi(AssistantProvider.CLAUDE, viewGeneration, () -> {
                    mAdapter.updateLastAssistantThinking(thinking);
                    scrollToCurrentTurnOutputStartOnce();
                });
            }
            @Override public void onToolUse(String name, String inputJson) {
                postProviderUi(AssistantProvider.CLAUDE, viewGeneration, () -> {
                    mAdapter.addMessage(ChatMessage.toolUse(name, inputJson));
                    scrollToCurrentTurnOutputStartOnce();
                });
            }
            @Override public void onToolResult(String name, String summary, String full) {
                postProviderUi(AssistantProvider.CLAUDE, viewGeneration, () -> {
                    mAdapter.addMessage(ChatMessage.toolResult(name, summary, full));
                    scrollToCurrentTurnOutputStartOnce();
                });
            }
            @Override public void onResult(String sid, boolean isError, String errMsg) {
                postProviderUi(AssistantProvider.CLAUDE, viewGeneration, () -> {
                    if (isError) {
                        clearAutomationTurnTracking();
                        mAdapter.updateLastAssistantText("⚠ " + errMsg);
                    } else {
                        // 占位 "…" 被流式 text 已经覆盖；若没有任何 text（极少见，全 tool 调用），
                        // 去掉占位避免气泡留空
                        dropPlaceholder();
                    }
                    mAdapter.collapseLastAssistantThinking();
                    mAdapter.collapseAllToolDetailsInLastTurn();
                    mWaitingResponse = false;

                    // 捕获 session_id：首次时绑定 + commit pending uploads
                    if (sid != null && !sid.equals(mCurrentSessionId)) {
                        mCurrentSessionId = sid;
                        mClaudeCurrentSessionId = sid;
                        saveSessionIdForProvider(AssistantProvider.CLAUDE, sid);
                        mUploadStore.commitPending(sid);
                    }
                    // 每条 turn 都 add：SessionStore 按 id 去重，但会刷新 timestamp + preview
                    // 每次都 refresh 抽屉，确保列表顺序/预览反映最新状态（修：之前只在首次
                    // 捕获时刷新，导致后续轮次的更新在用户切走再回来前看不到）
                    if (sid != null) {
                        mSessionStore.add(sid, System.currentTimeMillis(), mLastSentText);
                        refreshSessionDrawer();
                    }
                    if (!isError) {
                        generateAutomationCandidateForCompletedTurn(sid);
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
                postProviderUi(AssistantProvider.CLAUDE, viewGeneration, () -> {
                    clearAutomationTurnTracking();
                    dropPlaceholder();
                    mAdapter.collapseLastAssistantThinking();
                    mAdapter.collapseAllToolDetailsInLastTurn();
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

    private void persistCodexConversation() {
        if (mProvider != AssistantProvider.CODEX || mCurrentSessionId == null
                || mCodexSession == null || mTranscriptStore == null || mSessionStore == null) {
            return;
        }
        try {
            mTranscriptStore.save(mCurrentSessionId, mCodexSession.snapshotTranscript());
            mSessionStore.add(mCurrentSessionId, System.currentTimeMillis(), mLastSentText);
            mCodexCurrentSessionId = mCurrentSessionId;
            saveSessionIdForProvider(AssistantProvider.CODEX, mCurrentSessionId);
            refreshSessionDrawer();
        } catch (Exception ignored) {
        }
    }
}
