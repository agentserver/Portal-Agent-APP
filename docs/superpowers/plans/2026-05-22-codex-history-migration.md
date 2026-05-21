# Codex History Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `migrate-to-codex` path that creates Codex-continuable sessions from existing Claude Code history while preserving the original Claude jsonl files.

**Architecture:** Keep Claude history as the immutable source. Add focused units for Claude jsonl parsing, Codex migration prompt/archive generation, migration metadata storage, and Codex CLI invocation. Wire those units into `HomeFragment` so migrated history rows open in Codex mode and can continue through Codex without rewriting Claude data.

**Tech Stack:** Android Java, SharedPreferences, app-private files under `getFilesDir()`, JUnit/Robolectric unit tests, Codex CLI through Ubuntu proot.

---

## File Structure

Create:

- `app/src/main/java/com/termux/app/ClaudeHistoryImporter.java`
  - Reads Claude jsonl and returns normalized turns.
- `app/src/main/java/com/termux/app/CodexMigrationPrompt.java`
  - Builds the bounded initial prompt and JSONL archive for migrated sessions.
- `app/src/main/java/com/termux/app/CodexMigrationStore.java`
  - Stores migration metadata in `codex_migrated_sessions` and owns archive file paths.
- `app/src/main/java/com/termux/app/CodexRunner.java`
  - Runs initial Codex exec and Codex resume commands in Ubuntu proot.
- `app/src/test/java/com/termux/app/ClaudeHistoryImporterTest.java`
- `app/src/test/java/com/termux/app/CodexMigrationPromptTest.java`
- `app/src/test/java/com/termux/app/CodexMigrationStoreTest.java`
- `app/src/test/java/com/termux/app/CodexRunnerTest.java`

Modify:

- `app/src/main/java/com/termux/app/HomeFragment.java`
  - Add migration action, Codex session opening, and Codex send routing.
- `app/src/main/java/com/termux/app/SessionAdapter.java`
  - Add optional per-session badge text such as `Claude -> Codex`.

Do not modify:

- `app/src/main/java/com/termux/app/SessionStore.java`
  - Keep the existing Claude history store stable.
- Original Claude jsonl files under `/home/claude/.claude/projects/-home-claude/`.

---

### Task 1: Claude History Importer

**Files:**
- Create: `app/src/main/java/com/termux/app/ClaudeHistoryImporter.java`
- Test: `app/src/test/java/com/termux/app/ClaudeHistoryImporterTest.java`

- [ ] **Step 1: Write failing parser tests**

Create `app/src/test/java/com/termux/app/ClaudeHistoryImporterTest.java`:

```java
package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class ClaudeHistoryImporterTest {

    @Test
    public void importsUserAndAssistantText() throws Exception {
        File f = File.createTempFile("claude-history", ".jsonl");
        Files.write(f.toPath(), (
            "{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}\n" +
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi there\"}]}}\n"
        ).getBytes(StandardCharsets.UTF_8));

        List<ClaudeHistoryImporter.Turn> turns = ClaudeHistoryImporter.read(f);

        Assert.assertEquals(2, turns.size());
        Assert.assertEquals(ClaudeHistoryImporter.Role.USER, turns.get(0).role);
        Assert.assertEquals("hello", turns.get(0).text);
        Assert.assertEquals(ClaudeHistoryImporter.Role.ASSISTANT, turns.get(1).role);
        Assert.assertEquals("hi there", turns.get(1).text);
    }

    @Test
    public void filtersLargeBase64ToolResults() throws Exception {
        String b64 = "data:image/png;base64," + repeat("A", 9000);
        File f = File.createTempFile("claude-history", ".jsonl");
        Files.write(f.toPath(), (
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"" + b64 + "\"}]}}\n" +
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"screen.capture\"}]}}\n"
        ).getBytes(StandardCharsets.UTF_8));

        List<ClaudeHistoryImporter.Turn> turns = ClaudeHistoryImporter.read(f);

        Assert.assertEquals(2, turns.size());
        Assert.assertEquals(ClaudeHistoryImporter.Role.SYSTEM, turns.get(0).role);
        Assert.assertTrue(turns.get(0).text.contains("large tool result omitted"));
        Assert.assertEquals("Tool call: screen.capture", turns.get(1).text);
    }

    @Test
    public void skipsMalformedLinesAndKeepsReadableTurns() throws Exception {
        File f = File.createTempFile("claude-history", ".jsonl");
        Files.write(f.toPath(), (
            "not-json\n" +
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"keep me\"}]}}\n"
        ).getBytes(StandardCharsets.UTF_8));

        List<ClaudeHistoryImporter.Turn> turns = ClaudeHistoryImporter.read(f);

        Assert.assertEquals(1, turns.size());
        Assert.assertEquals("keep me", turns.get(0).text);
    }

    private static String repeat(String s, int count) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) b.append(s);
        return b.toString();
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.ClaudeHistoryImporterTest
```

Expected: FAIL because `ClaudeHistoryImporter` does not exist.

- [ ] **Step 3: Implement the importer**

Create `app/src/main/java/com/termux/app/ClaudeHistoryImporter.java`:

```java
package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ClaudeHistoryImporter {

    private static final int MAX_TOOL_TEXT = 2000;

    public enum Role { USER, ASSISTANT, SYSTEM }

    public static final class Turn {
        public final Role role;
        public final String text;

        public Turn(Role role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    private ClaudeHistoryImporter() {}

    public static List<Turn> read(File file) {
        List<Turn> out = new ArrayList<>();
        if (file == null || !file.exists()) return out;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    parse(new JSONObject(line), out);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void parse(JSONObject obj, List<Turn> out) {
        String type = obj.optString("type");
        JSONObject msg = obj.optJSONObject("message");
        if (msg == null) return;
        if ("user".equals(type)) {
            parseUser(msg.opt("content"), out);
            return;
        }
        if ("assistant".equals(type)) parseAssistant(msg.optJSONArray("content"), out);
    }

    private static void parseUser(Object content, List<Turn> out) {
        if (content instanceof String) {
            add(out, Role.USER, (String) content);
            return;
        }
        if (!(content instanceof JSONArray)) return;
        JSONArray arr = (JSONArray) content;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type");
            if ("text".equals(type)) {
                if (text.length() > 0) text.append('\n');
                text.append(item.optString("text"));
            } else if ("tool_result".equals(type)) {
                add(out, Role.SYSTEM, summarizeTool(item.opt("content")));
            }
        }
        add(out, Role.USER, text.toString());
    }

    private static void parseAssistant(JSONArray arr, List<Turn> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type");
            if ("text".equals(type)) {
                add(out, Role.ASSISTANT, item.optString("text"));
            } else if ("tool_use".equals(type)) {
                add(out, Role.SYSTEM, "Tool call: " + item.optString("name", "?"));
            }
        }
    }

    private static String summarizeTool(Object content) {
        String text = String.valueOf(content == null ? "" : content).trim();
        if (text.length() > MAX_TOOL_TEXT || text.contains(";base64,")) {
            return "large tool result omitted";
        }
        return "Tool result: " + text;
    }

    private static void add(List<Turn> out, Role role, String text) {
        String s = text == null ? "" : text.trim();
        if (!s.isEmpty()) out.add(new Turn(role, s));
    }
}
```

- [ ] **Step 4: Run importer tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.ClaudeHistoryImporterTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/termux/app/ClaudeHistoryImporter.java app/src/test/java/com/termux/app/ClaudeHistoryImporterTest.java
git commit -m "feat: parse claude history for codex migration"
```

---

### Task 2: Codex Migration Prompt And Archive

**Files:**
- Create: `app/src/main/java/com/termux/app/CodexMigrationPrompt.java`
- Test: `app/src/test/java/com/termux/app/CodexMigrationPromptTest.java`

- [ ] **Step 1: Write failing prompt tests**

Create `app/src/test/java/com/termux/app/CodexMigrationPromptTest.java`:

```java
package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CodexMigrationPromptTest {

    @Test
    public void buildsHeaderAndTurns() {
        List<ClaudeHistoryImporter.Turn> turns = new ArrayList<>();
        turns.add(new ClaudeHistoryImporter.Turn(ClaudeHistoryImporter.Role.USER, "build it"));
        turns.add(new ClaudeHistoryImporter.Turn(ClaudeHistoryImporter.Role.ASSISTANT, "done"));

        String prompt = CodexMigrationPrompt.initial("abc", "preview", turns);

        Assert.assertTrue(prompt.contains("Migrated from Claude Code session abc"));
        Assert.assertTrue(prompt.contains("User: build it"));
        Assert.assertTrue(prompt.contains("Assistant: done"));
        Assert.assertTrue(prompt.contains("Do not rerun historical tool calls"));
    }

    @Test
    public void boundsLongTranscripts() {
        List<ClaudeHistoryImporter.Turn> turns = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            turns.add(new ClaudeHistoryImporter.Turn(ClaudeHistoryImporter.Role.USER, "message-" + i + " " + repeat("x", 200)));
        }

        String prompt = CodexMigrationPrompt.initial("abc", "preview", turns);

        Assert.assertTrue(prompt.length() <= CodexMigrationPrompt.MAX_PROMPT_CHARS);
        Assert.assertTrue(prompt.contains("Older turns were summarized"));
        Assert.assertTrue(prompt.contains("message-199"));
    }

    @Test
    public void writesArchiveJsonl() {
        List<ClaudeHistoryImporter.Turn> turns = new ArrayList<>();
        turns.add(new ClaudeHistoryImporter.Turn(ClaudeHistoryImporter.Role.SYSTEM, "Tool call: read"));

        String jsonl = CodexMigrationPrompt.archive("abc", turns);

        Assert.assertTrue(jsonl.contains("\"source_session_id\":\"abc\""));
        Assert.assertTrue(jsonl.contains("\"role\":\"system\""));
        Assert.assertTrue(jsonl.endsWith("\n"));
    }

    @Test
    public void readsArchiveJsonl() throws Exception {
        java.io.File f = java.io.File.createTempFile("codex-archive", ".jsonl");
        java.nio.file.Files.write(f.toPath(), (
            "{\"source_session_id\":\"abc\",\"role\":\"user\",\"text\":\"hello\"}\n" +
            "{\"source_session_id\":\"abc\",\"role\":\"assistant\",\"text\":\"hi\"}\n"
        ).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<ClaudeHistoryImporter.Turn> turns = CodexMigrationPrompt.readArchive(f);

        Assert.assertEquals(2, turns.size());
        Assert.assertEquals(ClaudeHistoryImporter.Role.USER, turns.get(0).role);
        Assert.assertEquals("hello", turns.get(0).text);
        Assert.assertEquals(ClaudeHistoryImporter.Role.ASSISTANT, turns.get(1).role);
        Assert.assertEquals("hi", turns.get(1).text);
    }

    private static String repeat(String s, int count) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) b.append(s);
        return b.toString();
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.CodexMigrationPromptTest
```

Expected: FAIL because `CodexMigrationPrompt` does not exist.

- [ ] **Step 3: Implement prompt builder**

Create `app/src/main/java/com/termux/app/CodexMigrationPrompt.java`:

```java
package com.termux.app;

import org.json.JSONObject;

import java.util.List;

public final class CodexMigrationPrompt {

    public static final int MAX_PROMPT_CHARS = 24000;
    private static final int TAIL_TURNS = 40;

    private CodexMigrationPrompt() {}

    public static String initial(String source, String preview, List<ClaudeHistoryImporter.Turn> turns) {
        StringBuilder b = new StringBuilder();
        b.append("Migrated from Claude Code session ").append(source).append(".\n");
        if (preview != null && !preview.trim().isEmpty()) {
            b.append("Original preview: ").append(preview.trim()).append("\n");
        }
        b.append("Use this as conversation context for continuing in Codex. ");
        b.append("Do not rerun historical tool calls unless the user explicitly asks.\n\n");

        int start = Math.max(0, turns.size() - TAIL_TURNS);
        if (start > 0) {
            b.append("Older turns were summarized: ");
            b.append(start).append(" earlier turns exist in the original Claude history and were omitted for size.\n\n");
        }
        for (int i = start; i < turns.size(); i++) {
            ClaudeHistoryImporter.Turn t = turns.get(i);
            b.append(label(t.role)).append(": ").append(t.text).append("\n\n");
        }
        return bound(b.toString());
    }

    public static String archive(String source, List<ClaudeHistoryImporter.Turn> turns) {
        StringBuilder b = new StringBuilder();
        for (ClaudeHistoryImporter.Turn t : turns) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("source_session_id", source);
                obj.put("role", t.role.name().toLowerCase(java.util.Locale.US));
                obj.put("text", t.text);
                b.append(obj.toString()).append('\n');
            } catch (Exception ignored) {}
        }
        return b.toString();
    }

    public static String continuation(List<ClaudeHistoryImporter.Turn> turns, String input) {
        String base = initial("app-archive", "", turns);
        return bound(base + "\nNew user message:\n" + input);
    }

    public static List<ClaudeHistoryImporter.Turn> readArchive(java.io.File file) {
        List<ClaudeHistoryImporter.Turn> out = new java.util.ArrayList<>();
        if (file == null || !file.exists()) return out;
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                org.json.JSONObject obj = new org.json.JSONObject(line);
                String role = obj.optString("role");
                ClaudeHistoryImporter.Role r = "assistant".equals(role)
                        ? ClaudeHistoryImporter.Role.ASSISTANT
                        : ("system".equals(role) ? ClaudeHistoryImporter.Role.SYSTEM : ClaudeHistoryImporter.Role.USER);
                out.add(new ClaudeHistoryImporter.Turn(r, obj.optString("text")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String label(ClaudeHistoryImporter.Role role) {
        if (role == ClaudeHistoryImporter.Role.USER) return "User";
        if (role == ClaudeHistoryImporter.Role.ASSISTANT) return "Assistant";
        return "System";
    }

    private static String bound(String text) {
        if (text.length() <= MAX_PROMPT_CHARS) return text;
        return text.substring(0, 4000)
                + "\n\n[Middle content omitted during migration]\n\n"
                + text.substring(text.length() - (MAX_PROMPT_CHARS - 4050));
    }
}
```

- [ ] **Step 4: Run prompt tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.CodexMigrationPromptTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/termux/app/CodexMigrationPrompt.java app/src/test/java/com/termux/app/CodexMigrationPromptTest.java
git commit -m "feat: build codex migration prompts"
```

---

### Task 3: Codex Migration Store

**Files:**
- Create: `app/src/main/java/com/termux/app/CodexMigrationStore.java`
- Test: `app/src/test/java/com/termux/app/CodexMigrationStoreTest.java`

- [ ] **Step 1: Write failing store tests**

Create `app/src/test/java/com/termux/app/CodexMigrationStoreTest.java`:

```java
package com.termux.app;

import android.content.Context;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class CodexMigrationStoreTest {

    private Context ctx;
    private CodexMigrationStore store;

    @Before
    public void setup() {
        ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("codex_migrated_sessions", Context.MODE_PRIVATE).edit().clear().commit();
        store = new CodexMigrationStore(ctx);
    }

    @Test
    public void savesAndLoadsReadyMapping() {
        store.markReady("claude-1", "codex-1", "preview");

        CodexMigrationStore.Entry e = store.get("claude-1");

        Assert.assertNotNull(e);
        Assert.assertEquals("claude-1", e.sourceSessionId);
        Assert.assertEquals("codex-1", e.codexSessionId);
        Assert.assertEquals(CodexMigrationStore.STATUS_READY, e.status);
        Assert.assertEquals("preview", e.preview);
    }

    @Test
    public void writesAndDeletesArchiveWithoutSourcePath() throws Exception {
        store.writeArchive("claude-1", "{\"role\":\"user\"}\n");

        Assert.assertTrue(store.archiveFile("claude-1").exists());
        Assert.assertEquals("{\"role\":\"user\"}\n", new String(
            Files.readAllBytes(store.archiveFile("claude-1").toPath()), StandardCharsets.UTF_8));

        store.delete("claude-1");

        Assert.assertNull(store.get("claude-1"));
        Assert.assertFalse(store.archiveFile("claude-1").exists());
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.CodexMigrationStoreTest
```

Expected: FAIL because `CodexMigrationStore` does not exist.

- [ ] **Step 3: Implement store**

Create `app/src/main/java/com/termux/app/CodexMigrationStore.java`:

```java
package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CodexMigrationStore {

    public static final String STATUS_MIGRATING = "migrating";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED = "failed";

    private static final String PREFS = "codex_migrated_sessions";
    private static final String K_IDS = "ids";

    public static class Entry {
        public final String sourceSessionId;
        public final String codexSessionId;
        public final long createdAt;
        public final long updatedAt;
        public final String preview;
        public final String status;
        public final String error;

        Entry(String sourceSessionId, String codexSessionId, long createdAt, long updatedAt,
              String preview, String status, String error) {
            this.sourceSessionId = sourceSessionId;
            this.codexSessionId = codexSessionId;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.preview = preview;
            this.status = status;
            this.error = error;
        }
    }

    private final Context ctx;
    private final SharedPreferences prefs;

    public CodexMigrationStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Entry get(String source) {
        if (!ids().contains(source)) return null;
        return new Entry(source,
                prefs.getString(key(source, "codex"), ""),
                prefs.getLong(key(source, "created"), 0),
                prefs.getLong(key(source, "updated"), 0),
                prefs.getString(key(source, "preview"), ""),
                prefs.getString(key(source, "status"), ""),
                prefs.getString(key(source, "error"), ""));
    }

    public List<Entry> loadAll() {
        List<Entry> out = new ArrayList<>();
        for (String id : ids()) {
            Entry e = get(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    public void markMigrating(String source, String preview) {
        save(source, "", preview, STATUS_MIGRATING, "");
    }

    public void markReady(String source, String codex, String preview) {
        save(source, codex == null ? "" : codex, preview, STATUS_READY, "");
    }

    public void markFailed(String source, String preview, String error) {
        save(source, "", preview, STATUS_FAILED, error == null ? "" : error);
    }

    public void writeArchive(String source, String text) {
        try {
            File f = archiveFile(source);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public File archiveFile(String source) {
        return new File(new File(ctx.getFilesDir(), "codex-migrations"), safe(source) + ".jsonl");
    }

    public void delete(String source) {
        Set<String> ids = ids();
        ids.remove(source);
        SharedPreferences.Editor ed = prefs.edit().putStringSet(K_IDS, ids);
        ed.remove(key(source, "codex"));
        ed.remove(key(source, "created"));
        ed.remove(key(source, "updated"));
        ed.remove(key(source, "preview"));
        ed.remove(key(source, "status"));
        ed.remove(key(source, "error"));
        ed.apply();
        File f = archiveFile(source);
        if (f.exists()) f.delete();
    }

    private void save(String source, String codex, String preview, String status, String error) {
        long now = System.currentTimeMillis();
        Entry old = get(source);
        Set<String> ids = ids();
        ids.add(source);
        prefs.edit()
                .putStringSet(K_IDS, ids)
                .putString(key(source, "codex"), codex)
                .putLong(key(source, "created"), old == null ? now : old.createdAt)
                .putLong(key(source, "updated"), now)
                .putString(key(source, "preview"), preview == null ? "" : preview)
                .putString(key(source, "status"), status)
                .putString(key(source, "error"), error)
                .apply();
    }

    private Set<String> ids() {
        return new HashSet<>(prefs.getStringSet(K_IDS, new HashSet<>()));
    }

    private static String key(String source, String suffix) {
        return safe(source) + "_" + suffix;
    }

    private static String safe(String source) {
        return source == null ? "" : source.replaceAll("[^a-zA-Z0-9-]", "");
    }
}
```

- [ ] **Step 4: Run store tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.CodexMigrationStoreTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/termux/app/CodexMigrationStore.java app/src/test/java/com/termux/app/CodexMigrationStoreTest.java
git commit -m "feat: store codex migration metadata"
```

---

### Task 4: Codex Runner

**Files:**
- Create: `app/src/main/java/com/termux/app/CodexRunner.java`
- Test: `app/src/test/java/com/termux/app/CodexRunnerTest.java`

- [ ] **Step 1: Write failing runner tests**

Create `app/src/test/java/com/termux/app/CodexRunnerTest.java`:

```java
package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class CodexRunnerTest {

    @Test
    public void buildsInitialCommand() {
        List<String> cmd = CodexRunner.command("/usr/bin/proot-distro", "", false);

        Assert.assertEquals("/usr/bin/proot-distro", cmd.get(0));
        Assert.assertTrue(cmd.contains("ubuntu"));
        Assert.assertTrue(cmd.contains("claude"));
        Assert.assertTrue(cmd.get(cmd.size() - 1).contains("codex exec --json --skip-git-repo-check -"));
    }

    @Test
    public void buildsResumeCommandWithSafeId() {
        List<String> cmd = CodexRunner.command("proot-distro", "abc-123", true);

        Assert.assertTrue(cmd.get(cmd.size() - 1).contains("codex exec resume --json --skip-git-repo-check abc-123 -"));
    }

    @Test
    public void parsesSessionIdFromJsonEvents() {
        CodexRunner.Result r = CodexRunner.parse(
            "{\"type\":\"session_configured\",\"session_id\":\"11111111-1111-1111-1111-111111111111\"}\n" +
            "{\"type\":\"message\",\"text\":\"hello\"}\n"
        );

        Assert.assertEquals("11111111-1111-1111-1111-111111111111", r.sessionId);
        Assert.assertTrue(r.text.contains("hello"));
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.CodexRunnerTest
```

Expected: FAIL because `CodexRunner` does not exist.

- [ ] **Step 3: Implement runner**

Create `app/src/main/java/com/termux/app/CodexRunner.java`:

```java
package com.termux.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CodexRunner {

    public static final class Result {
        public final String sessionId;
        public final String text;
        public final int exitCode;

        Result(String sessionId, String text, int exitCode) {
            this.sessionId = sessionId;
            this.text = text;
            this.exitCode = exitCode;
        }
    }

    private CodexRunner() {}

    public static List<String> command(String proot, String sessionId, boolean resume) {
        String safe = sessionId == null ? "" : sessionId.replaceAll("[^a-zA-Z0-9-]", "");
        String cli = resume && !safe.isEmpty()
                ? "codex exec resume --json --skip-git-repo-check " + safe + " -"
                : "codex exec --json --skip-git-repo-check -";
        List<String> cmd = new ArrayList<>();
        cmd.add(proot);
        cmd.add("login");
        cmd.add("ubuntu");
        cmd.add("--user");
        cmd.add("claude");
        cmd.add("--");
        cmd.add("sh");
        cmd.add("-lc");
        cmd.add(cli);
        return cmd;
    }

    public static Result run(String proot, String prompt, String sessionId) throws Exception {
        boolean resume = sessionId != null && !sessionId.isEmpty();
        Process p = new ProcessBuilder(command(proot, sessionId, resume)).start();
        try (OutputStream out = p.getOutputStream()) {
            out.write(prompt.getBytes(StandardCharsets.UTF_8));
        }
        String stdout = read(p.getInputStream());
        String stderr = read(p.getErrorStream());
        int code = p.waitFor();
        Result r = parse(stdout);
        String text = r.text.isEmpty() ? stderr : r.text;
        return new Result(r.sessionId, text, code);
    }

    public static Result parse(String jsonl) {
        String session = "";
        StringBuilder text = new StringBuilder();
        String[] lines = jsonl == null ? new String[0] : jsonl.split("\\r?\\n");
        for (String line : lines) {
            try {
                JSONObject obj = new JSONObject(line);
                if (session.isEmpty()) {
                    session = first(obj.optString("session_id"), obj.optString("sessionId"));
                    JSONObject nested = obj.optJSONObject("session");
                    if (session.isEmpty() && nested != null) session = nested.optString("id");
                }
                String part = first(obj.optString("text"), obj.optString("message"), obj.optString("content"));
                if (!part.isEmpty()) {
                    if (text.length() > 0) text.append('\n');
                    text.append(part);
                }
            } catch (Exception ignored) {}
        }
        return new Result(session, text.toString(), 0);
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String first(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    private static String first(String a, String b, String c) {
        String v = first(a, b);
        return !v.isEmpty() ? v : (c == null ? "" : c);
    }
}
```

- [ ] **Step 4: Run runner tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.CodexRunnerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/termux/app/CodexRunner.java app/src/test/java/com/termux/app/CodexRunnerTest.java
git commit -m "feat: add codex cli runner"
```

---

### Task 5: History UI Migration Action

**Files:**
- Modify: `app/src/main/java/com/termux/app/HomeFragment.java`
- Modify: `app/src/main/java/com/termux/app/SessionAdapter.java`

- [ ] **Step 1: Add migration badge support to `SessionAdapter`**

Modify `SessionAdapter`:

```java
private java.util.Map<String, String> mBadges = new java.util.HashMap<>();

public void setBadges(java.util.Map<String, String> badges) {
    mBadges = badges == null ? new java.util.HashMap<>() : badges;
    notifyDataSetChanged();
}
```

In `onBindViewHolder`, replace preview binding with:

```java
String label = e.preview.isEmpty() ? "empty conversation" : e.preview;
String badge = mBadges.get(e.id);
holder.preview.setText((badge == null || badge.isEmpty()) ? label : badge + " · " + label);
```

- [ ] **Step 2: Add migration fields to `HomeFragment`**

Near existing session fields:

```java
private CodexMigrationStore mCodexStore;
private static final String AGENT_CLAUDE = "claude";
private static final String AGENT_CODEX = "codex";
private String mActiveAgent = AGENT_CLAUDE;
```

After `mSessionStore = new SessionStore(requireContext());`:

```java
mCodexStore = new CodexMigrationStore(requireContext());
```

- [ ] **Step 3: Add badge refresh helper**

Add near `refreshSessionDrawer()`:

```java
private void refreshMigrationBadges() {
    if (mSessionAdapter == null || mCodexStore == null) return;
    java.util.Map<String, String> badges = new java.util.HashMap<>();
    for (CodexMigrationStore.Entry e : mCodexStore.loadAll()) {
        if (CodexMigrationStore.STATUS_READY.equals(e.status)) {
            badges.put(e.sourceSessionId, "Claude -> Codex");
        } else if (CodexMigrationStore.STATUS_FAILED.equals(e.status)) {
            badges.put(e.sourceSessionId, "Migration failed");
        } else if (CodexMigrationStore.STATUS_MIGRATING.equals(e.status)) {
            badges.put(e.sourceSessionId, "Migrating");
        }
    }
    mSessionAdapter.setBadges(badges);
}
```

Call `refreshMigrationBadges();` after the adapter is assigned and inside `refreshSessionDrawer()`.

- [ ] **Step 4: Replace long-press delete-only dialog with action dialog**

In `onSessionLongPress`, replace the current `AlertDialog.Builder` with:

```java
String[] actions = new String[] { "Migrate to Codex", "Delete Claude history" };
new AlertDialog.Builder(getContext())
    .setTitle(entry.preview.isEmpty() ? "History action" : entry.preview)
    .setItems(actions, (dialog, which) -> {
        if (which == 0) {
            migrateToCodex(entry);
            return;
        }
        confirmDeleteClaudeHistory(entry, emptyHint);
    })
    .show();
```

Move the existing delete body into:

```java
private void confirmDeleteClaudeHistory(SessionStore.Entry entry, TextView emptyHint) {
    if (getContext() == null) return;
    new AlertDialog.Builder(getContext())
        .setTitle("Delete this history record?")
        .setMessage(entry.preview.isEmpty() ? "(empty conversation)" : entry.preview)
        .setPositiveButton("Delete", (d, w) -> {
            mSessionStore.delete(entry.id);
            mSessionEntries.remove(entry);
            mSessionAdapter.notifyDataSetChanged();
            emptyHint.setVisibility(mSessionEntries.isEmpty() ? View.VISIBLE : View.GONE);
            List<UploadStore.Entry> files = mUploadStore.deleteSession(entry.id);
            if (!files.isEmpty()) {
                final List<String> uuids = new ArrayList<>();
                for (UploadStore.Entry e : files) uuids.add(e.uuid);
                new Thread(() -> deleteUbuntuUploadDirs(uuids)).start();
            }
            new Thread(() -> deleteClaudeSessionJsonl(entry.id)).start();
        })
        .setNegativeButton("Cancel", null)
        .show();
}
```

- [ ] **Step 5: Add migration worker**

Add to `HomeFragment`:

```java
private void migrateToCodex(SessionStore.Entry entry) {
    CodexMigrationStore.Entry old = mCodexStore.get(entry.id);
    if (old != null && CodexMigrationStore.STATUS_READY.equals(old.status)) {
        openCodexSession(entry, old);
        return;
    }
    mCodexStore.markMigrating(entry.id, entry.preview);
    refreshMigrationBadges();
    mAdapter.addMessage(ChatMessage.system("Migrating Claude history to Codex..."));
    new Thread(() -> {
        File file = new File(CLAUDE_PROJECTS_DIR + "/" + entry.id + ".jsonl");
        if (!file.exists()) {
            mCodexStore.markFailed(entry.id, entry.preview, "Claude history file is missing");
            mHandler.post(this::refreshMigrationBadges);
            return;
        }
        List<ClaudeHistoryImporter.Turn> turns = ClaudeHistoryImporter.read(file);
        String archive = CodexMigrationPrompt.archive(entry.id, turns);
        mCodexStore.writeArchive(entry.id, archive);
        String prompt = CodexMigrationPrompt.initial(entry.id, entry.preview, turns);
        try {
            CodexRunner.Result result = CodexRunner.run(PROOT_D, prompt, "");
            if (result.exitCode != 0) {
                mCodexStore.markFailed(entry.id, entry.preview, result.text);
            } else {
                mCodexStore.markReady(entry.id, result.sessionId, entry.preview);
            }
        } catch (Exception e) {
            mCodexStore.markFailed(entry.id, entry.preview, e.getMessage());
        }
        mHandler.post(() -> {
            refreshMigrationBadges();
            CodexMigrationStore.Entry migrated = mCodexStore.get(entry.id);
            if (migrated != null && CodexMigrationStore.STATUS_READY.equals(migrated.status)) {
                openCodexSession(entry, migrated);
            } else {
                mAdapter.addMessage(ChatMessage.system("Codex migration failed. Original Claude history is unchanged."));
            }
        });
    }, "codex-migration").start();
}
```

- [ ] **Step 6: Add Codex open helper**

Add to `HomeFragment`:

```java
private void openCodexSession(SessionStore.Entry source, CodexMigrationStore.Entry migrated) {
    stopClaudeProcess();
    mActiveAgent = AGENT_CODEX;
    mCurrentSessionId = source.id;
    mResumeSessionId = null;
    mSessionStarted = false;
    mMessages.clear();
    mMessages.add(ChatMessage.system("Claude history migrated to Codex. Original Claude context is unchanged."));
    mMessages.addAll(loadCodexArchive(source.id));
    mAdapter.notifyDataSetChanged();
    updateSessionTitle("Codex: " + source.preview);
    mSessionAdapter.setActiveId(source.id);
    scrollToBottom();
}

private List<ChatMessage> loadCodexArchive(String source) {
    List<ChatMessage> out = new ArrayList<>();
    File f = mCodexStore.archiveFile(source);
    for (ClaudeHistoryImporter.Turn t : CodexMigrationPrompt.readArchive(f)) {
        if (t.role == ClaudeHistoryImporter.Role.USER) out.add(ChatMessage.user(t.text));
        else if (t.role == ClaudeHistoryImporter.Role.ASSISTANT) out.add(ChatMessage.assistant(t.text));
        else out.add(ChatMessage.system(t.text));
    }
    return out;
}
```

- [ ] **Step 7: Run compile check**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/com/termux/app/HomeFragment.java app/src/main/java/com/termux/app/SessionAdapter.java
git commit -m "feat: add codex migration action"
```

---

### Task 6: Codex Follow-Up Routing

**Files:**
- Modify: `app/src/main/java/com/termux/app/HomeFragment.java`
- Modify: `app/src/main/java/com/termux/app/CodexMigrationPrompt.java`
- Test: `app/src/test/java/com/termux/app/CodexMigrationPromptTest.java`

- [ ] **Step 1: Add continuation archive parser test**

Extend `CodexMigrationPromptTest`:

```java
@Test
public void continuationIncludesNewMessage() {
    List<ClaudeHistoryImporter.Turn> turns = new ArrayList<>();
    turns.add(new ClaudeHistoryImporter.Turn(ClaudeHistoryImporter.Role.USER, "old"));

    String prompt = CodexMigrationPrompt.continuation(turns, "new");

    Assert.assertTrue(prompt.contains("User: old"));
    Assert.assertTrue(prompt.contains("New user message:"));
    Assert.assertTrue(prompt.contains("new"));
}
```

- [ ] **Step 2: Run prompt test**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.CodexMigrationPromptTest
```

Expected: PASS if Task 2 already added `continuation`; otherwise FAIL and implement it now.

- [ ] **Step 3: Route send action by active agent**

In `sendOrConfirm()`, place this block after the existing `displayText` construction and before the `if (apiKey == null || apiKey.isEmpty())` block:

```java
if (AGENT_CODEX.equals(mActiveAgent)) {
    sendCodexMessage(text, displayText);
    return;
}
```

- [ ] **Step 4: Implement Codex send path**

Add to `HomeFragment`:

```java
private void sendCodexMessage(String text, String displayText) {
    CodexMigrationStore.Entry e = mCodexStore.get(mCurrentSessionId);
    if (e == null) {
        mAdapter.addMessage(ChatMessage.system("No Codex migration exists for this history item."));
        return;
    }
    mAdapter.addMessage(ChatMessage.user(displayText));
    mInputEdit.setText("");
    mAdapter.addMessage(ChatMessage.assistant(""));
    scrollToBottom();

    new Thread(() -> {
        try {
            CodexRunner.Result result;
            if (e.codexSessionId != null && !e.codexSessionId.isEmpty()) {
                result = CodexRunner.run(PROOT_D, text, e.codexSessionId);
            } else {
                List<ClaudeHistoryImporter.Turn> turns = readCodexArchiveTurns(mCurrentSessionId);
                result = CodexRunner.run(PROOT_D, CodexMigrationPrompt.continuation(turns, text), "");
            }
            String reply = result.text == null || result.text.trim().isEmpty()
                    ? "(Codex returned no visible text)"
                    : result.text.trim();
            appendCodexArchiveTurn(mCurrentSessionId, ClaudeHistoryImporter.Role.USER, text);
            appendCodexArchiveTurn(mCurrentSessionId, ClaudeHistoryImporter.Role.ASSISTANT, reply);
            mHandler.post(() -> {
                mAdapter.updateLastAssistant(reply);
                scrollToBottom();
            });
        } catch (Exception ex) {
            mHandler.post(() -> mAdapter.updateLastAssistant("Codex failed: " + ex.getMessage()));
        }
    }, "codex-send").start();
}
```

Add helpers:

```java
private List<ClaudeHistoryImporter.Turn> readCodexArchiveTurns(String source) {
    return CodexMigrationPrompt.readArchive(mCodexStore.archiveFile(source));
}

private void appendCodexArchiveTurn(String source, ClaudeHistoryImporter.Role role, String text) {
    List<ClaudeHistoryImporter.Turn> turns = readCodexArchiveTurns(source);
    turns.add(new ClaudeHistoryImporter.Turn(role, text));
    mCodexStore.writeArchive(source, CodexMigrationPrompt.archive(source, turns));
}
```

- [ ] **Step 5: Reset new chat to Claude mode**

Where the new-session button clears state, add:

```java
mActiveAgent = AGENT_CLAUDE;
```

- [ ] **Step 6: Run tests and compile**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.CodexMigrationPromptTest
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/termux/app/HomeFragment.java app/src/main/java/com/termux/app/CodexMigrationPrompt.java app/src/test/java/com/termux/app/CodexMigrationPromptTest.java
git commit -m "feat: continue migrated histories with codex"
```

---

### Task 7: Final Verification

- [ ] **Step 1: Run all focused unit tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.ClaudeHistoryImporterTest --tests com.termux.app.CodexMigrationPromptTest --tests com.termux.app.CodexMigrationStoreTest --tests com.termux.app.CodexRunnerTest
```

Expected: PASS.

- [ ] **Step 2: Run Java compile**

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build debug APK**

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: BUILD SUCCESSFUL and APK under `app/build/outputs/apk/debug/`.

- [ ] **Step 4: Manual smoke test on device**

Use an installed app with at least one Claude history item:

1. Open Home.
2. Open History.
3. Long press a Claude history item.
4. Tap `Migrate to Codex`.
5. Confirm the row badge changes to `Claude -> Codex`.
6. Open the migrated row.
7. Send `Continue from this migrated conversation with one short sentence.`
8. Confirm Codex responds.
9. Open the original Claude row path, if exposed by the UI, and confirm it still displays the original transcript.

- [ ] **Step 5: Verify source preservation**

Before migration, count Claude source jsonl files:

```sh
adb shell run-as com.termux sh -c 'ls files/usr/var/lib/proot-distro/installed-rootfs/ubuntu/home/claude/.claude/projects/-home-claude/*.jsonl 2>/dev/null | wc -l'
```

After migration and after deleting the migrated Codex entry, run the same command again.

Expected: the count is unchanged unless the user explicitly deleted a Claude history item.
