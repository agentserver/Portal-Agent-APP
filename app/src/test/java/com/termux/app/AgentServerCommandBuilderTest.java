package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

public class AgentServerCommandBuilderTest {

    @Test
    public void claudeScriptPreservesExistingCommandAndEnv() {
        AgentServerCommandBuilder.Config c = new AgentServerCommandBuilder.Config(
            "https://agent.example.com", "sandbox-1", "phone", "sk-ant", "https://api.example.com");

        String script = AgentServerCommandBuilder.connectScript(
            AssistantProvider.CLAUDE,
            c,
            "/data/data/com.termux/files/usr");

        Assert.assertTrue(script.contains("agentserver claudecode"));
        Assert.assertTrue(script.contains("ANTHROPIC_API_KEY='sk-ant'"));
        Assert.assertTrue(script.contains("ANTHROPIC_BASE_URL='https://api.example.com'"));
        Assert.assertTrue(script.contains("--user claude"));
        Assert.assertTrue(script.contains("/home/claude/.local/bin"));
        Assert.assertTrue(script.contains(".agentserver-pipe.jsonl"));
        Assert.assertTrue(script.contains("--resume 'sandbox-1'"));
        Assert.assertTrue(script.contains("--name 'phone'"));
        Assert.assertFalse(script.contains("OPENAI_API_KEY"));
    }

    @Test
    public void codexScriptUsesOpenAiEnvAndSupportProbe() {
        AgentServerCommandBuilder.Config c = new AgentServerCommandBuilder.Config(
            "https://agent.example.com", "", "phone", "sk-openai", "");

        String script = AgentServerCommandBuilder.connectScript(
            AssistantProvider.CODEX,
            c,
            "/data/data/com.termux/files/usr");

        Assert.assertTrue(script.contains("OPENAI_API_KEY='sk-openai'"));
        Assert.assertTrue(script.contains("--user codex"));
        Assert.assertTrue(script.contains("agentserver help"));
        Assert.assertTrue(script.contains("grep -Eq '(^|[[:space:]])codexcode([[:space:]]|\\$)'"));
        Assert.assertTrue(script.contains("grep -Eq '(^|[[:space:]])codex([[:space:]]|\\$)'"));
        Assert.assertTrue(script.contains("_agentserver_subcommand=codexcode"));
        Assert.assertTrue(script.contains("_agentserver_subcommand=codex"));
        Assert.assertTrue(script.contains("agentserver \\$_agentserver_subcommand"));
        Assert.assertTrue(script.contains("agentserver-codex-agent.log"));
        Assert.assertFalse(script.contains("ANTHROPIC_API_KEY"));
        Assert.assertFalse(script.contains(".agentserver-pipe.jsonl"));
    }

    @Test
    public void shellValuesAreQuoted() {
        AgentServerCommandBuilder.Config c = new AgentServerCommandBuilder.Config(
            "https://agent.example.com/path?a=1&b='two'",
            "sandbox'2",
            "phone'one",
            "sk'key",
            "https://api.example.com/v1'");

        String script = AgentServerCommandBuilder.connectScript(
            AssistantProvider.CLAUDE,
            c,
            "/data/data/com.termux/files/usr");

        Assert.assertTrue(script.contains("--server 'https://agent.example.com/path?a=1&b='\\\\''two'\\\\'''"));
        Assert.assertTrue(script.contains("--resume 'sandbox'\\\\''2'"));
        Assert.assertTrue(script.contains("--name 'phone'\\\\''one'"));
        Assert.assertTrue(script.contains("ANTHROPIC_API_KEY='sk'\\\\''key'"));
        Assert.assertTrue(script.contains("ANTHROPIC_BASE_URL='https://api.example.com/v1'\\\\'''"));
    }

    @Test
    public void providerProcessPatternsAreScoped() {
        Assert.assertEquals("agentserver claudecode([[:space:]]|$)",
            AgentServerCommandBuilder.processPattern(AssistantProvider.CLAUDE));
        Assert.assertEquals("agentserver codex(code)?([[:space:]]|$)",
            AgentServerCommandBuilder.processPattern(AssistantProvider.CODEX));
    }

    @Test
    public void codexProcessPatternRequiresCodexSubcommand() {
        String pattern = AgentServerCommandBuilder.processPattern(AssistantProvider.CODEX);
        String javaPattern = pattern.replace("[[:space:]]", "\\s");

        Assert.assertTrue(java.util.regex.Pattern.compile(javaPattern)
            .matcher("agentserver codex --server x").find());
        Assert.assertTrue(java.util.regex.Pattern.compile(javaPattern)
            .matcher("agentserver codexcode --server x").find());
        Assert.assertFalse(java.util.regex.Pattern.compile(javaPattern)
            .matcher("agentserver claudecode --server https://codex.example.com").find());
    }
}
