package com.termux.app.autotasks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoCodexManagerScriptTest {

    @Test
    public void innerScriptSetsUpCodexUserCliAndInstructionsNonInteractively() {
        String script = AutoCodexManager.buildInnerScriptForTest();

        assertTrue(script.contains("id codex >/dev/null 2>&1 || useradd -m -s /bin/bash codex"));
        assertTrue(script.contains("npm install -g @openai/codex"));
        assertTrue(script.contains("command -v codex"));
        assertTrue(script.contains("/home/codex/AGENTS.md"));
        assertTrue(script.contains("/home/codex/.codex/skills/android-phone/SKILL.md"));
        assertTrue(script.contains("[mcp_servers.android-mcp]"));
        assertTrue(script.contains("type = \"streamable_http\""));
        assertTrue(script.contains("url = \"http://127.0.0.1:8765/mcp\""));
        assertFalse(script.contains("OpenAI API Key"));
        assertFalse(script.contains("read -r _openai_key"));
        assertFalse(script.contains("export OPENAI_API_KEY"));
        assertFalse(script.contains("ANTHROPIC_API_KEY"));
        assertTrue(script.contains("sed -i '/.codex-setup/d' ~/.bashrc"));
        assertTrue(script.contains("rm -f ~/.codex-setup.sh"));
    }

    @Test
    public void innerScriptExitsBeforeOpenAiPromptWhenCodexSetupAlreadyCompleted() {
        String script = AutoCodexManager.buildInnerScriptForTest();

        int sentinel = script.indexOf("/home/codex/.codex/setup-complete");
        int earlyExit = script.indexOf("command -v codex >/dev/null 2>&1 && [ -f \"$CODEX_SETUP_SENTINEL\" ]");
        int sentinelTouch = script.indexOf("touch \"$CODEX_SETUP_SENTINEL\"");

        assertTrue("script should define a persistent setup-complete sentinel", sentinel >= 0);
        assertTrue("script should check installed Codex and sentinel before prompting", earlyExit >= 0);
        assertTrue("successful setup should leave the persistent sentinel",
            sentinelTouch >= 0);
    }

    @Test
    public void innerScriptDoesNotWriteOpenAiKeyFromRootHook() {
        String script = AutoCodexManager.buildInnerScriptForTest();

        assertFalse(script.contains("shell_quote()"));
        assertFalse(script.contains("_openai_key"));
        assertFalse(script.contains("export OPENAI_API_KEY=\\\"%s\\\""));
    }
}
