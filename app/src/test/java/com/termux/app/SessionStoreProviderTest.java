package com.termux.app;

import android.content.Context;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class SessionStoreProviderTest {

    @Test
    public void providerStoresAreIsolated() {
        Context context = RuntimeEnvironment.getApplication();
        clear(context, AssistantProvider.CLAUDE);
        clear(context, AssistantProvider.CODEX);

        SessionStore claude = new SessionStore(context, AssistantProvider.CLAUDE);
        SessionStore codex = new SessionStore(context, AssistantProvider.CODEX);

        claude.add("claude-1", 100L, "claude prompt");
        codex.add("codex-1", 200L, "codex prompt");

        Assert.assertEquals(1, new SessionStore(context, AssistantProvider.CLAUDE).loadAll().size());
        Assert.assertEquals("claude-1", new SessionStore(context, AssistantProvider.CLAUDE).loadAll().get(0).id);
        Assert.assertEquals(1, new SessionStore(context, AssistantProvider.CODEX).loadAll().size());
        Assert.assertEquals("codex-1", new SessionStore(context, AssistantProvider.CODEX).loadAll().get(0).id);
    }

    @Test
    public void defaultConstructorKeepsClaudeStoreForCompatibility() {
        Context context = RuntimeEnvironment.getApplication();
        clear(context, AssistantProvider.CLAUDE);

        new SessionStore(context).add("legacy", 100L, "legacy prompt");

        Assert.assertEquals("legacy",
            new SessionStore(context, AssistantProvider.CLAUDE).loadAll().get(0).id);
    }

    private static void clear(Context context, AssistantProvider provider) {
        context.getSharedPreferences(SessionStore.prefsNameForProvider(provider), Context.MODE_PRIVATE)
            .edit().clear().commit();
    }
}
