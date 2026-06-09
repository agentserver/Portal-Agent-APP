package com.termux.app;

import android.content.Context;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ApiKeyStoreProviderTest {

    @Test
    public void prefsNameDefaultsToClaudeAndUsesCodexStoreName() {
        Assert.assertEquals("api_keys_store", ApiKeyStore.prefsNameForProvider(null));
        Assert.assertEquals("api_keys_store", ApiKeyStore.prefsNameForProvider(AssistantProvider.CLAUDE));
        Assert.assertEquals("codex_api_keys", ApiKeyStore.prefsNameForProvider(AssistantProvider.CODEX));
    }

    @Test
    public void existingConstructorUsesClaudeStore() {
        Context context = RuntimeEnvironment.getApplication();
        clear(context, AssistantProvider.CLAUDE);

        ApiKeyStore legacy = new ApiKeyStore(context);
        legacy.add("claude", "sk-ant", "https://api.anthropic.com");

        Assert.assertEquals(1, new ApiKeyStore(context, AssistantProvider.CLAUDE).loadAll().size());
        Assert.assertEquals(0, new ApiKeyStore(context, AssistantProvider.CODEX).loadAll().size());
    }

    @Test
    public void codexKeysAreIsolatedFromClaudeKeys() {
        Context context = RuntimeEnvironment.getApplication();
        clear(context, AssistantProvider.CLAUDE);
        clear(context, AssistantProvider.CODEX);

        ApiKeyStore claude = new ApiKeyStore(context, AssistantProvider.CLAUDE);
        ApiKeyStore codex = new ApiKeyStore(context, AssistantProvider.CODEX);
        claude.add("claude", "sk-ant", "");
        codex.add("codex", "sk-openai", "");

        Assert.assertEquals("sk-ant", claude.loadAll().get(0).value);
        Assert.assertEquals("sk-openai", codex.loadAll().get(0).value);
        Assert.assertNotEquals(claude.loadAll().get(0).id, codex.loadAll().get(0).id);
    }

    @Test
    public void activeIdsAreIsolatedByProvider() {
        Context context = RuntimeEnvironment.getApplication();
        clear(context, AssistantProvider.CLAUDE);
        clear(context, AssistantProvider.CODEX);

        ApiKeyStore claude = new ApiKeyStore(context, AssistantProvider.CLAUDE);
        ApiKeyStore.Entry c = claude.add("claude", "sk-ant", "");
        ApiKeyStore codex = new ApiKeyStore(context, AssistantProvider.CODEX);
        ApiKeyStore.Entry x = codex.add("codex", "sk-openai", "");
        claude.setActiveId(c.id);
        codex.setActiveId(x.id);

        Assert.assertEquals(c.id, new ApiKeyStore(context, AssistantProvider.CLAUDE).getActiveId());
        Assert.assertEquals(x.id, new ApiKeyStore(context, AssistantProvider.CODEX).getActiveId());
    }

    private static void clear(Context context, AssistantProvider provider) {
        context.getSharedPreferences(ApiKeyStore.prefsNameForProvider(provider), Context.MODE_PRIVATE)
            .edit().clear().commit();
    }
}
