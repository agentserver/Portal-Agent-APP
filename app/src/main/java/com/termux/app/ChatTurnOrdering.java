package com.termux.app;

import java.util.List;

final class ChatTurnOrdering {

    private ChatTurnOrdering() {}

    static int currentTurnStart(List<ChatMessage> messages) {
        if (messages == null) return 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg != null && msg.type == ChatMessage.Type.USER) return i;
        }
        return -1;
    }

    static int findThinkingIndex(List<ChatMessage> messages) {
        if (messages == null) return -1;
        int start = currentTurnStart(messages);
        for (int i = start + 1; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (isThinkingCarrier(msg)) return i;
        }
        return -1;
    }

    static int findOutputIndex(List<ChatMessage> messages) {
        if (messages == null) return -1;
        int start = currentTurnStart(messages);
        for (int i = messages.size() - 1; i > start; i--) {
            ChatMessage msg = messages.get(i);
            if (isOutputAssistant(msg)) return i;
        }
        return -1;
    }

    static int findThinkingInsertIndex(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int start = currentTurnStart(messages);
        int index = start + 1;
        while (index < messages.size()) {
            ChatMessage msg = messages.get(index);
            if (msg == null || msg.type != ChatMessage.Type.SYSTEM) break;
            index++;
        }
        return index;
    }

    static int findToolInsertIndex(List<ChatMessage> messages) {
        int output = findOutputIndex(messages);
        return output >= 0 ? output : (messages == null ? 0 : messages.size());
    }

    static int findOutputInsertIndex(List<ChatMessage> messages) {
        return messages == null ? 0 : messages.size();
    }

    static boolean isThinkingCarrier(ChatMessage msg) {
        return msg != null
                && msg.type == ChatMessage.Type.ASSISTANT
                && msg.thinking != null
                && !msg.thinking.isEmpty()
                && isEmptyOrPlaceholder(msg.content);
    }

    static boolean isOutputAssistant(ChatMessage msg) {
        return msg != null
                && msg.type == ChatMessage.Type.ASSISTANT
                && !isThinkingCarrier(msg);
    }

    static boolean isToolMessage(ChatMessage msg) {
        return msg != null
                && (msg.type == ChatMessage.Type.TOOL_USE
                    || msg.type == ChatMessage.Type.TOOL_RESULT);
    }

    static boolean isEmptyOrPlaceholder(String content) {
        if (content == null) return true;
        String trimmed = content.trim();
        return trimmed.isEmpty() || "...".equals(trimmed) || "\u2026".equals(trimmed);
    }
}
