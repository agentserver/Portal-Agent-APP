package com.termux.app.automation;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class BoostExecutorTest {

    @Test
    public void executesStepsAndReportsSuccess() {
        RecordingRunner runner = new RecordingRunner();
        ActionStep first = step("step-1", "ui.click_text");
        ActionStep second = step("step-2", "ui.swipe");
        ActionRecipe recipe = recipe("recipe-1", "Open Settings", first, second);
        List<String> events = new ArrayList<>();
        BoostExecutor executor = new BoostExecutor(runner, null);

        BoostResult result = executor.execute(recipe, new BoostExecutor.Callback() {
            @Override
            public void onStep(String recipeName, int index, int total, String toolName) {
                events.add(recipeName + ":" + index + "/" + total + ":" + toolName);
            }

            @Override
            public void onCompleted(String recipeName) {
                events.add("completed:" + recipeName);
            }

            @Override
            public void onFailed(String recipeName, String reason) {
                events.add("failed:" + recipeName + ":" + reason);
            }
        });

        Assert.assertTrue(result.success);
        Assert.assertEquals("recipe-1", result.recipeId);
        Assert.assertEquals(Arrays.asList(first.id, second.id), runner.stepIds);
        Assert.assertEquals(Arrays.asList(
            "Open Settings:1/2:ui.click_text",
            "Open Settings:2/2:ui.swipe",
            "completed:Open Settings"), events);
    }

    @Test
    public void stopsAtFirstFailure() {
        RecordingRunner runner = new RecordingRunner();
        ActionStep first = step("step-1", "ui.click_text");
        ActionStep second = step("step-2", "ui.swipe");
        ActionStep third = step("step-3", "ui.click_text");
        runner.failOnStepId = "step-2";
        ActionRecipe recipe = recipe("recipe-1", "Open Settings", first, second, third);
        List<String> failures = new ArrayList<>();
        BoostExecutor executor = new BoostExecutor(runner, null);

        BoostResult result = executor.execute(recipe, new BoostExecutor.Callback() {
            @Override
            public void onStep(String recipeName, int index, int total, String toolName) {
            }

            @Override
            public void onCompleted(String recipeName) {
            }

            @Override
            public void onFailed(String recipeName, String reason) {
                failures.add(recipeName + ":" + reason);
            }
        });

        Assert.assertFalse(result.success);
        Assert.assertTrue(result.reason.contains("ui.swipe"));
        Assert.assertEquals(Arrays.asList(first.id, second.id), runner.stepIds);
        Assert.assertEquals(1, failures.size());
        Assert.assertTrue(failures.get(0).contains("ui.swipe"));
    }

    @Test
    public void failedPostconditionRecordsFailureWhenStoreProvided() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore store = new AutomationStore(context);
        RecordingRunner runner = new RecordingRunner();
        runner.matchesResult = false;
        ActionStep failing = step("step-post", "ui.click_text",
            new ScreenFingerprint("pkg", "Activity", Arrays.asList("Done"), 1, 0, ""));
        ActionRecipe recipe = recipe("recipe-post", "Open Settings", failing);
        BoostExecutor executor = new BoostExecutor(runner, store);

        BoostResult result = executor.execute(recipe, BoostExecutor.Callback.NOOP);

        Assert.assertFalse(result.success);
        Assert.assertEquals(1, store.loadFailures().size());
        JSONObject failure = store.loadFailures().get(0);
        Assert.assertEquals("recipe-post", failure.optString("recipe_id"));
        Assert.assertEquals("step-post", failure.optString("step_id"));
        Assert.assertTrue(failure.optString("reason").contains("postcondition"));
    }

    @Test
    public void callbackStepAndCompletedFailuresDoNotFailRecipe() {
        RecordingRunner runner = new RecordingRunner();
        ActionStep first = step("step-1", "ui.click_text");
        ActionStep second = step("step-2", "ui.swipe");
        ActionRecipe recipe = recipe("recipe-1", "Open Settings", first, second);
        BoostExecutor executor = new BoostExecutor(runner, null);

        BoostResult result = executor.execute(recipe, new BoostExecutor.Callback() {
            @Override
            public void onStep(String recipeName, int index, int total, String toolName) {
                throw new RuntimeException("onStep failed");
            }

            @Override
            public void onCompleted(String recipeName) {
                throw new RuntimeException("onCompleted failed");
            }

            @Override
            public void onFailed(String recipeName, String reason) {
                throw new RuntimeException("onFailed should not be called");
            }
        });

        Assert.assertTrue(result.success);
        Assert.assertEquals(Arrays.asList(first.id, second.id), runner.stepIds);
    }

    @Test
    public void callbackFailedFailureDoesNotOverrideOriginalFailure() {
        RecordingRunner runner = new RecordingRunner();
        ActionStep failing = step("step-1", "ui.click_text");
        runner.failOnStepId = "step-1";
        ActionRecipe recipe = recipe("recipe-1", "Open Settings", failing);
        BoostExecutor executor = new BoostExecutor(runner, null);

        BoostResult result = executor.execute(recipe, new BoostExecutor.Callback() {
            @Override
            public void onStep(String recipeName, int index, int total, String toolName) {
            }

            @Override
            public void onCompleted(String recipeName) {
            }

            @Override
            public void onFailed(String recipeName, String reason) {
                throw new RuntimeException("callback failure");
            }
        });

        Assert.assertFalse(result.success);
        Assert.assertEquals("ui.click_text failed", result.reason);
    }

    @Test
    public void ineligibleRecipesAreRejectedWithoutRunningOrRecordingFailure() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore store = new AutomationStore(context);
        ActionStep step = step("step-1", "ui.click_text");
        List<ActionRecipe> recipes = Arrays.asList(
            recipe("disabled", "Disabled", false, true, AutomationRiskLevel.LOW, step),
            recipe("non-auto", "Non Auto", true, false, AutomationRiskLevel.LOW, step),
            recipe("high-risk", "High Risk", true, true, AutomationRiskLevel.HIGH, step));

        for (ActionRecipe recipe : recipes) {
            RecordingRunner runner = new RecordingRunner();
            BoostExecutor executor = new BoostExecutor(runner, store);

            BoostResult result = executor.execute(recipe, BoostExecutor.Callback.NOOP);

            Assert.assertFalse(result.success);
            Assert.assertEquals("Recipe is not eligible for Automation Boost", result.reason);
            Assert.assertTrue(runner.stepIds.isEmpty());
        }
        Assert.assertTrue(store.loadFailures().isEmpty());
    }

    @Test
    public void repeatedFailureDisablesAutoBoost() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore store = new AutomationStore(context);
        RecordingRunner runner = new RecordingRunner();
        runner.failOnStepId = "step-1";
        ActionRecipe recipe = recipe("recipe-fail", "Open Settings", step("step-1", "ui.click_text"));
        store.saveRecipes(Collections.singletonList(recipe));
        BoostExecutor executor = new BoostExecutor(runner, store);

        executor.execute(recipe, BoostExecutor.Callback.NOOP);
        executor.execute(recipe, BoostExecutor.Callback.NOOP);

        ActionRecipe saved = store.loadRecipes().get(0);
        Assert.assertFalse(saved.autoBoostEnabled);
        Assert.assertEquals(2, saved.stats.failureCount);
        Assert.assertEquals(0, saved.stats.successCount);
        Assert.assertEquals("ui.click_text failed", saved.stats.lastFailureReason);
    }

    @Test
    public void successfulExecutionIncrementsSuccessCount() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore store = new AutomationStore(context);
        ActionRecipe recipe = recipe("recipe-success", "Open Settings", step("step-1", "ui.click_text"));
        store.saveRecipes(Collections.singletonList(recipe));
        BoostExecutor executor = new BoostExecutor(new RecordingRunner(), store);

        BoostResult result = executor.execute(recipe, BoostExecutor.Callback.NOOP);

        ActionRecipe saved = store.loadRecipes().get(0);
        Assert.assertTrue(result.success);
        Assert.assertTrue(saved.autoBoostEnabled);
        Assert.assertEquals(1, saved.stats.successCount);
        Assert.assertEquals(0, saved.stats.failureCount);
    }

    @Test
    public void ineligibleRecipeDoesNotUpdateStats() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore store = new AutomationStore(context);
        ActionRecipe recipe = recipe("recipe-disabled", "Disabled", true, false,
            AutomationRiskLevel.LOW, step("step-1", "ui.click_text"));
        store.saveRecipes(Collections.singletonList(recipe));
        BoostExecutor executor = new BoostExecutor(new RecordingRunner(), store);

        BoostResult result = executor.execute(recipe, BoostExecutor.Callback.NOOP);

        ActionRecipe saved = store.loadRecipes().get(0);
        Assert.assertFalse(result.success);
        Assert.assertEquals(0, saved.stats.successCount);
        Assert.assertEquals(0, saved.stats.failureCount);
        Assert.assertTrue(store.loadFailures().isEmpty());
    }

    @Test
    public void nullRecipeReturnsFailure() {
        BoostExecutor executor = new BoostExecutor(new RecordingRunner(), null);

        BoostResult result = executor.execute(null, null);

        Assert.assertFalse(result.success);
        Assert.assertEquals("", result.recipeId);
        Assert.assertEquals("Missing recipe", result.reason);
    }

    private static ActionRecipe recipe(String id, String name, ActionStep... steps) {
        return recipe(id, name, true, true, AutomationRiskLevel.LOW, steps);
    }

    private static ActionRecipe recipe(String id, String name, boolean enabled, boolean autoBoostEnabled,
                                       AutomationRiskLevel riskLevel, ActionStep... steps) {
        ScreenFingerprint end = new ScreenFingerprint("pkg", "Activity", Arrays.asList("Ready"), 1, 0, "");
        return new ActionRecipe(id, name, enabled, autoBoostEnabled, riskLevel,
            Arrays.asList(name), "pkg", "Activity", ScreenFingerprint.empty(), end,
            Arrays.asList(steps), "test", Collections.<String>emptyList(), RecipeStats.empty(), "v1", null);
    }

    private static ActionStep step(String id, String toolName) {
        return step(id, toolName, ScreenFingerprint.empty());
    }

    private static ActionStep step(String id, String toolName, ScreenFingerprint postconditions) {
        return new ActionStep(id, toolName, new JSONObject(), Collections.<UiSelector>emptyList(),
            ScreenFingerprint.empty(), postconditions, 1000, "");
    }

    private static void deleteDir(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteDir(child);
        }
        file.delete();
    }

    private static final class RecordingRunner implements AndroidActionRunner {
        final List<String> stepIds = new ArrayList<>();
        String failOnStepId = "";
        boolean matchesResult = true;

        @Override
        public void runStep(ActionStep step) throws Exception {
            stepIds.add(step.id);
            if (step.id.equals(failOnStepId)) {
                throw new IllegalStateException(step.toolName + " failed");
            }
        }

        @Override
        public ScreenFingerprint currentFingerprint() {
            return ScreenFingerprint.empty();
        }

        @Override
        public boolean matches(ScreenFingerprint expected) {
            return matchesResult;
        }
    }
}
