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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.loom.LoomCommandBuilder;
import com.termux.app.loom.LoomSettings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoomFragment extends Fragment {

    private static final String[] ROLE_LABELS = {
        "All-in-one", "Observer", "Driver", "Slave"
    };
    private static final String[] ROLE_VALUES = {
        "all", "observer", "driver", "slave"
    };
    private static final Pattern AUTH_URL_PATTERN =
        Pattern.compile("https?://[\\w.-]+(?:/[\\w./?=&%+-]*)?");

    private Spinner mRoleMode;
    private TextView mStatusText;
    private TextView mInfoText;
    private TextView mProviderText;
    private TextView mLogText;
    private TextView mLogLabel;
    private ScrollView mLogScroll;
    private EditText mObserverUrl;
    private EditText mWorkspaceId;
    private EditText mWorkspaceApiKey;
    private EditText mAgentServerUrl;
    private EditText mObserverName;
    private EditText mDriverName;
    private EditText mSlaveName;
    private EditText mTags;
    private TextView mSetupButton;
    private View mStartObserverButton;
    private View mStopObserverButton;
    private View mRegisterDriverButton;
    private View mStartSlaveButton;
    private View mStopSlaveButton;

    private Thread mActiveThread;
    private Process mActiveProcess;
    private boolean mMonitoring;
    private AlertDialog mAuthDialog;
    private boolean mAuthDialogShown;
    private boolean mAuthUrlExpected;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_loom, container, false);
        bindViews(v);
        setupRoleSpinner();
        loadPrefs();
        wireButtons(v);
        return v;
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

    @Override
    public void onResume() {
        super.onResume();
        refreshProviderText();
    }

    private void bindViews(View v) {
        mRoleMode = v.findViewById(R.id.loom_role_mode);
        mStatusText = v.findViewById(R.id.loom_status_text);
        mInfoText = v.findViewById(R.id.loom_info);
        mProviderText = v.findViewById(R.id.loom_provider_text);
        mLogText = v.findViewById(R.id.loom_log);
        mLogLabel = v.findViewById(R.id.loom_log_label);
        mLogScroll = v.findViewById(R.id.loom_log_scroll);
        mObserverUrl = v.findViewById(R.id.loom_observer_url);
        mWorkspaceId = v.findViewById(R.id.loom_workspace_id);
        mWorkspaceApiKey = v.findViewById(R.id.loom_workspace_api_key);
        mAgentServerUrl = v.findViewById(R.id.loom_agentserver_url);
        mObserverName = v.findViewById(R.id.loom_observer_name);
        mDriverName = v.findViewById(R.id.loom_driver_name);
        mSlaveName = v.findViewById(R.id.loom_slave_name);
        mTags = v.findViewById(R.id.loom_tags);
        mSetupButton = v.findViewById(R.id.btn_loom_setup);
        mStartObserverButton = v.findViewById(R.id.btn_loom_start_observer);
        mStopObserverButton = v.findViewById(R.id.btn_loom_stop_observer);
        mRegisterDriverButton = v.findViewById(R.id.btn_loom_register_driver);
        mStartSlaveButton = v.findViewById(R.id.btn_loom_start_slave);
        mStopSlaveButton = v.findViewById(R.id.btn_loom_stop_slave);
    }

    private void setupRoleSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
            android.R.layout.simple_spinner_item, ROLE_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mRoleMode.setAdapter(adapter);
        mRoleMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateRoleActions();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateRoleActions();
            }
        });
    }

    private void wireButtons(View v) {
        v.findViewById(R.id.btn_loom_save).setOnClickListener(b -> {
            savePrefs();
            Toast.makeText(getContext(), "Loom 设置已保存", Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.btn_loom_status).setOnClickListener(b ->
            runScript(LoomCommandBuilder.statusScript(prefix(), runtimeSettings()), "Loom 状态", null));
        v.findViewById(R.id.btn_loom_setup).setOnClickListener(b -> runSetupForSelectedRole());
        v.findViewById(R.id.btn_loom_start_observer).setOnClickListener(b -> {
            LoomSettings settings = currentSettings();
            String script = transitionScript(settings, "observer")
                + LoomCommandBuilder.startObserverScript(prefix(), settings);
            rememberRuntimeProvider(settings);
            runScript(script, "启动 Observer", null);
        });
        v.findViewById(R.id.btn_loom_stop_observer).setOnClickListener(b ->
            runScript(LoomCommandBuilder.stopObserverScript(runtimeSettings()), "停止 Observer", null));
        v.findViewById(R.id.btn_loom_register_driver).setOnClickListener(b -> {
            LoomSettings settings = currentSettings();
            mAuthDialogShown = false;
            mAuthUrlExpected = false;
            runScript(LoomCommandBuilder.registerDriverScript(settings), "注册 Driver", this::maybeShowAuthUrl);
        });
        v.findViewById(R.id.btn_loom_start_slave).setOnClickListener(b -> {
            LoomSettings settings = currentSettings();
            String script = transitionScript(settings, "slave")
                + LoomCommandBuilder.startSlaveScript(prefix(), settings);
            mAuthDialogShown = false;
            mAuthUrlExpected = false;
            rememberRuntimeProvider(settings);
            runScript(script, "启动 Slave", this::maybeShowAuthUrl);
        });
        v.findViewById(R.id.btn_loom_stop_slave).setOnClickListener(b ->
            runScript(LoomCommandBuilder.stopSlaveScript(runtimeSettings()), "停止 Slave", null));
        v.findViewById(R.id.btn_loom_monitor).setOnClickListener(b -> monitorLogs());
        v.findViewById(R.id.btn_loom_clear_log).setOnClickListener(b -> clearLog());
    }

    private void runSetupForSelectedRole() {
        savePrefs();
        LoomSettings settings = currentSettings();
        if ("all".equals(selectedRoleMode())) {
            mAuthDialogShown = false;
            mAuthUrlExpected = false;
            String script = transitionScript(settings, "all")
                + LoomCommandBuilder.startAllInOneScript(prefix(), settings);
            rememberRuntimeProvider(settings);
            runScript(script,
                "启动 All-in-one", this::maybeShowAuthUrl);
        } else {
            runScript(LoomCommandBuilder.setupConfigScript(settings), "生成 Loom 配置", null);
        }
    }

    private LoomSettings currentSettings() {
        LoomSettings d = LoomSettings.defaults();
        return d.withRoleMode(selectedRoleMode())
            .withObserverUrl(valueOrDefault(mObserverUrl, d.observerUrl))
            .withWorkspaceId(valueOrDefault(mWorkspaceId, d.workspaceId))
            .withWorkspaceApiKey(valueOrDefault(mWorkspaceApiKey, d.workspaceApiKey))
            .withAgentServerUrl(valueOrDefault(mAgentServerUrl, d.agentServerUrl))
            .withObserverName(valueOrDefault(mObserverName, d.observerName))
            .withDriverName(valueOrDefault(mDriverName, d.driverName))
            .withSlaveName(valueOrDefault(mSlaveName, d.slaveName))
            .withTags(valueOrDefault(mTags, d.tags))
            .withAgentProvider(new ProviderSettingsStore(requireContext()).getSelectedProvider());
    }

    private void loadPrefs() {
        LoomSettings d = LoomSettings.defaults();
        SharedPreferences p = requireContext()
            .getSharedPreferences(LoomSettings.PREFS_NAME, Context.MODE_PRIVATE);
        mObserverUrl.setText(prefOrDefault(p, LoomSettings.KEY_OBSERVER_URL, d.observerUrl));
        mWorkspaceId.setText(prefOrDefault(p, LoomSettings.KEY_WORKSPACE_ID, d.workspaceId));
        mWorkspaceApiKey.setText(prefOrDefault(p, LoomSettings.KEY_WORKSPACE_API_KEY, d.workspaceApiKey));
        mAgentServerUrl.setText(prefOrDefault(p, LoomSettings.KEY_AGENTSERVER_URL, d.agentServerUrl));
        mObserverName.setText(prefOrDefault(p, LoomSettings.KEY_OBSERVER_NAME, d.observerName));
        mDriverName.setText(prefOrDefault(p, LoomSettings.KEY_DRIVER_NAME, d.driverName));
        mSlaveName.setText(prefOrDefault(p, LoomSettings.KEY_SLAVE_NAME, d.slaveName));
        mTags.setText(prefOrDefault(p, LoomSettings.KEY_TAGS, d.tags));
        mRoleMode.setSelection(indexOfRole(p.getString(LoomSettings.KEY_ROLE_MODE, d.roleMode)), false);
        refreshProviderText();
        updateRoleActions();
    }

    private void savePrefs() {
        LoomSettings d = LoomSettings.defaults();
        requireContext().getSharedPreferences(LoomSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LoomSettings.KEY_ROLE_MODE, selectedRoleMode())
            .putString(LoomSettings.KEY_OBSERVER_URL, valueOrDefault(mObserverUrl, d.observerUrl))
            .putString(LoomSettings.KEY_WORKSPACE_ID, valueOrDefault(mWorkspaceId, d.workspaceId))
            .putString(LoomSettings.KEY_WORKSPACE_API_KEY, valueOrDefault(mWorkspaceApiKey, d.workspaceApiKey))
            .putString(LoomSettings.KEY_AGENTSERVER_URL, valueOrDefault(mAgentServerUrl, d.agentServerUrl))
            .putString(LoomSettings.KEY_OBSERVER_NAME, valueOrDefault(mObserverName, d.observerName))
            .putString(LoomSettings.KEY_DRIVER_NAME, valueOrDefault(mDriverName, d.driverName))
            .putString(LoomSettings.KEY_SLAVE_NAME, valueOrDefault(mSlaveName, d.slaveName))
            .putString(LoomSettings.KEY_TAGS, valueOrDefault(mTags, d.tags))
            .apply();
    }

    private void updateRoleActions() {
        String role = selectedRoleMode();
        boolean all = "all".equals(role);
        boolean observer = all || "observer".equals(role);
        boolean driver = all || "driver".equals(role);
        boolean slave = all || "slave".equals(role);

        if (mSetupButton != null) {
            mSetupButton.setText(all ? "启动 All-in-one" : "生成配置");
        }
        if (mStartObserverButton != null) mStartObserverButton.setVisibility(observer ? View.VISIBLE : View.GONE);
        if (mStopObserverButton != null) mStopObserverButton.setVisibility(observer ? View.VISIBLE : View.GONE);
        if (mRegisterDriverButton != null) mRegisterDriverButton.setVisibility(driver ? View.VISIBLE : View.GONE);
        if (mStartSlaveButton != null) mStartSlaveButton.setVisibility(slave ? View.VISIBLE : View.GONE);
        if (mStopSlaveButton != null) mStopSlaveButton.setVisibility(slave ? View.VISIBLE : View.GONE);
    }

    private void monitorLogs() {
        mMonitoring = true;
        String p = prefix();
        String observerLog = p + "/../home/loom-observer.log";
        String slaveLog = p + "/../home/loom-slave.log";
        String driverLog = p + "/../home/loom-driver-register.log";
        String script = ""
            + "echo '== Loom logs =='\n"
            + "tail -n 60 " + sq(observerLog) + " 2>/dev/null || true\n"
            + "tail -n 60 " + sq(slaveLog) + " 2>/dev/null || true\n"
            + "tail -n 60 " + sq(driverLog) + " 2>/dev/null || true\n"
            + "tail -f -n 0 " + sq(observerLog) + " " + sq(slaveLog) + " " + sq(driverLog) + " 2>/dev/null\n";
        runScript(script, "实时监控", null);
    }

    private void maybeShowAuthUrl(String line) {
        String lower = line.toLowerCase();
        boolean authHint = lower.contains("register") || lower.contains("authenticate") || lower.contains("device");
        if (authHint) mAuthUrlExpected = true;
        if (mAuthDialogShown || !lower.contains("http")) return;
        if (!authHint && !mAuthUrlExpected) return;
        Matcher m = AUTH_URL_PATTERN.matcher(line);
        if (!m.find()) return;
        String authUrl = m.group();
        mAuthDialogShown = true;
        mAuthUrlExpected = false;
        post(() -> showAuthDialog(authUrl));
    }

    private void runScript(String bashScript, String label, @Nullable Consumer<String> lineCallback) {
        cancelActiveThread();
        if (mLogLabel != null) mLogLabel.setText(mMonitoring ? "实时监控中..." : "执行日志");
        clearLog();
        appendLog("> " + label + "\n");
        setStatus("● 运行中", "#F57C00");
        setInfo("正在执行...");

        String prefix = prefix();
        String bash = prefix + "/bin/bash";
        String sysPath = System.getenv("PATH");
        if (sysPath == null) sysPath = "";
        String termuxPath = prefix + "/bin:" + prefix + "/bin/applets:" + sysPath;
        final String finalPrefix = prefix;
        final String finalPath = termuxPath;
        final String finalScript = bashScript;

        mActiveThread = new Thread(() -> {
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(bash, "-c", finalScript);
                pb.redirectErrorStream(true);
                java.util.Map<String, String> env = pb.environment();
                env.putAll(System.getenv());
                env.put("PATH", finalPath);
                env.put("PREFIX", finalPrefix);
                env.put("HOME", finalPrefix + "/../home");
                p = pb.start();
                mActiveProcess = p;

                BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
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
                int exit = p.waitFor();
                post(() -> {
                    appendLog("--------------- 完成(exit " + exit + ")\n");
                    updateStatusFromLog(exit);
                });
            } catch (InterruptedException ignored) {
                if (p != null) p.destroy();
            } catch (Exception e) {
                post(() -> {
                    appendLog("[!] 执行出错: " + e.getMessage() + "\n");
                    setStatus("● 错误", "#E53935");
                    setInfo("执行出错");
                });
            } finally {
                if (mActiveProcess == p) mActiveProcess = null;
                mMonitoring = false;
                post(() -> { if (mLogLabel != null) mLogLabel.setText("执行日志"); });
            }
        }, "loom-cmd");
        mActiveThread.setDaemon(true);
        mActiveThread.start();
    }

    private void updateStatusFromLog(int exitCode) {
        String log = mLogText == null ? "" : mLogText.getText().toString();
        if (exitCode != 0) {
            if (log.contains("proot-distro") || log.contains("not found") || log.contains("No such file")) {
                setStatus("● 环境未就绪", "#888888");
                setInfo("请先进入终端等待 Ubuntu 和 Loom 自动安装完成");
            } else {
                setStatus("● 失败", "#E53935");
                setInfo("命令执行失败，请查看日志");
            }
            return;
        }
        if (log.contains("observer: running") || log.contains("slave: running")) {
            setStatus("● 运行中", "#388E3C");
            setInfo("Loom 角色进程已检测到");
        } else {
            setStatus("● 已完成", "#555555");
            setInfo("命令执行完成");
        }
    }

    private void showAuthDialog(String authUrl) {
        if (getContext() == null) return;
        if (mAuthDialog != null && mAuthDialog.isShowing()) mAuthDialog.dismiss();

        View view = LayoutInflater.from(getContext())
            .inflate(R.layout.dialog_auth_qr, null, false);
        android.widget.ImageView qrIv = view.findViewById(R.id.auth_qr_image);
        TextView urlTv = view.findViewById(R.id.auth_url_text);

        urlTv.setText(authUrl);
        Bitmap bmp = QrCodeUtil.generate(authUrl, 600);
        if (bmp != null) qrIv.setImageBitmap(bmp);
        else qrIv.setVisibility(View.GONE);

        view.findViewById(R.id.auth_btn_copy).setOnClickListener(b -> {
            ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("loom auth url", authUrl));
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

    private String selectedRoleMode() {
        int pos = mRoleMode == null ? 0 : mRoleMode.getSelectedItemPosition();
        if (pos < 0 || pos >= ROLE_VALUES.length) return "all";
        return ROLE_VALUES[pos];
    }

    private int indexOfRole(String role) {
        for (int i = 0; i < ROLE_VALUES.length; i++) {
            if (ROLE_VALUES[i].equals(role)) return i;
        }
        return 0;
    }

    private String valueOrDefault(EditText view, String defaultValue) {
        String value = view.getText().toString().trim();
        return value.isEmpty() ? defaultValue : value;
    }

    private String prefOrDefault(SharedPreferences prefs, String key, String defaultValue) {
        String value = prefs.getString(key, defaultValue);
        if (value == null || value.trim().isEmpty()) return defaultValue;
        return value;
    }

    private String prefix() {
        String p = System.getenv("PREFIX");
        return (p == null || p.isEmpty()) ? "/data/data/com.termux/files/usr" : p;
    }

    private void appendLog(String text) {
        if (mLogText == null) return;
        mLogText.append(text);
        if (mLogScroll != null) mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void clearLog() {
        if (mLogText != null) mLogText.setText("");
    }

    private void setStatus(String text, String colorHex) {
        if (mStatusText == null) return;
        mStatusText.setText(text);
        mStatusText.setTextColor(Color.parseColor(colorHex));
    }

    private void setInfo(String text) {
        if (mInfoText != null) mInfoText.setText(text);
    }

    private void refreshProviderText() {
        if (getContext() == null || mProviderText == null) return;
        AssistantProvider provider = new ProviderSettingsStore(requireContext()).getSelectedProvider();
        ProviderProfile profile = ProviderProfile.forProvider(provider);
        AssistantProvider runtimeProvider = savedRuntimeProvider();
        if (runtimeProvider != null && runtimeProvider != provider) {
            ProviderProfile runtimeProfile = ProviderProfile.forProvider(runtimeProvider);
            mProviderText.setText("当前助手：" + profile.displayName
                + "（上次 Loom 使用 " + runtimeProfile.displayName + "，启动时会先停止旧角色）");
        } else {
            mProviderText.setText("当前助手：" + profile.displayName + "（启动前会按它生成配置）");
        }
    }

    private LoomSettings runtimeSettings() {
        AssistantProvider runtimeProvider = savedRuntimeProvider();
        LoomSettings settings = currentSettings();
        return runtimeProvider == null ? settings : settings.withAgentProvider(runtimeProvider);
    }

    @Nullable
    private AssistantProvider savedRuntimeProvider() {
        if (getContext() == null) return null;
        SharedPreferences p = requireContext()
            .getSharedPreferences(LoomSettings.PREFS_NAME, Context.MODE_PRIVATE);
        String id = p.getString(LoomSettings.KEY_AGENT_PROVIDER, null);
        return id == null ? null : AssistantProvider.fromId(id);
    }

    private void rememberRuntimeProvider(LoomSettings settings) {
        requireContext().getSharedPreferences(LoomSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LoomSettings.KEY_AGENT_PROVIDER, settings.agentProvider.id)
            .apply();
        refreshProviderText();
    }

    private String transitionScript(LoomSettings targetSettings, String role) {
        AssistantProvider previousProvider = savedRuntimeProvider();
        if (previousProvider == null || previousProvider == targetSettings.agentProvider) return "";

        ProviderProfile previousProfile = ProviderProfile.forProvider(previousProvider);
        LoomSettings previousSettings = targetSettings.withAgentProvider(previousProvider);
        String header = "echo '[*] Loom 上次使用 " + previousProfile.displayName + "，先停止旧角色进程'\n";
        if ("observer".equals(role)) {
            return header + LoomCommandBuilder.stopObserverScript(previousSettings) + "\n";
        }
        if ("slave".equals(role)) {
            return header + LoomCommandBuilder.stopSlaveScript(previousSettings) + "\n";
        }
        if ("all".equals(role)) {
            return header
                + LoomCommandBuilder.stopObserverScript(previousSettings) + "\n"
                + LoomCommandBuilder.stopSlaveScript(previousSettings) + "\n";
        }
        return "";
    }

    private void cancelActiveThread() {
        if (mActiveProcess != null) {
            mActiveProcess.destroy();
            mActiveProcess = null;
        }
        if (mActiveThread != null && mActiveThread.isAlive()) {
            mActiveThread.interrupt();
        }
    }

    private void post(Runnable r) {
        if (getActivity() != null) getActivity().runOnUiThread(r);
    }

    private static String sq(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
