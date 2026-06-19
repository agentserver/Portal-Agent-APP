package com.termux.app.mcp.tools;

import android.content.Context;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.termux.app.mcp.McpAccessibilityService;
import com.termux.app.mcp.McpTool;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ui.get_accessibility_tree — reads the current screen's UI node tree.
 *
 * Two modes:
 *   "flat"  (default) — returns a flat list of "interesting" nodes
 *                        (nodes with text, content-desc, or that are clickable/editable).
 *                        Much more concise and Claude-friendly.
 *   "tree"             — full hierarchical JSON tree (can be very large).
 *
 * max_depth limits tree traversal depth (default 20).
 */
public class UiTreeTool implements McpTool {

    private static final int DEFAULT_MAX_DEPTH = 20;

    @Override public String getName() { return "ui.get_accessibility_tree"; }

    @Override public String getDescription() {
        return "Read the current screen's UI element tree via AccessibilityService. " +
               "Use mode='flat' (default) for a compact list of interactive elements, " +
               "or mode='tree' for the full hierarchy. " +
               "Requires accessibility permission.";
    }

    @Override public String getInputSchema() {
        return "{\"type\":\"object\",\"properties\":{" +
            "\"task_id\":{\"type\":\"string\"}," +
            "\"mode\":{\"type\":\"string\",\"enum\":[\"flat\",\"tree\"],\"default\":\"flat\"," +
                "\"description\":\"flat=compact list of interactive nodes, tree=full hierarchy\"}," +
            "\"max_depth\":{\"type\":\"integer\",\"default\":20," +
                "\"description\":\"Max tree depth (tree mode only)\"}" +
            "}}";
    }

    @Override
    public String call(JSONObject args, Context context) throws Exception {
        if (!McpAccessibilityService.isRunning()) {
            return textContent("Accessibility permission not granted. " +
                "Please enable 'PortalAgent' in Settings → Accessibility.");
        }

        McpAccessibilityService svc = McpAccessibilityService.getInstance();
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) {
            return textContent("Cannot read active window. Make sure the screen is on " +
                "and an app is in the foreground.");
        }

        String mode     = args.optString("mode", "flat");
        int    maxDepth = args.optInt("max_depth", DEFAULT_MAX_DEPTH);

        JSONObject result = new JSONObject();
        result.put("package",  svc.getCurrentPackage());
        result.put("activity", svc.getCurrentActivity());

        try {
            if ("tree".equals(mode)) {
                result.put("mode", "tree");
                result.put("root", buildTree(root, 0, maxDepth));
            } else {
                JSONArray flat = new JSONArray();
                flattenInteresting(root, flat, 0, maxDepth);
                result.put("mode",  "flat");
                result.put("count", flat.length());
                result.put("nodes", flat);
            }
        } finally {
            root.recycle();
        }

        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", result.toString(2));
        return new JSONArray().put(item).toString();
    }

    // ── Flat mode: collect "interesting" nodes ────────────────────────────────

    private void flattenInteresting(AccessibilityNodeInfo node, JSONArray out,
                                     int depth, int maxDepth) throws Exception {
        if (node == null || depth > maxDepth) return;

        CharSequence text    = node.getText();
        CharSequence desc    = node.getContentDescription();
        boolean hasText      = text != null && text.length() > 0;
        boolean hasDesc      = desc != null && desc.length() > 0;
        boolean isClickable  = node.isClickable();
        boolean isEditable   = node.isEditable();
        boolean isScrollable = node.isScrollable();

        if (hasText || hasDesc || isClickable || isEditable || isScrollable) {
            JSONObject n = new JSONObject();
            if (hasText)  n.put("text",         text.toString());
            if (hasDesc)  n.put("content_desc",  desc.toString());
            n.put("class",      shortClass(node.getClassName()));
            n.put("bounds",     boundsJson(node));
            if (isClickable)  n.put("clickable",  true);
            if (isEditable)   n.put("editable",   true);
            if (isScrollable) n.put("scrollable", true);
            if (!node.isEnabled()) n.put("enabled", false);
            out.put(n);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            flattenInteresting(child, out, depth + 1, maxDepth);
            if (child != null) child.recycle();
        }
    }

    // ── Tree mode: full hierarchy ─────────────────────────────────────────────

    private JSONObject buildTree(AccessibilityNodeInfo node, int depth, int maxDepth)
            throws Exception {
        if (node == null) return null;

        JSONObject obj = new JSONObject();
        obj.put("class",  shortClass(node.getClassName()));

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) obj.put("text",         text.toString());
        if (desc != null && desc.length() > 0) obj.put("content_desc",  desc.toString());

        obj.put("bounds",     boundsJson(node));
        obj.put("clickable",  node.isClickable());
        obj.put("enabled",    node.isEnabled());
        obj.put("editable",   node.isEditable());
        obj.put("scrollable", node.isScrollable());
        obj.put("focused",    node.isFocused());

        JSONArray children = new JSONArray();
        if (depth < maxDepth) {
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                JSONObject childObj = buildTree(child, depth + 1, maxDepth);
                if (childObj != null) children.put(childObj);
                if (child != null) child.recycle();
            }
        }
        obj.put("children", children);
        return obj;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JSONArray boundsJson(AccessibilityNodeInfo node) throws Exception {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        JSONArray a = new JSONArray();
        a.put(r.left); a.put(r.top); a.put(r.right); a.put(r.bottom);
        return a;
    }

    /** "android.widget.TextView" → "TextView" */
    private static String shortClass(CharSequence cls) {
        if (cls == null) return "";
        String s = cls.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static String textContent(String msg) throws Exception {
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", msg);
        return new JSONArray().put(item).toString();
    }
}
