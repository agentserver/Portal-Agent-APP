package com.termux.app.automation;

import android.content.Context;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class AutomationStoreTest {

    @Test
    public void recipesCandidatesAndFailuresPersistInAppFiles() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore store = new AutomationStore(context);
        ActionRecipe recipe = recipe("recipe-1", "打开设置");

        store.saveRecipes(Arrays.asList(recipe));
        store.saveCandidates(Arrays.asList(recipe));
        store.appendFailure("recipe-1", "step-1", "Text not found", ScreenFingerprint.empty());

        Assert.assertEquals(1, store.loadRecipes().size());
        Assert.assertEquals("打开设置", store.loadRecipes().get(0).name);
        Assert.assertEquals(1, store.loadCandidates().size());
        Assert.assertEquals(1, store.loadFailures().size());
        Assert.assertTrue(store.loadFailures().get(0).optString("reason").contains("Text not found"));
    }

    @Test
    public void nullRecipeAndCandidateListsPersistAsEmptyArrays() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore store = new AutomationStore(context);

        store.saveRecipes(null);
        store.saveCandidates(null);

        Assert.assertTrue(store.loadRecipes().isEmpty());
        Assert.assertTrue(store.loadCandidates().isEmpty());
        Assert.assertEquals("[]", new String(Files.readAllBytes(new File(store.automationDir(), "recipes.json").toPath()),
            StandardCharsets.UTF_8));
        Assert.assertEquals("[]", new String(Files.readAllBytes(new File(store.automationDir(), "candidates.json").toPath()),
            StandardCharsets.UTF_8));
    }

    @Test
    public void multipleStoreInstancesSharePersistedRecipeCandidateAndFailureState() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore first = new AutomationStore(context);
        AutomationStore second = new AutomationStore(context);

        first.saveRecipes(Arrays.asList(recipe("recipe-1", "打开设置")));
        Assert.assertEquals("打开设置", second.loadRecipes().get(0).name);

        second.saveCandidates(Arrays.asList(recipe("candidate-1", "打开网络")));
        Assert.assertEquals("打开网络", first.loadCandidates().get(0).name);

        first.appendFailure("recipe-1", "step-1", "first", ScreenFingerprint.empty());
        second.appendFailure("candidate-1", "step-2", "second", ScreenFingerprint.empty());

        Assert.assertEquals(2, first.loadFailures().size());
        Assert.assertEquals(2, second.loadFailures().size());
    }

    @Test
    public void concurrentEditRecipesAcrossStoreInstancesPreservesBothUpdates() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore first = new AutomationStore(context);
        AutomationStore second = new AutomationStore(context);

        runConcurrentEdits(first, second, true);

        assertRecipeIds(first.loadRecipes(), "recipe-1", "recipe-2");
    }

    @Test
    public void concurrentEditCandidatesAcrossStoreInstancesPreservesBothUpdates() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore first = new AutomationStore(context);
        AutomationStore second = new AutomationStore(context);

        runConcurrentEdits(first, second, false);

        assertRecipeIds(first.loadCandidates(), "candidate-1", "candidate-2");
    }

    @Test
    public void corruptRecipesAndCandidatesLoadAsEmptyLists() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore store = new AutomationStore(context);
        File dir = store.automationDir();
        Assert.assertTrue(dir.mkdirs() || dir.isDirectory());
        Files.write(new File(dir, "recipes.json").toPath(), "not-json".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "candidates.json").toPath(), "{".getBytes(StandardCharsets.UTF_8));

        Assert.assertTrue(store.loadRecipes().isEmpty());
        Assert.assertTrue(store.loadCandidates().isEmpty());
    }

    @Test
    public void corruptFailureLinesAreSkipped() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        deleteDir(new File(context.getFilesDir(), "automation"));
        AutomationStore store = new AutomationStore(context);
        File dir = store.automationDir();
        Assert.assertTrue(dir.mkdirs() || dir.isDirectory());
        Files.write(new File(dir, "failures.jsonl").toPath(),
            ("not-json\n" + new org.json.JSONObject().put("reason", "ok").toString() + "\n").getBytes(StandardCharsets.UTF_8));

        Assert.assertEquals(1, store.loadFailures().size());
        Assert.assertEquals("ok", store.loadFailures().get(0).optString("reason"));
    }

    @Test
    public void settingsStorePersistsGlobalAndWhitelistFlags() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(AutomationSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit();
        AutomationSettingsStore store = new AutomationSettingsStore(context);

        store.setBoostEnabled(true);
        store.setAppWhitelisted("com.android.settings", true);
        store.setRecipeWhitelisted("recipe-1", true);

        AutomationSettingsStore next = new AutomationSettingsStore(context);
        Assert.assertTrue(next.isBoostEnabled());
        Assert.assertTrue(next.isAppWhitelisted("com.android.settings"));
        Assert.assertTrue(next.isRecipeWhitelisted("recipe-1"));
        Assert.assertFalse(next.isAppWhitelisted("com.example.other"));
    }

    @Test
    public void settingsStoreRemovesWhitelistEntriesWhenDisabled() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(AutomationSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit();
        AutomationSettingsStore store = new AutomationSettingsStore(context);

        store.setAppWhitelisted("com.android.settings", true);
        store.setRecipeWhitelisted("recipe-1", true);
        store.setAppWhitelisted("com.android.settings", false);
        store.setRecipeWhitelisted("recipe-1", false);

        Assert.assertFalse(store.isAppWhitelisted("com.android.settings"));
        Assert.assertFalse(store.isRecipeWhitelisted("recipe-1"));
    }

    private static void runConcurrentEdits(AutomationStore first, AutomationStore second, boolean recipes) throws Exception {
        ActionRecipe firstRecipe = recipe(recipes ? "recipe-1" : "candidate-1", "first");
        ActionRecipe secondRecipe = recipe(recipes ? "recipe-2" : "candidate-2", "second");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread firstThread = new Thread(() -> {
            try {
                edit(first, recipes, list -> {
                    list.add(firstRecipe);
                    firstEntered.countDown();
                    try {
                        Assert.assertFalse(secondFinished.await(150, TimeUnit.MILLISECONDS));
                        Assert.assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                });
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        Thread secondThread = new Thread(() -> {
            try {
                edit(second, recipes, list -> list.add(secondRecipe));
                secondFinished.countDown();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        firstThread.start();
        Assert.assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
        secondThread.start();
        Assert.assertFalse(secondFinished.await(150, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();
        firstThread.join(2000);
        secondThread.join(2000);

        Assert.assertFalse(firstThread.isAlive());
        Assert.assertFalse(secondThread.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        Assert.assertTrue(secondFinished.await(0, TimeUnit.MILLISECONDS));
    }

    private static List<ActionRecipe> edit(AutomationStore store, boolean recipes,
                                           AutomationStore.RecipeListEditor editor) {
        if (recipes) {
            return store.editRecipes(editor);
        }
        return store.editCandidates(editor);
    }

    private static void assertRecipeIds(List<ActionRecipe> recipes, String firstId, String secondId) {
        Set<String> ids = new HashSet<>();
        for (ActionRecipe recipe : recipes) {
            ids.add(recipe.id);
        }
        Assert.assertEquals(2, recipes.size());
        Assert.assertTrue(ids.contains(firstId));
        Assert.assertTrue(ids.contains(secondId));
    }

    private static ActionRecipe recipe(String id, String name) throws Exception {
        ScreenFingerprint end = new ScreenFingerprint("pkg", "Activity", Arrays.asList("完成"), 1, 0, "");
        return new ActionRecipe(id, name, true, false, AutomationRiskLevel.LOW,
            Arrays.asList(name), "pkg", "Activity", ScreenFingerprint.empty(), end,
            Arrays.asList(), "agent_success", Arrays.asList(), new RecipeStats(0, 0, 0, 0, 0, ""),
            "v1", null);
    }

    private static void deleteDir(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteDir(child);
        }
        file.delete();
    }
}
