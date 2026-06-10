package com.termux.app.automation;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class AutomationCandidateGeneratorTest {

    @Test
    public void appOpenAndClickTextGeneratesLowCandidate() throws Exception {
        ActionRecipe recipe = AutomationCandidateGenerator.fromSuccessfulTurn(
            "打开设置",
            "task-1",
            Arrays.asList(
                trace("1", "app.open", new JSONObject().put("package", "com.android.settings"),
                    true, "opened settings", "com.android.settings", ".Settings"),
                trace("2", "ui.click_text", new JSONObject().put("text", "Network").put("match_mode", "exact"),
                    true, "clicked Network", "com.android.settings", ".NetworkDashboardActivity")));

        Assert.assertNotNull(recipe);
        Assert.assertFalse(recipe.enabled);
        Assert.assertFalse(recipe.autoBoostEnabled);
        Assert.assertEquals(AutomationRiskLevel.LOW, recipe.riskLevel);
        Assert.assertEquals("agent_success", recipe.source);
        Assert.assertEquals("com.android.settings", recipe.targetPackage);
        Assert.assertEquals(".NetworkDashboardActivity", recipe.targetActivity);
        Assert.assertEquals(2, recipe.steps.size());
        Assert.assertEquals("app.open", recipe.steps.get(0).toolName);
        Assert.assertEquals("com.android.settings", recipe.steps.get(0).arguments.optString("package_name"));
        Assert.assertTrue(recipe.steps.get(0).selectors.isEmpty());
        Assert.assertEquals("ui.click_text", recipe.steps.get(1).toolName);
        Assert.assertEquals("Network", recipe.steps.get(1).selectors.get(0).text);
        Assert.assertEquals("exact", recipe.steps.get(1).arguments.optString("match_mode"));
        Assert.assertEquals(Arrays.asList("打开设置"), recipe.intentPatterns);
        Assert.assertEquals(Arrays.asList("task-1"), recipe.sourceTaskIds);
        Assert.assertTrue(recipe.endConditions.containsAnchor("Network"));
        Assert.assertEquals("v1", recipe.currentVersionId);
        Assert.assertEquals("v1", recipe.versions.optJSONObject(0).optString("id"));
        Assert.assertEquals(0, recipe.stats.successCount);
        Assert.assertEquals(0, recipe.stats.failureCount);
    }

    @Test
    public void appOpenPackageArgumentIsNormalizedForReplay() throws Exception {
        ActionRecipe recipe = AutomationCandidateGenerator.fromSuccessfulTurn(
            "打开应用",
            "task-1",
            Arrays.asList(trace("1", "app.open", new JSONObject().put("package", "com.example.app"),
                true, "opened example", "com.example.app", "")));

        Assert.assertNotNull(recipe);
        Assert.assertEquals("com.example.app", recipe.steps.get(0).arguments.optString("package_name"));
        Assert.assertEquals("com.example.app", recipe.targetPackage);
    }

    @Test
    public void targetPackageFallsBackToAppOpenArgumentsWhenTracePackageIsEmpty() throws Exception {
        ActionRecipe recipe = AutomationCandidateGenerator.fromSuccessfulTurn(
            "打开应用",
            "task-1",
            Arrays.asList(trace("1", "app.open", new JSONObject().put("package_name", "com.fallback.app"),
                true, "opened fallback", "", "")));

        Assert.assertNotNull(recipe);
        Assert.assertEquals("com.fallback.app", recipe.targetPackage);
        Assert.assertEquals("com.fallback.app", recipe.endConditions.packageName);
    }

    @Test
    public void coordinateOnlyTapReturnsNull() throws Exception {
        ActionRecipe recipe = AutomationCandidateGenerator.fromSuccessfulTurn(
            "点一下",
            "task-1",
            Arrays.asList(trace("1", "ui.tap", new JSONObject().put("x", 10).put("y", 20),
                true, "tapped", "pkg", "Activity")));

        Assert.assertNull(recipe);
    }

    @Test
    public void inputTextReturnsNullEvenWhenUnsuccessful() throws Exception {
        ActionRecipe recipe = AutomationCandidateGenerator.fromSuccessfulTurn(
            "输入内容",
            "task-1",
            Arrays.asList(
                trace("1", "app.open", new JSONObject().put("package", "pkg"),
                    true, "opened", "pkg", "Activity"),
                trace("2", "ui.input_text", new JSONObject().put("text", "secret"),
                    false, "failed", "pkg", "Activity")));

        Assert.assertNull(recipe);
    }

    @Test
    public void unsuccessfulTraceEventsAreIgnored() throws Exception {
        ActionRecipe recipe = AutomationCandidateGenerator.fromSuccessfulTurn(
            "打开页面",
            "task-1",
            Arrays.asList(
                trace("1", "ui.click_text", new JSONObject().put("text", "删除"),
                    false, "failed dangerous click", "pkg", "Activity"),
                trace("2", "ui.click_text", new JSONObject().put("text", "Settings"),
                    true, "clicked Settings", "pkg", "Activity")));

        Assert.assertNotNull(recipe);
        Assert.assertEquals(1, recipe.steps.size());
        Assert.assertEquals("Settings", recipe.steps.get(0).selectors.get(0).text);
        Assert.assertTrue(recipe.endConditions.containsAnchor("Settings"));
    }

    @Test
    public void nonLowCandidateReturnsNull() throws Exception {
        ActionRecipe recipe = AutomationCandidateGenerator.fromSuccessfulTurn(
            "删除项目",
            "task-1",
            Arrays.asList(trace("1", "ui.click_text", new JSONObject().put("text", "删除"),
                true, "clicked delete", "pkg", "Activity")));

        Assert.assertNull(recipe);
    }

    @Test
    public void swipeWithoutStableAnchorReturnsNull() throws Exception {
        ActionRecipe recipe = AutomationCandidateGenerator.fromSuccessfulTurn(
            "向上滑动",
            "task-1",
            Arrays.asList(trace("1", "ui.swipe", new JSONObject()
                    .put("start_x", 100)
                    .put("start_y", 500)
                    .put("end_x", 100)
                    .put("end_y", 100),
                true, "swiped", "pkg", "Activity")));

        Assert.assertNull(recipe);
    }

    private static ToolTraceEvent trace(String id, String toolName, JSONObject arguments, boolean success,
                                        String resultSummary, String packageName, String activityName) {
        return new ToolTraceEvent(id, 100, "task-1", toolName, arguments, success,
            resultSummary, packageName, activityName);
    }
}
