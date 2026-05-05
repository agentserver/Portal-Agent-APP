package com.termux.app.autotasks;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;
import com.termux.app.mcp.AuditLogger;
import com.termux.app.mcp.McpHttpServer;
import com.termux.app.mcp.tools.AndroidStatusTool;
import com.termux.app.mcp.tools.CameraTool;
import com.termux.app.mcp.tools.FileTool;
import com.termux.app.mcp.tools.AppTool;
import com.termux.app.mcp.tools.ScreenCaptureTool;
import com.termux.app.mcp.tools.UiTool;
import com.termux.app.mcp.tools.UiTreeTool;

public class AutoTaskCoordinator {

    private final ApiSelfCheckManager mApiSelfCheckManager;
    private final AutoUbuntuManager mAutoUbuntuManager;
    private final ApiHttpBridgeServer mApiHttpBridgeServer;
    private final McpHttpServer mMcpHttpServer;
    @SuppressWarnings("FieldCanBeLocal")
    private final AutoClaudeManager mAutoClaudeManager;
    @SuppressWarnings("FieldCanBeLocal")
    private final AutoAgentServerManager mAutoAgentServerManager;
    private boolean mEnabled = true;

    public AutoTaskCoordinator(@NonNull TermuxActivity activity) {
        // AutoClaudeManager / AutoAgentServerManager 先初始化：后台写 inner 脚本，
        // Ubuntu 安装需要几分钟，有充足准备时间
        mAutoClaudeManager = new AutoClaudeManager(activity);
        mAutoAgentServerManager = new AutoAgentServerManager(activity);
        mApiSelfCheckManager = new ApiSelfCheckManager(activity);
        mAutoUbuntuManager = new AutoUbuntuManager(activity);
        mAutoUbuntuManager.setAgentServerManager(mAutoAgentServerManager);
        // 旧 HTTP API 桥（只读，保留向后兼容）
        mApiHttpBridgeServer = new ApiHttpBridgeServer(activity);
        mApiHttpBridgeServer.start();
        // MCP Server：将 Android 原生能力封装为 Claude Code 可调用的 MCP 工具
        String termuxHome = activity.getFilesDir().getParent() + "/home";
        AuditLogger audit = new AuditLogger(termuxHome);
        mMcpHttpServer = new McpHttpServer(activity, audit);
        mMcpHttpServer.registerTool(new AndroidStatusTool());
        mMcpHttpServer.registerTool(new CameraTool());
        mMcpHttpServer.registerTool(new FileTool(FileTool.Kind.CHECK_EXISTS));
        mMcpHttpServer.registerTool(new FileTool(FileTool.Kind.LIST));
        mMcpHttpServer.registerTool(new FileTool(FileTool.Kind.READ));
        mMcpHttpServer.registerTool(new ScreenCaptureTool());
        // Phase 4: UI control via AccessibilityService
        mMcpHttpServer.registerTool(new UiTool(UiTool.Kind.TAP));
        mMcpHttpServer.registerTool(new UiTool(UiTool.Kind.SWIPE));
        mMcpHttpServer.registerTool(new UiTool(UiTool.Kind.CLICK_TEXT));
        mMcpHttpServer.registerTool(new UiTool(UiTool.Kind.INPUT_TEXT));
        mMcpHttpServer.registerTool(new UiTreeTool());
        mMcpHttpServer.registerTool(new AppTool(AppTool.Kind.OPEN));
        mMcpHttpServer.registerTool(new AppTool(AppTool.Kind.GET_ACTIVITY));
        mMcpHttpServer.start();
        // 后台生成 capabilities.json，供 Ubuntu 里的 Claude Code 读取设备能力快照
        new CapabilitiesManager(activity).generateAsync();
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        mApiSelfCheckManager.setEnabled(enabled);
        mAutoUbuntuManager.setEnabled(enabled);
    }

    public void setApiSelfCheckEnabled(boolean enabled) {
        mApiSelfCheckManager.setEnabled(enabled);
    }

    public void setAutoUbuntuEnabled(boolean enabled) {
        mAutoUbuntuManager.setEnabled(enabled);
    }

    public void init() {
        if (!mEnabled) return;
        mApiSelfCheckManager.initViews();
    }

    public void onStart() {
        if (!mEnabled) return;
        mApiSelfCheckManager.start();
    }

    public void onResume() {
        if (!mEnabled) return;
        mAutoUbuntuManager.maybeAutoLaunchUbuntu();
    }

    public void onSessionReady() {
        if (!mEnabled) return;
        mApiSelfCheckManager.tryPrintPending();
        mAutoUbuntuManager.maybeAutoLaunchUbuntu();
    }

    public void onDestroy() {
        mApiSelfCheckManager.shutdown();
        mApiHttpBridgeServer.stop();
        mMcpHttpServer.stop();
    }
}
