package com.termux.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ChatTranscriptStore {

    private final File mDir;

    public ChatTranscriptStore(Context context, AssistantProvider provider) {
        AssistantProvider safe = provider == null ? AssistantProvider.CODEX : provider;
        mDir = new File(context.getApplicationContext().getFilesDir(),
            "chat-transcripts/" + safe.id);
    }

    public void save(String id, List<ChatMessage> messages)
            throws java.io.IOException, org.json.JSONException {
        if (id == null || id.trim().isEmpty()) return;
        if (!mDir.exists() && !mDir.mkdirs()) {
            throw new java.io.IOException("Cannot create " + mDir.getAbsolutePath());
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(fileFor(id)), StandardCharsets.UTF_8))) {
            if (messages == null) return;
            for (ChatMessage message : messages) {
                if (message == null) continue;
                writer.write(toJson(message).toString());
                writer.write('\n');
            }
        }
    }

    public List<ChatMessage> load(String id) {
        List<ChatMessage> out = new ArrayList<>();
        if (id == null || id.trim().isEmpty()) return out;
        File file = fileFor(id);
        if (!file.isFile()) return out;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                ChatMessage message = fromJson(new JSONObject(line));
                if (message != null) out.add(message);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void delete(String id) {
        if (id == null || id.trim().isEmpty()) return;
        File file = fileFor(id);
        if (file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private File fileFor(String id) {
        return new File(mDir, safeId(id) + ".jsonl");
    }

    private static String safeId(String id) {
        return (id == null ? "" : id).replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static JSONObject toJson(ChatMessage message) throws org.json.JSONException {
        JSONObject obj = new JSONObject();
        obj.put("type", message.type.name());
        obj.put("content", message.content == null ? "" : message.content);
        obj.put("thinking", message.thinking == null ? "" : message.thinking);
        obj.put("thinkingCollapsed", message.thinkingCollapsed);
        obj.put("toolName", message.toolName == null ? "" : message.toolName);
        obj.put("toolDetail", message.toolDetail == null ? "" : message.toolDetail);
        obj.put("toolDetailCollapsed", message.toolDetailCollapsed);
        return obj;
    }

    private static ChatMessage fromJson(JSONObject obj) {
        if (obj == null) return null;
        ChatMessage.Type type;
        try {
            type = ChatMessage.Type.valueOf(obj.optString("type", ChatMessage.Type.SYSTEM.name()));
        } catch (Exception e) {
            type = ChatMessage.Type.SYSTEM;
        }
        ChatMessage message = new ChatMessage(type, obj.optString("content", ""));
        String thinking = obj.optString("thinking", "");
        message.thinking = thinking.isEmpty() ? null : thinking;
        message.thinkingCollapsed = obj.optBoolean("thinkingCollapsed", false);
        String toolName = obj.optString("toolName", "");
        message.toolName = toolName.isEmpty() ? null : toolName;
        String toolDetail = obj.optString("toolDetail", "");
        message.toolDetail = toolDetail.isEmpty() ? null : toolDetail;
        message.toolDetailCollapsed = obj.optBoolean("toolDetailCollapsed", true);
        return message;
    }
}
