package com.termux.app.autotasks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AndroidCapabilityPromptBuilderTest {

    @Test
    public void claudeInstructionsIncludeAndroidMcpAndMemoryRules() {
        String prompt = AndroidCapabilityPromptBuilder.buildClaudeInstructions();

        assertTrue(prompt.contains("Android"));
        assertTrue(prompt.contains("screen.capture"));
        assertTrue(prompt.contains("ui.tap"));
        assertTrue(prompt.contains("MCP"));
        assertTrue(prompt.contains("HTTP"));
        assertTrue(prompt.contains("~/.claude/memory"));
        assertTrue(prompt.contains("不要修改 `~/CLAUDE.md`"));
    }

    @Test
    public void codexInstructionsIncludeAndroidMcpAndAgentsRulesWithoutClaudeMemory() {
        String prompt = AndroidCapabilityPromptBuilder.buildCodexInstructions();

        assertTrue(prompt.contains("Android"));
        assertTrue(prompt.contains("screen.capture"));
        assertTrue(prompt.contains("ui.tap"));
        assertTrue(prompt.contains("MCP"));
        assertTrue(prompt.contains("HTTP"));
        assertTrue(prompt.contains("AGENTS.md"));
        assertFalse(prompt.contains("已自动注册"));
        assertFalse(prompt.contains("~/.claude/memory"));
        assertFalse(prompt.contains("CLAUDE.md"));
    }

    @Test
    public void codexAndroidSkillDescribesMcpTools() {
        String skill = AndroidCapabilityPromptBuilder.buildCodexAndroidSkill();

        assertTrue(skill.contains("name: android-phone"));
        assertTrue(skill.contains("android-mcp"));
        assertTrue(skill.contains("screen.capture"));
        assertTrue(skill.contains("ui.tap"));
        assertTrue(skill.contains("MCP 配置未生效"));
    }
}
