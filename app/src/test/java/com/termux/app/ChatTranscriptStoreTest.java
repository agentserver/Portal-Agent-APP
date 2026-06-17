package com.termux.app;

import android.content.Context;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ChatTranscriptStoreTest {

    @Test
    public void savesAndLoadsStructuredCodexTranscript() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ChatTranscriptStore store = new ChatTranscriptStore(context, AssistantProvider.CODEX);
        store.delete("codex-local");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("inspect"));
        ChatMessage thinking = ChatMessage.assistant("");
        thinking.thinking = "plan first";
        thinking.thinkingCollapsed = true;
        messages.add(thinking);
        messages.add(ChatMessage.toolUse("android.get_status", "{}"));
        messages.add(ChatMessage.toolResult("android.get_status", "ok", "{\"ok\":true}"));
        messages.add(ChatMessage.assistant("done"));

        store.save("codex-local", messages);
        List<ChatMessage> loaded = store.load("codex-local");

        Assert.assertEquals(messages.size(), loaded.size());
        Assert.assertEquals(ChatMessage.Type.USER, loaded.get(0).type);
        Assert.assertEquals("plan first", loaded.get(1).thinking);
        Assert.assertTrue(loaded.get(1).thinkingCollapsed);
        Assert.assertEquals(ChatMessage.Type.TOOL_USE, loaded.get(2).type);
        Assert.assertEquals("android.get_status", loaded.get(2).toolName);
        Assert.assertEquals(ChatMessage.Type.TOOL_RESULT, loaded.get(3).type);
        Assert.assertEquals("done", loaded.get(4).content);
    }
}
