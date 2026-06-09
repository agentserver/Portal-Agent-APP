package com.termux.app;

import org.junit.Assert;
import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class CodexExecSessionTest {

    @After
    public void tearDown() {
        CodexExecSession.clearInstanceForTest();
    }

    @Test
    public void buildsCodexExecCommandForCodexUser() {
        List<String> cmd = CodexExecSession.command("/usr/bin/proot-distro");

        Assert.assertEquals("/usr/bin/proot-distro", cmd.get(0));
        Assert.assertTrue(cmd.contains("login"));
        Assert.assertTrue(cmd.contains("ubuntu"));
        Assert.assertTrue(cmd.contains("--user"));
        Assert.assertTrue(cmd.contains("codex"));

        int size = cmd.size();
        Assert.assertEquals("sh", cmd.get(size - 3));
        Assert.assertEquals("-lc", cmd.get(size - 2));
        Assert.assertTrue(cmd.get(size - 1).contains("cd /home/codex"));
        Assert.assertTrue(cmd.get(size - 1)
            .contains("codex exec --json --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -"));
    }

    @Test
    public void commandLoadsRuntimeEnvAndOverridesProviderForCustomBaseUrl() {
        List<String> cmd = CodexExecSession.command("/usr/bin/proot-distro", "https://api.example.com/v1");

        String script = cmd.get(cmd.size() - 1);
        Assert.assertTrue(script.contains(".codex-active-env"));
        Assert.assertTrue(script.contains("'model_provider=\"app_openai\"'"));
        Assert.assertTrue(script.contains("model_providers.app_openai="));
        Assert.assertTrue(script.contains("base_url=\"https://api.example.com/v1\""));
        Assert.assertTrue(script.contains("env_key=\"OPENAI_API_KEY\""));
        Assert.assertTrue(script.contains("wire_api=\"responses\""));
    }

    @Test
    public void parsesJsonEventsAndPlainOutput() {
        String output = ""
            + "{\"text\":\"from text\"}\n"
            + "{\"message\":\"from message\"}\n"
            + "plain fallback\n";

        String parsed = CodexExecSession.parseOutput(output);

        Assert.assertTrue(parsed.contains("from text"));
        Assert.assertTrue(parsed.contains("from message"));
        Assert.assertTrue(parsed.contains("plain fallback"));
    }

    @Test
    public void parsesCodexNestedAgentMessageEvents() {
        String output = ""
            + "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"command_execution\",\"command\":\"ls\",\"text\":\"noise\",\"aggregated_output\":\"noise\"}}\n"
            + "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\",\"type\":\"agent_message\",\"text\":\"nested answer\"}}\n"
            + "{\"type\":\"turn.completed\"}\n";

        String parsed = CodexExecSession.parseOutput(output);

        Assert.assertEquals("nested answer", parsed);
    }

    @Test
    public void parsesCodexFinalAnswerWithoutCommentaryOrReasoning() {
        String output = ""
            + "{\"type\":\"response_item\",\"payload\":{\"type\":\"reasoning\",\"summary\":[],\"content\":null}}\n"
            + "{\"type\":\"event_msg\",\"payload\":{\"type\":\"agent_message\",\"message\":\"I will inspect tools first.\",\"phase\":\"commentary\"}}\n"
            + "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\",\"phase\":\"commentary\",\"content\":[{\"type\":\"output_text\",\"text\":\"I will inspect tools first.\"}]}}\n"
            + "{\"type\":\"event_msg\",\"payload\":{\"type\":\"agent_message\",\"message\":\"final answer\",\"phase\":\"final_answer\"}}\n"
            + "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\",\"phase\":\"final_answer\",\"content\":[{\"type\":\"output_text\",\"text\":\"final answer\"}]}}\n";

        String parsed = CodexExecSession.parseOutput(output);

        Assert.assertEquals("final answer", parsed);
    }

    @Test
    public void parsesCodexAssistantOutputTextResponseItems() {
        String output = ""
            + "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"answer from output text\"}]}}\n";

        String parsed = CodexExecSession.parseOutput(output);

        Assert.assertEquals("answer from output text", parsed);
    }

    @Test
    public void parsesLegacyCodexAgentMessagesWithoutPreToolCommentary() {
        String output = ""
            + "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"I will inspect status first.\"}}\n"
            + "{\"type\":\"item.started\",\"item\":{\"id\":\"item_1\",\"type\":\"mcp_tool_call\",\"server\":\"android-mcp\",\"tool\":\"android.get_status\",\"arguments\":{}}}\n"
            + "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\",\"type\":\"mcp_tool_call\",\"server\":\"android-mcp\",\"tool\":\"android.get_status\",\"arguments\":{},\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}]},\"error\":null}}\n"
            + "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_2\",\"type\":\"agent_message\",\"text\":\"Android MCP is running.\"}}\n"
            + "{\"type\":\"turn.completed\"}\n";

        String parsed = CodexExecSession.parseOutput(output);

        Assert.assertEquals("Android MCP is running.", parsed);
    }

    @Test
    public void parsesSingleLegacyCodexAgentMessageAsFinalAnswer() {
        String output = ""
            + "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"Direct answer.\"}}\n"
            + "{\"type\":\"turn.completed\"}\n";

        String parsed = CodexExecSession.parseOutput(output);

        Assert.assertEquals("Direct answer.", parsed);
    }

    @Test
    public void parsesCodexMcpToolEvents() {
        String started = "{\"type\":\"item.started\",\"item\":{\"id\":\"item_1\",\"type\":\"mcp_tool_call\",\"server\":\"android-mcp\",\"tool\":\"android.get_status\",\"arguments\":{}}}";
        String completed = "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\",\"type\":\"mcp_tool_call\",\"server\":\"android-mcp\",\"tool\":\"android.get_status\",\"arguments\":{},\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}]},\"error\":null}}";

        CodexExecSession.CodexToolEvent use = CodexExecSession.parseToolEventForTest(started);
        CodexExecSession.CodexToolEvent result = CodexExecSession.parseToolEventForTest(completed);

        Assert.assertNotNull(use);
        Assert.assertEquals(CodexExecSession.CodexToolEvent.Kind.USE, use.kind);
        Assert.assertEquals("android.get_status", use.name);
        Assert.assertNotNull(result);
        Assert.assertEquals(CodexExecSession.CodexToolEvent.Kind.RESULT, result.kind);
        Assert.assertEquals("android.get_status", result.name);
        Assert.assertTrue(result.full.contains("ok"));
        Assert.assertTrue(result.full.contains("true"));
    }

    @Test
    public void identifiesTransientCodexSystemMessages() {
        Assert.assertTrue(CodexExecSession.isTransientSystemMessageForTest("Starting Codex."));
        Assert.assertTrue(CodexExecSession.isTransientSystemMessageForTest("Codex session started."));
        Assert.assertTrue(CodexExecSession.isTransientSystemMessageForTest("Codex is waiting for model response."));
        Assert.assertFalse(CodexExecSession.isTransientSystemMessageForTest("Codex failed: missing key"));
    }

    @Test
    public void buildsTranscriptPromptWithPreviousMessages() {
        CodexExecSession session = new CodexExecSession(null);
        session.recordForTest(ChatMessage.user("first"));
        session.recordForTest(ChatMessage.assistant("answer"));

        String prompt = session.buildPromptForTest("next");

        Assert.assertTrue(prompt.contains("USER: first"));
        Assert.assertTrue(prompt.contains("ASSISTANT: answer"));
        Assert.assertTrue(prompt.contains("USER: next"));
    }

    @Test
    public void setupEnvIncludesOpenAiBaseUrlWhenProvided() {
        Map<String, String> env = new HashMap<>();
        env.put("OPENAI_BASE_URL", "https://stale.example.com");

        CodexExecSession.setupEnvForTest(env, "sk-openai", "https://api.example.com/v1");

        Assert.assertEquals("sk-openai", env.get("OPENAI_API_KEY"));
        Assert.assertEquals("https://api.example.com/v1", env.get("OPENAI_BASE_URL"));
    }

    @Test
    public void setupEnvRemovesStaleOpenAiBaseUrlWhenEmpty() {
        Map<String, String> env = new HashMap<>();
        env.put("OPENAI_BASE_URL", "https://stale.example.com");

        CodexExecSession.setupEnvForTest(env, "sk-openai", "");

        Assert.assertEquals("sk-openai", env.get("OPENAI_API_KEY"));
        Assert.assertFalse(env.containsKey("OPENAI_BASE_URL"));
    }

    @Test
    public void singletonRetainsRunningSessionAcrossFragmentRecreation() {
        CodexExecSession first = CodexExecSession.get(null);
        first.startRunForTest();

        CodexExecSession second = CodexExecSession.get(null);

        Assert.assertSame(first, second);
        Assert.assertTrue(second.isRunning());
    }

    @Test
    public void singletonRetainsTranscriptAcrossFragmentRecreation() {
        CodexExecSession first = CodexExecSession.get(null);
        first.recordForTest(ChatMessage.user("hello"));
        first.recordForTest(ChatMessage.assistant("answer"));

        CodexExecSession second = CodexExecSession.get(null);
        List<ChatMessage> snapshot = second.snapshotTranscript();

        Assert.assertEquals(2, snapshot.size());
        Assert.assertEquals(ChatMessage.Type.USER, snapshot.get(0).type);
        Assert.assertEquals("hello", snapshot.get(0).content);
        Assert.assertEquals(ChatMessage.Type.ASSISTANT, snapshot.get(1).type);
        Assert.assertEquals("answer", snapshot.get(1).content);

        snapshot.clear();
        Assert.assertTrue(second.buildPromptForTest("next").contains("hello"));
    }

    @Test
    public void interruptInvalidatesStaleWorkerResults() {
        CodexExecSession session = new CodexExecSession(null);
        int generation = session.currentGenerationForTest();

        session.interrupt();

        Assert.assertFalse(session.acceptAssistantForTest(generation, "stale"));
        Assert.assertFalse(session.buildPromptForTest("next").contains("stale"));
    }

    @Test
    public void invalidationAfterCallbackCheckPreventsDelivery() {
        CodexExecSession session = new CodexExecSession(null);
        AtomicInteger callbacks = new AtomicInteger();
        session.addListener(new CodexExecSession.Listener() {
            @Override public void onSystem(String info) {}
            @Override public void onAssistantText(String text) {
                callbacks.incrementAndGet();
            }
            @Override public void onResult(boolean isError, String errMsg) {}
        });

        int generation = session.startRunForTest();
        Runnable callback = session.prepareAssistantCallbackForTest(generation, "stale");
        session.interrupt();
        if (callback != null) callback.run();

        Assert.assertEquals(0, callbacks.get());
    }

    @Test
    public void streamingAssistantUpdatesAreThrottledAndFinalFlushesLatestText() {
        CodexExecSession session = new CodexExecSession(null);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<String> latest = new AtomicReference<>("");
        session.addListener(new CodexExecSession.Listener() {
            @Override public void onSystem(String info) {}
            @Override public void onAssistantText(String text) {
                callbacks.incrementAndGet();
                latest.set(text);
            }
            @Override public void onResult(boolean isError, String errMsg) {}
        });

        AtomicLong now = new AtomicLong(1000);
        CodexExecSession.StreamDeliveryState delivery =
            CodexExecSession.streamDeliveryForTest(250, now::get);
        int generation = session.startRunForTest();

        Assert.assertTrue(session.streamAssistantForTest(generation, "one", delivery, false));
        Assert.assertTrue(session.streamAssistantForTest(generation, "two", delivery, false));

        Assert.assertEquals(1, callbacks.get());
        Assert.assertEquals("one", latest.get());

        Assert.assertTrue(session.streamAssistantForTest(generation, "three", delivery, true));

        Assert.assertEquals(2, callbacks.get());
        Assert.assertEquals("three", latest.get());
    }
}
