package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class WorkspaceAccessSettingsStore {

    public static final String PREFS_NAME = "workspace_access_settings";
    private static final String KEY_ALLOWED_APPS = "allowed_apps";

    public static final String[] DEFAULT_ANDROID_DIRS = {
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Documents",
        "/storage/emulated/0/Pictures",
        "/storage/emulated/0/DCIM",
        "/storage/emulated/0/Movies",
        "/storage/emulated/0/Music"
    };

    private final SharedPreferences prefs;

    public WorkspaceAccessSettingsStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public Set<String> allowedApps() {
        return new HashSet<>(prefs.getStringSet(KEY_ALLOWED_APPS, Collections.emptySet()));
    }

    public boolean isAppAllowed(String packageName) {
        String safe = clean(packageName);
        return !safe.isEmpty() && allowedApps().contains(safe);
    }

    public void setAppAllowed(String packageName, boolean allowed) {
        String safe = clean(packageName);
        if (safe.isEmpty()) return;
        Set<String> apps = allowedApps();
        if (allowed) {
            apps.add(safe);
        } else {
            apps.remove(safe);
        }
        prefs.edit().putStringSet(KEY_ALLOWED_APPS, apps).apply();
    }

    public int allowedAppCount() {
        return allowedApps().size();
    }

    public static String ubuntuUserScope(AssistantProvider provider) {
        ProviderProfile profile = ProviderProfile.forProvider(
            provider == null ? AssistantProvider.CODEX : provider);
        return profile.home + "/**";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
