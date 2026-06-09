package com.termux.app;

import android.content.Context;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ProviderSettingsStoreTest {

    @Test
    public void defaultsToClaude() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(ProviderSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit();

        ProviderSettingsStore store = new ProviderSettingsStore(context);

        Assert.assertEquals(AssistantProvider.CLAUDE, store.getSelectedProvider());
    }

    @Test
    public void persistsCodexSelection() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(ProviderSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit();

        ProviderSettingsStore store = new ProviderSettingsStore(context);
        store.setSelectedProvider(AssistantProvider.CODEX);

        Assert.assertEquals(AssistantProvider.CODEX,
            new ProviderSettingsStore(context).getSelectedProvider());
    }
}
