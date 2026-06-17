package com.termux.app;

import android.content.Context;

import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

public final class CodexExecSession {

    private static final String PREFIX = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
    private static final String PROOT_D = PREFIX + "/bin/proot-distro";
    private static final String CODEX_ACTIVE_ENV =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.codex-active-env";
    private static final String CODEX_APP_PROVIDER = "app_openai";
    private static final long ASSISTANT_STREAM_MIN_INTERVAL_MS = 250L;

    private static volatile CodexExecSession sInstance;

    public static synchronized void init(@Nullable Context context) {
        if (sInstance == null) sInstance = new CodexExecSession(context);
    }

    public static synchronized CodexExecSession get(@Nullable Context context) {
        if (sInstance == null) init(context);
        return sInstance;
    }

    static synchronized void clearInstanceForTest() {
        if (sInstance != null) sInstance.interrupt();
        sInstance = null;
    }

    @Nullable
    private final Context mAppCtx;
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final List<ChatMessage> mTranscript =
        Collections.synchronizedList(new ArrayList<ChatMessage>());
    private final Object mStateLock = new Object();

    private volatile boolean mRunning;
    private volatile Process mProcess;
    private volatile Thread mWorkerThread;
    private int mGeneration;

    public interface Listener {
        void onSystem(String info);
        default void onThinking(String thinking) {}
        default void onToolUse(String name, String inputJson) {}
        default void onToolResult(String name, String summary, String full) {}
        void onAssistantText(String text);
        void onResult(boolean isError, String errMsg);
    }

    static final class CodexToolEvent {
        enum Kind { USE, RESULT }

        final Kind kind;
        final String name;
        final String detail;
        final String summary;
        final String full;

        CodexToolEvent(Kind kind, String name, String detail, String summary, String full) {
            this.kind = kind;
            this.name = name == null ? "" : name;
            this.detail = detail == null ? "" : detail;
            this.summary = summary == null ? "" : summary;
            this.full = full == null ? "" : full;
        }
    }

    static final class StreamDeliveryState {
        private final long mMinIntervalMs;
        private final LongSupplier mClock;
        private long mLastEmitMs = Long.MIN_VALUE;
        private String mLastEmittedText = "";

        StreamDeliveryState(long minIntervalMs, LongSupplier clock) {
            mMinIntervalMs = Math.max(0, minIntervalMs);
            mClock = clock == null ? System::currentTimeMillis : clock;
        }

        boolean shouldEmit(String text, boolean force) {
            String clean = text == null ? "" : text;
            if (clean.trim().isEmpty()) return false;
            if (force) return !Objects.equals(mLastEmittedText, clean);
            long now = mClock.getAsLong();
            return mLastEmitMs == Long.MIN_VALUE || now - mLastEmitMs >= mMinIntervalMs;
        }

        void markEmitted(String text) {
            mLastEmittedText = text == null ? "" : text;
            mLastEmitMs = mClock.getAsLong();
        }
    }

    public CodexExecSession(@Nullable Context context) {
        mAppCtx = context == null ? null : context.getApplicationContext();
    }

    public boolean isRunning() {
        synchronized (mStateLock) {
            return mRunning;
        }
    }

    public void addListener(Listener listener) {
        if (listener != null) mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public List<ChatMessage> snapshotTranscript() {
        List<ChatMessage> copy = new ArrayList<>();
        synchronized (mTranscript) {
            for (ChatMessage message : mTranscript) {
                copy.add(copyMessage(message));
            }
        }
        return copy;
    }

    public void loadTranscript(List<ChatMessage> messages) {
        interrupt();
        synchronized (mTranscript) {
            mTranscript.clear();
            if (messages == null) return;
            for (ChatMessage message : messages) {
                if (message != null) mTranscript.add(copyMessage(message));
            }
        }
    }

    public void resetForNewConversation() {
        interrupt();
        synchronized (mTranscript) {
            mTranscript.clear();
        }
    }

    public void interrupt() {
        Process process;
        Thread worker;
        synchronized (mStateLock) {
            mGeneration++;
            process = mProcess;
            worker = mWorkerThread;
            mRunning = false;
            mProcess = null;
            mWorkerThread = null;
        }
        if (process != null) {
            process.destroy();
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    public void send(String userText, String apiKey) {
        send(userText, apiKey, "");
    }

    public void send(String userText, String apiKey, String baseUrl) {
        final int generation;
        final String prompt;
        final Thread worker;
        synchronized (mStateLock) {
            if (mRunning) {
                generation = -1;
                prompt = null;
                worker = null;
            } else {
                prompt = buildPrompt(userText);
                mTranscript.add(ChatMessage.user(userText));
                mRunning = true;
                generation = ++mGeneration;
                worker = new Thread(() -> runCodex(generation, prompt, apiKey, baseUrl), "CodexExecSession");
                worker.setDaemon(true);
                mWorkerThread = worker;
            }
        }
        if (generation < 0) {
            emitSystem("Codex is already running.");
            return;
        }
        worker.start();
    }

    public static List<String> command(String proot) {
        return command(proot, "");
    }

    public static List<String> command(String proot, String baseUrl) {
        List<String> cmd = new ArrayList<>();
        cmd.add(proot);
        cmd.add("login");
        cmd.add("--user");
        cmd.add("codex");
        cmd.add("ubuntu");
        cmd.add("--");
        cmd.add("sh");
        cmd.add("-lc");
        cmd.add(codexShellCommand(baseUrl));
        return cmd;
    }

    public static String parseOutput(String output) {
        if (output == null || output.trim().isEmpty()) return "";

        StringBuilder parsed = new StringBuilder();
        LegacyAgentMessageState legacyState = new LegacyAgentMessageState();
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            String text = null;
            if (trimmed.startsWith("{")) {
                try {
                    JSONObject obj = new JSONObject(trimmed);
                    String legacyAgentText = extractLegacyAgentMessage(obj);
                    if (!legacyAgentText.isEmpty()) {
                        legacyState.replacePending(legacyAgentText);
                        continue;
                    }
                    if (parseToolEvent(obj) != null) {
                        legacyState.consumePending();
                        continue;
                    }
                    if ("turn.completed".equals(stringField(obj, "type"))) {
                        appendUniqueLine(parsed, legacyState.consumePending());
                        continue;
                    }
                    text = extractText(obj);
                } catch (Exception ignored) {
                    String legacyAgentText = extractLegacyAgentMessageFallback(trimmed);
                    if (!legacyAgentText.isEmpty()) {
                        legacyState.replacePending(legacyAgentText);
                        continue;
                    }
                    if (parseToolEventFallback(trimmed) != null) {
                        legacyState.consumePending();
                        continue;
                    }
                    if (trimmed.contains("\"type\":\"turn.completed\"")) {
                        appendUniqueLine(parsed, legacyState.consumePending());
                        continue;
                    }
                    text = extractTextFallback(trimmed);
                    if (text.isEmpty() && !looksLikeJsonObject(trimmed)) {
                        text = trimmed;
                    }
                }
            } else {
                text = trimmed;
            }

            if (text != null && !text.trim().isEmpty()) {
                legacyState.clear();
                appendUniqueLine(parsed, text.trim());
            }
        }
        appendUniqueLine(parsed, legacyState.consumePending());
        return parsed.toString();
    }

    static CodexToolEvent parseToolEventForTest(String line) {
        try {
            return parseToolEvent(new JSONObject(line));
        } catch (Exception e) {
            return parseToolEventFallback(line);
        }
    }

    static boolean isTransientSystemMessageForTest(String info) {
        return isTransientSystemMessage(info);
    }

    static boolean isTransientSystemMessage(String info) {
        if (info == null) return false;
        String clean = info.trim();
        return "Starting Codex.".equals(clean)
            || "Codex session started.".equals(clean)
            || "Codex is waiting for model response.".equals(clean);
    }

    public void recordForTest(ChatMessage message) {
        if (message != null) mTranscript.add(message);
    }

    void recordThinkingForTest(int generation, String thinking) {
        synchronized (mStateLock) {
            if (isCurrentRunLocked(generation)) recordThinkingLocked(thinking);
        }
    }

    void recordToolUseForTest(int generation, String name, String inputJson) {
        synchronized (mStateLock) {
            if (isCurrentRunLocked(generation)) recordToolUseLocked(name, inputJson);
        }
    }

    void recordToolResultForTest(int generation, String name, String summary, String full) {
        synchronized (mStateLock) {
            if (isCurrentRunLocked(generation)) recordToolResultLocked(name, summary, full);
        }
    }

    void finishRunForTest(int generation) {
        synchronized (mStateLock) {
            if (isCurrentRunLocked(generation)) collapseTranscriptThinkingLocked();
        }
        finishRunIfCurrent(generation);
    }

    public String buildPromptForTest(String userText) {
        return buildPrompt(userText);
    }

    int currentGenerationForTest() {
        synchronized (mStateLock) {
            return mGeneration;
        }
    }

    boolean acceptAssistantForTest(int generation, String text) {
        return acceptAssistantText(generation, text);
    }

    int startRunForTest() {
        synchronized (mStateLock) {
            mRunning = true;
            return ++mGeneration;
        }
    }

    Runnable prepareAssistantCallbackForTest(int generation, String text) {
        return () -> acceptAssistantText(generation, text);
    }

    static StreamDeliveryState streamDeliveryForTest(long minIntervalMs, LongSupplier clock) {
        return new StreamDeliveryState(minIntervalMs, clock);
    }

    boolean streamAssistantForTest(
            int generation,
            String text,
            StreamDeliveryState delivery,
            boolean forceEmit) {
        return updateAssistantText(generation, text, delivery, forceEmit);
    }

    private void runCodex(int generation, String prompt, String apiKey, String baseUrl) {
        Process process = null;
        Thread stderrThread = null;
        try {
            if (!isCurrentRun(generation)) return;
            writeActiveEnv(apiKey, baseUrl);
            emitSystemIfCurrent(generation, "Starting Codex.");
            ProcessBuilder pb = new ProcessBuilder(command(PROOT_D, baseUrl));
            setupEnv(pb.environment(), apiKey, baseUrl);
            pb.redirectErrorStream(false);

            if (!isCurrentRun(generation)) return;
            process = pb.start();
            if (!setProcessIfCurrent(generation, process)) {
                process.destroy();
                return;
            }

            StringBuilder stderr = new StringBuilder();
            Process startedProcess = process;
            stderrThread = new Thread(() -> readStream(startedProcess.getErrorStream(), stderr),
                "CodexExecSession-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            BufferedWriter writer =
                new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(prompt);
            writer.flush();
            writer.close();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stdoutErrors = new StringBuilder();
            StringBuilder assistant = new StringBuilder();
            readCodexStdout(generation, process.getInputStream(), stdout, stdoutErrors, assistant);
            int exitCode = process.waitFor();
            if (stderrThread != null) stderrThread.join();

            if (!isCurrentRun(generation)) return;

            if (assistant.length() == 0) {
                String assistantText = parseOutput(stdout.toString());
                acceptAssistantText(generation, assistantText);
            }

            boolean isError = exitCode != 0;
            String errMsg = isError
                ? firstNonEmpty(stdoutErrors.toString().trim(), stderr.toString().trim(),
                    "codex exited with code " + exitCode)
                : "";
            emitResultIfCurrent(generation, isError, errMsg);
        } catch (Exception e) {
            emitResultIfCurrent(generation, true, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
            finishRunIfCurrent(generation);
        }
    }

    private String buildPrompt(String userText) {
        StringBuilder prompt = new StringBuilder();
        synchronized (mTranscript) {
            for (ChatMessage message : mTranscript) {
                if (message == null || message.content == null) continue;
                if (message.type == ChatMessage.Type.USER) {
                    prompt.append("USER: ").append(message.content).append("\n\n");
                } else if (message.type == ChatMessage.Type.ASSISTANT) {
                    prompt.append("ASSISTANT: ").append(message.content).append("\n\n");
                }
            }
        }
        prompt.append("USER: ").append(userText == null ? "" : userText).append('\n');
        return prompt.toString();
    }

    private static ChatMessage copyMessage(ChatMessage message) {
        if (message == null) return ChatMessage.system("");
        ChatMessage copy = new ChatMessage(message.type, message.content);
        copy.thinking = message.thinking;
        copy.thinkingCollapsed = message.thinkingCollapsed;
        copy.toolName = message.toolName;
        copy.toolDetail = message.toolDetail;
        copy.toolDetailCollapsed = message.toolDetailCollapsed;
        return copy;
    }

    static void setupEnvForTest(Map<String, String> env, String apiKey, String baseUrl) {
        setupEnv(env, apiKey, baseUrl);
    }

    private static void setupEnv(Map<String, String> env, String apiKey, String baseUrl) {
        env.put("PREFIX", PREFIX);
        env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
            + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
        env.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        env.put("LANG", "en_US.UTF-8");
        env.put("OPENAI_API_KEY", apiKey == null ? "" : apiKey);
        if (baseUrl != null && !baseUrl.isEmpty()) {
            env.put("OPENAI_BASE_URL", baseUrl);
        } else {
            env.remove("OPENAI_BASE_URL");
        }
    }

    private void writeActiveEnv(String apiKey, String baseUrl) throws IOException {
        if (mAppCtx == null) return;
        File home = new File(mAppCtx.getFilesDir(), "home");
        if (!home.exists() && !home.mkdirs()) {
            throw new IOException("Cannot create " + home.getAbsolutePath());
        }
        File envFile = new File(home, ".codex-active-env");
        StringBuilder out = new StringBuilder();
        out.append("export OPENAI_API_KEY='").append(shellSingleQuote(apiKey)).append("'\n");
        if (baseUrl != null && !baseUrl.isEmpty()) {
            out.append("export OPENAI_BASE_URL='").append(shellSingleQuote(baseUrl)).append("'\n");
        }
        Files.write(envFile.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String codexShellCommand(String baseUrl) {
        StringBuilder script = new StringBuilder();
        script.append("cd /home/codex && ");
        script.append("if [ -f ").append(shellSingleQuote(CODEX_ACTIVE_ENV)).append(" ]; then . ")
            .append(shellSingleQuote(CODEX_ACTIVE_ENV)).append("; fi; ");
        script.append("codex exec --json --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox");
        if (baseUrl != null && !baseUrl.isEmpty()) {
            String escapedBaseUrl = tomlEscape(baseUrl);
            script.append(" -c ").append(shellArg("model_provider=\"" + CODEX_APP_PROVIDER + "\""));
            script.append(" -c ").append(shellArg("model_providers." + CODEX_APP_PROVIDER
                + "={name=\"App OpenAI Compatible\",base_url=\"" + escapedBaseUrl
                + "\",env_key=\"OPENAI_API_KEY\",wire_api=\"responses\"}"));
            script.append(" -c ").append(shellArg("openai_base_url=\"" + escapedBaseUrl + "\""));
        }
        script.append(" -");
        return script.toString();
    }

    private static String shellArg(String value) {
        return "'" + shellSingleQuote(value) + "'";
    }

    private static String shellSingleQuote(String value) {
        return (value == null ? "" : value).replace("'", "'\\''");
    }

    private static String tomlEscape(String value) {
        return (value == null ? "" : value)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }

    private static void readStream(java.io.InputStream inputStream, StringBuilder output) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append('\n');
                output.append(line);
            }
        } catch (Exception ignored) {
        }
    }

    private void readCodexStdout(
        int generation,
        java.io.InputStream inputStream,
        StringBuilder raw,
        StringBuilder errors,
        StringBuilder assistant) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            String line;
            LegacyAgentMessageState legacyState = new LegacyAgentMessageState();
            StreamDeliveryState delivery = new StreamDeliveryState(
                ASSISTANT_STREAM_MIN_INTERVAL_MS, System::currentTimeMillis);
            while ((line = reader.readLine()) != null) {
                if (raw.length() > 0) raw.append('\n');
                raw.append(line);
                handleCodexStdoutLine(generation, line.trim(), errors, assistant, legacyState, delivery);
            }
            if (assistant.length() > 0) {
                updateAssistantText(generation, assistant.toString(), delivery, true);
            }
        } catch (Exception ignored) {
        }
    }

    private void handleCodexStdoutLine(
        int generation,
        String line,
        StringBuilder errors,
        StringBuilder assistant,
        LegacyAgentMessageState legacyState,
        StreamDeliveryState delivery) {
        if (line == null || line.isEmpty() || !line.startsWith("{")) return;
        try {
            JSONObject obj = new JSONObject(line);
            String type = stringField(obj, "type");
            if ("thread.started".equals(type)) {
                emitSystemIfCurrent(generation, "Codex session started.");
            } else if ("turn.started".equals(type)) {
                emitSystemIfCurrent(generation, "Codex is waiting for model response.");
            } else if ("error".equals(type)) {
                String msg = stringField(obj, "message");
                if (!msg.isEmpty()) {
                    appendLine(errors, msg);
                    emitSystemIfCurrent(generation, "Codex: " + msg);
                }
            } else if ("turn.failed".equals(type)) {
                String msg = errorMessage(obj);
                if (!msg.isEmpty()) {
                    appendLine(errors, msg);
                    emitSystemIfCurrent(generation, "Codex failed: " + msg);
                }
            }

            String legacyAgentText = extractLegacyAgentMessage(obj);
            if (!legacyAgentText.isEmpty()) {
                String previous = legacyState.replacePending(legacyAgentText);
                if (!previous.isEmpty()) emitThinkingIfCurrent(generation, previous);
                return;
            }

            CodexToolEvent toolEvent = parseToolEvent(obj);
            if (toolEvent != null) {
                String pendingThinking = legacyState.consumePending();
                if (!pendingThinking.isEmpty()) emitThinkingIfCurrent(generation, pendingThinking);
                if (toolEvent.kind == CodexToolEvent.Kind.USE) {
                    emitToolUseIfCurrent(generation, toolEvent.name, toolEvent.detail);
                } else {
                    emitToolResultIfCurrent(generation, toolEvent.name, toolEvent.summary, toolEvent.full);
                }
                return;
            }

            if ("turn.completed".equals(type)) {
                String finalText = legacyState.consumePending();
                if (!finalText.isEmpty()) {
                    appendUniqueLine(assistant, finalText);
                    updateAssistantText(generation, assistant.toString(), delivery, true);
                }
                return;
            }

            String thinking = extractThinking(obj);
            if (!thinking.isEmpty()) {
                emitThinkingIfCurrent(generation, thinking);
            }

            String text = extractText(obj);
            if (!text.isEmpty()) {
                legacyState.clear();
                appendUniqueLine(assistant, text);
                updateAssistantText(generation, assistant.toString(), delivery, false);
            }
        } catch (Exception ignored) {
        }
    }

    private static String errorMessage(JSONObject obj) {
        JSONObject error = obj == null ? null : obj.optJSONObject("error");
        return firstNonEmpty(stringField(obj, "message"), stringField(error, "message"));
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(line);
    }

    private static void appendUniqueLine(StringBuilder sb, String line) {
        if (line == null || line.trim().isEmpty()) return;
        String clean = line.trim();
        if (sb.length() == 0) {
            sb.append(clean);
            return;
        }
        String current = sb.toString();
        if (current.equals(clean) || current.endsWith("\n" + clean)) return;
        sb.append('\n').append(clean);
    }

    private boolean setProcessIfCurrent(int generation, Process process) {
        synchronized (mStateLock) {
            if (!isCurrentRunLocked(generation)) return false;
            mProcess = process;
            return true;
        }
    }

    private boolean acceptAssistantText(int generation, String text) {
        return updateAssistantText(generation, text, null, true);
    }

    private boolean updateAssistantText(
            int generation,
            String text,
            @Nullable StreamDeliveryState delivery,
            boolean forceEmit) {
        if (text == null || text.trim().isEmpty()) return false;
        synchronized (mStateLock) {
            if (!isCurrentRunLocked(generation)) return false;
            String clean = text.trim();
            boolean shouldEmit = delivery == null || delivery.shouldEmit(clean, forceEmit);
            synchronized (mTranscript) {
                int outputIndex = ChatTurnOrdering.findOutputIndex(mTranscript);
                if (outputIndex >= 0) {
                    mTranscript.get(outputIndex).content = clean;
                } else {
                    mTranscript.add(ChatTurnOrdering.findOutputInsertIndex(mTranscript),
                        ChatMessage.assistant(clean));
                }
            }
            if (shouldEmit) {
                if (delivery != null) delivery.markEmitted(clean);
                emitAssistantTextLocked(clean);
            }
        }
        return true;
    }

    private void emitSystemIfCurrent(int generation, String info) {
        synchronized (mStateLock) {
            if (isCurrentRunLocked(generation)) emitSystemLocked(info);
        }
    }

    private void emitThinkingIfCurrent(int generation, String thinking) {
        synchronized (mStateLock) {
            if (isCurrentRunLocked(generation)) emitThinkingLocked(thinking);
        }
    }

    private void emitToolUseIfCurrent(int generation, String name, String inputJson) {
        synchronized (mStateLock) {
            if (isCurrentRunLocked(generation)) emitToolUseLocked(name, inputJson);
        }
    }

    private void emitToolResultIfCurrent(int generation, String name, String summary, String full) {
        synchronized (mStateLock) {
            if (isCurrentRunLocked(generation)) emitToolResultLocked(name, summary, full);
        }
    }

    private void emitResultIfCurrent(int generation, boolean isError, String errMsg) {
        synchronized (mStateLock) {
            if (isCurrentRunLocked(generation)) emitResultLocked(isError, errMsg);
        }
    }

    private void finishRunIfCurrent(int generation) {
        synchronized (mStateLock) {
            if (mGeneration != generation) return;
            mProcess = null;
            mWorkerThread = null;
            mRunning = false;
        }
    }

    private boolean isCurrentRun(int generation) {
        synchronized (mStateLock) {
            return isCurrentRunLocked(generation);
        }
    }

    private boolean isCurrentRunLocked(int generation) {
        return mRunning && mGeneration == generation;
    }

    private void emitSystem(String info) {
        synchronized (mStateLock) {
            emitSystemLocked(info);
        }
    }

    private void emitSystemLocked(String info) {
        for (Listener listener : mListeners) {
            try {
                listener.onSystem(info);
            } catch (Throwable ignored) {
            }
        }
    }

    private void emitThinkingLocked(String thinking) {
        recordThinkingLocked(thinking);
        for (Listener listener : mListeners) {
            try {
                listener.onThinking(thinking);
            } catch (Throwable ignored) {
            }
        }
    }

    private void emitToolUseLocked(String name, String inputJson) {
        recordToolUseLocked(name, inputJson);
        for (Listener listener : mListeners) {
            try {
                listener.onToolUse(name, inputJson);
            } catch (Throwable ignored) {
            }
        }
    }

    private void emitToolResultLocked(String name, String summary, String full) {
        recordToolResultLocked(name, summary, full);
        for (Listener listener : mListeners) {
            try {
                listener.onToolResult(name, summary, full);
            } catch (Throwable ignored) {
            }
        }
    }

    private void emitAssistantText(String text) {
        synchronized (mStateLock) {
            emitAssistantTextLocked(text);
        }
    }

    private void emitAssistantTextLocked(String text) {
        for (Listener listener : mListeners) {
            try {
                listener.onAssistantText(text);
            } catch (Throwable ignored) {
            }
        }
    }

    private void emitResult(boolean isError, String errMsg) {
        synchronized (mStateLock) {
            emitResultLocked(isError, errMsg);
        }
    }

    private void emitResultLocked(boolean isError, String errMsg) {
        collapseTranscriptThinkingLocked();
        for (Listener listener : mListeners) {
            try {
                listener.onResult(isError, errMsg);
            } catch (Throwable ignored) {
            }
        }
    }

    private void recordThinkingLocked(String thinking) {
        if (thinking == null || thinking.trim().isEmpty()) return;
        synchronized (mTranscript) {
            int thinkingIndex = ChatTurnOrdering.findThinkingIndex(mTranscript);
            if (thinkingIndex >= 0) {
                mTranscript.get(thinkingIndex).thinking = thinking.trim();
                return;
            }
            ChatMessage message = ChatMessage.assistant("");
            message.thinking = thinking.trim();
            mTranscript.add(ChatTurnOrdering.findThinkingInsertIndex(mTranscript), message);
        }
    }

    private void recordToolUseLocked(String name, String inputJson) {
        synchronized (mTranscript) {
            mTranscript.add(ChatTurnOrdering.findToolInsertIndex(mTranscript),
                ChatMessage.toolUse(name, inputJson));
        }
    }

    private void recordToolResultLocked(String name, String summary, String full) {
        synchronized (mTranscript) {
            mTranscript.add(ChatTurnOrdering.findToolInsertIndex(mTranscript),
                ChatMessage.toolResult(name, summary, full));
        }
    }

    private void collapseTranscriptThinkingLocked() {
        synchronized (mTranscript) {
            for (int i = mTranscript.size() - 1; i >= 0; i--) {
                ChatMessage message = mTranscript.get(i);
                if (message != null && message.type == ChatMessage.Type.ASSISTANT
                        && message.thinking != null && !message.thinking.isEmpty()) {
                    message.thinkingCollapsed = true;
                    return;
                }
            }
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String extractText(JSONObject obj) {
        if (obj == null) return "";
        String type = stringField(obj, "type");
        if ("event_msg".equals(type)) {
            return extractEventMessageText(obj.optJSONObject("payload"));
        }
        if ("response_item".equals(type)) {
            return extractResponseItemText(obj.optJSONObject("payload"));
        }
        if ("reasoning".equals(type)) return "";
        if (isNonAssistantEvent(obj)) return "";
        String direct = firstNonEmpty(
            stringField(obj, "text"),
            stringField(obj, "message"),
            stringField(obj, "content"));
        if (!direct.isEmpty()) return direct;

        String item = extractText(obj.optJSONObject("item"));
        if (!item.isEmpty()) return item;

        String details = extractText(obj.optJSONObject("details"));
        if (!details.isEmpty()) return details;

        JSONObject message = obj.optJSONObject("message");
        String messageText = extractText(message);
        if (!messageText.isEmpty()) return messageText;

        return extractTextArray(obj.optJSONArray("content"));
    }

    private static String extractLegacyAgentMessage(JSONObject obj) {
        if (obj == null) return "";
        String type = stringField(obj, "type");
        if (!"item.completed".equals(type)) return "";
        JSONObject item = obj.optJSONObject("item");
        if (item == null) return "";
        if (!"agent_message".equals(stringField(item, "type"))) return "";
        return stringField(item, "text").trim();
    }

    private static String extractThinking(JSONObject obj) {
        if (obj == null) return "";
        if (!"event_msg".equals(stringField(obj, "type"))) return "";
        JSONObject payload = obj.optJSONObject("payload");
        if (payload == null) return "";
        if (!"agent_message".equals(stringField(payload, "type"))) return "";
        if (!"commentary".equals(stringField(payload, "phase"))) return "";
        return stringField(payload, "message").trim();
    }

    private static String extractEventMessageText(JSONObject payload) {
        if (payload == null) return "";
        String payloadType = stringField(payload, "type");
        if (!"agent_message".equals(payloadType) && !"assistant_message".equals(payloadType)) {
            return "";
        }
        String phase = stringField(payload, "phase");
        if ("commentary".equals(phase) || "analysis".equals(phase)) return "";
        if (phase.isEmpty() || "final_answer".equals(phase)) {
            return stringField(payload, "message").trim();
        }
        return "";
    }

    private static String extractResponseItemText(JSONObject payload) {
        if (payload == null) return "";
        String payloadType = stringField(payload, "type");
        if ("reasoning".equals(payloadType)) return "";
        if (!"message".equals(payloadType)) return "";
        String phase = stringField(payload, "phase");
        if ("commentary".equals(phase) || "analysis".equals(phase)) return "";
        String role = stringField(payload, "role");
        if (!role.isEmpty() && !"assistant".equals(role)) return "";
        return extractOutputTextArray(payload.optJSONArray("content"));
    }

    private static boolean isNonAssistantEvent(JSONObject obj) {
        String type = stringField(obj, "type");
        if (type.isEmpty()) return false;
        return type.contains("command")
            || type.contains("tool")
            || "error".equals(type);
    }

    private static CodexToolEvent parseToolEvent(JSONObject obj) {
        if (obj == null) return null;
        String eventType = stringField(obj, "type");
        boolean started = "item.started".equals(eventType);
        boolean completed = "item.completed".equals(eventType);
        if (!started && !completed) return null;

        JSONObject item = obj.optJSONObject("item");
        if (item == null) return null;
        String itemType = stringField(item, "type");
        if ("mcp_tool_call".equals(itemType)) {
            String toolName = firstNonEmpty(stringField(item, "tool"), stringField(item, "name"));
            if (toolName.isEmpty()) toolName = "MCP";
            JSONObject args = item.optJSONObject("arguments");
            String detail = args == null ? "{}" : args.toString();
            if (started) {
                return new CodexToolEvent(CodexToolEvent.Kind.USE, toolName, detail, "", "");
            }
            String full = firstNonEmpty(pretty(item.optJSONObject("result")),
                stringField(item, "error"), item.toString());
            String summary = summarizeToolResult(item);
            return new CodexToolEvent(CodexToolEvent.Kind.RESULT, toolName, detail, summary, full);
        }
        if ("command_execution".equals(itemType)) {
            String command = stringField(item, "command");
            String name = command.isEmpty() ? "Shell" : "Shell";
            String detail = command.isEmpty() ? "{}" : "{\"command\":\"" + jsonEscape(command) + "\"}";
            if (started) {
                return new CodexToolEvent(CodexToolEvent.Kind.USE, name, detail, "", "");
            }
            String output = stringField(item, "aggregated_output");
            String exitCode = item.has("exit_code") && !item.isNull("exit_code")
                ? String.valueOf(item.opt("exit_code"))
                : "";
            String summary = exitCode.isEmpty() ? shortLine(output) : ("exit " + exitCode);
            if (!output.trim().isEmpty()) {
                String outputSummary = shortLine(output);
                if (!outputSummary.isEmpty()) summary = summary + ": " + outputSummary;
            }
            String full = output.trim().isEmpty() ? item.toString() : output;
            return new CodexToolEvent(CodexToolEvent.Kind.RESULT, name, detail, summary, full);
        }
        return null;
    }

    private static CodexToolEvent parseToolEventFallback(String json) {
        if (json == null) return null;
        boolean started = json.contains("\"type\":\"item.started\"");
        boolean completed = json.contains("\"type\":\"item.completed\"");
        if (!started && !completed) return null;
        if (json.contains("\"type\":\"mcp_tool_call\"")) {
            String toolName = firstNonEmpty(extractJsonString(json, "tool"), extractJsonString(json, "name"));
            if (toolName.isEmpty()) toolName = "MCP";
            if (started) {
                return new CodexToolEvent(CodexToolEvent.Kind.USE, toolName, "{}", "", "");
            }
            return new CodexToolEvent(CodexToolEvent.Kind.RESULT, toolName, "{}", shortLine(json), json);
        }
        if (json.contains("\"type\":\"command_execution\"")) {
            String command = extractJsonString(json, "command");
            String detail = command.isEmpty() ? "{}" : "{\"command\":\"" + jsonEscape(command) + "\"}";
            if (started) {
                return new CodexToolEvent(CodexToolEvent.Kind.USE, "Shell", detail, "", "");
            }
            String output = extractJsonString(json, "aggregated_output");
            String summary = output.isEmpty() ? shortLine(json) : shortLine(output);
            return new CodexToolEvent(CodexToolEvent.Kind.RESULT, "Shell", detail, summary,
                output.isEmpty() ? json : output);
        }
        return null;
    }

    private static String summarizeToolResult(JSONObject item) {
        if (item == null) return "";
        String error = stringField(item, "error");
        if (!error.isEmpty()) return "error: " + shortLine(error);
        JSONObject result = item.optJSONObject("result");
        if (result == null) return "";
        JSONArray content = result.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                Object value = content.opt(i);
                if (value instanceof JSONObject) {
                    String text = stringField((JSONObject) value, "text");
                    if (!text.trim().isEmpty()) return shortLine(text);
                } else if (value instanceof String) {
                    return shortLine((String) value);
                }
            }
        }
        return shortLine(result.toString());
    }

    private static String pretty(JSONObject obj) {
        if (obj == null) return "";
        try {
            return obj.toString(2);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    private static String shortLine(String value) {
        if (value == null) return "";
        String clean = value.replace('\r', '\n').trim();
        int newline = clean.indexOf('\n');
        if (newline >= 0) clean = clean.substring(0, newline).trim();
        return clean.length() > 120 ? clean.substring(0, 117) + "..." : clean;
    }

    private static String jsonEscape(String value) {
        return (value == null ? "" : value)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }

    private static final class LegacyAgentMessageState {
        private String pending = "";

        String replacePending(String text) {
            String previous = pending;
            pending = text == null ? "" : text.trim();
            return previous == null ? "" : previous;
        }

        String consumePending() {
            String value = pending == null ? "" : pending;
            pending = "";
            return value;
        }

        void clear() {
            pending = "";
        }
    }

    private static String stringField(JSONObject obj, String key) {
        Object value = obj.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private static String extractTextArray(JSONArray array) {
        if (array == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            String text = "";
            if (value instanceof String) {
                text = (String) value;
            } else if (value instanceof JSONObject) {
                text = extractText((JSONObject) value);
            }
            if (text != null && !text.trim().isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text.trim());
            }
        }
        return sb.toString();
    }

    private static String extractOutputTextArray(JSONArray array) {
        if (array == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            String text = "";
            if (value instanceof String) {
                text = (String) value;
            } else if (value instanceof JSONObject) {
                JSONObject obj = (JSONObject) value;
                String type = stringField(obj, "type");
                if (type.isEmpty() || "output_text".equals(type) || "text".equals(type)) {
                    text = stringField(obj, "text");
                }
            }
            if (text != null && !text.trim().isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text.trim());
            }
        }
        return sb.toString();
    }

    private static boolean looksLikeJsonObject(String value) {
        return value != null && value.startsWith("{") && value.endsWith("}");
    }

    private static String extractTextFallback(String json) {
        if (json == null) return "";
        if (json.contains("\"type\":\"reasoning\"")
                || json.contains("\"phase\":\"commentary\"")
                || json.contains("\"phase\":\"analysis\"")) {
            return "";
        }
        if (json.contains("\"phase\":\"final_answer\"")) {
            return extractJsonString(json, "message");
        }
        if (json.contains("\"type\":\"output_text\"")) {
            return extractJsonString(json, "text");
        }
        boolean assistantEvent = json.contains("\"type\":\"agent_message\"")
            || json.contains("\"type\":\"assistant_message\"")
            || json.contains("\"role\":\"assistant\"");
        boolean directTextEvent = json.startsWith("{\"text\"")
            || json.startsWith("{\"message\"")
            || json.startsWith("{\"content\"");
        if (!assistantEvent && !directTextEvent) return "";
        return firstNonEmpty(
            extractJsonString(json, "text"),
            extractJsonString(json, "message"),
            extractJsonString(json, "content"));
    }

    private static String extractLegacyAgentMessageFallback(String json) {
        if (json == null) return "";
        if (!json.contains("\"type\":\"item.completed\"")
                || !json.contains("\"type\":\"agent_message\"")) {
            return "";
        }
        return extractJsonString(json, "text").trim();
    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int keyPos = json.indexOf(needle);
        while (keyPos >= 0) {
            int pos = keyPos + needle.length();
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++;
            if (pos < json.length() && json.charAt(pos) == ':') break;
            keyPos = json.indexOf(needle, keyPos + needle.length());
        }
        if (keyPos < 0) return "";
        int colon = keyPos + needle.length();
        while (colon < json.length() && Character.isWhitespace(json.charAt(colon))) colon++;
        if (colon >= json.length() || json.charAt(colon) != ':') return "";
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '"') return "";
        StringBuilder sb = new StringBuilder();
        boolean escaping = false;
        for (int i = start + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (escaping) {
                switch (ch) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(ch); break;
                }
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                return sb.toString();
            } else {
                sb.append(ch);
            }
        }
        return "";
    }
}
