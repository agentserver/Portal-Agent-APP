package com.termux.app.automation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

public final class AutomationPolicy {
    private AutomationPolicy() {}

    public static AutomationRiskLevel classifyStep(ActionStep step) {
        if (step == null) return AutomationRiskLevel.HIGH;
        String tool = step.toolName;
        if ("ui.input_text".equals(tool)) return AutomationRiskLevel.HIGH;
        if (hasHighRiskText(step)) return AutomationRiskLevel.HIGH;
        if ("app.open".equals(tool) || "ui.click_text".equals(tool) || "ui.tap".equals(tool) || "ui.swipe".equals(tool)) {
            return AutomationRiskLevel.LOW;
        }
        return AutomationRiskLevel.MEDIUM;
    }

    public static AutomationRiskLevel classifyRecipe(ActionRecipe recipe) {
        if (recipe == null) return AutomationRiskLevel.HIGH;
        if (recipe.steps.isEmpty()) return AutomationRiskLevel.HIGH;
        AutomationRiskLevel result = AutomationRiskLevel.LOW;
        for (ActionStep step : recipe.steps) {
            AutomationRiskLevel risk = classifyStep(step);
            if (risk == AutomationRiskLevel.HIGH) return AutomationRiskLevel.HIGH;
            if (risk == AutomationRiskLevel.MEDIUM) result = AutomationRiskLevel.MEDIUM;
        }
        return result;
    }

    public static JSONObject redactArguments(String toolName, JSONObject args) throws Exception {
        JSONObject copy = args == null ? new JSONObject() : new JSONObject(args.toString());
        if ("ui.input_text".equals(toolName) && copy.has("text")) {
            copy.put("text", "[redacted]");
        }
        return copy;
    }

    public static boolean hasStableSelector(ActionStep step) {
        if (step == null) return false;
        for (UiSelector selector : step.selectors) {
            if (hasStableAnchor(selector)) return true;
        }
        String text = step.arguments.optString("text", "").trim();
        return !text.isEmpty() && !"ui.tap".equals(step.toolName);
    }

    public static boolean canAutoBoost(ActionRecipe recipe) {
        if (recipe == null || !recipe.enabled || !recipe.autoBoostEnabled) return false;
        if (recipe.riskLevel != AutomationRiskLevel.LOW) return false;
        if (recipe.endConditions == null || !hasNonEmptyAnchor(recipe.endConditions)) return false;
        if (recipe.steps.isEmpty()) return false;
        for (ActionStep step : recipe.steps) {
            if (classifyStep(step) != AutomationRiskLevel.LOW) return false;
            if (!hasStableSelector(step) && "ui.tap".equals(step.toolName)) return false;
        }
        return true;
    }

    private static boolean hasStableAnchor(UiSelector selector) {
        if (selector == null) return false;
        return !selector.text.trim().isEmpty() || !selector.contentDesc.trim().isEmpty()
            || !selector.parentSummary.trim().isEmpty() || !selector.screenFingerprint.trim().isEmpty();
    }

    private static boolean hasNonEmptyAnchor(ScreenFingerprint fingerprint) {
        for (String anchor : fingerprint.anchors) {
            if (anchor != null && !anchor.trim().isEmpty()) return true;
        }
        return false;
    }

    private static boolean hasHighRiskText(ActionStep step) {
        if (containsHighRiskValue(step.arguments)) return true;
        for (UiSelector selector : step.selectors) {
            if (selector == null) continue;
            if (containsHighRiskWord(selector.text)
                || containsHighRiskWord(selector.contentDesc)
                || containsHighRiskWord(selector.parentSummary)
                || containsHighRiskWord(selector.screenFingerprint)) {
                return true;
            }
        }
        return hasHighRiskFingerprintText(step.preconditions) || hasHighRiskFingerprintText(step.postconditions);
    }

    private static boolean containsHighRiskValue(Object value) {
        if (value instanceof String) {
            return containsHighRiskWord((String) value);
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object nested = object.opt(keys.next());
                if (containsHighRiskValue(nested)) return true;
            }
            return false;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                if (containsHighRiskValue(array.opt(i))) return true;
            }
        }
        return false;
    }

    private static boolean hasHighRiskFingerprintText(ScreenFingerprint fingerprint) {
        if (fingerprint == null) return false;
        if (containsHighRiskWord(fingerprint.rootSummary)) return true;
        for (String anchor : fingerprint.anchors) {
            if (containsHighRiskWord(anchor)) return true;
        }
        return false;
    }

    private static boolean containsHighRiskWord(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        text = text.toLowerCase(Locale.ROOT);
        return text.contains("支付") || text.contains("删除") || text.contains("发送")
            || text.contains("转账") || text.contains("确认下单") || text.contains("授权")
            || text.contains("password") || text.contains("token") || text.contains("验证码");
    }
}
