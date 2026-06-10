package com.termux.app.automation;

import android.content.Context;

import com.termux.app.mcp.McpAccessibilityService;
import com.termux.app.mcp.tools.AppTool;
import com.termux.app.mcp.tools.UiTool;

public final class AndroidMcpActionRunner implements AndroidActionRunner {

    private final Context mContext;

    public AndroidMcpActionRunner(Context context) {
        mContext = context == null ? null : context.getApplicationContext();
    }

    @Override
    public void runStep(ActionStep step) throws Exception {
        if (step == null) throw new Exception("Unsupported Boost tool: ");
        switch (step.toolName) {
            case "app.open":
                new AppTool(AppTool.Kind.OPEN).call(step.arguments, mContext);
                return;
            case "ui.click_text":
                new UiTool(UiTool.Kind.CLICK_TEXT).call(step.arguments, mContext);
                return;
            case "ui.tap":
                new UiTool(UiTool.Kind.TAP).call(step.arguments, mContext);
                return;
            case "ui.swipe":
                new UiTool(UiTool.Kind.SWIPE).call(step.arguments, mContext);
                return;
            default:
                throw new Exception("Unsupported Boost tool: " + step.toolName);
        }
    }

    @Override
    public ScreenFingerprint currentFingerprint() {
        if (!McpAccessibilityService.isRunning()) return ScreenFingerprint.empty();
        McpAccessibilityService service = McpAccessibilityService.getInstance();
        return service == null ? ScreenFingerprint.empty() : service.currentScreenFingerprint();
    }

    @Override
    public boolean matches(ScreenFingerprint expected) {
        if (expected == null || expected.anchors.isEmpty()) return true;
        if (!McpAccessibilityService.isRunning()) return false;
        McpAccessibilityService service = McpAccessibilityService.getInstance();
        return service != null && service.screenMatches(expected);
    }
}
