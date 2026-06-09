package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

public class ProviderProfileTest {

    @Test
    public void claudeProfileMatchesExistingRuntime() {
        ProviderProfile p = ProviderProfile.forProvider(AssistantProvider.CLAUDE);

        Assert.assertEquals("Claude Code", p.displayName);
        Assert.assertEquals("claude", p.user);
        Assert.assertEquals("/home/claude", p.home);
        Assert.assertEquals("claude", p.cliBinary);
        Assert.assertEquals("ANTHROPIC_API_KEY", p.apiKeyEnv);
        Assert.assertEquals("ANTHROPIC_BASE_URL", p.baseUrlEnv);
        Assert.assertEquals("/home/claude/.claude/memory", p.memoryDir);
        Assert.assertEquals("/home/claude/.claude/commands", p.commandsDir);
        Assert.assertEquals("/home/claude/CLAUDE.md", p.instructionsFile);
    }

    @Test
    public void codexProfileUsesSeparateUserAndOpenAiKey() {
        ProviderProfile p = ProviderProfile.forProvider(AssistantProvider.CODEX);

        Assert.assertEquals("Codex", p.displayName);
        Assert.assertEquals("codex", p.user);
        Assert.assertEquals("/home/codex", p.home);
        Assert.assertEquals("codex", p.cliBinary);
        Assert.assertEquals("OPENAI_API_KEY", p.apiKeyEnv);
        Assert.assertEquals("", p.baseUrlEnv);
        Assert.assertEquals("", p.memoryDir);
        Assert.assertEquals("", p.commandsDir);
        Assert.assertEquals("/home/codex/AGENTS.md", p.instructionsFile);
    }

    @Test
    public void providerIdsRoundTrip() {
        Assert.assertEquals(AssistantProvider.CLAUDE, AssistantProvider.fromId("claude"));
        Assert.assertEquals(AssistantProvider.CODEX, AssistantProvider.fromId("codex"));
        Assert.assertEquals(AssistantProvider.CLAUDE, AssistantProvider.fromId(""));
        Assert.assertEquals(AssistantProvider.CLAUDE, AssistantProvider.fromId(null));
        Assert.assertEquals("codex", AssistantProvider.CODEX.id);
    }
}
