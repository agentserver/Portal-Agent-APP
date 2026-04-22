package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.Consumer;

/**
 * AgentServer 配置与管理页面。
 *
 * 通过 proot-distro login ubuntu -- agentserver <subcommand> 与 Ubuntu 内的 agentserver 交互。
 */
public class AgentServerFragment extends Fragment {

    private static final String PREFS_NAME        = "agentserver_config";
    private static final String PROOT_USER        = "claude";  // non-root user inside Ubuntu proot
    private static final String KEY_SERVER_URL    = "server_url";
    private static final String KEY_SANDBOX_CODE  = "sandbox_code";
    private static final String KEY_DEVICE_NAME   = "device_name";
    private static final String KEY_SANDBOX_ID    = "sandbox_id";  // 上次成功连接的沙盒 ID

    private TextView  mStatusText;
    private TextView  mInfoText;
    private EditText  mUrlEdit;
    private EditText  mCodeEdit;
    private EditText  mDeviceNameEdit;
    private TextView  mLogText;
    private ScrollView mLogScroll;

    private Thread mActiveThread;
    private String mLastSandboxId = "";  // 上次成功连接的沙盒 ID，用于 --resume
    private boolean mConnected = false;       // 本次 connect 是否成功建立 tunnel
    private boolean mRetryWithoutResume = false; // 401 后重试（不带 --resume）

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
        mUrlEdit       = v.findViewById(R.id.agentserver_url);
        mCodeEdit      = v.findViewById(R.id.agentserver_code);
        mDeviceNameEdit = v.findViewById(R.id.agentserver_device_name);
        mLogText       = v.findViewById(R.id.agentserver_log);
        mLogScroll     = v.findViewById(R.id.agentserver_log_scroll);

        v.findViewById(R.id.btn_agentserver_connect)   .setOnClickListener(b -> doConnect());
        v.findViewById(R.id.btn_agentserver_stop)      .setOnClickListener(b -> doStop());
        v.findViewById(R.id.btn_agentserver_refresh)   .setOnClickListener(b -> checkStatus());
        v.findViewById(R.id.btn_agentserver_clear_log) .setOnClickListener(b -> clearLog());

        loadPrefs();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelActiveThread();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 操作
    // ─────────────────────────────────────────────────────────────────────────

    private void checkStatus() {
        String prefix  = System.getenv("PREFIX");
        if (prefix == null || prefix.isEmpty()) prefix = "/data/data/com.termux/files/usr";
        String logFile = prefix + "/../home/agentserver-agent.log";

        String script =
            "if ! command -v proot-distro >/dev/null 2>&1; then\n" +
            "  echo '[!] proot-distro 未找到，Ubuntu 环境尚未初始化'; exit 1\n" +
            "fi\n" +
            "if ! proot-distro login --user " + PROOT_USER + " ubuntu -- sh -c 'command -v agentserver >/dev/null 2>&1'; then\n" +
            "  echo '[!] AgentServer 未安装'; exit 1\n" +
            "fi\n" +
            "echo \"版本: $(proot-distro login --user " + PROOT_USER + " ubuntu -- agentserver version 2>/dev/null)\"\n" +
            "echo ''\n" +
            "if pgrep -f 'agentserver' >/dev/null 2>&1; then\n" +
            "  echo '[*] Agent 运行中'\n" +
            "  pgrep -a -f 'agentserver' 2>/dev/null | grep -v grep | head -5\n" +
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
        mConnected = false;
        mRetryWithoutResume = false;
        String url    = mUrlEdit.getText().toString().trim();
        String code   = mCodeEdit != null ? mCodeEdit.getText().toString().trim() : "";
        String device = mDeviceNameEdit.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(getContext(), "请填写服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }
        savePrefs();

        String prefix   = System.getenv("PREFIX");
        if (prefix == null || prefix.isEmpty()) prefix = "/data/data/com.termux/files/usr";
        String home     = prefix + "/../home";
        String logFile  = home + "/agentserver-agent.log";
        String pdBin    = prefix + "/bin/proot-distro";
        String safeUrl  = url.replace("'", "'\\''");
        String nameFlag = device.isEmpty() ? "" : " --name '" + device.replace("'", "'\\''") + "'";

        // 优先级：用户手填沙盒 ID > 上次自动保存的 ID > 不传（首次新建）
        String resumeId = !code.isEmpty() ? code : mLastSandboxId;
        String resumeFlag = resumeId.isEmpty() ? "" : " --resume '" + resumeId.replace("'", "'\\''") + "'";

        String agentArgs = "claudecode --server '" + safeUrl + "'" + resumeFlag + nameFlag + " --skip-open-browser";

        String script =
            // pgrep 排除当前 bash 自身（$$），避免 pkill -f 因 cmdline 包含 'agentserver claudecode' 把自己杀掉（exit 143）
            "for _p in $(pgrep -f 'agentserver claudecode' 2>/dev/null);" +
            " do [ \"$_p\" != \"$$\" ] && kill \"$_p\" 2>/dev/null; done; sleep 1\n" +
            "> '" + logFile + "'\n" +          // 清空旧日志，避免历史内容干扰状态检测
            "echo '[*] 正在启动 AgentServer...'\n" +
            // bash -c 包裹：source .bashrc 使 ANTHROPIC_API_KEY 等环境变量生效
            // proot-distro 直接执行命令不走 login shell，env var 不会自动注入
            "nohup '" + pdBin + "' login --user " + PROOT_USER + " ubuntu -- bash -c" +
            " \". /home/claude/.profile 2>/dev/null; . /home/claude/.bashrc 2>/dev/null; exec agentserver " + agentArgs + "\"" +
            " >> '" + logFile + "' 2>&1 &\n" +
            "AS_PID=$!\n" +
            "echo '[*] 等待启动（5 秒）...'\n" +
            "sleep 5\n" +
            "echo ''\n" +
            "echo '=== 当前日志 ==='\n" +
            "cat '" + logFile + "' 2>/dev/null || echo '（无日志）'\n" +
            "echo ''\n" +
            "if kill -0 $AS_PID 2>/dev/null; then\n" +
            "  echo \"[*] Agent 进程运行中（PID: $AS_PID）\"\n" +
            "else\n" +
            "  echo '[!] Agent 进程已退出'\n" +
            "fi\n";

        // 监听输出：提取沙盒 ID；遇到 session not found 时自动清除旧 ID 避免下次继续失败
        runScript(script, "连接 AgentServer", line -> {
            if (line.contains("Failed to load session") || line.contains("session not found")
                    || line.contains("got 401") || line.contains("status code 101 but got")) {
                mLastSandboxId = "";
                saveSandboxId("");
                mRetryWithoutResume = true;
                post(() -> {
                    setStatus("● 重试中", "#F57C00");
                    setInfo("沙盒 token 已过期，即将重新创建连接...");
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
                    });
                }
            }
        });
    }

    /** 停止后台运行的 Agent（在 Termux 层 kill，不进入 proot）。 */
    private void doStop() {
        mConnected = false;
        runScript(
            "pkill -f 'proot-distro.*login ubuntu' 2>/dev/null && echo '[*] Agent 已断开连接'" +
            " || echo '[!] 未找到运行中的 Agent 进程'",
            "断开连接", null
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 命令执行
    // ─────────────────────────────────────────────────────────────────────────

    private void runScript(String bashScript, String label, @Nullable Consumer<String> lineCallback) {
        cancelActiveThread();
        clearLog();
        appendLog("▶ " + label + "\n");
        setStatus("● 运行中", "#F57C00");
        setInfo("正在执行...");

        String prefix = System.getenv("PREFIX");
        if (prefix == null || prefix.isEmpty()) prefix = "/data/data/com.termux/files/usr";
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
            if (log.contains("未安装") || log.contains("not installed") || log.contains("not found")) {
                setStatus("● 未安装", "#888888");
                setInfo("AgentServer 未安装，请重启应用等待自动安装");
            } else if (log.contains("未就绪") || log.contains("proot-distro 未找到")) {
                setStatus("● 环境未就绪", "#888888");
                setInfo("Ubuntu 环境尚未初始化，请先切换到终端 Tab");
            } else {
                setStatus("● 失败", "#E53935");
                setInfo("命令执行失败，请查看日志");
            }
            return;
        }

        if (mConnected) {
            setStatus("● 已连接", "#388E3C");
            setInfo("AgentServer 已连接到服务器" +
                (mLastSandboxId.isEmpty() ? "" : "（沙盒: " + mLastSandboxId.substring(0, 8) + "...）"));
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
        mUrlEdit.setText(p.getString(KEY_SERVER_URL, ""));
        mCodeEdit.setText(p.getString(KEY_SANDBOX_CODE, ""));
        mDeviceNameEdit.setText(p.getString(KEY_DEVICE_NAME, ""));
        mLastSandboxId = p.getString(KEY_SANDBOX_ID, "");
    }

    private void savePrefs() {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, mUrlEdit.getText().toString().trim())
            .putString(KEY_SANDBOX_CODE, mCodeEdit.getText().toString().trim())
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
}
