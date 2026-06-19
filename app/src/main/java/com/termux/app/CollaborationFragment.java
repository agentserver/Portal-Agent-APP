package com.termux.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.collab.CollaborationConnectionState;
import com.termux.app.loom.LoomCommandBuilder;
import com.termux.app.loom.LoomDriverConfigIdentity;
import com.termux.app.loom.LoomLocalSlaveRuntimeStore;
import com.termux.app.loom.LoomSettings;
import com.termux.app.loom.LoomSlave;
import com.termux.app.loom.LoomSlaveListPolicy;
import com.termux.app.loom.LoomSlaveRegistry;
import com.termux.app.loom.LoomSlaveStatus;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentServer 与 Loom 的协作控制台。
 */
public class CollaborationFragment extends Fragment {

    private static final String AGENTSERVER_PREFS_NAME = "agentserver_config";
    private static final String KEY_AGENTSERVER_URL = "server_url";
    private static final String KEY_AGENTSERVER_DEVICE_NAME = "device_name";
    private static final String KEY_AGENTSERVER_SANDBOX_CODE = "sandbox_code";
    private static final String KEY_AGENTSERVER_SANDBOX_ID = "sandbox_id";
    private static final String KEY_AGENTSERVER_WORKSPACE_ID = "workspace_id";
    private static final String KEY_AGENTSERVER_SHORT_ID = "short_id";
    private static final String KEY_DRIVER_BINDING_FINGERPRINT = "driver_binding_fingerprint";
    private static final String KEY_DRIVER_BINDING_STATUS = "driver_binding_status";
    private static final String KEY_DRIVER_BINDING_PROVIDER = "driver_binding_provider";
    private static final String KEY_DRIVER_BINDING_SANDBOX_ID = "driver_binding_sandbox_id";
    private static final String KEY_DRIVER_BINDING_SERVER_URL = "driver_binding_server_url";
    private static final String KEY_DRIVER_BINDING_DEVICE_NAME = "driver_binding_device_name";
    private static final String KEY_DRIVER_BINDING_DRIVER_NAME = "driver_binding_driver_name";

    private TextView mProviderText;
    private TextView mProviderSwitchButton;
    private View mDriverBindingDot;
    private TextView mDriverBindingStatus;
    private TextView mWorkspaceSummary;
    private TextView mLoomSummary;
    private TextView mAndroidCapabilitiesSummary;
    private TextView mSlaveMachineText;
    private TextView mEmptySlavesText;
    private LinearLayout mSlaveList;
    private Thread mDriverBindingThread;
    private Thread mDriverValidationThread;
    private Thread mLoomRuntimeThread;
    private Process mLoomRuntimeProcess;
    private AlertDialog mAuthDialog;
    private boolean mAuthDialogShown;
    private LoomSlaveRegistry.Machine mMachine;

    private static final Pattern AUTH_URL_PATTERN =
        Pattern.compile("https?://[\\w.-]+(?:/[\\w./?=&%+-]*)?");

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_collaboration, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mProviderText = view.findViewById(R.id.collaboration_provider_text);
        mProviderSwitchButton = view.findViewById(R.id.btn_collaboration_switch_provider);
        mDriverBindingDot = view.findViewById(R.id.collaboration_driver_binding_dot);
        mDriverBindingStatus = view.findViewById(R.id.collaboration_driver_binding_status);
        mWorkspaceSummary = view.findViewById(R.id.collaboration_workspace_summary);
        mLoomSummary = view.findViewById(R.id.collaboration_loom_summary);
        mAndroidCapabilitiesSummary = view.findViewById(R.id.collaboration_android_capabilities_summary);
        mSlaveMachineText = view.findViewById(R.id.collaboration_slave_machine);
        mEmptySlavesText = view.findViewById(R.id.collaboration_empty_slaves);
        mSlaveList = view.findViewById(R.id.collaboration_slave_list);

        view.findViewById(R.id.btn_collaboration_switch_provider)
            .setOnClickListener(v -> showProviderDialog());
        view.findViewById(R.id.btn_collaboration_bind_driver)
            .setOnClickListener(v -> bindDriverToCurrentAgent(
                new ProviderSettingsStore(requireContext()).getSelectedProvider()));
        view.findViewById(R.id.btn_collaboration_create_slave)
            .setOnClickListener(v -> createManagedSlave());
        view.findViewById(R.id.btn_collaboration_refresh_slaves)
            .setOnClickListener(v -> refreshDashboard());
        view.findViewById(R.id.btn_collaboration_workspace_access)
            .setOnClickListener(v -> {
                TermuxActivity a = act();
                if (a != null) a.showWorkspaceAccessSettingsMode();
            });

        View.OnClickListener agentServerClick = v -> {
            TermuxActivity a = act();
            if (a != null) a.showAgentServerMode();
        };
        view.findViewById(R.id.btn_collaboration_agentserver_optional).setOnClickListener(agentServerClick);

        View.OnClickListener loomClick = v -> {
            TermuxActivity a = act();
            if (a != null) a.showLoomMode();
        };
        view.findViewById(R.id.collaboration_loom_summary).setOnClickListener(loomClick);

        refreshDashboard();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDashboard();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mDriverBindingThread != null && mDriverBindingThread.isAlive()) {
            mDriverBindingThread.interrupt();
        }
        if (mDriverValidationThread != null && mDriverValidationThread.isAlive()) {
            mDriverValidationThread.interrupt();
        }
        if (mLoomRuntimeProcess != null) {
            mLoomRuntimeProcess.destroy();
            mLoomRuntimeProcess = null;
        }
        if (mLoomRuntimeThread != null && mLoomRuntimeThread.isAlive()) {
            mLoomRuntimeThread.interrupt();
        }
        dismissAuthDialog();
    }

    private void refreshDashboard() {
        if (getContext() == null) return;

        ProviderProfile profile = ProviderProfile.forProvider(
            new ProviderSettingsStore(requireContext()).getSelectedProvider());
        if (mProviderText != null) {
            mProviderText.setText("新建 Slave 默认使用：" + profile.displayName);
        }
        if (mProviderSwitchButton != null) {
            mProviderSwitchButton.setText("切换 Agent");
        }
        if (mDriverBindingStatus != null
                && (mDriverBindingThread == null || !mDriverBindingThread.isAlive())) {
            String status = currentDriverBindingStatus(profile.provider);
            mDriverBindingStatus.setText(driverBindingStatusText(status));
            updateDriverBindingDot(status);
            validateDriverBindingIfNeeded(profile.provider, status);
        }

        SharedPreferences agentPrefs = requireContext()
            .getSharedPreferences(AGENTSERVER_PREFS_NAME, Context.MODE_PRIVATE);
        String configuredServerUrl = trim(agentPrefs.getString(KEY_AGENTSERVER_URL, ""));
        String serverUrl = firstNonEmpty(configuredServerUrl, LoomSettings.defaults().agentServerUrl);
        String deviceName = currentDeviceName();
        String workspaceId = currentWorkspaceId();

        if (mWorkspaceSummary != null) {
            String serverLine = serverUrl.isEmpty() ? "服务器：未配置" : "服务器：" + serverUrl;
            String workspaceLine = workspaceId.isEmpty()
                ? "工作区：绑定后自动同步"
                : "工作区：" + shortId(workspaceId);
            mWorkspaceSummary.setText(serverLine + "\n" + workspaceLine + "\n" + "本机：" + deviceName);
        }

        LoomSlaveRegistry registry = LoomSlaveRegistry.forContext(requireContext());
        mMachine = registry.ensureMachine(deviceName);
        registry.markStaleStarting(System.currentTimeMillis(), 60_000);
        if (mSlaveMachineText != null) {
            mSlaveMachineText.setText("本机：" + mMachine.computerName);
        }
        List<LoomSlave> allSlaves = registry.list();
        LoomLocalSlaveRuntimeStore.sync(requireContext(), mMachine.computerName, allSlaves);
        List<LoomSlave> slaves = LoomSlaveListPolicy.visibleSlaves(allSlaves);
        dismissAuthDialogIfNoAuthRequired(slaves);
        renderSlaveList(slaves);

        SharedPreferences loomPrefs = requireContext()
            .getSharedPreferences(LoomSettings.PREFS_NAME, Context.MODE_PRIVATE);
        LoomSettings defaults = LoomSettings.defaults();
        String observerUrl = trim(loomPrefs.getString(LoomSettings.KEY_OBSERVER_URL, defaults.observerUrl));
        if (mLoomSummary != null) {
            mLoomSummary.setText("Loom 编排设置\nObserver：" + observerUrl);
        }
        if (mAndroidCapabilitiesSummary != null) {
            mAndroidCapabilitiesSummary.setText(
                "管理应用/文件目录权限");
        }
    }

    private void renderSlaveList(List<LoomSlave> slaves) {
        if (mSlaveList == null) return;
        mSlaveList.removeAllViews();
        if (mEmptySlavesText != null) {
            mEmptySlavesText.setVisibility(slaves == null || slaves.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (slaves == null) return;
        for (LoomSlave slave : slaves) {
            mSlaveList.addView(createSlaveRow(slave));
        }
    }

    private void dismissAuthDialogIfNoAuthRequired(List<LoomSlave> slaves) {
        if (mAuthDialog == null || !mAuthDialog.isShowing()) return;
        if (slaves != null) {
            for (LoomSlave slave : slaves) {
                if (LoomSlaveStatus.AUTH_REQUIRED.equals(slave.status)) {
                    return;
                }
            }
        }
        dismissAuthDialog();
    }

    private View createSlaveRow(LoomSlave slave) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rowParams);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(header);

        TextView title = new TextView(context);
        title.setText("Slave：" + slave.displayName);
        title.setTextColor(getResources().getColor(R.color.app_text_primary));
        title.setTextSize(14);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1);
        title.setLayoutParams(titleParams);
        header.addView(title);

        LinearLayout status = new LinearLayout(context);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(android.view.Gravity.CENTER_VERTICAL);
        status.setPadding(dp(8), dp(3), dp(8), dp(3));
        status.setBackgroundResource(R.drawable.bg_status_chip);
        header.addView(status);

        View statusDot = new View(context);
        statusDot.setBackgroundResource(slaveStatusDotRes(slave.status));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotParams.setMargins(0, 0, dp(6), 0);
        status.addView(statusDot, dotParams);

        TextView statusText = new TextView(context);
        statusText.setText(slaveStatusLabel(slave.status));
        statusText.setTextColor(getResources().getColor(R.color.app_text_secondary));
        statusText.setTextSize(12);
        status.addView(statusText);

        TextView detail = new TextView(context);
        detail.setText(slave.folder + "\n" + ProviderProfile.forProvider(
            AssistantProvider.fromId(slave.providerId)).displayName);
        detail.setTextColor(getResources().getColor(R.color.app_text_secondary));
        detail.setTextSize(12);
        detail.setPadding(0, dp(4), 0, 0);
        row.addView(detail);

        if (!slave.authUrl.isEmpty()) {
            TextView auth = new TextView(context);
            auth.setText("待认证：" + slave.authUrl);
            auth.setTextColor(getResources().getColor(R.color.app_accent));
            auth.setTextSize(12);
            auth.setPadding(0, dp(6), 0, 0);
            auth.setOnClickListener(v -> showAuthDialog(slave.authUrl));
            row.addView(auth);
        }
        if (!slave.lastError.isEmpty()) {
            TextView error = new TextView(context);
            error.setText(slave.lastError);
            error.setTextColor(getResources().getColor(R.color.app_warning));
            error.setTextSize(12);
            error.setPadding(0, dp(6), 0, 0);
            row.addView(error);
        }

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);
        row.addView(actions);

        actions.addView(rowButton("启动/重启", R.color.app_accent, () -> startManagedSlave(slave)));
        actions.addView(rowButton("暂停", R.color.app_primary, () -> pauseManagedSlave(slave)));
        actions.addView(rowButton("删除", R.color.app_warning, () -> deleteManagedSlave(slave)));
        return row;
    }

    private MaterialButton rowButton(String text, int colorRes, Runnable action) {
        MaterialButton button = new MaterialButton(requireContext(), null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setLetterSpacing(0);
        button.setTextSize(12);
        button.setTextColor(getResources().getColor(colorRes));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(
            getResources().getColor(colorRes)));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(8));
        button.setMinHeight(dp(36));
        button.setMinWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0,
            dp(40),
            1);
        params.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void createManagedSlave() {
        if (getContext() == null) return;
        if (isLoomRuntimeBusy()) {
            Toast.makeText(getContext(), "本机 Slave 操作正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        AssistantProvider provider = new ProviderSettingsStore(requireContext()).getSelectedProvider();
        ProviderProfile profile = ProviderProfile.forProvider(provider);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), 0);

        EditText folderInput = new EditText(requireContext());
        folderInput.setHint("工作目录，例如 " + profile.home);
        folderInput.setSingleLine(true);
        folderInput.setInputType(InputType.TYPE_CLASS_TEXT);
        folderInput.setText(profile.home);
        content.addView(folderInput);

        EditText nameInput = new EditText(requireContext());
        nameInput.setHint("名称，默认使用目录名");
        nameInput.setSingleLine(true);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        content.addView(nameInput);

        new AlertDialog.Builder(requireContext())
            .setTitle("创建本机 Slave")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建并启动", (dialog, which) -> {
                try {
                    LoomSlaveRegistry registry = LoomSlaveRegistry.forContext(requireContext());
                    LoomSlaveRegistry.Machine machine = registry.ensureMachine(currentDeviceName());
                    LoomSlave slave = registry.create(
                        machine,
                        folderInput.getText().toString(),
                        nameInput.getText().toString(),
                        provider);
                    refreshDashboard();
                    startManagedSlave(slave);
                } catch (IllegalArgumentException e) {
                    Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    private void startManagedSlave(LoomSlave slave) {
        if (getContext() == null || slave == null) return;
        if (isLoomRuntimeBusy()) {
            Toast.makeText(getContext(), "本机 Slave 操作正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        mAuthDialogShown = false;
        AssistantProvider provider = AssistantProvider.fromId(slave.providerId);
        LoomSettings settings = currentLoomSettings().withAgentProvider(provider);
        LoomSlaveRegistry.Machine machine = currentMachine();
        String script = LoomCommandBuilder.startManagedSlaveRuntimeScript(
            prefix(),
            settings,
            slave,
            machine.machineId,
            machine.computerName);
        runManagedSlaveScript(slave, script, "start");
    }

    private void pauseManagedSlave(LoomSlave slave) {
        if (getContext() == null || slave == null) return;
        if (isLoomRuntimeBusy()) {
            Toast.makeText(getContext(), "本机 Slave 操作正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        LoomSettings settings = currentLoomSettings().withAgentProvider(
            AssistantProvider.fromId(slave.providerId));
        runManagedSlaveScript(slave, LoomCommandBuilder.stopManagedSlaveScript(settings, slave), "pause");
    }

    private void deleteManagedSlave(LoomSlave slave) {
        if (getContext() == null || slave == null) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("删除本机 Slave")
            .setMessage("删除这台设备上的本地配置和进程。远端 AgentServer 记录可能需要在网页中手动清理。确定删除吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", (dialog, which) -> {
                LoomSettings settings = currentLoomSettings().withAgentProvider(
                    AssistantProvider.fromId(slave.providerId));
                runManagedSlaveScript(slave, LoomCommandBuilder.deleteManagedSlaveScript(settings, slave), "delete");
            })
            .show();
    }

    private void runManagedSlaveScript(LoomSlave slave, String script, String action) {
        LoomSlaveRegistry registry = LoomSlaveRegistry.forContext(requireContext());
        registry.updateRuntime(slave.id, LoomSlaveStatus.STARTING, slave.pid, "", "");
        refreshDashboard();
        mLoomRuntimeThread = new Thread(() -> {
            Process process = null;
            int pid = slave.pid;
            String prefix = prefix();
            String bash = prefix + "/bin/bash";
            String sysPath = System.getenv("PATH");
            if (sysPath == null) sysPath = "";
            String termuxPath = prefix + "/bin:" + prefix + "/bin/applets:" + sysPath;
            try {
                ProcessBuilder pb = new ProcessBuilder(bash, "-c", script);
                pb.redirectErrorStream(true);
                java.util.Map<String, String> env = pb.environment();
                env.putAll(System.getenv());
                env.put("PATH", termuxPath);
                env.put("PREFIX", prefix);
                env.put("HOME", prefix + "/../home");
                process = pb.start();
                mLoomRuntimeProcess = process;

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        process.destroy();
                        return;
                    }
                    if (line.startsWith("__LOOM_SLAVE_PID__=")) {
                        pid = parsePid(line.substring("__LOOM_SLAVE_PID__=".length()));
                        int currentPid = pid;
                        post(() -> {
                            LoomSlaveRegistry.forContext(requireContext()).updateRuntime(
                                slave.id, LoomSlaveStatus.STARTING, currentPid, "", "");
                            refreshDashboard();
                        });
                    } else if (line.startsWith("__LOOM_SLAVE_AUTH_URL__=")) {
                        String authUrl = line.substring("__LOOM_SLAVE_AUTH_URL__=".length()).trim();
                        int currentPid = pid;
                        post(() -> {
                            LoomSlaveRegistry.forContext(requireContext()).updateRuntime(
                                slave.id, LoomSlaveStatus.AUTH_REQUIRED, currentPid, authUrl, "");
                            showAuthDialog(authUrl);
                            refreshDashboard();
                        });
                    } else if (line.startsWith("__LOOM_SLAVE_READY__=1")) {
                        int currentPid = pid;
                        post(() -> {
                            LoomSlaveRegistry.forContext(requireContext()).updateRuntime(
                                slave.id, LoomSlaveStatus.RUNNING, currentPid, "", "");
                            dismissAuthDialog();
                            refreshDashboard();
                        });
                    } else if (line.startsWith("__LOOM_SLAVE_ERROR__=")) {
                        String error = line.substring("__LOOM_SLAVE_ERROR__=".length()).trim();
                        post(() -> {
                            LoomSlaveRegistry.forContext(requireContext()).updateRuntime(
                                slave.id, LoomSlaveStatus.ERROR, 0, "", firstNonEmpty(error, "Slave 启动失败"));
                            dismissAuthDialog();
                            refreshDashboard();
                        });
                    } else {
                        maybeShowAuthUrl(line);
                    }
                }
                int exit = process.waitFor();
                int finalPid = pid;
                post(() -> handleManagedSlaveExit(slave, action, exit, finalPid));
            } catch (InterruptedException ignored) {
                if (process != null) process.destroy();
            } catch (Exception e) {
                post(() -> {
                    LoomSlaveRegistry.forContext(requireContext()).updateRuntime(
                        slave.id, LoomSlaveStatus.ERROR, 0, "", e.getMessage());
                    refreshDashboard();
                });
            } finally {
                if (mLoomRuntimeProcess == process) {
                    mLoomRuntimeProcess = null;
                }
            }
        }, "loom-managed-slave-" + slave.id);
        mLoomRuntimeThread.setDaemon(true);
        mLoomRuntimeThread.start();
    }

    private void handleManagedSlaveExit(LoomSlave slave, String action, int exit, int pid) {
        LoomSlaveRegistry registry = LoomSlaveRegistry.forContext(requireContext());
        if ("pause".equals(action) && exit == 0) {
            registry.updateRuntime(slave.id, LoomSlaveStatus.PAUSED, 0, "", "");
        } else if ("delete".equals(action) && exit == 0) {
            registry.delete(slave.id);
        } else if (exit != 0) {
            LoomSlave latest = registry.get(slave.id);
            if (!LoomSlaveStatus.ERROR.equals(latest.status) || latest.lastError.isEmpty()) {
                registry.updateRuntime(slave.id, LoomSlaveStatus.ERROR, 0, "", "操作失败，退出码 " + exit);
            }
        } else if ("start".equals(action)) {
            LoomSlave latest = registry.get(slave.id);
            if (LoomSlaveStatus.STARTING.equals(latest.status)) {
                registry.updateRuntime(slave.id, LoomSlaveStatus.STARTING, pid, "", "");
            }
        }
        refreshDashboard();
    }

    private void showProviderDialog() {
        if (getContext() == null) return;
        AssistantProvider current = new ProviderSettingsStore(requireContext()).getSelectedProvider();
        String[] labels = {"Codex", "Claude Code"};
        int checked = current == AssistantProvider.CLAUDE ? 1 : 0;
        new AlertDialog.Builder(requireContext())
            .setTitle("切换当前助手")
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                AssistantProvider next = which == 1
                    ? AssistantProvider.CLAUDE
                    : AssistantProvider.CODEX;
                dialog.dismiss();
                switchProviderAndMarkDriverStale(next);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void switchProviderAndMarkDriverStale(AssistantProvider provider) {
        if (getContext() == null) return;
        AssistantProvider safe = provider == null ? AssistantProvider.CODEX : provider;
        AssistantProvider current = new ProviderSettingsStore(requireContext()).getSelectedProvider();
        new ProviderSettingsStore(requireContext()).setSelectedProvider(safe);
        if (current != safe) {
            markDriverBindingStale();
            Toast.makeText(getContext(), "已切换 Agent，请按需重新绑定 Driver", Toast.LENGTH_SHORT).show();
        }
        refreshDashboard();
    }

    private void bindDriverToCurrentAgent(AssistantProvider provider) {
        if (getContext() == null) return;
        if (mDriverBindingThread != null && mDriverBindingThread.isAlive()) {
            Toast.makeText(getContext(), "Driver 正在绑定中", Toast.LENGTH_SHORT).show();
            return;
        }

        AssistantProvider safe = provider == null ? AssistantProvider.CODEX : provider;
        ProviderProfile profile = ProviderProfile.forProvider(safe);
        LoomSettings settings = currentLoomSettings().withAgentProvider(safe);
        String serverUrl = settings.agentServerUrl;
        String workspaceId = currentWorkspaceId();
        if (!CollaborationConnectionState.canBindDriver(serverUrl, workspaceId)) {
            setDriverBindingStatus("Driver：请先配置协作服务器");
            Toast.makeText(getContext(), "请先配置协作服务器地址。", Toast.LENGTH_SHORT).show();
            return;
        }

        String script = LoomCommandBuilder.setupConfigScript(settings)
            + "\n" + LoomCommandBuilder.bindDriverIfNeededScript(settings)
            + "\n" + LoomCommandBuilder.readDriverConfigScript(settings);
        mAuthDialogShown = false;
        setDriverBindingStatus("Driver：绑定中");
        updateDriverBindingDot(CollaborationConnectionState.DRIVER_STATUS_BINDING);
        setDriverBindingStatusPref(CollaborationConnectionState.DRIVER_STATUS_BINDING, "");

        mDriverBindingThread = new Thread(() -> runDriverBindingScript(script, profile),
            "loom-driver-bind-" + safe.id);
        mDriverBindingThread.setDaemon(true);
        mDriverBindingThread.start();
    }

    private void runDriverBindingScript(String script, ProviderProfile profile) {
        String prefix = prefix();
        String bash = prefix + "/bin/bash";
        String sysPath = System.getenv("PATH");
        if (sysPath == null) sysPath = "";
        String termuxPath = prefix + "/bin:" + prefix + "/bin/applets:" + sysPath;
        try {
            ProcessBuilder pb = new ProcessBuilder(bash, "-c", script);
            pb.redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            env.putAll(System.getenv());
            env.put("PATH", termuxPath);
            env.put("PREFIX", prefix);
            env.put("HOME", prefix + "/../home");
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            boolean readingDriverConfig = false;
            boolean credentialsValid = false;
            boolean credentialsProbeSeen = false;
            StringBuilder driverConfig = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    process.destroy();
                    return;
                }
                if (LoomCommandBuilder.DRIVER_CONFIG_BEGIN_MARKER.equals(line.trim())) {
                    readingDriverConfig = true;
                    continue;
                }
                if (LoomCommandBuilder.DRIVER_CONFIG_END_MARKER.equals(line.trim())) {
                    readingDriverConfig = false;
                    continue;
                }
                if (readingDriverConfig) {
                    driverConfig.append(line).append('\n');
                    continue;
                }
                if (LoomCommandBuilder.DRIVER_CREDENTIALS_VALID_MARKER.equals(line.trim())) {
                    credentialsValid = true;
                    credentialsProbeSeen = true;
                    continue;
                }
                if (LoomCommandBuilder.DRIVER_CREDENTIALS_INVALID_MARKER.equals(line.trim())) {
                    credentialsValid = false;
                    credentialsProbeSeen = true;
                    continue;
                }
                maybeShowAuthUrl(line);
            }
            int exit = process.waitFor();
            LoomDriverConfigIdentity identity = exit == 0
                ? LoomDriverConfigIdentity.parse(driverConfig.toString())
                : LoomDriverConfigIdentity.empty();
            boolean driverReady = identity.hasRemoteIdentity()
                && (!credentialsProbeSeen || credentialsValid);
            post(() -> {
                dismissAuthDialog();
                if (exit == 0 && driverReady) {
                    saveDriverBindingSuccess(profile.provider, identity);
                    updateDriverBindingDot(CollaborationConnectionState.DRIVER_STATUS_VALID);
                    setDriverBindingStatus("Driver：已绑定到 " + profile.displayName);
                    refreshDashboard();
                } else if (exit == 0 && identity.hasRemoteIdentity()) {
                    setDriverBindingStatusPref(CollaborationConnectionState.DRIVER_STATUS_STALE, "");
                    updateDriverBindingDot(CollaborationConnectionState.DRIVER_STATUS_STALE);
                    setDriverBindingStatus("Driver：需重新绑定");
                } else if (exit == 0) {
                    setDriverBindingStatusPref(CollaborationConnectionState.DRIVER_STATUS_FAILED, "");
                    updateDriverBindingDot(CollaborationConnectionState.DRIVER_STATUS_FAILED);
                    setDriverBindingStatus("Driver：绑定未写入身份");
                } else {
                    setDriverBindingStatusPref(CollaborationConnectionState.DRIVER_STATUS_FAILED, "");
                    updateDriverBindingDot(CollaborationConnectionState.DRIVER_STATUS_FAILED);
                    setDriverBindingStatus("Driver：绑定失败");
                }
            });
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            post(() -> {
                dismissAuthDialog();
                setDriverBindingStatusPref(CollaborationConnectionState.DRIVER_STATUS_FAILED, "");
                updateDriverBindingDot(CollaborationConnectionState.DRIVER_STATUS_FAILED);
                setDriverBindingStatus("Driver：绑定失败");
            });
        }
    }

    private void validateDriverBindingIfNeeded(AssistantProvider provider, String status) {
        if (!CollaborationConnectionState.DRIVER_STATUS_VALID.equals(status)) return;
        if (mDriverBindingThread != null && mDriverBindingThread.isAlive()) return;
        if (mDriverValidationThread != null && mDriverValidationThread.isAlive()) return;

        AssistantProvider safe = provider == null ? AssistantProvider.CODEX : provider;
        String script = LoomCommandBuilder.readDriverConfigScript(
            currentLoomSettings().withAgentProvider(safe));
        mDriverValidationThread = new Thread(() -> runDriverValidationScript(script),
            "loom-driver-validate-" + safe.id);
        mDriverValidationThread.setDaemon(true);
        mDriverValidationThread.start();
    }

    private void runDriverValidationScript(String script) {
        String prefix = prefix();
        String bash = prefix + "/bin/bash";
        String sysPath = System.getenv("PATH");
        if (sysPath == null) sysPath = "";
        String termuxPath = prefix + "/bin:" + prefix + "/bin/applets:" + sysPath;
        try {
            ProcessBuilder pb = new ProcessBuilder(bash, "-c", script);
            pb.redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            env.putAll(System.getenv());
            env.put("PATH", termuxPath);
            env.put("PREFIX", prefix);
            env.put("HOME", prefix + "/../home");
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            boolean credentialsValid = false;
            boolean credentialsProbeSeen = false;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    process.destroy();
                    return;
                }
                if (LoomCommandBuilder.DRIVER_CREDENTIALS_VALID_MARKER.equals(line.trim())) {
                    credentialsValid = true;
                    credentialsProbeSeen = true;
                } else if (LoomCommandBuilder.DRIVER_CREDENTIALS_INVALID_MARKER.equals(line.trim())) {
                    credentialsValid = false;
                    credentialsProbeSeen = true;
                }
            }
            int exit = process.waitFor();
            boolean valid = exit == 0 && credentialsProbeSeen && credentialsValid;
            post(() -> {
                if (getContext() == null) return;
                String current = currentDriverBindingStatus(
                    new ProviderSettingsStore(requireContext()).getSelectedProvider());
                String next = CollaborationConnectionState.driverStatusAfterCredentialProbe(
                    current,
                    valid);
                if (!current.equals(next)) {
                    setDriverBindingStatusPref(next, "");
                    updateDriverBindingDot(next);
                    setDriverBindingStatus(driverBindingStatusText(next));
                }
            });
        } catch (InterruptedException ignored) {
        } catch (Exception ignored) {
        }
    }

    private LoomSettings currentLoomSettings() {
        LoomSettings d = LoomSettings.defaults();
        SharedPreferences p = requireContext()
            .getSharedPreferences(LoomSettings.PREFS_NAME, Context.MODE_PRIVATE);
        LoomSettings settings = d.withRoleMode(prefOrDefault(p, LoomSettings.KEY_ROLE_MODE, d.roleMode))
            .withObserverUrl(prefOrDefault(p, LoomSettings.KEY_OBSERVER_URL, d.observerUrl))
            .withWorkspaceId(prefOrDefault(p, LoomSettings.KEY_WORKSPACE_ID, d.workspaceId))
            .withWorkspaceApiKey(prefOrDefault(p, LoomSettings.KEY_WORKSPACE_API_KEY, d.workspaceApiKey))
            .withAgentServerUrl(prefOrDefault(p, LoomSettings.KEY_AGENTSERVER_URL, d.agentServerUrl))
            .withObserverName(prefOrDefault(p, LoomSettings.KEY_OBSERVER_NAME, d.observerName))
            .withDriverName(prefOrDefault(p, LoomSettings.KEY_DRIVER_NAME, d.driverName))
            .withSlaveName(prefOrDefault(p, LoomSettings.KEY_SLAVE_NAME, d.slaveName))
            .withTags(prefOrDefault(p, LoomSettings.KEY_TAGS, d.tags))
            .withAgentProvider(new ProviderSettingsStore(requireContext()).getSelectedProvider());
        return agentServerBackedSettings(settings);
    }

    private LoomSettings agentServerBackedSettings(LoomSettings settings) {
        SharedPreferences p = requireContext()
            .getSharedPreferences(AGENTSERVER_PREFS_NAME, Context.MODE_PRIVATE);
        String serverUrl = prefOrDefault(p, KEY_AGENTSERVER_URL, settings.agentServerUrl);
        String workspaceId = firstNonEmpty(
            p.getString(KEY_AGENTSERVER_WORKSPACE_ID, ""),
            p.getString(KEY_AGENTSERVER_SANDBOX_CODE, ""),
            p.getString(KEY_AGENTSERVER_SANDBOX_ID, ""),
            settings.workspaceId);
        return settings.withAgentServerUrl(serverUrl).withWorkspaceId(workspaceId);
    }

    private void maybeShowAuthUrl(String line) {
        String lower = line == null ? "" : line.toLowerCase();
        if (mAuthDialogShown || !lower.contains("http")) return;
        if (!lower.contains("device") && !lower.contains("authenticate")
                && !lower.contains("register") && !lower.contains("auth")) {
            return;
        }
        Matcher matcher = AUTH_URL_PATTERN.matcher(line);
        if (!matcher.find()) return;
        String authUrl = matcher.group();
        mAuthDialogShown = true;
        post(() -> showAuthDialog(authUrl));
    }

    private void showAuthDialog(String authUrl) {
        if (getContext() == null || authUrl == null || authUrl.trim().isEmpty()) return;
        if (mAuthDialog != null && mAuthDialog.isShowing()) mAuthDialog.dismiss();

        View view = LayoutInflater.from(getContext())
            .inflate(R.layout.dialog_auth_qr, null, false);
        ImageView qrIv = view.findViewById(R.id.auth_qr_image);
        TextView urlTv = view.findViewById(R.id.auth_url_text);
        urlTv.setText(authUrl);
        Bitmap bmp = QrCodeUtil.generate(authUrl, 600);
        if (bmp != null) qrIv.setImageBitmap(bmp);
        else qrIv.setVisibility(View.GONE);

        view.findViewById(R.id.auth_btn_copy).setOnClickListener(b -> {
            ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                ClipData.newPlainText("loom auth url", authUrl));
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

    private void dismissAuthDialog() {
        if (mAuthDialog != null && mAuthDialog.isShowing()) {
            mAuthDialog.dismiss();
        }
        mAuthDialog = null;
        mAuthDialogShown = false;
    }

    private void setDriverBindingStatus(String text) {
        if (mDriverBindingStatus != null) mDriverBindingStatus.setText(text);
    }

    private String driverBindingStatusText(String status) {
        if (CollaborationConnectionState.DRIVER_STATUS_VALID.equals(status)) {
            return "Driver：已绑定";
        }
        if (CollaborationConnectionState.DRIVER_STATUS_STALE.equals(status)) {
            return "Driver：需重新绑定";
        }
        if (CollaborationConnectionState.DRIVER_STATUS_BINDING.equals(status)) {
            return "Driver：绑定中";
        }
        if (CollaborationConnectionState.DRIVER_STATUS_FAILED.equals(status)) {
            return "Driver：绑定失败";
        }
        return "Driver：需绑定";
    }

    private void updateDriverBindingDot(String status) {
        if (mDriverBindingDot == null) return;
        int resId = CollaborationConnectionState.DRIVER_STATUS_VALID.equals(status)
            ? R.drawable.bg_status_dot_connected
            : R.drawable.bg_status_dot_idle;
        mDriverBindingDot.setBackgroundResource(resId);
    }

    private String currentDriverBindingStatus(AssistantProvider provider) {
        if (getContext() == null) return CollaborationConnectionState.DRIVER_STATUS_MISSING;
        SharedPreferences p = requireContext()
            .getSharedPreferences(LoomSettings.PREFS_NAME, Context.MODE_PRIVATE);
        return CollaborationConnectionState.driverBindingStatus(
            p.getString(KEY_DRIVER_BINDING_FINGERPRINT, ""),
            currentDriverFingerprint(provider),
            p.getString(KEY_DRIVER_BINDING_STATUS, ""));
    }

    private String currentDriverFingerprint(AssistantProvider provider) {
        AssistantProvider safe = provider == null ? AssistantProvider.CODEX : provider;
        ProviderProfile profile = ProviderProfile.forProvider(safe);
        LoomSettings settings = currentLoomSettings().withAgentProvider(safe);
        SharedPreferences agentPrefs = requireContext()
            .getSharedPreferences(AGENTSERVER_PREFS_NAME, Context.MODE_PRIVATE);
        String serverUrl = trim(agentPrefs.getString(KEY_AGENTSERVER_URL, ""));
        String deviceName = trim(agentPrefs.getString(KEY_AGENTSERVER_DEVICE_NAME, ""));
        return CollaborationConnectionState.computeDriverFingerprint(
            safe,
            serverUrl,
            currentWorkspaceId(),
            deviceName,
            settings.driverName,
            profile.driverConfigPath,
            profile.loomMcpConfigPath);
    }

    private String currentWorkspaceId() {
        if (getContext() == null) return "";
        SharedPreferences p = requireContext()
            .getSharedPreferences(AGENTSERVER_PREFS_NAME, Context.MODE_PRIVATE);
        return firstNonEmpty(
            p.getString(KEY_AGENTSERVER_WORKSPACE_ID, ""),
            p.getString(KEY_AGENTSERVER_SANDBOX_CODE, ""),
            p.getString(KEY_AGENTSERVER_SANDBOX_ID, ""));
    }

    private void markDriverBindingStale() {
        if (getContext() == null) return;
        requireContext().getSharedPreferences(LoomSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRIVER_BINDING_STATUS, CollaborationConnectionState.DRIVER_STATUS_STALE)
            .apply();
    }

    private void setDriverBindingStatusPref(String status, String fingerprint) {
        if (getContext() == null) return;
        SharedPreferences.Editor editor = requireContext()
            .getSharedPreferences(LoomSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRIVER_BINDING_STATUS, status);
        if (fingerprint != null && !fingerprint.trim().isEmpty()) {
            editor.putString(KEY_DRIVER_BINDING_FINGERPRINT, fingerprint);
        }
        editor.apply();
    }

    private void saveDriverBindingSuccess(AssistantProvider provider, LoomDriverConfigIdentity identity) {
        if (getContext() == null) return;
        AssistantProvider safe = provider == null ? AssistantProvider.CODEX : provider;
        SharedPreferences agentPrefs = requireContext()
            .getSharedPreferences(AGENTSERVER_PREFS_NAME, Context.MODE_PRIVATE);
        LoomDriverConfigIdentity safeIdentity = identity == null ? LoomDriverConfigIdentity.empty() : identity;
        String serverUrl = firstNonEmpty(safeIdentity.serverUrl, agentPrefs.getString(KEY_AGENTSERVER_URL, ""));
        String deviceName = trim(agentPrefs.getString(KEY_AGENTSERVER_DEVICE_NAME, ""));
        String workspaceId = firstNonEmpty(safeIdentity.workspaceId, currentWorkspaceId());
        LoomSettings settings = currentLoomSettings().withAgentProvider(safe);
        SharedPreferences.Editor agentEditor = agentPrefs.edit();
        if (!safeIdentity.serverUrl.isEmpty()) {
            agentEditor.putString(KEY_AGENTSERVER_URL, safeIdentity.serverUrl);
        }
        if (!safeIdentity.sandboxId.isEmpty()) {
            agentEditor.putString(KEY_AGENTSERVER_SANDBOX_ID, safeIdentity.sandboxId);
        }
        if (!safeIdentity.workspaceId.isEmpty()) {
            agentEditor.putString(KEY_AGENTSERVER_WORKSPACE_ID, safeIdentity.workspaceId);
        }
        if (!safeIdentity.shortId.isEmpty()) {
            agentEditor.putString(KEY_AGENTSERVER_SHORT_ID, safeIdentity.shortId);
        }
        agentEditor.apply();
        requireContext().getSharedPreferences(LoomSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRIVER_BINDING_FINGERPRINT, currentDriverFingerprint(safe))
            .putString(KEY_DRIVER_BINDING_STATUS, CollaborationConnectionState.DRIVER_STATUS_VALID)
            .putString(KEY_DRIVER_BINDING_PROVIDER, safe.id)
            .putString(KEY_DRIVER_BINDING_SANDBOX_ID, workspaceId)
            .putString(KEY_DRIVER_BINDING_SERVER_URL, serverUrl)
            .putString(KEY_DRIVER_BINDING_DEVICE_NAME, deviceName)
            .putString(KEY_DRIVER_BINDING_DRIVER_NAME, settings.driverName)
            .apply();
    }

    private LoomSlaveRegistry.Machine currentMachine() {
        if (mMachine != null) return mMachine;
        mMachine = LoomSlaveRegistry.forContext(requireContext()).ensureMachine(currentDeviceName());
        return mMachine;
    }

    private String currentDeviceName() {
        if (getContext() == null) return "Android";
        SharedPreferences p = requireContext()
            .getSharedPreferences(AGENTSERVER_PREFS_NAME, Context.MODE_PRIVATE);
        String configured = trim(p.getString(KEY_AGENTSERVER_DEVICE_NAME, ""));
        if (!configured.isEmpty()) return configured;
        String model = trim(Build.MODEL);
        return model.isEmpty() ? "Android" : model;
    }

    private boolean isLoomRuntimeBusy() {
        return mLoomRuntimeThread != null && mLoomRuntimeThread.isAlive();
    }

    private String prefix() {
        String p = System.getenv("PREFIX");
        return (p == null || p.isEmpty()) ? TermuxConstants.TERMUX_PREFIX_DIR_PATH : p;
    }

    private void post(Runnable runnable) {
        if (getActivity() != null) getActivity().runOnUiThread(runnable);
    }

    private String slaveStatusLabel(String status) {
        if (LoomSlaveStatus.STARTING.equals(status)) return "启动中";
        if (LoomSlaveStatus.AUTH_REQUIRED.equals(status)) return "待认证";
        if (LoomSlaveStatus.RUNNING.equals(status)) return "运行中";
        if (LoomSlaveStatus.PAUSED.equals(status)) return "已暂停";
        if (LoomSlaveStatus.ERROR.equals(status)) return "出错";
        return "已停止";
    }

    private int slaveStatusDotRes(String status) {
        if (LoomSlaveStatus.RUNNING.equals(status)) return R.drawable.bg_status_dot_connected;
        if (LoomSlaveStatus.STARTING.equals(status)
                || LoomSlaveStatus.AUTH_REQUIRED.equals(status)) {
            return R.drawable.bg_status_dot_warning;
        }
        if (LoomSlaveStatus.ERROR.equals(status)) return R.drawable.bg_status_dot_danger;
        return R.drawable.bg_status_dot_idle;
    }

    private int parsePid(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8) + "...";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String prefOrDefault(SharedPreferences prefs, String key, String fallback) {
        return firstNonEmpty(prefs.getString(key, ""), fallback);
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String v = trim(value);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    @Nullable
    private TermuxActivity act() {
        return getActivity() instanceof TermuxActivity ? (TermuxActivity) getActivity() : null;
    }
}
