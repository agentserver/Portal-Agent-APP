package com.termux.app.automation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RecipeDedupe {
    private RecipeDedupe() {}

    public static boolean shouldMerge(ActionRecipe existing, ActionRecipe candidate) {
        if (existing == null || candidate == null) return false;
        if (existing.riskLevel != candidate.riskLevel) return false;
        if (!safeEquals(existing.targetPackage, candidate.targetPackage)) return false;
        if (!safeEquals(existing.endConditions.packageName, candidate.endConditions.packageName)) return false;
        int anchorOverlap = anchorOverlap(existing.endConditions.anchors, candidate.endConditions.anchors);
        boolean intentOverlap = intentOverlap(existing.intentPatterns, candidate.intentPatterns);
        boolean sameActivity = safeEquals(existing.endConditions.activityName, candidate.endConditions.activityName);
        boolean sameToolSequence = hasNonEmptyToolSequence(existing) && toolSequence(existing).equals(toolSequence(candidate));
        return (intentOverlap && sameActivity)
            || (anchorOverlap >= 2 && sameActivity)
            || (anchorOverlap >= 2 && sameToolSequence);
    }

    public static ActionRecipe mergeAsVersion(ActionRecipe existing, ActionRecipe candidate) throws Exception {
        JSONArray versions = new JSONArray(existing.versions != null ? existing.versions.toString() : "[]");
        String candidateVersionId = candidate.currentVersionId == null ? "" : candidate.currentVersionId.trim();
        String versionId = candidateVersionId.isEmpty()
            ? "v" + (versions.length() + 1)
            : candidateVersionId;
        versionId = uniqueVersionId(versionId, versions);
        versions.put(versionJson(versionId, candidate.steps));

        List<String> sourceTaskIds = new ArrayList<>();
        for (String id : existing.sourceTaskIds) {
            addSourceTaskId(sourceTaskIds, id);
        }
        for (String id : candidate.sourceTaskIds) {
            addSourceTaskId(sourceTaskIds, id);
        }
        return new ActionRecipe(
            existing.id, existing.name, existing.enabled, existing.autoBoostEnabled,
            existing.riskLevel, existing.intentPatterns, existing.targetPackage,
            existing.targetActivity, existing.startConditions, existing.endConditions,
            existing.steps, existing.source, sourceTaskIds, existing.stats,
            existing.currentVersionId, versions);
    }

    public static JSONObject versionJson(String versionId, List<ActionStep> steps) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", versionId);
        JSONArray arr = new JSONArray();
        if (steps != null) {
            for (ActionStep step : steps) {
                if (step != null) arr.put(step.toJson());
            }
        }
        o.put("steps", arr);
        o.put("created_at", System.currentTimeMillis());
        return o;
    }

    private static void addSourceTaskId(List<String> sourceTaskIds, String id) {
        if (id == null || id.trim().isEmpty()) return;
        if (!sourceTaskIds.contains(id)) sourceTaskIds.add(id);
    }

    private static String uniqueVersionId(String preferred, JSONArray versions) {
        Set<String> existingIds = new HashSet<>();
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.optJSONObject(i);
            if (version != null) existingIds.add(version.optString("id", ""));
        }
        if (!existingIds.contains(preferred)) return preferred;

        int next = versions.length() + 1;
        String candidate;
        do {
            candidate = "v" + next;
            next++;
        } while (existingIds.contains(candidate));
        return candidate;
    }

    private static boolean hasNonEmptyToolSequence(ActionRecipe recipe) {
        return !recipe.steps.isEmpty();
    }

    private static List<String> toolSequence(ActionRecipe recipe) {
        List<String> out = new ArrayList<>();
        for (ActionStep step : recipe.steps) out.add(step.toolName);
        return out;
    }

    private static int anchorOverlap(List<String> a, List<String> b) {
        Set<String> existing = new HashSet<>();
        for (String value : a) {
            String anchor = normalize(value);
            if (!anchor.isEmpty()) existing.add(anchor);
        }
        Set<String> candidate = new HashSet<>();
        for (String value : b) {
            String anchor = normalize(value);
            if (!anchor.isEmpty()) candidate.add(anchor);
        }
        int count = 0;
        for (String anchor : candidate) {
            if (existing.contains(anchor)) count++;
        }
        return count;
    }

    private static boolean intentOverlap(List<String> a, List<String> b) {
        for (String left : a) {
            String nl = normalize(left);
            for (String right : b) {
                String nr = normalize(right);
                if (!nl.isEmpty() && !nr.isEmpty() && (nl.contains(nr) || nr.contains(nl))) return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace(" ", "").replace("设置", "").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
