package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ProviderSettingsStore {

    public static final String PREFS_NAME = "assistant_provider";
    public static final String PROVIDER_FILE_REL = "home/.assistant-provider";
    private static final String KEY_SELECTED_PROVIDER = "selected_provider";

    private final SharedPreferences mPrefs;
    private final Context mContext;

    public ProviderSettingsStore(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public AssistantProvider getSelectedProvider() {
        AssistantProvider provider =
            AssistantProvider.fromId(mPrefs.getString(KEY_SELECTED_PROVIDER, AssistantProvider.CLAUDE.id));
        writeProviderFile(mContext, provider);
        return provider;
    }

    public void setSelectedProvider(AssistantProvider provider) {
        AssistantProvider safe = provider == null ? AssistantProvider.CLAUDE : provider;
        mPrefs.edit().putString(KEY_SELECTED_PROVIDER, safe.id).apply();
        writeProviderFile(mContext, safe);
    }

    public static void writeProviderFile(Context context, AssistantProvider provider) {
        if (context == null) return;
        AssistantProvider safe = provider == null ? AssistantProvider.CLAUDE : provider;
        File providerFile = new File(context.getApplicationContext().getFilesDir(), PROVIDER_FILE_REL);
        File parent = providerFile.getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            Files.write(providerFile.toPath(), (safe.id + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }
}
