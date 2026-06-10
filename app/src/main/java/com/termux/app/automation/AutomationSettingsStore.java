package com.termux.app.automation;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public final class AutomationSettingsStore {
    public static final String PREFS_NAME = "automation_boost";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_APP_WHITELIST = "app_whitelist";
    private static final String KEY_RECIPE_WHITELIST = "recipe_whitelist";

    private final SharedPreferences prefs;

    public AutomationSettingsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isBoostEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setBoostEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public boolean isAppWhitelisted(String packageName) {
        return prefs.getStringSet(KEY_APP_WHITELIST, new HashSet<String>()).contains(packageName);
    }

    public void setAppWhitelisted(String packageName, boolean enabled) {
        updateSet(KEY_APP_WHITELIST, packageName, enabled);
    }

    public boolean isRecipeWhitelisted(String recipeId) {
        return prefs.getStringSet(KEY_RECIPE_WHITELIST, new HashSet<String>()).contains(recipeId);
    }

    public void setRecipeWhitelisted(String recipeId, boolean enabled) {
        updateSet(KEY_RECIPE_WHITELIST, recipeId, enabled);
    }

    public Set<String> appWhitelist() {
        return new HashSet<>(prefs.getStringSet(KEY_APP_WHITELIST, new HashSet<String>()));
    }

    public Set<String> recipeWhitelist() {
        return new HashSet<>(prefs.getStringSet(KEY_RECIPE_WHITELIST, new HashSet<String>()));
    }

    private void updateSet(String key, String value, boolean enabled) {
        if (value == null || value.isEmpty()) return;
        Set<String> values = new HashSet<>(prefs.getStringSet(key, new HashSet<String>()));
        if (enabled) values.add(value); else values.remove(value);
        prefs.edit().putStringSet(key, values).apply();
    }
}
