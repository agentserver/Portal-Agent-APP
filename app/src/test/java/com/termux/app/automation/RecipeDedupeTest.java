package com.termux.app.automation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class RecipeDedupeTest {

    @Test
    public void similarSettingsRecipesMergeAsVersions() throws Exception {
        ScreenFingerprint end = new ScreenFingerprint(
            "com.android.settings", "AccessibilitySettings",
            Arrays.asList("已下载的应用", "Claude Code Test"), 7, 0, "root");
        ActionRecipe existing = recipe("r1", "打开无障碍设置", AutomationRiskLevel.LOW, end, "v1");
        ActionRecipe candidate = recipe("r2", "进入辅助功能权限页", AutomationRiskLevel.LOW, end, "v2");

        Assert.assertTrue(RecipeDedupe.shouldMerge(existing, candidate));
        ActionRecipe merged = RecipeDedupe.mergeAsVersion(existing, candidate);

        Assert.assertEquals("r1", merged.id);
        Assert.assertEquals(2, merged.versions.length());
        Assert.assertEquals("v1", merged.currentVersionId);
        Assert.assertEquals(2, merged.sourceTaskIds.size());
    }

    @Test
    public void differentRiskRecipesDoNotMerge() throws Exception {
        ScreenFingerprint end = new ScreenFingerprint("pkg", "Activity", Arrays.asList("目标"), 1, 0, "");
        ActionRecipe low = recipe("r1", "打开页面", AutomationRiskLevel.LOW, end, "v1");
        ActionRecipe high = recipe("r2", "打开页面并发送", AutomationRiskLevel.HIGH, end, "v2");

        Assert.assertFalse(RecipeDedupe.shouldMerge(low, high));
    }

    @Test
    public void recipesWithOnlyEmptyAnchorsAndUnrelatedIntentDoNotMerge() throws Exception {
        ScreenFingerprint existingEnd = new ScreenFingerprint("pkg", "Activity", Arrays.asList("", "   "), 1, 0, "");
        ScreenFingerprint candidateEnd = new ScreenFingerprint("pkg", "Activity", Arrays.asList("", null), 1, 0, "");
        ActionRecipe existing = recipe("r1", "打开页面", AutomationRiskLevel.LOW, existingEnd, "v1");
        ActionRecipe candidate = recipe("r2", "查看资料", AutomationRiskLevel.LOW, candidateEnd, "v2");

        Assert.assertFalse(RecipeDedupe.shouldMerge(existing, candidate));
    }

    @Test
    public void sameActivityAndToolsWithoutSemanticOverlapDoNotMerge() throws Exception {
        ScreenFingerprint existingEnd = new ScreenFingerprint("pkg", "Activity", Arrays.asList("账户"), 1, 0, "");
        ScreenFingerprint candidateEnd = new ScreenFingerprint("pkg", "Activity", Arrays.asList("网络"), 1, 0, "");
        List<ActionStep> steps = Arrays.asList(step("ui.click_text", "打开"));
        ActionRecipe existing = recipe("r1", "打开账户", AutomationRiskLevel.LOW, existingEnd, "v1", steps);
        ActionRecipe candidate = recipe("r2", "查看网络", AutomationRiskLevel.LOW, candidateEnd, "v2", steps);

        Assert.assertFalse(RecipeDedupe.shouldMerge(existing, candidate));
    }

    @Test
    public void oneGenericAnchorAndSameActivityWithDifferentToolsDoesNotMerge() throws Exception {
        ScreenFingerprint existingEnd = new ScreenFingerprint("pkg", "Activity", Arrays.asList("完成", "账户"), 1, 0, "");
        ScreenFingerprint candidateEnd = new ScreenFingerprint("pkg", "Activity", Arrays.asList("完成", "网络"), 1, 0, "");
        ActionRecipe existing = recipe(
            "r1", "打开账户", AutomationRiskLevel.LOW, existingEnd, "v1",
            Arrays.asList(step("ui.click_text", "打开")));
        ActionRecipe candidate = recipe(
            "r2", "查看网络", AutomationRiskLevel.LOW, candidateEnd, "v2",
            Arrays.asList(step("ui.swipe", "滑动")));

        Assert.assertFalse(RecipeDedupe.shouldMerge(existing, candidate));
    }

    @Test
    public void oneGenericAnchorWithSameToolSequenceDoesNotMerge() throws Exception {
        ScreenFingerprint existingEnd = new ScreenFingerprint("pkg", "Activity", Arrays.asList("完成", "账户"), 1, 0, "");
        ScreenFingerprint candidateEnd = new ScreenFingerprint("pkg", "Activity", Arrays.asList("完成", "网络"), 1, 0, "");
        List<ActionStep> steps = Arrays.asList(step("ui.click_text", "打开"));
        ActionRecipe existing = recipe("r1", "打开账户", AutomationRiskLevel.LOW, existingEnd, "v1", steps);
        ActionRecipe candidate = recipe("r2", "查看网络", AutomationRiskLevel.LOW, candidateEnd, "v2", steps);

        Assert.assertFalse(RecipeDedupe.shouldMerge(existing, candidate));
    }

    @Test
    public void duplicateCandidateAnchorsDoNotInflateOverlap() throws Exception {
        ScreenFingerprint existingEnd = new ScreenFingerprint("pkg", "Activity", Arrays.asList("完成"), 1, 0, "");
        ScreenFingerprint candidateEnd = new ScreenFingerprint("pkg", "Activity", Arrays.asList("完成", "完成"), 1, 0, "");
        List<ActionStep> steps = Arrays.asList(step("ui.click_text", "打开"));
        ActionRecipe existing = recipe("r1", "打开账户", AutomationRiskLevel.LOW, existingEnd, "v1", steps);
        ActionRecipe candidate = recipe("r2", "查看网络", AutomationRiskLevel.LOW, candidateEnd, "v2", steps);

        Assert.assertFalse(RecipeDedupe.shouldMerge(existing, candidate));
    }

    @Test
    public void mergeGeneratesUniqueVersionIdOnCollision() throws Exception {
        ScreenFingerprint end = new ScreenFingerprint("pkg", "Activity", Arrays.asList("目标"), 1, 0, "");
        ActionRecipe existing = recipe("r1", "打开页面", AutomationRiskLevel.LOW, end, "v1");
        ActionRecipe candidate = recipe("r2", "打开页面", AutomationRiskLevel.LOW, end, "v1");

        ActionRecipe merged = RecipeDedupe.mergeAsVersion(existing, candidate);

        Assert.assertEquals(2, merged.versions.length());
        Assert.assertEquals("v1", merged.versions.getJSONObject(0).optString("id"));
        Assert.assertEquals("v2", merged.versions.getJSONObject(1).optString("id"));
    }

    @Test
    public void mergeTreatsWhitespaceVersionIdAsBlank() throws Exception {
        ScreenFingerprint end = new ScreenFingerprint("pkg", "Activity", Arrays.asList("目标"), 1, 0, "");
        ActionRecipe existing = recipe("r1", "打开页面", AutomationRiskLevel.LOW, end, "v1");
        ActionRecipe candidate = recipe("r2", "打开页面", AutomationRiskLevel.LOW, end, "   ");

        ActionRecipe merged = RecipeDedupe.mergeAsVersion(existing, candidate);

        Assert.assertEquals("v2", merged.versions.getJSONObject(1).optString("id"));
    }

    @Test
    public void mergeSkipsBlankSourceTaskIds() throws Exception {
        ScreenFingerprint end = new ScreenFingerprint("pkg", "Activity", Arrays.asList("目标"), 1, 0, "");
        ActionRecipe existing = recipe(
            "r1", "打开页面", AutomationRiskLevel.LOW, end, "v1", Arrays.asList(), Arrays.asList("", "r1-task"));
        ActionRecipe candidate = recipe(
            "r2", "打开页面", AutomationRiskLevel.LOW, end, "v2", Arrays.asList(), Arrays.asList("", "   ", "task-2"));

        ActionRecipe merged = RecipeDedupe.mergeAsVersion(existing, candidate);

        Assert.assertEquals(Arrays.asList("r1-task", "task-2"), merged.sourceTaskIds);
    }

    private static ActionRecipe recipe(String id, String name, AutomationRiskLevel risk,
                                       ScreenFingerprint end, String version) throws Exception {
        return recipe(id, name, risk, end, version, Arrays.asList());
    }

    private static ActionRecipe recipe(String id, String name, AutomationRiskLevel risk,
                                       ScreenFingerprint end, String version, List<ActionStep> steps) throws Exception {
        return recipe(id, name, risk, end, version, steps, Arrays.asList(id + "-task"));
    }

    private static ActionRecipe recipe(String id, String name, AutomationRiskLevel risk,
                                       ScreenFingerprint end, String version, List<ActionStep> steps,
                                       List<String> sourceTaskIds) throws Exception {
        JSONArray versions = new JSONArray().put(RecipeDedupe.versionJson(version, steps));
        return new ActionRecipe(
            id, name, true, false, risk, Arrays.asList(name),
            end.packageName, end.activityName, ScreenFingerprint.empty(), end,
            steps, "agent_success", sourceTaskIds,
            new RecipeStats(0, 0, 0, 0, 0, ""), version, versions);
    }

    private static ActionStep step(String toolName, String text) throws Exception {
        return new ActionStep(
            "s", toolName, new JSONObject().put("text", text),
            Arrays.asList(new UiSelector(text, "", "TextView", new int[]{0, 0, 1, 1}, "", "", 80)),
            ScreenFingerprint.empty(), ScreenFingerprint.empty(), 3000, "fallback_agent");
    }
}
