package com.termux.app.mcp.tools;

import android.content.Context;
import android.os.Build;

import com.termux.app.mcp.McpAccessibilityService;
import com.termux.app.mcp.McpTool;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * UI gesture and interaction tools backed by AccessibilityService.
 *
 * Kind.TAP        → ui.tap(x, y)
 * Kind.SWIPE      → ui.swipe(x1, y1, x2, y2, duration_ms)
 * Kind.CLICK_TEXT → ui.click_text(text, match_mode)
 * Kind.INPUT_TEXT → ui.input_text(text)
 */
public class UiTool implements McpTool {

    public enum Kind { TAP, SWIPE, CLICK_TEXT, INPUT_TEXT }

    private final Kind mKind;

    public UiTool(Kind kind) { mKind = kind; }

    @Override public String getName() {
        switch (mKind) {
            case TAP:        return "ui.tap";
            case SWIPE:      return "ui.swipe";
            case CLICK_TEXT: return "ui.click_text";
            case INPUT_TEXT: return "ui.input_text";
            default:         return "ui.unknown";
        }
    }

    @Override public String getDescription() {
        switch (mKind) {
            case TAP:
                return "Tap at screen coordinates (x, y). Requires accessibility permission.";
            case SWIPE:
                return "Swipe from (x1,y1) to (x2,y2). Requires accessibility permission.";
            case CLICK_TEXT:
                return "Find a UI element by text and click it. Requires accessibility permission.";
            case INPUT_TEXT:
                return "Type text into the currently focused input field. " +
                       "Requires accessibility permission. Tap a field first if none is focused.";
            default: return "";
        }
    }

    @Override public String getInputSchema() {
        switch (mKind) {
            case TAP:
                return "{\"type\":\"object\",\"required\":[\"x\",\"y\"],\"properties\":{" +
                    "\"task_id\":{\"type\":\"string\"}," +
                    "\"x\":{\"type\":\"number\",\"description\":\"Screen X coordinate (pixels)\"}," +
                    "\"y\":{\"type\":\"number\",\"description\":\"Screen Y coordinate (pixels)\"}" +
                    "}}";
            case SWIPE:
                return "{\"type\":\"object\",\"required\":[\"x1\",\"y1\",\"x2\",\"y2\"]," +
                    "\"properties\":{" +
                    "\"task_id\":{\"type\":\"string\"}," +
                    "\"x1\":{\"type\":\"number\"},\"y1\":{\"type\":\"number\"}," +
                    "\"x2\":{\"type\":\"number\"},\"y2\":{\"type\":\"number\"}," +
                    "\"duration_ms\":{\"type\":\"integer\",\"default\":300," +
                        "\"description\":\"Swipe duration in milliseconds\"}" +
                    "}}";
            case CLICK_TEXT:
                return "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{" +
                    "\"task_id\":{\"type\":\"string\"}," +
                    "\"text\":{\"type\":\"string\",\"description\":\"Text to find and click\"}," +
                    "\"match_mode\":{\"type\":\"string\",\"enum\":[\"contains\",\"exact\"]," +
                        "\"default\":\"contains\"}" +
                    "}}";
            case INPUT_TEXT:
                return "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{" +
                    "\"task_id\":{\"type\":\"string\"}," +
                    "\"text\":{\"type\":\"string\",\"description\":\"Text to type into focused field\"}" +
                    "}}";
            default: return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    @Override
    public String call(JSONObject args, Context context) throws Exception {
        if (!McpAccessibilityService.isRunning()) {
            return text("Accessibility permission not granted. " +
                "Please enable 'Claude Code Test' in Settings → Accessibility.");
        }

        McpAccessibilityService svc = McpAccessibilityService.getInstance();

        switch (mKind) {
            case TAP: {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
                    return text("ui.tap requires Android 7.0+ (API 24)");
                float x = (float) args.getDouble("x");
                float y = (float) args.getDouble("y");
                svc.tap(x, y);
                return text("Tapped at (" + (int)x + ", " + (int)y + ")");
            }
            case SWIPE: {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
                    return text("ui.swipe requires Android 7.0+ (API 24)");
                float x1 = (float) args.getDouble("x1");
                float y1 = (float) args.getDouble("y1");
                float x2 = (float) args.getDouble("x2");
                float y2 = (float) args.getDouble("y2");
                int dur  = args.optInt("duration_ms", 300);
                svc.swipe(x1, y1, x2, y2, dur);
                return text("Swiped from (" + (int)x1 + "," + (int)y1 + ") to (" +
                            (int)x2 + "," + (int)y2 + ") over " + dur + "ms");
            }
            case CLICK_TEXT: {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
                    return text("ui.click_text requires Android 7.0+ (API 24)");
                String t    = args.getString("text");
                String mode = args.optString("match_mode", "contains");
                svc.clickText(t, mode);
                return text("Clicked element with text: \"" + t + "\"");
            }
            case INPUT_TEXT: {
                String t = args.getString("text");
                svc.inputText(t);
                return text("Input text: \"" + t + "\"");
            }
            default:
                return text("Unknown tool kind");
        }
    }

    private static String text(String msg) throws Exception {
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", msg);
        return new JSONArray().put(item).toString();
    }
}
