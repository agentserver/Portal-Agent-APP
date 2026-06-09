package com.termux.app.autotasks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoClaudeManagerScriptTest {

    @Test
    public void innerScriptDoesNotBlockOnInteractiveAuthPrompts() throws Exception {
        String script = AutoClaudeManager.buildInnerScriptForTest();

        assertTrue(script.contains("npm install -g @anthropic-ai/claude-code --include=optional"));
        assertTrue(script.contains("_chome=/home/claude"));
        assertTrue(script.contains("hasCompletedOnboarding"));
        assertTrue(script.contains("claude mcp add --transport http android-mcp"));

        assertFalse(script.contains("read -r _auth"));
        assertFalse(script.contains("read -r _key"));
        assertFalse(script.contains("read -r _ep"));
        assertFalse(script.contains("read -r _base"));
        assertFalse(script.contains("Anthropic API Key"));
    }
}
