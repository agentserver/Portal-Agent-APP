package com.termux.app.mcp.tools;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.termux.app.mcp.McpAccessibilityService;
import com.termux.app.mcp.McpTool;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * App control tools.
 *
 * Kind.OPEN         → app.open(package_name)
 * Kind.GET_ACTIVITY → app.get_current_activity()
 */
public class AppTool implements McpTool {

    public enum Kind { OPEN, GET_ACTIVITY }

    private final Kind mKind;

    public AppTool(Kind kind) { mKind = kind; }

    @Override public String getName() {
        return mKind == Kind.OPEN ? "app.open" : "app.get_current_activity";
    }

    @Override public String getDescription() {
        if (mKind == Kind.OPEN) {
            return "Launch an app by its package name. " +
                   "Example: app.open({\"package_name\":\"com.android.settings\"})";
        }
        return "Get the package name and activity class of the current foreground app. " +
               "Requires accessibility permission.";
    }

    @Override public String getInputSchema() {
        if (mKind == Kind.OPEN) {
            return "{\"type\":\"object\",\"required\":[\"package_name\"],\"properties\":{" +
                "\"task_id\":{\"type\":\"string\"}," +
                "\"package_name\":{\"type\":\"string\"," +
                    "\"description\":\"Android package name, e.g. com.android.settings\"}" +
                "}}";
        }
        return "{\"type\":\"object\",\"properties\":{\"task_id\":{\"type\":\"string\"}}}";
    }

    @Override
    public String call(JSONObject args, Context context) throws Exception {
        if (mKind == Kind.OPEN) {
            return openApp(args, context);
        } else {
            return getCurrentActivity();
        }
    }

    // ── app.open ──────────────────────────────────────────────────────────────

    private String openApp(JSONObject args, Context context) throws Exception {
        String pkg = args.getString("package_name");
        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            throw new Exception("App not found or not launchable: " + pkg +
                ". Tip: use android.get_status() or check installed apps.");
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        return text("Launched app: " + pkg);
    }

    // ── app.get_current_activity ──────────────────────────────────────────────

    private String getCurrentActivity() throws Exception {
        if (!McpAccessibilityService.isRunning()) {
            return text("Accessibility permission not granted. " +
                "Please enable 'Claude Code Test' in Settings → Accessibility.");
        }
        McpAccessibilityService svc = McpAccessibilityService.getInstance();
        JSONObject result = new JSONObject();
        result.put("package",  svc.getCurrentPackage());
        result.put("activity", svc.getCurrentActivity());
        return text(result.toString(2));
    }

    private static String text(String msg) throws Exception {
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", msg);
        return new JSONArray().put(item).toString();
    }
}
