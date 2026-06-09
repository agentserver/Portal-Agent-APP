package com.termux.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

public final class ProviderEnvironmentWriter {

    private ProviderEnvironmentWriter() {}

    public static void writeActiveKey(Context context, AssistantProvider provider, String key, String baseUrl)
            throws IOException {
        ProviderProfile p = ProviderProfile.forProvider(provider);
        File bashrc = new File(context.getApplicationContext().getFilesDir(),
            "usr/var/lib/proot-distro/installed-rootfs/ubuntu" + p.home + "/.bashrc");
        File parent = bashrc.getParentFile();
        if (parent == null || !parent.exists()) return;
        String existing = bashrc.exists()
            ? new String(Files.readAllBytes(bashrc.toPath()), StandardCharsets.UTF_8)
            : "";
        Files.write(bashrc.toPath(),
            rewriteBashrc(existing, provider, key, baseUrl).getBytes(StandardCharsets.UTF_8));
    }

    public static String rewriteBashrc(
        String content,
        AssistantProvider provider,
        String key,
        String baseUrl) {
        ProviderProfile p = ProviderProfile.forProvider(provider);
        StringBuilder kept = new StringBuilder();
        String[] lines = (content == null ? "" : content).split("\n", -1);
        for (String line : lines) {
            if (isManagedExport(line, p.apiKeyEnv)) continue;
            if (isManagedBaseUrlExport(line, p)) continue;
            kept.append(line).append("\n");
        }
        String trimmed = kept.toString().replaceAll("\n{2,}$", "\n");
        StringBuilder out = new StringBuilder(trimmed);
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        out.append("export ").append(p.apiKeyEnv).append("='").append(shellSingleQuote(key)).append("'\n");
        if (!p.baseUrlEnv.isEmpty() && baseUrl != null && !baseUrl.isEmpty()) {
            out.append("export ").append(p.baseUrlEnv).append("='")
                .append(shellSingleQuote(baseUrl)).append("'\n");
        } else if (p.provider == AssistantProvider.CODEX && baseUrl != null && !baseUrl.isEmpty()) {
            out.append("export OPENAI_BASE_URL='")
                .append(shellSingleQuote(baseUrl)).append("'\n");
        }
        return out.toString();
    }

    private static String shellSingleQuote(String value) {
        return (value == null ? "" : value).replace("'", "'\\''");
    }

    private static boolean isManagedExport(String line, String envName) {
        return line != null && line.matches("^export\\s+" + Pattern.quote(envName) + "=.*$");
    }

    private static boolean isManagedBaseUrlExport(String line, ProviderProfile profile) {
        if (!profile.baseUrlEnv.isEmpty() && isManagedExport(line, profile.baseUrlEnv)) return true;
        return profile.provider == AssistantProvider.CODEX && isManagedExport(line, "OPENAI_BASE_URL");
    }
}
