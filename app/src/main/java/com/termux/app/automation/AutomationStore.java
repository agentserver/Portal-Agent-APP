package com.termux.app.automation;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class AutomationStore {

    private static final String DIR_NAME = "automation";
    private static final String RECIPES_FILE = "recipes.json";
    private static final String CANDIDATES_FILE = "candidates.json";
    private static final String FAILURES_FILE = "failures.jsonl";
    private static final Object FILE_LOCK = new Object();

    private final Context context;

    public interface RecipeListEditor {
        void edit(List<ActionRecipe> recipes);
    }

    public AutomationStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<ActionRecipe> loadRecipes() {
        synchronized (FILE_LOCK) {
            return loadRecipeArrayLocked(new File(automationDir(), RECIPES_FILE));
        }
    }

    public void saveRecipes(List<ActionRecipe> recipes) {
        synchronized (FILE_LOCK) {
            saveRecipeArrayLocked(new File(automationDir(), RECIPES_FILE), recipes);
        }
    }

    public List<ActionRecipe> loadCandidates() {
        synchronized (FILE_LOCK) {
            return loadRecipeArrayLocked(new File(automationDir(), CANDIDATES_FILE));
        }
    }

    public void saveCandidates(List<ActionRecipe> candidates) {
        synchronized (FILE_LOCK) {
            saveRecipeArrayLocked(new File(automationDir(), CANDIDATES_FILE), candidates);
        }
    }

    public List<ActionRecipe> editRecipes(RecipeListEditor editor) {
        synchronized (FILE_LOCK) {
            return editRecipeArrayLocked(new File(automationDir(), RECIPES_FILE), editor);
        }
    }

    public List<ActionRecipe> editCandidates(RecipeListEditor editor) {
        synchronized (FILE_LOCK) {
            return editRecipeArrayLocked(new File(automationDir(), CANDIDATES_FILE), editor);
        }
    }

    public void updateRecipeStats(final String recipeId, final boolean success, final String failureReason) {
        if (recipeId == null || recipeId.isEmpty()) {
            return;
        }
        synchronized (FILE_LOCK) {
            File file = new File(automationDir(), RECIPES_FILE);
            List<ActionRecipe> recipes = loadRecipeArrayLocked(file);
            long now = System.currentTimeMillis();
            for (int i = 0; i < recipes.size(); i++) {
                ActionRecipe recipe = recipes.get(i);
                if (recipe == null || !recipeId.equals(recipe.id)) {
                    continue;
                }
                recipes.set(i, recipeWithUpdatedStats(recipe, success, failureReason, now));
                saveRecipeArrayLocked(file, recipes);
                return;
            }
        }
    }

    public void appendFailure(String recipeId, String stepId, String reason, ScreenFingerprint fingerprint) {
        synchronized (FILE_LOCK) {
            try {
                ensureDir();
                JSONObject json = new JSONObject();
                AutomationJson.put(json, "timestamp_ms", System.currentTimeMillis());
                AutomationJson.put(json, "recipe_id", recipeId);
                AutomationJson.put(json, "step_id", stepId);
                AutomationJson.put(json, "reason", reason);
                AutomationJson.put(json, "fingerprint",
                    (fingerprint == null ? ScreenFingerprint.empty() : fingerprint).toJson());
                String line = json.toString() + "\n";
                Files.write(new File(automationDir(), FAILURES_FILE).toPath(), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {
            }
        }
    }

    public List<JSONObject> loadFailures() {
        synchronized (FILE_LOCK) {
            List<JSONObject> failures = new ArrayList<>();
            File file = new File(automationDir(), FAILURES_FILE);
            if (!file.exists()) return failures;
            try {
                String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                String[] lines = raw.split("\\r?\\n");
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        failures.add(new JSONObject(line));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            return failures;
        }
    }

    public File automationDir() {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    private ActionRecipe recipeWithUpdatedStats(ActionRecipe recipe, boolean success, String failureReason, long now) {
        RecipeStats oldStats = recipe.stats == null ? RecipeStats.empty() : recipe.stats;
        RecipeStats newStats;
        boolean autoBoostEnabled = recipe.autoBoostEnabled;
        if (success) {
            newStats = new RecipeStats(
                oldStats.successCount + 1,
                oldStats.failureCount,
                now,
                oldStats.lastFailureAt,
                oldStats.averageDurationMs,
                oldStats.lastFailureReason);
        } else {
            int failureCount = oldStats.failureCount + 1;
            newStats = new RecipeStats(
                oldStats.successCount,
                failureCount,
                oldStats.lastSuccessAt,
                now,
                oldStats.averageDurationMs,
                failureReason);
            if (failureCount >= 2) {
                autoBoostEnabled = false;
            }
        }
        return new ActionRecipe(recipe.id, recipe.name, recipe.enabled, autoBoostEnabled,
            recipe.riskLevel, recipe.intentPatterns, recipe.targetPackage, recipe.targetActivity,
            recipe.startConditions, recipe.endConditions, recipe.steps, recipe.source,
            recipe.sourceTaskIds, newStats, recipe.currentVersionId, recipe.versionsCopy());
    }

    private List<ActionRecipe> editRecipeArrayLocked(File file, RecipeListEditor editor) {
        List<ActionRecipe> recipes = loadRecipeArrayLocked(file);
        if (editor == null) {
            return copyRecipes(recipes);
        }
        editor.edit(recipes);
        saveRecipeArrayLocked(file, recipes);
        return copyRecipes(recipes);
    }

    private List<ActionRecipe> loadRecipeArrayLocked(File file) {
        List<ActionRecipe> recipes = new ArrayList<>();
        if (!file.exists()) return recipes;
        try {
            JSONArray array = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject recipe = array.optJSONObject(i);
                if (recipe != null) {
                    recipes.add(ActionRecipe.fromJson(recipe));
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return recipes;
    }

    private void saveRecipeArrayLocked(File file, List<ActionRecipe> recipes) {
        Path tempPath = null;
        try {
            ensureDir();
            JSONArray array = new JSONArray();
            if (recipes != null) {
                for (ActionRecipe recipe : recipes) {
                    if (recipe != null) {
                        array.put(recipe.toJson());
                    }
                }
            }
            Path targetPath = file.toPath();
            tempPath = Files.createTempFile(automationDir().toPath(), file.getName(), ".tmp");
            Files.write(tempPath, array.toString().getBytes(StandardCharsets.UTF_8));
            moveIntoPlace(tempPath, targetPath);
            tempPath = null;
        } catch (Exception ignored) {
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private List<ActionRecipe> copyRecipes(List<ActionRecipe> recipes) {
        List<ActionRecipe> copy = new ArrayList<>();
        if (recipes == null) return copy;
        for (ActionRecipe recipe : recipes) {
            if (recipe != null) {
                copy.add(ActionRecipe.fromJson(recipe.toJson()));
            }
        }
        return copy;
    }

    private void moveIntoPlace(Path tempPath, Path targetPath) throws Exception {
        try {
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureDir() {
        File dir = automationDir();
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
    }
}
