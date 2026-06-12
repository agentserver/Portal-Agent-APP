package com.termux.app.automation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class AutomationCandidateGenerator {

    private static final String TOOL_APP_OPEN = "app.open";
    private static final String TOOL_CLICK_TEXT = "ui.click_text";
    private static final String TOOL_SWIPE = "ui.swipe";
    private static final String TOOL_TAP = "ui.tap";
    private static final String TOOL_INPUT_TEXT = "ui.input_text";
    private static final String SOURCE_AGENT_SUCCESS = "agent_success";
    private static final String VERSION_ID = "v1";

    private AutomationCandidateGenerator() {}

    public static ActionRecipe fromSuccessfulTurn(String prompt, String sourceTaskId, List<ToolTraceEvent> traces) {
        if (traces == null) return null;

        List<ToolTraceEvent> retained = new ArrayList<>();
        boolean hasClickTextAnchor = false;
        boolean hasStableAnchor = false;
        String lastClickedText = "";
        String lastAppOpenPackage = "";

        for (ToolTraceEvent trace : traces) {
            if (trace == null) continue;
            if (TOOL_INPUT_TEXT.equals(trace.toolName)) return null;
            if (!trace.success || !isAllowedTool(trace.toolName)) continue;

            retained.add(trace);
            if (TOOL_CLICK_TEXT.equals(trace.toolName)) {
                String text = trace.arguments.optString("text", "").trim();
                if (!text.isEmpty()) {
                    hasClickTextAnchor = true;
                    hasStableAnchor = true;
                    lastClickedText = text;
                }
            } else if (TOOL_APP_OPEN.equals(trace.toolName)) {
                String packageName = firstNonEmpty(
                    trace.arguments.optString("package_name", ""),
                    trace.arguments.optString("package", ""));
                if (!packageName.isEmpty()) {
                    hasStableAnchor = true;
                    lastAppOpenPackage = packageName;
                }
            }
        }

        if (retained.isEmpty() || !hasStableAnchor) return null;

        List<ActionStep> steps = new ArrayList<>();
        ToolTraceEvent lastRetained = null;
        for (ToolTraceEvent trace : retained) {
            if (TOOL_TAP.equals(trace.toolName) && !hasClickTextAnchor) continue;
            ActionStep step = toStep(trace);
            if (step != null) {
                steps.add(step);
                lastRetained = trace;
            }
        }

        if (steps.isEmpty()) return null;

        String endAnchor = lastClickedText;
        if (endAnchor.isEmpty() && lastRetained != null) {
            endAnchor = lastRetained.resultSummary.trim();
        }
        if (endAnchor.isEmpty()) return null;

        String targetPackage = targetPackageFor(lastRetained, lastAppOpenPackage);
        String targetActivity = "";
        if (lastRetained != null && targetPackage.equals(lastRetained.packageName)) {
            targetActivity = lastRetained.activityName;
        }
        ScreenFingerprint endConditions = new ScreenFingerprint(
            targetPackage, targetActivity, Collections.singletonList(endAnchor), 0, 0, "");

        ActionRecipe candidate = new ActionRecipe(
            UUID.randomUUID().toString(),
            defaultString(prompt).trim(),
            false,
            false,
            AutomationRiskLevel.LOW,
            nonEmptyList(prompt),
            targetPackage,
            targetActivity,
            ScreenFingerprint.empty(),
            endConditions,
            steps,
            SOURCE_AGENT_SUCCESS,
            nonEmptyList(sourceTaskId),
            RecipeStats.empty(),
            VERSION_ID,
            versions());

        AutomationRiskLevel risk = AutomationPolicy.classifyRecipe(candidate);
        if (risk != AutomationRiskLevel.LOW) return null;
        return new ActionRecipe(
            candidate.id,
            candidate.name,
            candidate.enabled,
            candidate.autoBoostEnabled,
            risk,
            candidate.intentPatterns,
            candidate.targetPackage,
            candidate.targetActivity,
            candidate.startConditions,
            candidate.endConditions,
            candidate.steps,
            candidate.source,
            candidate.sourceTaskIds,
            candidate.stats,
            candidate.currentVersionId,
            candidate.versions);
    }

    private static ActionStep toStep(ToolTraceEvent trace) {
        if (trace == null) return null;
        JSONObject arguments = trace.argumentsCopy();
        if (TOOL_APP_OPEN.equals(trace.toolName)) {
            normalizeAppOpenArguments(arguments);
        }
        List<UiSelector> selectors = Collections.emptyList();
        if (TOOL_CLICK_TEXT.equals(trace.toolName)) {
            String text = arguments.optString("text", "").trim();
            if (text.isEmpty()) return null;
            selectors = Arrays.asList(new UiSelector(text, "", "", new int[]{0, 0, 0, 0}, "", "", 100));
        }
        return new ActionStep(
            UUID.randomUUID().toString(),
            trace.toolName,
            arguments,
            selectors,
            ScreenFingerprint.empty(),
            ScreenFingerprint.empty(),
            3000,
            "fallback_agent");
    }

    private static void normalizeAppOpenArguments(JSONObject arguments) {
        String packageName = arguments.optString("package_name", "").trim();
        if (!packageName.isEmpty()) return;

        String legacyPackage = arguments.optString("package", "").trim();
        if (!legacyPackage.isEmpty()) {
            AutomationJson.put(arguments, "package_name", legacyPackage);
        }
    }

    private static String targetPackageFor(ToolTraceEvent lastRetained, String lastAppOpenPackage) {
        if (lastRetained == null) return defaultString(lastAppOpenPackage).trim();
        if (TOOL_APP_OPEN.equals(lastRetained.toolName)) {
            return firstNonEmpty(
                lastRetained.arguments.optString("package_name", ""),
                firstNonEmpty(lastRetained.arguments.optString("package", ""), lastAppOpenPackage));
        }
        String targetPackage = defaultString(lastRetained.packageName).trim();
        if (!targetPackage.isEmpty()) return targetPackage;
        return defaultString(lastAppOpenPackage).trim();
    }

    private static boolean isAllowedTool(String toolName) {
        return TOOL_APP_OPEN.equals(toolName)
            || TOOL_CLICK_TEXT.equals(toolName)
            || TOOL_SWIPE.equals(toolName)
            || TOOL_TAP.equals(toolName);
    }

    private static List<String> nonEmptyList(String value) {
        String trimmed = defaultString(value).trim();
        if (trimmed.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(trimmed);
    }

    private static JSONArray versions() {
        JSONObject version = new JSONObject();
        AutomationJson.put(version, "id", VERSION_ID);
        AutomationJson.put(version, "source", SOURCE_AGENT_SUCCESS);
        AutomationJson.put(version, "created_at", System.currentTimeMillis());
        return new JSONArray().put(version);
    }

    private static String firstNonEmpty(String first, String second) {
        first = defaultString(first).trim();
        if (!first.isEmpty()) return first;
        return defaultString(second).trim();
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}
