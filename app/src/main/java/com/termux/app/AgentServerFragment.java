package com.termux.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentServer 配置与管理页面。
 *
 * 通过 proot-distro login ubuntu -- agentserver <subcommand> 与 Ubuntu 内的 agentserver 交互。
 */
public class AgentServerFragment extends Fragment {

    private static final String PREFS_NAME        = "agentserver_config";
    private static final String KEY_SERVER_URL    = "server_url";
    private static final String KEY_SANDBOX_CODE  = "sandbox_code";
    private static final String KEY_DEVICE_NAME   = "device_name";
    private static final String KEY_SANDBOX_ID    = "sandbox_id";  // 上次成功连接的沙盒 ID
    private static final String KEY_WORKSPACE_ID  = "workspace_id";
    private static final String KEY_CODEX_CONNECT_COMMAND = "codex_connect_command";

    private TextView  mStatusText;
    private TextView  mInfoText;
    private TextView  mProviderText;
    private TextView  mCodeLabel;
    private TextView  mCodexCommandLabel;
    private EditText  mUrlEdit;
    private EditText  mCodeEdit;
    private EditText  mCodexCommandEdit;
    private EditText  mDeviceNameEdit;
    private TextView  mLogText;
    private TextView  mLogLabel;
    private ScrollView mLogScroll;
    private boolean   mMonitoring = false;

    private Thread mActiveThread;
    private String mLastSandboxId = "";  // 上次成功连接的沙盒 ID，用于 --resume
    private boolean mConnected = false;       // 本次 connect 是否成功建立 tunnel
    private boolean mRetryWithoutResume = false; // 401 后重试（不带 --resume）
    private AssistantProvider mProvider = AssistantProvider.CODEX;

    // OAuth Device Flow 授权弹窗：每次 doConnect 重置；同一次连接只弹一次
    private AlertDialog mAuthDialog;
    private boolean mAuthDialogShown = false;
    /** agentserver --skip-open-browser 输出格式：
     *    To authenticate, visit:
     *      https://...
     *  抓 https 链接（device flow 的 verification_uri_complete） */
    private static final Pattern AUTH_URL_PATTERN =
        Pattern.compile("https?://[\\w.-]+(?:/[\\w./?=&%+-]*)?");

    // ─────────────────────────────────────────────────────────────────────────
    // Fragment 生命周期
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_agent_server, container, false);

        mStatusText    = v.findViewById(R.id.agentserver_status_text);
        mInfoText      = v.findViewById(R.id.agentserver_info);
        mProviderText  = v.findViewById(R.id.agentserver_provider_text);
        mCodeLabel     = v.findViewById(R.id.agentserver_code_label);
        mCodexCommandLabel = v.findViewById(R.id.agentserver_codex_command_label);
        mUrlEdit       = v.findViewById(R.id.agentserver_url);
        mCodeEdit      = v.findViewById(R.id.agentserver_code);
        mCodexCommandEdit = v.findViewById(R.id.agentserver_codex_command);
        mDeviceNameEdit = v.findViewById(R.id.agentserver_device_name);
        mLogText       = v.findViewById(R.id.agentserver_log);
        mLogLabel      = v.findViewById(R.id.agentserver_log_label);
        mLogScroll     = v.findViewById(R.id.agentserver_log_scroll);

        v.findViewById(R.id.agentserver_back_button).setOnClickListener(b -> {
            if (getActivity() instanceof TermuxActivity) {
                ((TermuxActivity) getActivity()).navigateBackToCollaboration();
            }
        });
        v.findViewById(R.id.btn_agentserver_connect)   .setOnClickListener(b -> doConnect());
        v.findViewById(R.id.btn_agentserver_stop)      .setOnClickListener(b -> doStop());
        v.findViewById(R.id.btn_agentserver_refresh)   .setOnClickListener(b -> checkStatus());
        v.findViewById(R.id.btn_agentserver_monitor)   .setOnClickListener(b -> doMonitor());
        v.findViewById(R.id.btn_agentserver_clear_log) .setOnClickListener(b -> clearLog());

        loadPrefs();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshProviderFromSettings();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelActiveThread();
        if (mAuthDialog != null && mAuthDialog.isShowing()) {
            mAuthDialog.dismiss();
            mAuthDialog = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 操作
    // ─────────────────────────────────────────────────────────────────────────

    private void checkStatus() {
        refreshProviderFromSettings();
        String prefix  = System.getenv("PREFIX");
        if (prefix == null || prefix.isEmpty()) prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String logFile = agentLogFile(prefix, mProvider);
        ProviderProfile profile = ProviderProfile.forProvider(mProvider);
        String processPattern = AgentServerCommandBuilder.processPattern(mProvider);
        String binary = mProvider == AssistantProvider.CODEX ? "codex" : "agentserver";
        String missing = mProvider == AssistantProvider.CODEX
            ? "Codex CLI 未安装"
            : "AgentServer 未安装";
        String version = mProvider == AssistantProvider.CODEX
            ? "codex --version 2>/dev/null"
            : "agentserver version 2>/dev/null";

        String script =
            "if ! command -v proot-distro >/dev/null 2>&1; then\n" +
            "  echo '[!] proot-distro 未找到，Ubuntu 环境尚未初始化'; exit 1\n" +
            "fi\n" +
            "agentserver_pids() {\n" +
            "  pattern=\"$1\"\n" +
            "  pgrep -f \"$pattern\" 2>/dev/null | while read -r p; do\n" +
            "    [ \"$p\" = \"$$\" ] && continue\n" +
            "    args=$(ps -p \"$p\" -o args= 2>/dev/null || true)\n" +
            "    printf '%s\\n' \"$args\" | grep -Eq 'bash -c|proot-distro login' && continue\n" +
            "    echo \"$p\"\n" +
            "  done\n" +
            "}\n" +
            "if ! proot-distro login --user " + profile.user + " ubuntu -- sh -c 'command -v " + binary + " >/dev/null 2>&1'; then\n" +
            "  echo '[!] " + missing + "'; exit 1\n" +
            "fi\n" +
            "echo \"当前助手: " + profile.displayName + "\"\n" +
            "echo \"版本: $(proot-distro login --user " + profile.user + " ubuntu -- " + version + ")\"\n" +
            "echo ''\n" +
            "pids=$(agentserver_pids '" + processPattern + "')\n" +
            "if [ -n \"$pids\" ]; then\n" +
            "  echo '[*] Agent 运行中'\n" +
            "  printf '%s\\n' \"$pids\" | head -5 | while read -r p; do ps -p \"$p\" -o pid=,args= 2>/dev/null; done\n" +
            "else\n" +
            "  echo '[-] Agent 未运行'\n" +
            "fi\n" +
            "echo ''\n" +
            "echo '=== 最近日志（最后 30 行）==='\n" +
            "tail -30 '" + logFile + "' 2>/dev/null || echo '（无日志文件）'\n";

        runScript(script, "刷新状态", null);
    }

    /**
     * 在 Termux 层 nohup 整个 proot-distro 进程，使 agentserver 后台持续运行。
     * 连接成功后从日志解析 sandbox ID 并持久化，下次用 --resume 复用同一沙盒。
     */
    private void doConnect() {
        refreshProviderFromSettings();
        mConnected = false;
        mRetryWithoutResume = false;
        String url    = mUrlEdit.getText().toString().trim();
        String code   = mCodeEdit != null ? mCodeEdit.getText().toString().trim() : "";
        String codexConnectCommand = mCodexCommandEdit != null
            ? mCodexCommandEdit.getText().toString().trim() : "";
        String device = mDeviceNameEdit.getText().toString().trim();
        if (mProvider == AssistantProvider.CLAUDE && url.isEmpty()) {
            Toast.makeText(getContext(), "请填写服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mProvider == AssistantProvider.CODEX && codexConnectCommand.isEmpty()) {
            Toast.makeText(getContext(), "请粘贴 Codex Connector 命令", Toast.LENGTH_SHORT).show();
            return;
        }
        savePrefs();

        ProviderProfile profile = ProviderProfile.forProvider(mProvider);
        String apiKey = "", apiBaseUrl = "";
        if (mProvider == AssistantProvider.CLAUDE) {
            // Claude legacy path still launches agentserver claudecode and needs a local API key.
            ApiKeyStore keyStore = new ApiKeyStore(requireContext(), mProvider);
            String activeId = keyStore.getActiveId();
            if (activeId != null) {
                for (ApiKeyStore.Entry e : keyStore.loadAll()) {
                    if (e.id.equals(activeId)) { apiKey = e.value; apiBaseUrl = e.baseUrl; break; }
                }
            }
            if (mProvider == AssistantProvider.CLAUDE && apiKey.isEmpty()) {
                Toast.makeText(getContext(), "请先激活 Claude Code API Key", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        String prefix   = System.getenv("PREFIX");
        if (prefix == null || prefix.isEmpty()) prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;

        // 优先级：用户手填沙盒 ID > 上次自动保存的 ID > 不传（首次新建）
        String resumeId = !code.isEmpty() ? code : mLastSandboxId;
        AgentServerCommandBuilder.Config cfg = new AgentServerCommandBuilder.Config(
            url, resumeId, device, apiKey, apiBaseUrl, codexConnectCommand);
        String script = AgentServerCommandBuilder.connectScript(mProvider, cfg, prefix);

        // 重置授权弹窗状态（每次 doConnect 视为新的一次授权流程）
        mAuthDialogShown = false;

        // 监听输出：提取沙盒 ID；遇到 session not found 时自动清除旧 ID 避免下次继续失败
        runScript(script, "连接 AgentServer", line -> {
            // OAuth Device Flow：检测授权 URL 并弹窗（每次连接最多弹一次）
            if (!mAuthDialogShown && line.toLowerCase().contains("to authenticate") && line.contains("http")) {
                Matcher m = AUTH_URL_PATTERN.matcher(line);
                if (m.find()) {
                    final String authUrl = m.group();
                    mAuthDialogShown = true;
                    post(() -> showAuthDialog(authUrl));
                }
            }
            // 兼容：URL 可能在 "To authenticate, visit:" 的下一行
            if (!mAuthDialogShown && line.trim().startsWith("http")) {
                Matcher m = AUTH_URL_PATTERN.matcher(line);
                if (m.find() && line.contains("device")) {
                    final String authUrl = m.group();
                    mAuthDialogShown = true;
                    post(() -> showAuthDialog(authUrl));
                }
            }
            if (mProvider == AssistantProvider.CLAUDE && (line.contains("Failed to load session") || line.contains("session not found")
                    || line.contains("got 401") || line.contains("status code 101 but got"))) {
                mLastSandboxId = "";
                saveSandboxId("");
                mRetryWithoutResume = true;
                post(() -> {
                    setStatus("● 重试中", "#F57C00");
                    setInfo("沙盒 token 已过期，即将重新创建连接...");
                });
                return;
            }
            if (mProvider == AssistantProvider.CODEX && line.contains("Codex Connector 进程运行中")) {
                mConnected = true;
                post(() -> {
                    setStatus("● 已连接", "#388E3C");
                    setInfo("Codex Connector 已启动，手机已作为 AgentServer Connector 运行");
                });
                return;
            }
            int idx = line.indexOf("tunnel connected (sandbox:");
            if (idx < 0) return;
            int start = line.indexOf(':', idx + "tunnel connected ".length()) + 1;
            int end   = line.lastIndexOf(')');
            if (start > 0 && end > start) {
                String sandboxId = line.substring(start, end).trim();
                if (!sandboxId.isEmpty()) {
                    mLastSandboxId = sandboxId;
                    saveSandboxId(sandboxId);
                    mConnected = true;
                    // 立即更新状态，不等 25 秒 tail 超时
                    final String sid = sandboxId;
                    post(() -> {
                        setStatus("● 已连接", "#388E3C");
                        setInfo("AgentServer 已连接到服务器（沙盒: " + sid.substring(0, 8) + "...）");
                        // 授权成功后自动关闭授权弹窗
                        if (mAuthDialog != null && mAuthDialog.isShowing()) {
                            mAuthDialog.dismiss();
                            mAuthDialog = null;
                        }
                    });
                }
            }
        });
    }

    /**
     * 实时监控：快照两个日志文件最近内容，然后 tail -f 跟踪新增行。
     * - agentserver-*.log：agentserver 收到的任务描述 + 当前助手的完整输出
     * - mcp-audit.log：MCP 工具调用审计（Claude 对手机做了什么操作）
     * 点击任意其他按钮（或 onDestroyView）会自动停止跟踪。
     */
    private void doMonitor() {
        refreshProviderFromSettings();
        mMonitoring = true;
        String prefix = System.getenv("PREFIX");
        if (prefix == null || prefix.isEmpty()) prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String home     = prefix + "/../home";
        String agentLog = agentLogFile(prefix, mProvider);
        String mcpLog   = home + "/mcp-audit.log";

        String script =
            "echo '════════════════════════════════════════'\n" +
            "echo '  实时任务监控（新行自动追加）'\n" +
            "echo '  点击「刷新状态」/「连接」等按钮可停止'\n" +
            "echo '════════════════════════════════════════'\n" +
            "echo ''\n" +
            "echo '── AgentServer 任务日志（最近 40 行）──'\n" +
            "tail -n 40 '" + agentLog + "' 2>/dev/null || echo '（暂无日志）'\n" +
            "echo ''\n" +
            "echo '── MCP 工具调用记录（最近 40 行）──'\n" +
            "tail -n 40 '" + mcpLog + "' 2>/dev/null || echo '（暂无调用记录）'\n" +
            "echo ''\n" +
            "echo '── 实时跟踪中 ──'\n" +
            // tail -f on two files: prints "==> filename <==" header when source switches
            "tail -f -n 0 '" + agentLog + "' '" + mcpLog + "' 2>/dev/null\n";

        // 覆盖 runScript 默认行为：监控时不修改状态徽章
        cancelActiveThread();
        clearLog();
        if (mLogLabel != null) mLogLabel.setText("实时监控中…");

        String bash = prefix + "/bin/bash";
        String sysPath = System.getenv("PATH");
        if (sysPath == null) sysPath = "";
        final String finalPath   = prefix + "/bin:" + prefix + "/bin/applets:" + sysPath;
        final String finalPrefix = prefix;
        final String finalScript = script;

        mActiveThread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(bash, "-c", finalScript);
                pb.redirectErrorStream(true);
                java.util.Map<String, String> env = pb.environment();
                env.putAll(System.getenv());
                env.put("PATH",   finalPath);
                env.put("PREFIX", finalPrefix);
                env.put("HOME",   finalPrefix + "/../home");
                Process p = pb.start();

                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) { p.destroy(); break; }
                    final String l = line;
                    post(() -> appendLog(l + "\n"));
                }
                p.waitFor();
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                post(() -> appendLog("[!] 监控出错：" + e.getMessage() + "\n"));
            } finally {
                mMonitoring = false;
                post(() -> { if (mLogLabel != null) mLogLabel.setText("执行日志"); });
            }
        }, "agentserver-monitor");
        mActiveThread.setDaemon(true);
        mActiveThread.start();
    }

    /** 停止后台运行的 Agent（在 Termux 层 kill，不进入 proot）。 */
    private void doStop() {
        refreshProviderFromSettings();
        mConnected = false;
        String pattern = AgentServerCommandBuilder.processPattern(mProvider);
        runScript(
            "for _p in $(pgrep -f '" + pattern + "' 2>/dev/null); do [ \"$_p\" != \"$$\" ] && kill \"$_p\" 2>/dev/null; done\n" +
            "echo '[*] 当前助手 Agent 已断开连接'",
            "断开连接", null
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 命令执行
    // ─────────────────────────────────────────────────────────────────────────

    private void runScript(String bashScript, String label, @Nullable Consumer<String> lineCallback) {
        cancelActiveThread();
        mMonitoring = false;
        if (mLogLabel != null) mLogLabel.setText("执行日志");
        clearLog();
        appendLog("▶ " + label + "\n");
        setStatus("● 运行中", "#F57C00");
        setInfo("正在执行...");

        String prefix = System.getenv("PREFIX");
        if (prefix == null || prefix.isEmpty()) prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String bash = prefix + "/bin/bash";

        String sysPath = System.getenv("PATH");
        if (sysPath == null) sysPath = "";
        String termuxPath = prefix + "/bin:" + prefix + "/bin/applets:" + sysPath;
        final String finalPrefix = prefix;
        final String finalPath   = termuxPath;

        mActiveThread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(bash, "-c", bashScript);
                pb.redirectErrorStream(true);
                java.util.Map<String, String> env = pb.environment();
                env.putAll(System.getenv());
                env.put("PATH",   finalPath);
                env.put("PREFIX", finalPrefix);
                env.put("HOME",   finalPrefix + "/../home");
                Process p = pb.start();

                BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        p.destroy();
                        return;
                    }
                    if (lineCallback != null) lineCallback.accept(line);
                    final String l = line;
                    post(() -> appendLog(l + "\n"));
                }
                p.waitFor();
                int exit = p.exitValue();
                post(() -> {
                    appendLog("─────────────── 完成（exit " + exit + "）\n");
                    updateStatusFromLog(exit);
                });

            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                post(() -> {
                    appendLog("[!] 执行出错：" + e.getMessage() + "\n");
                    setStatus("● 错误", "#E53935");
                    setInfo("执行出错");
                });
            }
        }, "agentserver-cmd");
        mActiveThread.setDaemon(true);
        mActiveThread.start();
    }

    /** 根据退出码和 mConnected 标志更新状态徽章和摘要文本。 */
    private void updateStatusFromLog(int exitCode) {
        if (exitCode != 0) {
            String log = mLogText.getText().toString();
            boolean missingBinary =
                log.contains("未安装") ||
                log.contains("not installed") ||
                log.contains("command not found") ||
                log.contains("No such file or directory") ||
                log.contains("agentserver: not found") ||
                log.contains("proot-distro: not found");

            boolean badServer =
                log.contains("404") ||
                log.contains("page not found") ||
                log.contains("Not Found");

            if (missingBinary) {
                setStatus("● 未安装", "#888888");
                setInfo(mProvider == AssistantProvider.CODEX
                    ? "Codex CLI 未安装，请确认 Codex runtime 已完成安装"
                    : "AgentServer 未安装，请重启应用等待自动安装");
            } else if (log.contains("请粘贴 AgentServer Web UI 生成的 Codex Connector 命令")) {
                setStatus("● 待配置", "#888888");
                setInfo("请从 AgentServer Web UI 的 Connectors 页面复制 Codex Connector 命令");
            } else if (badServer) {
                setStatus("● 失败", "#E53935");
                setInfo("连接失败：服务器地址可能不对（404 Not Found），请检查 URL 是否为 agentserver 的根地址");
            } else if (log.contains("未就绪") || log.contains("proot-distro 未找到")) {
                setStatus("● 环境未就绪", "#888888");
                setInfo("Ubuntu 环境尚未初始化，请先切换到终端 Tab");
            } else {
                setStatus("● 失败", "#E53935");
                setInfo("命令执行失败，请查看日志");
            }
            return;
        }

        String log = mLogText.getText().toString();
        if (log.contains("请粘贴 AgentServer Web UI 生成的 Codex Connector 命令")) {
            setStatus("● 待配置", "#888888");
            setInfo("请从 AgentServer Web UI 的 Connectors 页面复制 Codex Connector 命令");
        } else if (mConnected) {
            setStatus("● 已连接", "#388E3C");
            if (mProvider == AssistantProvider.CODEX) {
                setInfo("Codex Connector 已启动，手机已作为 AgentServer Connector 运行");
            } else {
                setInfo("AgentServer 已连接到服务器" +
                    (mLastSandboxId.isEmpty() ? "" : "（沙盒: " + mLastSandboxId.substring(0, 8) + "...）"));
            }
        } else if (mRetryWithoutResume) {
            mRetryWithoutResume = false;
            setStatus("● Token 已过期", "#F57C00");
            setInfo("沙盒 token 已过期，旧 ID 已清除，请点击「连接」重新创建沙盒");
            appendLog("\n[!] 沙盒 token 过期（401），已清除旧 ID，请重新点击「连接」\n");
        } else {
            setStatus("● 已安装", "#555555");
            setInfo("Agent 进程已启动，但未检测到 tunnel 连接");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI 辅助
    // ─────────────────────────────────────────────────────────────────────────

    private void appendLog(String text) {
        mLogText.append(text);
        mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * 弹出 OAuth Device Flow 授权对话框：显示 QR 码 + 可复制链接 + 浏览器按钮。
     * 连接成功（拿到 sandbox id）时会被 doConnect 主动 dismiss。
     */
    private void showAuthDialog(String authUrl) {
        if (getContext() == null) return;
        // 关掉旧的（同一次连接里防御性）
        if (mAuthDialog != null && mAuthDialog.isShowing()) mAuthDialog.dismiss();

        View view = LayoutInflater.from(getContext())
            .inflate(R.layout.dialog_auth_qr, null, false);
        ImageView qrIv = view.findViewById(R.id.auth_qr_image);
        TextView  urlTv = view.findViewById(R.id.auth_url_text);

        urlTv.setText(authUrl);
        Bitmap bmp = QrCodeUtil.generate(authUrl, 600);
        if (bmp != null) qrIv.setImageBitmap(bmp);
        else qrIv.setVisibility(View.GONE);

        view.findViewById(R.id.auth_btn_copy).setOnClickListener(b -> {
            ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("auth url", authUrl));
            Toast.makeText(getContext(), "链接已复制", Toast.LENGTH_SHORT).show();
        });

        view.findViewById(R.id.auth_btn_open).setOnClickListener(b -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(getContext(), "无法打开浏览器: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            }
        });

        mAuthDialog = new AlertDialog.Builder(requireContext())
            .setView(view)
            .setNegativeButton("取消授权", (d, w) -> {
                d.dismiss();
                mAuthDialog = null;
            })
            .setCancelable(true)
            .create();
        mAuthDialog.show();
    }

    private void clearLog() {
        if (mLogText != null) mLogText.setText("");
    }

    private void setStatus(String text, String colorHex) {
        mStatusText.setText(text);
        mStatusText.setTextColor(Color.parseColor(colorHex));
    }

    private void setInfo(String text) {
        mInfoText.setText(text);
    }

    private void loadPrefs() {
        SharedPreferences p = requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        refreshProviderFromSettings();
        mUrlEdit.setText(p.getString(KEY_SERVER_URL, ""));
        if (mProvider == AssistantProvider.CODEX) {
            mCodeEdit.setText(firstNonEmpty(
                p.getString(KEY_WORKSPACE_ID, ""),
                p.getString(KEY_SANDBOX_CODE, "")));
        } else {
            mCodeEdit.setText(firstNonEmpty(
                p.getString(KEY_SANDBOX_CODE, ""),
                p.getString(KEY_WORKSPACE_ID, "")));
        }
        if (mCodexCommandEdit != null) {
            mCodexCommandEdit.setText(p.getString(KEY_CODEX_CONNECT_COMMAND, ""));
        }
        mDeviceNameEdit.setText(p.getString(KEY_DEVICE_NAME, ""));
        mLastSandboxId = p.getString(KEY_SANDBOX_ID, "");
    }

    private void savePrefs() {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, mUrlEdit.getText().toString().trim())
            .putString(KEY_SANDBOX_CODE, mCodeEdit.getText().toString().trim())
            .putString(KEY_WORKSPACE_ID, mProvider == AssistantProvider.CODEX
                ? mCodeEdit.getText().toString().trim()
                : requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_WORKSPACE_ID, ""))
            .putString(KEY_CODEX_CONNECT_COMMAND, mCodexCommandEdit == null
                ? "" : mCodexCommandEdit.getText().toString().trim())
            .putString(KEY_DEVICE_NAME, mDeviceNameEdit.getText().toString().trim())
            .apply();
    }

    private void saveSandboxId(String sandboxId) {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SANDBOX_ID, sandboxId)
            .apply();
    }

    private void cancelActiveThread() {
        if (mActiveThread != null && mActiveThread.isAlive()) {
            mActiveThread.interrupt();
        }
    }

    private void post(Runnable r) {
        if (getActivity() != null) getActivity().runOnUiThread(r);
    }

    private void refreshProviderFromSettings() {
        if (getContext() == null) return;
        mProvider = new ProviderSettingsStore(requireContext()).getSelectedProvider();
        if (mProviderText != null) {
            ProviderProfile profile = ProviderProfile.forProvider(mProvider);
            if (mProvider == AssistantProvider.CODEX) {
                mProviderText.setText("当前助手：" + profile.displayName
                    + "（Driver 为主；Codex Connector / exec-server 可选）");
            } else {
                mProviderText.setText("当前助手：" + profile.displayName + "（Driver 为主；旧版 claudecode 连接可选）");
            }
        }
        if (mCodeLabel != null && mCodeEdit != null) {
            if (mProvider == AssistantProvider.CODEX) {
                mCodeLabel.setText("工作空间 ID（可选）");
                mCodeEdit.setHint("例如 ws_...，用于协作页和 Loom 复用");
            } else {
                mCodeLabel.setText("工作空间 / 沙盒 ID（可选）");
                mCodeEdit.setHint("留空则由 Driver 绑定后自动同步 workspace");
            }
        }
        int codexVisibility = mProvider == AssistantProvider.CODEX ? View.VISIBLE : View.GONE;
        if (mCodexCommandLabel != null) {
            mCodexCommandLabel.setVisibility(codexVisibility);
        }
        if (mCodexCommandEdit != null) {
            mCodexCommandEdit.setVisibility(codexVisibility);
        }
    }

    private static String agentLogFile(String prefix, AssistantProvider provider) {
        String home = prefix + "/../home";
        return home + (provider == AssistantProvider.CODEX
            ? "/agentserver-codex-agent.log"
            : "/agentserver-agent.log");
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }
}
