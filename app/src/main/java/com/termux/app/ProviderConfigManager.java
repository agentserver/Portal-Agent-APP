package com.termux.app;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class ProviderConfigManager {

    private ProviderConfigManager() {}

    private static final String CODEX_APP_PROVIDER = "app_openai";
    private static final String DEFAULT_CODEX_MODEL = "gpt-5.1-codex";
    private static final String ANDROID_MCP_SERVER = "android-mcp";
    private static final String ANDROID_MCP_URL = "http://127.0.0.1:8765/mcp";

    public static final class ProviderConfig {
        public final String apiKey;
        public final String baseUrl;
        public final String model;

        public ProviderConfig(String apiKey, String baseUrl, String model) {
            this.apiKey = apiKey == null ? "" : apiKey;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.model = model == null ? "" : model;
        }
    }

    public static File configFile(Context context, AssistantProvider provider) {
        File ubuntu = new File(context.getApplicationContext().getFilesDir(),
            "usr/var/lib/proot-distro/installed-rootfs/ubuntu");
        if (provider == AssistantProvider.CODEX) {
            return new File(ubuntu, "home/codex/.codex/config.toml");
        }
        return new File(ubuntu, "home/claude/.claude/settings.json");
    }

    public static String readRaw(Context context, AssistantProvider provider) throws IOException {
        File file = configFile(context, provider);
        if (!file.isFile()) return "";
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    public static void writeRaw(Context context, AssistantProvider provider, String content) throws IOException {
        File file = configFile(context, provider);
        ensureParent(file);
        backup(file);
        Files.write(file.toPath(), (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    public static void writeClaudeSettings(Context context, ProviderConfig config) throws IOException {
        File file = configFile(context, AssistantProvider.CLAUDE);
        ensureParent(file);
        backup(file);

        JSONObject settings = new JSONObject();
        if (file.isFile()) {
            String existing = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            try {
                settings = new JSONObject(existing);
            } catch (Exception ignored) {
                settings = new JSONObject();
            }
        }

        try {
            JSONObject env = settings.optJSONObject("env");
            if (env == null) {
                env = new JSONObject();
                settings.put("env", env);
            }
            if (!config.apiKey.isEmpty()) env.put("ANTHROPIC_API_KEY", config.apiKey);
            if (!config.baseUrl.isEmpty()) {
                env.put("ANTHROPIC_BASE_URL", config.baseUrl);
            } else {
                env.remove("ANTHROPIC_BASE_URL");
            }
            if (!config.model.isEmpty()) settings.put("model", config.model);

            Files.write(file.toPath(), settings.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Invalid Claude settings JSON", e);
        }
    }

    public static void writeCodexConfig(Context context, ProviderConfig config) throws IOException {
        File file = configFile(context, AssistantProvider.CODEX);
        ensureParent(file);
        backup(file);

        String existing = file.isFile()
            ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
            : "";
        String updated = rewriteCodexToml(existing, config);
        Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
    }

    static String rewriteCodexToml(String content, ProviderConfig config) {
        String existing = content == null ? "" : content;
        String existingModel = extractRootTomlString(existing, "model");
        List<String> kept = new ArrayList<>();
        List<String> env = new ArrayList<>();
        String section = "";
        String[] lines = existing.split("\\r?\\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if ("[env]".equals(trimmed)) {
                section = trimmed;
                continue;
            }
            if (("[model_providers." + CODEX_APP_PROVIDER + "]").equals(trimmed)) {
                section = trimmed;
                continue;
            }
            if (isAndroidMcpSection(trimmed)) {
                section = trimmed;
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed;
                kept.add(line);
                continue;
            }
            if ("[env]".equals(section)) {
                if (trimmed.startsWith("OPENAI_API_KEY")
                        || trimmed.startsWith("OPENAI_BASE_URL")) {
                    continue;
                }
                if (!trimmed.isEmpty()) env.add(line);
            } else if (("[model_providers." + CODEX_APP_PROVIDER + "]").equals(section)) {
                continue;
            } else if (isAndroidMcpSection(section)) {
                continue;
            } else {
                if (trimmed.startsWith("model =")) continue;
                if (trimmed.startsWith("model_provider =")) continue;
                if (trimmed.startsWith("openai_base_url =")) continue;
                if (!trimmed.isEmpty()) kept.add(line);
            }
        }

        StringBuilder out = new StringBuilder();
        String model = firstNonEmpty(config.model, existingModel, DEFAULT_CODEX_MODEL);
        out.append("model = \"").append(tomlEscape(model)).append("\"\n");
        if (!config.baseUrl.isEmpty()) {
            out.append("model_provider = \"").append(CODEX_APP_PROVIDER).append("\"\n");
            out.append("openai_base_url = \"").append(tomlEscape(config.baseUrl)).append("\"\n");
        }
        for (String line : kept) out.append(line).append('\n');
        if (!config.baseUrl.isEmpty()) {
            out.append("\n[model_providers.").append(CODEX_APP_PROVIDER).append("]\n");
            out.append("name = \"App OpenAI Compatible\"\n");
            out.append("base_url = \"").append(tomlEscape(config.baseUrl)).append("\"\n");
            out.append("env_key = \"OPENAI_API_KEY\"\n");
            out.append("wire_api = \"responses\"\n");
        }
        out.append("\n[mcp_servers.").append(ANDROID_MCP_SERVER).append("]\n");
        out.append("type = \"streamable_http\"\n");
        out.append("url = \"").append(ANDROID_MCP_URL).append("\"\n");
        out.append("\n[env]\n");
        if (!config.apiKey.isEmpty()) {
            out.append("OPENAI_API_KEY = \"").append(tomlEscape(config.apiKey)).append("\"\n");
        }
        if (!config.baseUrl.isEmpty()) {
            out.append("OPENAI_BASE_URL = \"").append(tomlEscape(config.baseUrl)).append("\"\n");
        }
        for (String line : env) out.append(line).append('\n');
        return out.toString().replaceAll("\\n{3,}", "\n\n");
    }

    private static void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent.getAbsolutePath());
        }
    }

    private static void backup(File file) throws IOException {
        if (file.isFile()) {
            Files.copy(file.toPath(), new File(file.getAbsolutePath() + ".bak").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String extractRootTomlString(String content, String key) {
        if (content == null || key == null || key.isEmpty()) return "";
        String prefix = key + " =";
        String[] lines = content.split("\\r?\\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) return "";
            if (!trimmed.startsWith(prefix)) continue;
            int first = trimmed.indexOf('"');
            int last = trimmed.lastIndexOf('"');
            if (first >= 0 && last > first) return trimmed.substring(first + 1, last);
            return "";
        }
        return "";
    }

    private static boolean isAndroidMcpSection(String section) {
        return ("[mcp_servers." + ANDROID_MCP_SERVER + "]").equals(section)
            || section.startsWith("[mcp_servers." + ANDROID_MCP_SERVER + ".");
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    @NonNull
    private static String tomlEscape(String value) {
        return (value == null ? "" : value)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }
}
