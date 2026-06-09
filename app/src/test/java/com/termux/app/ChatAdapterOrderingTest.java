package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapterOrderingTest {

    @Test
    public void currentTurnKeepsThinkingToolsThenOutput() {
        List<ChatMessage> messages = new ArrayList<>();

        messages.add(ChatMessage.user("inspect files"));
        messages.add(ChatMessage.assistant("..."));
        insertThinking(messages, "I should inspect the workspace.");
        upsertOutput(messages, "I will inspect files first.");
        insertTool(messages, ChatMessage.toolUse("Bash", "{\"cmd\":\"ls\"}"));
        insertTool(messages, ChatMessage.toolResult("Bash", "ok", "ok"));
        upsertOutput(messages, "The files look correct.");

        Assert.assertEquals(5, messages.size());
        Assert.assertEquals(ChatMessage.Type.USER, messages.get(0).type);
        Assert.assertEquals(ChatMessage.Type.ASSISTANT, messages.get(1).type);
        Assert.assertEquals("I should inspect the workspace.", messages.get(1).thinking);
        Assert.assertTrue(messages.get(1).content == null || messages.get(1).content.isEmpty());
        Assert.assertEquals(ChatMessage.Type.TOOL_USE, messages.get(2).type);
        Assert.assertEquals(ChatMessage.Type.TOOL_RESULT, messages.get(3).type);
        Assert.assertEquals(ChatMessage.Type.ASSISTANT, messages.get(4).type);
        Assert.assertEquals("The files look correct.", messages.get(4).content);
    }

    @Test
    public void toolMessagesInsertedBeforeExistingOutput() {
        List<ChatMessage> messages = new ArrayList<>();

        messages.add(ChatMessage.user("run command"));
        messages.add(ChatMessage.assistant("..."));
        upsertOutput(messages, "I can answer soon.");
        insertTool(messages, ChatMessage.toolUse("Bash", "{}"));

        Assert.assertEquals(ChatMessage.Type.USER, messages.get(0).type);
        Assert.assertEquals(ChatMessage.Type.TOOL_USE, messages.get(1).type);
        Assert.assertEquals(ChatMessage.Type.ASSISTANT, messages.get(2).type);
        Assert.assertEquals("I can answer soon.", messages.get(2).content);
    }

    private static void insertThinking(List<ChatMessage> messages, String thinking) {
        ChatMessage message = ChatMessage.assistant("");
        message.thinking = thinking;
        messages.add(ChatTurnOrdering.findThinkingInsertIndex(messages), message);
    }

    private static void insertTool(List<ChatMessage> messages, ChatMessage toolMessage) {
        messages.add(ChatTurnOrdering.findToolInsertIndex(messages), toolMessage);
    }

    private static void upsertOutput(List<ChatMessage> messages, String text) {
        int outputIndex = ChatTurnOrdering.findOutputIndex(messages);
        if (outputIndex >= 0) {
            messages.get(outputIndex).content = text;
        } else {
            messages.add(ChatTurnOrdering.findOutputInsertIndex(messages), ChatMessage.assistant(text));
        }
    }
}
