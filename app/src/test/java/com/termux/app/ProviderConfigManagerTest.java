package com.termux.app;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class ProviderConfigManagerTest {

    @Test
    public void providerSettingsStoreWritesUbuntuSelectedProviderFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File ubuntu = new File(context.getFilesDir(), "usr/var/lib/proot-distro/installed-rootfs/ubuntu");
        new File(ubuntu, "home/claude").mkdirs();

        ProviderSettingsStore store = new ProviderSettingsStore(context);
        store.setSelectedProvider(AssistantProvider.CODEX);

        File providerFile = new File(context.getFilesDir(), "home/.assistant-provider");
        Assert.assertTrue(providerFile.isFile());
        Assert.assertEquals("codex",
            new String(Files.readAllBytes(providerFile.toPath()), StandardCharsets.UTF_8).trim());
    }

    @Test
    public void providerSettingsStoreBackfillsProviderFileWhenRead() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ProviderSettingsStore store = new ProviderSettingsStore(context);
        store.setSelectedProvider(AssistantProvider.CODEX);

        File providerFile = new File(context.getFilesDir(), "home/.assistant-provider");
        Files.delete(providerFile.toPath());

        ProviderSettingsStore restored = new ProviderSettingsStore(context);
        Assert.assertEquals(AssistantProvider.CODEX, restored.getSelectedProvider());
        Assert.assertEquals("codex",
            new String(Files.readAllBytes(providerFile.toPath()), StandardCharsets.UTF_8).trim());
    }

    @Test
    public void claudeSettingsPreserveExistingAndWriteEnvModelAndBaseUrl() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File claudeDir = new File(context.getFilesDir(),
            "usr/var/lib/proot-distro/installed-rootfs/ubuntu/home/claude/.claude");
        claudeDir.mkdirs();
        File settings = new File(claudeDir, "settings.json");
        Files.write(settings.toPath(),
            ("{\"mcpServers\":{\"android-mcp\":{\"type\":\"http\"}},\"theme\":\"dark\"}")
                .getBytes(StandardCharsets.UTF_8));

        ProviderConfigManager.writeClaudeSettings(context,
            new ProviderConfigManager.ProviderConfig("sk-ant", "https://api.example.com", "claude-sonnet-4"));

        String out = new String(Files.readAllBytes(settings.toPath()), StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(out);
        JSONObject env = json.getJSONObject("env");
        Assert.assertEquals("dark", json.getString("theme"));
        Assert.assertEquals("sk-ant", env.getString("ANTHROPIC_API_KEY"));
        Assert.assertEquals("https://api.example.com", env.getString("ANTHROPIC_BASE_URL"));
        Assert.assertEquals("claude-sonnet-4", json.getString("model"));
        Assert.assertTrue(new File(claudeDir, "settings.json.bak").isFile());
    }

    @Test
    public void codexConfigTomlWritesModelAndOpenAiEnv() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File codexDir = new File(context.getFilesDir(),
            "usr/var/lib/proot-distro/installed-rootfs/ubuntu/home/codex/.codex");
        codexDir.mkdirs();
        File config = new File(codexDir, "config.toml");
        Files.write(config.toPath(), "approval_policy = \"never\"\n".getBytes(StandardCharsets.UTF_8));

        ProviderConfigManager.writeCodexConfig(context,
            new ProviderConfigManager.ProviderConfig("sk-openai", "https://api.example.com/v1", "gpt-5-codex"));

        String out = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(out.contains("approval_policy = \"never\""));
        Assert.assertTrue(out.contains("model = \"gpt-5-codex\""));
        Assert.assertTrue(out.contains("model_provider = \"app_openai\""));
        Assert.assertTrue(out.contains("[model_providers.app_openai]"));
        Assert.assertTrue(out.contains("base_url = \"https://api.example.com/v1\""));
        Assert.assertTrue(out.contains("env_key = \"OPENAI_API_KEY\""));
        Assert.assertTrue(out.contains("wire_api = \"responses\""));
        Assert.assertTrue(out.contains("[mcp_servers.android-mcp]"));
        Assert.assertTrue(out.contains("type = \"streamable_http\""));
        Assert.assertTrue(out.contains("url = \"http://127.0.0.1:8765/mcp\""));
        Assert.assertTrue(out.contains("[env]"));
        Assert.assertTrue(out.contains("OPENAI_API_KEY = \"sk-openai\""));
        Assert.assertTrue(out.contains("OPENAI_BASE_URL = \"https://api.example.com/v1\""));
        Assert.assertTrue(new File(codexDir, "config.toml.bak").isFile());
    }

    @Test
    public void codexConfigPreservesExistingModelWhenNewModelIsBlank() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File codexDir = new File(context.getFilesDir(),
            "usr/var/lib/proot-distro/installed-rootfs/ubuntu/home/codex/.codex");
        codexDir.mkdirs();
        File config = new File(codexDir, "config.toml");
        Files.write(config.toPath(), "model = \"gpt-5.3-codex\"\n".getBytes(StandardCharsets.UTF_8));

        ProviderConfigManager.writeCodexConfig(context,
            new ProviderConfigManager.ProviderConfig("sk-openai", "https://api.example.com/v1", ""));

        String out = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(out.contains("model = \"gpt-5.3-codex\""));
        Assert.assertFalse(out.contains("model = \"gpt-5.1-codex\""));
    }

    @Test
    public void codexConfigReplacesStaleAndroidMcpServer() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File codexDir = new File(context.getFilesDir(),
            "usr/var/lib/proot-distro/installed-rootfs/ubuntu/home/codex/.codex");
        codexDir.mkdirs();
        File config = new File(codexDir, "config.toml");
        Files.write(config.toPath(),
            ("[mcp_servers.android-mcp]\n"
                + "type = \"stdio\"\n"
                + "command = \"old\"\n"
                + "url = \"http://stale.example.com/mcp\"\n")
                .getBytes(StandardCharsets.UTF_8));

        ProviderConfigManager.writeCodexConfig(context,
            new ProviderConfigManager.ProviderConfig("sk-openai", "https://api.example.com/v1", ""));

        String out = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(out.contains("[mcp_servers.android-mcp]"));
        Assert.assertTrue(out.contains("type = \"streamable_http\""));
        Assert.assertTrue(out.contains("url = \"http://127.0.0.1:8765/mcp\""));
        Assert.assertFalse(out.contains("command = \"old\""));
        Assert.assertFalse(out.contains("http://stale.example.com/mcp"));
    }
}
