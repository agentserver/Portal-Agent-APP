package com.termux.app.automation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class AutomationPolicyTest {

    @Test
    public void inputTextIsHighRiskAndRedacted() throws Exception {
        ActionStep step = new ActionStep(
            "s", "ui.input_text", new JSONObject().put("text", "secret-token"),
            Arrays.asList(new UiSelector("", "", "EditText", new int[]{0, 0, 1, 1}, "", "", 80)),
            ScreenFingerprint.empty(), ScreenFingerprint.empty(), 3000, "fallback_agent");

        Assert.assertEquals(AutomationRiskLevel.HIGH, AutomationPolicy.classifyStep(step));
        JSONObject redacted = AutomationPolicy.redactArguments("ui.input_text", step.arguments);
        Assert.assertEquals("[redacted]", redacted.optString("text"));
        Assert.assertEquals("secret-token", step.arguments.optString("text"));
    }

    @Test
    public void clickTextWithStableSelectorIsLowRisk() throws Exception {
        ActionStep step = new ActionStep(
            "s", "ui.click_text", new JSONObject().put("text", "无障碍"),
            Arrays.asList(new UiSelector("无障碍", "", "TextView", new int[]{0, 0, 1, 1}, "", "", 80)),
            ScreenFingerprint.empty(), ScreenFingerprint.empty(), 3000, "fallback_agent");

        Assert.assertEquals(AutomationRiskLevel.LOW, AutomationPolicy.classifyStep(step));
        Assert.assertTrue(AutomationPolicy.hasStableSelector(step));
    }

    @Test
    public void recipeCannotAutoBoostWithoutEndCondition() throws Exception {
        ActionRecipe recipe = new ActionRecipe(
            "r", "打开页面", true, true, AutomationRiskLevel.LOW,
            Arrays.asList("打开页面"), "pkg", "Activity",
            ScreenFingerprint.empty(), ScreenFingerprint.empty(),
            Arrays.asList(), "agent_success", Arrays.asList(), new RecipeStats(0, 0, 0, 0, 0, ""),
            "v1", null);

        Assert.assertFalse(AutomationPolicy.canAutoBoost(recipe));
    }

    @Test
    public void recipeCannotAutoBoostWithOnlyEmptyEndConditionAnchors() throws Exception {
        ActionRecipe recipe = new ActionRecipe(
            "r", "打开页面", true, true, AutomationRiskLevel.LOW,
            Arrays.asList("打开页面"), "pkg", "Activity",
            ScreenFingerprint.empty(),
            new ScreenFingerprint("pkg", "Activity", Arrays.asList("", "   "), 1, 0, ""),
            Arrays.asList(), "agent_success", Arrays.asList(), new RecipeStats(0, 0, 0, 0, 0, ""),
            "v1", null);

        Assert.assertFalse(AutomationPolicy.canAutoBoost(recipe));
    }

    @Test
    public void selectorHighRiskTextMakesClickHighRiskAndBlocksAutoBoost() throws Exception {
        ActionStep step = new ActionStep(
            "s", "ui.click_text", new JSONObject().put("text", "项目"),
            Arrays.asList(new UiSelector("", "删除", "TextView", new int[]{0, 0, 1, 1}, "", "", 80)),
            ScreenFingerprint.empty(), ScreenFingerprint.empty(), 3000, "fallback_agent");
        ActionRecipe recipe = new ActionRecipe(
            "r", "删除入口", true, true, AutomationRiskLevel.LOW,
            Arrays.asList("打开页面"), "pkg", "Activity",
            ScreenFingerprint.empty(),
            new ScreenFingerprint("pkg", "Activity", Arrays.asList("完成"), 1, 0, ""),
            Arrays.asList(step), "agent_success", Arrays.asList("task"),
            new RecipeStats(0, 0, 0, 0, 0, ""), "v1", null);

        Assert.assertEquals(AutomationRiskLevel.HIGH, AutomationPolicy.classifyStep(step));
        Assert.assertFalse(AutomationPolicy.canAutoBoost(recipe));
    }

    @Test
    public void nonTextArgumentHighRiskWordMakesStepHighRisk() throws Exception {
        ActionStep step = new ActionStep(
            "s", "ui.click_text", new JSONObject().put("label", "删除"),
            Arrays.asList(new UiSelector("项目", "", "TextView", new int[]{0, 0, 1, 1}, "", "", 80)),
            ScreenFingerprint.empty(), ScreenFingerprint.empty(), 3000, "fallback_agent");

        Assert.assertEquals(AutomationRiskLevel.HIGH, AutomationPolicy.classifyStep(step));
    }

    @Test
    public void nestedArgumentHighRiskWordMakesStepHighRiskAndPrimitivesDoNotCrash() throws Exception {
        JSONObject arguments = new JSONObject()
            .put("enabled", true)
            .put("count", 3)
            .put("payload", new JSONObject()
                .put("items", new JSONArray().put("safe").put("token").put(7).put(false)));
        ActionStep step = new ActionStep(
            "s", "ui.click_text", arguments,
            Arrays.asList(new UiSelector("项目", "", "TextView", new int[]{0, 0, 1, 1}, "", "", 80)),
            ScreenFingerprint.empty(), ScreenFingerprint.empty(), 3000, "fallback_agent");

        Assert.assertEquals(AutomationRiskLevel.HIGH, AutomationPolicy.classifyStep(step));
    }

    @Test
    public void emptyStepRecipeClassifiesHighRisk() throws Exception {
        ActionRecipe recipe = new ActionRecipe(
            "r", "打开页面", true, true, AutomationRiskLevel.LOW,
            Arrays.asList("打开页面"), "pkg", "Activity",
            ScreenFingerprint.empty(),
            new ScreenFingerprint("pkg", "Activity", Arrays.asList("完成"), 1, 0, ""),
            Arrays.asList(), "agent_success", Arrays.asList("task"),
            new RecipeStats(0, 0, 0, 0, 0, ""), "v1", null);

        Assert.assertEquals(AutomationRiskLevel.HIGH, AutomationPolicy.classifyRecipe(recipe));
    }

    @Test
    public void zeroStepRecipeCannotAutoBoost() throws Exception {
        ActionRecipe recipe = new ActionRecipe(
            "r", "打开页面", true, true, AutomationRiskLevel.LOW,
            Arrays.asList("打开页面"), "pkg", "Activity",
            ScreenFingerprint.empty(),
            new ScreenFingerprint("pkg", "Activity", Arrays.asList("完成"), 1, 0, ""),
            Arrays.asList(), "agent_success", Arrays.asList("task"),
            new RecipeStats(0, 0, 0, 0, 0, ""), "v1", null);

        Assert.assertFalse(AutomationPolicy.canAutoBoost(recipe));
    }
}
