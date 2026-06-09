package com.termux.app.loom;

import org.junit.Assert;
import org.junit.Test;

public class LoomConfigRendererTest {

    @Test
    public void defaultsIncludeLocalBootstrapApiKey() {
        LoomSettings settings = LoomSettings.defaults();

        Assert.assertEquals("loom-local-bootstrap-key", settings.workspaceApiKey);
    }

    @Test
    public void observerConfigContainsListenDbAndApiKey() {
        LoomSettings settings = LoomSettings.defaults()
            .withObserverListenAddr("127.0.0.1:8090")
            .withWorkspaceApiKey("secret-key");

        String yaml = LoomConfigRenderer.renderObserverConfig(settings, "/home/claude/.loom/observer-local");

        Assert.assertTrue(yaml.contains("listen_addr: \"127.0.0.1:8090\""));
        Assert.assertTrue(yaml.contains("db_path: \"/home/claude/.loom/observer-local/observer.db\""));
        Assert.assertTrue(yaml.contains("id: bootstrap"));
        Assert.assertTrue(yaml.contains("key: \"secret-key\""));
    }

    @Test
    public void driverConfigContainsAgentServerObserverAndClaudeBackend() {
        LoomSettings settings = LoomSettings.defaults()
            .withAgentServerUrl("https://agent.example.com")
            .withObserverUrl("http://127.0.0.1:8090")
            .withWorkspaceId("ws-phone")
            .withWorkspaceApiKey("secret-key")
            .withDriverName("driver-phone");

        String yaml = LoomConfigRenderer.renderDriverConfig(
            settings,
            "/home/claude/loom-driver",
            "/home/claude/.loom/driver-local");

        Assert.assertTrue(yaml.contains("server:\n"));
        Assert.assertFalse(yaml.contains("agentserver:"));
        Assert.assertTrue(yaml.contains("sandbox_id: \"\""));
        Assert.assertTrue(yaml.contains("tunnel_token: \"\""));
        Assert.assertTrue(yaml.contains("proxy_token: \"\""));
        Assert.assertTrue(yaml.contains("workspace_id: \"\""));
        Assert.assertTrue(yaml.contains("short_id: \"\""));
        Assert.assertTrue(yaml.contains("listen_addr: 127.0.0.1:0"));
        Assert.assertTrue(yaml.contains("display_name: \"driver-phone\""));
        Assert.assertTrue(yaml.contains("description: \"Android Loom driver (driver-phone)\""));
        Assert.assertTrue(yaml.contains("skills: []"));
        Assert.assertFalse(yaml.contains("    - chat"));
        Assert.assertFalse(yaml.contains("    - bash"));
        Assert.assertFalse(yaml.contains("    - file"));
        Assert.assertFalse(yaml.contains("    - register_mcp"));
        Assert.assertTrue(yaml.contains("url: \"https://agent.example.com\""));
        Assert.assertTrue(yaml.contains("name: \"driver-phone\""));
        Assert.assertTrue(yaml.contains("kind: claude"));
        Assert.assertTrue(yaml.contains("bin: \"\""));
        Assert.assertTrue(yaml.contains("extra_args: []"));
        Assert.assertTrue(yaml.contains("timeout_sec: 300"));
        Assert.assertTrue(yaml.contains("max_concurrency: 2"));
        Assert.assertTrue(yaml.contains("default_policy: \"\""));
        Assert.assertTrue(yaml.contains("subtask_defaults:\n    timeout_sec: 600\n    max_budget_usd: 0"));
        Assert.assertTrue(yaml.contains("target_display_name: \"\""));
        Assert.assertTrue(yaml.contains("task_timeout_sec: 600"));
        Assert.assertTrue(yaml.contains("disable_uid_check: true"));
        Assert.assertTrue(yaml.contains("max_dir_cache_entries: 50000"));
        Assert.assertTrue(yaml.contains("artifact_transport: observer_lazy"));
        Assert.assertFalse(yaml.contains("max_concurrency: 4"));
        Assert.assertFalse(yaml.contains("target_display_name: \"slave-phone\""));
        Assert.assertFalse(yaml.contains("task_timeout_sec: 300"));
        Assert.assertFalse(yaml.contains("artifact_transport: observer\n"));
        Assert.assertTrue(yaml.contains("workdir: \"/home/claude/loom-driver\""));
        Assert.assertTrue(yaml.contains("url: \"http://127.0.0.1:8090\""));
        Assert.assertTrue(yaml.contains("workspace_id: \"ws-phone\""));
        Assert.assertTrue(yaml.contains("agent_id: \"driver-phone\""));
        Assert.assertTrue(yaml.contains("api_key: \"secret-key\""));
        Assert.assertTrue(yaml.contains("token_state_path: \"/home/claude/.loom/driver-local/observer.token\""));
    }

    @Test
    public void codexDriverConfigContainsCodexBackendAndPaths() {
        LoomSettings settings = LoomSettings.defaults()
            .withAgentProvider(com.termux.app.AssistantProvider.CODEX)
            .withDriverName("driver-codex");

        String yaml = LoomConfigRenderer.renderDriverConfig(
            settings,
            "/home/codex/loom-driver",
            "/home/codex/.loom/driver-local");

        Assert.assertTrue(yaml.contains("kind: codex"));
        Assert.assertTrue(yaml.contains("codex:\n"));
        Assert.assertTrue(yaml.contains("bin: codex"));
        Assert.assertTrue(yaml.contains("workdir: \"/home/codex/loom-driver\""));
        Assert.assertFalse(yaml.contains("claude:\n"));
    }

    @Test
    public void slaveConfigContainsSkillsResourcesAndTags() {
        LoomSettings settings = LoomSettings.defaults()
            .withAgentServerUrl("https://agent.example.com")
            .withObserverUrl("http://127.0.0.1:8090")
            .withWorkspaceId("ws-phone")
            .withWorkspaceApiKey("secret-key")
            .withSlaveName("slave-phone")
            .withTags("android,phone,aarch64");

        String yaml = LoomConfigRenderer.renderSlaveConfig(
            settings,
            "/home/claude/.loom/slave-local",
            8,
            "aarch64",
            6);

        Assert.assertTrue(yaml.contains("server:\n"));
        Assert.assertFalse(yaml.contains("agentserver:"));
        Assert.assertTrue(yaml.contains("sandbox_id: \"\""));
        Assert.assertTrue(yaml.contains("tunnel_token: \"\""));
        Assert.assertTrue(yaml.contains("proxy_token: \"\""));
        Assert.assertTrue(yaml.contains("short_id: \"\""));
        Assert.assertTrue(yaml.contains("mcp_servers: {}"));
        Assert.assertTrue(yaml.contains("display_name: \"slave-phone\""));
        Assert.assertTrue(yaml.contains("description: \"Android Loom slave (slave-phone)\""));
        Assert.assertTrue(yaml.contains("name: \"slave-phone\""));
        Assert.assertTrue(yaml.contains("- chat"));
        Assert.assertTrue(yaml.contains("- bash"));
        Assert.assertTrue(yaml.contains("- file"));
        Assert.assertTrue(yaml.contains("- register_mcp"));
        Assert.assertTrue(yaml.contains("bin: \"\""));
        Assert.assertTrue(yaml.contains("extra_args: []"));
        Assert.assertTrue(yaml.contains("timeout_sec: 300"));
        Assert.assertTrue(yaml.contains("max_concurrency: 1"));
        Assert.assertTrue(yaml.contains("default_policy: best_effort"));
        Assert.assertFalse(yaml.contains("max_concurrency: 4"));
        Assert.assertFalse(yaml.contains("default_policy: local"));
        Assert.assertTrue(yaml.contains("cores: 8"));
        Assert.assertTrue(yaml.contains("arch: \"aarch64\""));
        Assert.assertTrue(yaml.contains("memory_gb: 6"));
        Assert.assertTrue(yaml.contains("resources:\n  cpu:\n    cores: 8\n    arch: \"aarch64\"\n  memory_gb: 6\n  tags:\n"));
        Assert.assertFalse(yaml.contains("\ntags:\n"));
        Assert.assertTrue(yaml.contains("- \"android\""));
        Assert.assertTrue(yaml.contains("- \"phone\""));
        Assert.assertTrue(yaml.contains("- \"aarch64\""));
    }

    @Test
    public void codexSlaveConfigContainsCodexBackendAndPaths() {
        LoomSettings settings = LoomSettings.defaults()
            .withAgentProvider(com.termux.app.AssistantProvider.CODEX)
            .withSlaveName("slave-codex");

        String yaml = LoomConfigRenderer.renderSlaveConfig(
            settings,
            "/home/codex/.loom/slave-local",
            1,
            "aarch64",
            1);

        Assert.assertTrue(yaml.contains("kind: codex"));
        Assert.assertTrue(yaml.contains("codex:\n"));
        Assert.assertTrue(yaml.contains("bin: codex"));
        Assert.assertTrue(yaml.contains("workdir: \"/home/codex/.loom/slave-local\""));
        Assert.assertFalse(yaml.contains("claude:\n"));
    }

    @Test
    public void configQuotesYamlSensitiveScalars() {
        LoomSettings driverSettings = LoomSettings.defaults()
            .withAgentServerUrl("https://agent.example.com/root?x=a: b#frag")
            .withObserverUrl("http://127.0.0.1:8090/path#observer")
            .withWorkspaceId("ws: phone #1")
            .withWorkspaceApiKey("sec\"ret")
            .withDriverName("driver:phone #1");

        String driverYaml = LoomConfigRenderer.renderDriverConfig(
            driverSettings,
            "/home/claude/loom driver#1",
            "/home/claude/.loom/driver\\local");

        Assert.assertTrue(driverYaml.contains("url: \"https://agent.example.com/root?x=a: b#frag\""));
        Assert.assertTrue(driverYaml.contains("name: \"driver:phone #1\""));
        Assert.assertTrue(driverYaml.contains("display_name: \"driver:phone #1\""));
        Assert.assertTrue(driverYaml.contains("description: \"Android Loom driver (driver:phone #1)\""));
        Assert.assertTrue(driverYaml.contains("workdir: \"/home/claude/loom driver#1\""));
        Assert.assertTrue(driverYaml.contains("audit_log_dir: \"/home/claude/loom driver#1/logs\""));
        Assert.assertTrue(driverYaml.contains("workspace_id: \"ws: phone #1\""));
        Assert.assertTrue(driverYaml.contains("agent_id: \"driver:phone #1\""));
        Assert.assertTrue(driverYaml.contains("api_key: \"sec\\\"ret\""));
        Assert.assertTrue(driverYaml.contains("token_state_path: \"/home/claude/.loom/driver\\\\local/observer.token\""));

        LoomSettings slaveSettings = LoomSettings.defaults()
            .withAgentServerUrl("https://agent.example.com/root?x=a: b#frag")
            .withObserverUrl("http://127.0.0.1:8090/path#observer")
            .withWorkspaceId("ws: phone #1")
            .withWorkspaceApiKey("sec\"ret")
            .withSlaveName("slave:phone #1")
            .withTags("on,null,a: b,foo #bar");

        String slaveYaml = LoomConfigRenderer.renderSlaveConfig(
            slaveSettings,
            "/home/claude/.loom/slave\\local",
            8,
            "arm64: v8 #cpu",
            6);

        Assert.assertTrue(slaveYaml.contains("name: \"slave:phone #1\""));
        Assert.assertTrue(slaveYaml.contains("description: \"Android Loom slave (slave:phone #1)\""));
        Assert.assertTrue(slaveYaml.contains("workdir: \"/home/claude/.loom/slave\\\\local\""));
        Assert.assertTrue(slaveYaml.contains("arch: \"arm64: v8 #cpu\""));
        Assert.assertTrue(slaveYaml.contains("agent_id: \"slave:phone #1\""));
        Assert.assertTrue(slaveYaml.contains("api_key: \"sec\\\"ret\""));
        Assert.assertTrue(slaveYaml.contains("token_state_path: \"/home/claude/.loom/slave\\\\local/observer.token\""));
        Assert.assertTrue(slaveYaml.contains("- \"on\""));
        Assert.assertTrue(slaveYaml.contains("- \"null\""));
        Assert.assertTrue(slaveYaml.contains("- \"a: b\""));
        Assert.assertTrue(slaveYaml.contains("- \"foo #bar\""));
    }
}
