package com.termux.app;

import android.content.Context;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ProviderScopedStoresTest {

    @Test
    public void uploadStoresAreIsolatedByProvider() {
        Context context = RuntimeEnvironment.getApplication();
        clear(context, UploadStore.prefsNameForProvider(AssistantProvider.CLAUDE));
        clear(context, UploadStore.prefsNameForProvider(AssistantProvider.CODEX));

        new UploadStore(context, AssistantProvider.CLAUDE).addFile("claude-session", "u1", "a.txt");
        new UploadStore(context, AssistantProvider.CODEX).addFile("codex-session", "u2", "b.txt");

        Assert.assertTrue(new UploadStore(context, AssistantProvider.CLAUDE)
            .getAll().containsKey("claude-session"));
        Assert.assertFalse(new UploadStore(context, AssistantProvider.CLAUDE)
            .getAll().containsKey("codex-session"));
        Assert.assertTrue(new UploadStore(context, AssistantProvider.CODEX)
            .getAll().containsKey("codex-session"));
        Assert.assertFalse(new UploadStore(context, AssistantProvider.CODEX)
            .getAll().containsKey("claude-session"));
    }

    @Test
    public void agentTaskStoresAreIsolatedByProvider() {
        Context context = RuntimeEnvironment.getApplication();
        clear(context, AgentTaskStore.prefsNameForProvider(AssistantProvider.CLAUDE));
        clear(context, AgentTaskStore.prefsNameForProvider(AssistantProvider.CODEX));

        AgentTask claudeTask = new AgentTask();
        claudeTask.id = "claude-task";
        claudeTask.prompt = "claude";
        AgentTask codexTask = new AgentTask();
        codexTask.id = "codex-task";
        codexTask.prompt = "codex";

        new AgentTaskStore(context, AssistantProvider.CLAUDE).upsert(claudeTask);
        new AgentTaskStore(context, AssistantProvider.CODEX).upsert(codexTask);

        Assert.assertNotNull(new AgentTaskStore(context, AssistantProvider.CLAUDE).get("claude-task"));
        Assert.assertNull(new AgentTaskStore(context, AssistantProvider.CLAUDE).get("codex-task"));
        Assert.assertNotNull(new AgentTaskStore(context, AssistantProvider.CODEX).get("codex-task"));
        Assert.assertNull(new AgentTaskStore(context, AssistantProvider.CODEX).get("claude-task"));
    }

    private static void clear(Context context, String prefsName) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit();
    }
}
