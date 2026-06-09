# Codex Provider Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Codex support with a global Claude/Codex provider switch, separate `claude` and `codex` Ubuntu users, provider-specific key management, and provider-aware AgentServer/Loom setup.

**Architecture:** Keep one shared Ubuntu rootfs and shared addon binaries. Add small provider model classes that describe each backend, keep provider-specific state under `/home/claude` and `/home/codex`, and move command/config generation into testable builder classes before touching Fragment integration.

**Tech Stack:** Android Java, SharedPreferences, Robolectric/JUnit4, Gradle, Ubuntu proot-distro, Claude Code CLI, OpenAI Codex CLI, AgentServer, Loom.

---

## File Structure

Create:

- `app/src/main/java/com/termux/app/AssistantProvider.java`
  - Enum for `CLAUDE` and `CODEX`.
- `app/src/main/java/com/termux/app/ProviderProfile.java`
  - Immutable provider metadata: display name, user, home, CLI, env names, directories.
- `app/src/main/java/com/termux/app/ProviderSettingsStore.java`
  - SharedPreferences wrapper for selected provider.
- `app/src/main/java/com/termux/app/ProviderEnvironmentWriter.java`
  - Writes provider key exports into provider user `.bashrc`.
- `app/src/main/java/com/termux/app/CodexExecSession.java`
  - Runs Codex CLI per turn and emits parsed text back to Home.
- `app/src/main/java/com/termux/app/AgentServerCommandBuilder.java`
  - Builds provider-aware AgentServer scripts.
- `app/src/main/java/com/termux/app/autotasks/AndroidCapabilityPromptBuilder.java`
  - Shared Android capability prompt content for Claude `CLAUDE.md` and Codex `AGENTS.md`.
- `app/src/main/java/com/termux/app/autotasks/AutoCodexManager.java`
  - Writes and injects Codex setup script.
- `app/src/test/java/com/termux/app/ProviderProfileTest.java`
- `app/src/test/java/com/termux/app/ProviderSettingsStoreTest.java`
- `app/src/test/java/com/termux/app/ApiKeyStoreProviderTest.java`
- `app/src/test/java/com/termux/app/ProviderEnvironmentWriterTest.java`
- `app/src/test/java/com/termux/app/CodexExecSessionTest.java`
- `app/src/test/java/com/termux/app/AgentServerCommandBuilderTest.java`
- `app/src/test/java/com/termux/app/autotasks/AndroidCapabilityPromptBuilderTest.java`
- `app/src/test/java/com/termux/app/autotasks/AutoCodexManagerScriptTest.java`

Modify:

- `app/src/main/java/com/termux/app/ApiKeyStore.java`
  - Add provider-aware constructor while keeping existing Claude default.
- `app/src/main/java/com/termux/app/ApiKeyFragment.java`
  - Add top Claude/Codex selector and reload list per provider.
- `app/src/main/java/com/termux/app/TermuxActivity.java`
  - Delegate active key writes through `ProviderEnvironmentWriter`.
- `app/src/main/java/com/termux/app/HomeFragment.java`
  - Add provider selector title and route sends/stops/new sessions.
- `app/src/main/res/layout/fragment_home.xml`
  - Make title visually clickable.
- `app/src/main/res/layout/fragment_api_key.xml`
  - Add top selector row.
- `app/src/main/java/com/termux/app/AgentServerFragment.java`
  - Use `AgentServerCommandBuilder` and selected provider.
- `app/src/main/res/layout/fragment_agent_server.xml`
  - Show selected provider hint.
- `app/src/main/java/com/termux/app/autotasks/AutoTaskCoordinator.java`
  - Construct `AutoCodexManager`.
- `app/src/main/java/com/termux/app/autotasks/AutoUbuntuManager.java`
  - Inject Codex setup and write Codex capability files.
- `app/src/main/java/com/termux/app/autotasks/AutoClaudeManager.java`
  - Reuse shared prompt builder for Claude content.
- `app/src/main/java/com/termux/app/loom/LoomSettings.java`
  - Store provider/backend.
- `app/src/main/java/com/termux/app/loom/LoomConfigRenderer.java`
  - Render Claude or Codex backend YAML.
- `app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java`
  - Use provider-specific user/path/process patterns.
- `app/src/main/java/com/termux/app/LoomFragment.java`
  - Save/render provider and show current backend.
- `app/src/main/res/layout/fragment_loom.xml`
  - Show current provider hint.
- `app/src/test/java/com/termux/app/loom/LoomConfigRendererTest.java`
- `app/src/test/java/com/termux/app/loom/LoomCommandBuilderTest.java`

---

### Task 1: Provider Core Model

**Files:**
- Create: `app/src/main/java/com/termux/app/AssistantProvider.java`
- Create: `app/src/main/java/com/termux/app/ProviderProfile.java`
- Create: `app/src/main/java/com/termux/app/ProviderSettingsStore.java`
- Test: `app/src/test/java/com/termux/app/ProviderProfileTest.java`
- Test: `app/src/test/java/com/termux/app/ProviderSettingsStoreTest.java`

- [ ] **Step 1: Write provider model tests**

Create `app/src/test/java/com/termux/app/ProviderProfileTest.java`:

```java
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
```

Create `app/src/test/java/com/termux/app/ProviderSettingsStoreTest.java`:

```java
package com.termux.app;

import android.content.Context;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ProviderSettingsStoreTest {

    @Test
    public void defaultsToClaude() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(ProviderSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit();

        ProviderSettingsStore store = new ProviderSettingsStore(context);

        Assert.assertEquals(AssistantProvider.CLAUDE, store.getSelectedProvider());
    }

    @Test
    public void persistsCodexSelection() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(ProviderSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit();

        ProviderSettingsStore store = new ProviderSettingsStore(context);
        store.setSelectedProvider(AssistantProvider.CODEX);

        Assert.assertEquals(AssistantProvider.CODEX,
            new ProviderSettingsStore(context).getSelectedProvider());
    }
}
```

- [ ] **Step 2: Run tests and confirm they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.ProviderProfileTest --tests com.termux.app.ProviderSettingsStoreTest
```

Expected: FAIL because `AssistantProvider`, `ProviderProfile`, and `ProviderSettingsStore` do not exist.

- [ ] **Step 3: Add `AssistantProvider`**

Create `app/src/main/java/com/termux/app/AssistantProvider.java`:

```java
package com.termux.app;

public enum AssistantProvider {
    CLAUDE("claude"),
    CODEX("codex");

    public final String id;

    AssistantProvider(String id) {
        this.id = id;
    }

    public static AssistantProvider fromId(String id) {
        if (CODEX.id.equals(id)) return CODEX;
        return CLAUDE;
    }
}
```

- [ ] **Step 4: Add `ProviderProfile`**

Create `app/src/main/java/com/termux/app/ProviderProfile.java`:

```java
package com.termux.app;

public final class ProviderProfile {

    public final AssistantProvider provider;
    public final String displayName;
    public final String user;
    public final String home;
    public final String cliBinary;
    public final String apiKeyEnv;
    public final String baseUrlEnv;
    public final String memoryDir;
    public final String commandsDir;
    public final String instructionsFile;

    private ProviderProfile(
        AssistantProvider provider,
        String displayName,
        String user,
        String home,
        String cliBinary,
        String apiKeyEnv,
        String baseUrlEnv,
        String memoryDir,
        String commandsDir,
        String instructionsFile) {
        this.provider = provider;
        this.displayName = displayName;
        this.user = user;
        this.home = home;
        this.cliBinary = cliBinary;
        this.apiKeyEnv = apiKeyEnv;
        this.baseUrlEnv = baseUrlEnv == null ? "" : baseUrlEnv;
        this.memoryDir = memoryDir;
        this.commandsDir = commandsDir;
        this.instructionsFile = instructionsFile;
    }

    public static ProviderProfile forProvider(AssistantProvider provider) {
        if (provider == AssistantProvider.CODEX) {
            return new ProviderProfile(
                AssistantProvider.CODEX,
                "Codex",
                "codex",
                "/home/codex",
                "codex",
                "OPENAI_API_KEY",
                "",
                "",
                "",
                "/home/codex/AGENTS.md");
        }
        return new ProviderProfile(
            AssistantProvider.CLAUDE,
            "Claude Code",
            "claude",
            "/home/claude",
            "claude",
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_BASE_URL",
            "/home/claude/.claude/memory",
            "/home/claude/.claude/commands",
            "/home/claude/CLAUDE.md");
    }
}
```

- [ ] **Step 5: Add `ProviderSettingsStore`**

Create `app/src/main/java/com/termux/app/ProviderSettingsStore.java`:

```java
package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class ProviderSettingsStore {

    public static final String PREFS_NAME = "assistant_provider";
    private static final String KEY_SELECTED_PROVIDER = "selected_provider";

    private final SharedPreferences mPrefs;

    public ProviderSettingsStore(Context context) {
        mPrefs = context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public AssistantProvider getSelectedProvider() {
        return AssistantProvider.fromId(mPrefs.getString(KEY_SELECTED_PROVIDER, AssistantProvider.CLAUDE.id));
    }

    public void setSelectedProvider(AssistantProvider provider) {
        AssistantProvider safe = provider == null ? AssistantProvider.CLAUDE : provider;
        mPrefs.edit().putString(KEY_SELECTED_PROVIDER, safe.id).apply();
    }
}
```

- [ ] **Step 6: Run provider tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.ProviderProfileTest --tests com.termux.app.ProviderSettingsStoreTest
```

Expected: PASS.

- [ ] **Step 7: Commit provider core**

```powershell
git add app/src/main/java/com/termux/app/AssistantProvider.java `
        app/src/main/java/com/termux/app/ProviderProfile.java `
        app/src/main/java/com/termux/app/ProviderSettingsStore.java `
        app/src/test/java/com/termux/app/ProviderProfileTest.java `
        app/src/test/java/com/termux/app/ProviderSettingsStoreTest.java
git commit -m "feat(codex): add assistant provider model"
```

---

### Task 2: Provider-Aware API Key Storage

**Files:**
- Modify: `app/src/main/java/com/termux/app/ApiKeyStore.java`
- Create: `app/src/main/java/com/termux/app/ProviderEnvironmentWriter.java`
- Test: `app/src/test/java/com/termux/app/ApiKeyStoreProviderTest.java`
- Test: `app/src/test/java/com/termux/app/ProviderEnvironmentWriterTest.java`

- [ ] **Step 1: Write API key isolation tests**

Create `app/src/test/java/com/termux/app/ApiKeyStoreProviderTest.java`:

```java
package com.termux.app;

import android.content.Context;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ApiKeyStoreProviderTest {

    @Test
    public void existingConstructorUsesClaudeStore() {
        Context context = RuntimeEnvironment.getApplication();
        clear(context, AssistantProvider.CLAUDE);

        ApiKeyStore legacy = new ApiKeyStore(context);
        legacy.add("claude", "sk-ant", "https://api.anthropic.com");

        Assert.assertEquals(1, new ApiKeyStore(context, AssistantProvider.CLAUDE).loadAll().size());
        Assert.assertEquals(0, new ApiKeyStore(context, AssistantProvider.CODEX).loadAll().size());
    }

    @Test
    public void codexKeysAreIsolatedFromClaudeKeys() {
        Context context = RuntimeEnvironment.getApplication();
        clear(context, AssistantProvider.CLAUDE);
        clear(context, AssistantProvider.CODEX);

        ApiKeyStore claude = new ApiKeyStore(context, AssistantProvider.CLAUDE);
        ApiKeyStore codex = new ApiKeyStore(context, AssistantProvider.CODEX);
        claude.add("claude", "sk-ant", "");
        codex.add("codex", "sk-openai", "");

        Assert.assertEquals("sk-ant", claude.loadAll().get(0).value);
        Assert.assertEquals("sk-openai", codex.loadAll().get(0).value);
        Assert.assertNotEquals(claude.loadAll().get(0).id, codex.loadAll().get(0).id);
    }

    @Test
    public void activeIdsAreIsolatedByProvider() {
        Context context = RuntimeEnvironment.getApplication();
        clear(context, AssistantProvider.CLAUDE);
        clear(context, AssistantProvider.CODEX);

        ApiKeyStore claude = new ApiKeyStore(context, AssistantProvider.CLAUDE);
        ApiKeyStore.Entry c = claude.add("claude", "sk-ant", "");
        ApiKeyStore codex = new ApiKeyStore(context, AssistantProvider.CODEX);
        ApiKeyStore.Entry x = codex.add("codex", "sk-openai", "");
        claude.setActiveId(c.id);
        codex.setActiveId(x.id);

        Assert.assertEquals(c.id, new ApiKeyStore(context, AssistantProvider.CLAUDE).getActiveId());
        Assert.assertEquals(x.id, new ApiKeyStore(context, AssistantProvider.CODEX).getActiveId());
    }

    private static void clear(Context context, AssistantProvider provider) {
        context.getSharedPreferences(ApiKeyStore.prefsNameForProvider(provider), Context.MODE_PRIVATE)
            .edit().clear().commit();
    }
}
```

- [ ] **Step 2: Write environment writer tests**

Create `app/src/test/java/com/termux/app/ProviderEnvironmentWriterTest.java`:

```java
package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

public class ProviderEnvironmentWriterTest {

    @Test
    public void claudeExportsAnthropicKeyAndBaseUrl() {
        String updated = ProviderEnvironmentWriter.rewriteBashrc(
            "export ANTHROPIC_API_KEY='old'\nexport ANTHROPIC_BASE_URL='old'\nexport PATH=\"$HOME/.local/bin:$PATH\"\n",
            AssistantProvider.CLAUDE,
            "sk-new",
            "https://api.example.com");

        Assert.assertFalse(updated.contains("old"));
        Assert.assertTrue(updated.contains("export ANTHROPIC_API_KEY='sk-new'"));
        Assert.assertTrue(updated.contains("export ANTHROPIC_BASE_URL='https://api.example.com'"));
        Assert.assertTrue(updated.contains("export PATH=\"$HOME/.local/bin:$PATH\""));
    }

    @Test
    public void codexExportsOpenAiKeyAndRemovesOldOpenAiLine() {
        String updated = ProviderEnvironmentWriter.rewriteBashrc(
            "export OPENAI_API_KEY='old'\nexport ANTHROPIC_API_KEY='keep'\n",
            AssistantProvider.CODEX,
            "sk-openai",
            "");

        Assert.assertFalse(updated.contains("OPENAI_API_KEY='old'"));
        Assert.assertTrue(updated.contains("export OPENAI_API_KEY='sk-openai'"));
        Assert.assertTrue(updated.contains("export ANTHROPIC_API_KEY='keep'"));
        Assert.assertFalse(updated.contains("OPENAI_BASE_URL"));
    }
}
```

- [ ] **Step 3: Run tests and confirm they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.ApiKeyStoreProviderTest --tests com.termux.app.ProviderEnvironmentWriterTest
```

Expected: FAIL because the provider constructor and environment writer do not exist.

- [ ] **Step 4: Update `ApiKeyStore`**

Modify `app/src/main/java/com/termux/app/ApiKeyStore.java`:

```java
private static final String PREFS_NAME_CLAUDE = "api_keys_store";
private static final String PREFS_NAME_CODEX = "codex_api_keys";
```

Replace the existing constructor with both overloads:

```java
public ApiKeyStore(Context context) {
    this(context, AssistantProvider.CLAUDE);
}

public ApiKeyStore(Context context, AssistantProvider provider) {
    mPrefs = context.getApplicationContext()
                    .getSharedPreferences(prefsNameForProvider(provider), Context.MODE_PRIVATE);
}

public static String prefsNameForProvider(AssistantProvider provider) {
    return provider == AssistantProvider.CODEX ? PREFS_NAME_CODEX : PREFS_NAME_CLAUDE;
}
```

Keep the rest of the store methods unchanged.

- [ ] **Step 5: Add `ProviderEnvironmentWriter`**

Create `app/src/main/java/com/termux/app/ProviderEnvironmentWriter.java`:

```java
package com.termux.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ProviderEnvironmentWriter {

    private ProviderEnvironmentWriter() {}

    public static void writeActiveKey(Context context, AssistantProvider provider, String key, String baseUrl)
            throws IOException {
        ProviderProfile p = ProviderProfile.forProvider(provider);
        File bashrc = new File(context.getApplicationContext().getFilesDir(),
            "usr/var/lib/proot-distro/installed-rootfs/ubuntu" + p.home + "/.bashrc");
        File parent = bashrc.getParentFile();
        if (parent == null || !parent.exists()) return;
        String existing = bashrc.exists()
            ? new String(Files.readAllBytes(bashrc.toPath()), StandardCharsets.UTF_8)
            : "";
        Files.write(bashrc.toPath(),
            rewriteBashrc(existing, provider, key, baseUrl).getBytes(StandardCharsets.UTF_8));
    }

    public static String rewriteBashrc(
        String content,
        AssistantProvider provider,
        String key,
        String baseUrl) {
        ProviderProfile p = ProviderProfile.forProvider(provider);
        StringBuilder kept = new StringBuilder();
        String[] lines = (content == null ? "" : content).split("\n", -1);
        for (String line : lines) {
            if (line.contains(p.apiKeyEnv)) continue;
            if (!p.baseUrlEnv.isEmpty() && line.contains(p.baseUrlEnv)) continue;
            kept.append(line).append("\n");
        }
        String trimmed = kept.toString().replaceAll("\n{2,}$", "\n");
        StringBuilder out = new StringBuilder(trimmed);
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        out.append("export ").append(p.apiKeyEnv).append("='").append(shellSingleQuote(key)).append("'\n");
        if (!p.baseUrlEnv.isEmpty() && baseUrl != null && !baseUrl.isEmpty()) {
            out.append("export ").append(p.baseUrlEnv).append("='")
                .append(shellSingleQuote(baseUrl)).append("'\n");
        }
        return out.toString();
    }

    private static String shellSingleQuote(String value) {
        return (value == null ? "" : value).replace("'", "'\\''");
    }
}
```

- [ ] **Step 6: Update `TermuxActivity` key writer**

In `app/src/main/java/com/termux/app/TermuxActivity.java`, replace the body of `setActiveApiKey(String key, String baseUrl)` with:

```java
public void setActiveApiKey(String key, String baseUrl) {
    setActiveApiKey(AssistantProvider.CLAUDE, key, baseUrl);
}

public void setActiveApiKey(AssistantProvider provider, String key, String baseUrl) {
    try {
        ProviderEnvironmentWriter.writeActiveKey(this, provider, key, baseUrl);
    } catch (java.io.IOException e) {
        Logger.logError(LOG_TAG, "setActiveApiKey: " + e.getMessage());
    }
}
```

- [ ] **Step 7: Run storage and writer tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.ApiKeyStoreProviderTest --tests com.termux.app.ProviderEnvironmentWriterTest
```

Expected: PASS.

- [ ] **Step 8: Commit provider-aware key storage**

```powershell
git add app/src/main/java/com/termux/app/ApiKeyStore.java `
        app/src/main/java/com/termux/app/ProviderEnvironmentWriter.java `
        app/src/main/java/com/termux/app/TermuxActivity.java `
        app/src/test/java/com/termux/app/ApiKeyStoreProviderTest.java `
        app/src/test/java/com/termux/app/ProviderEnvironmentWriterTest.java
git commit -m "feat(codex): isolate provider api keys"
```

---

### Task 3: API Key Provider Selector UI

**Files:**
- Modify: `app/src/main/res/layout/fragment_api_key.xml`
- Modify: `app/src/main/java/com/termux/app/ApiKeyFragment.java`
- Test: `app/src/test/java/com/termux/app/ApiKeyStoreProviderTest.java`

- [ ] **Step 1: Add selector IDs to the layout**

In `app/src/main/res/layout/fragment_api_key.xml`, insert this row between the title bar and `RecyclerView`:

```xml
    <LinearLayout
        android:id="@+id/key_provider_selector"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:paddingStart="12dp"
        android:paddingEnd="12dp"
        android:paddingTop="8dp"
        android:paddingBottom="8dp"
        android:background="#FFFFFF">

        <com.google.android.material.button.MaterialButton
            android:id="@+id/btn_key_provider_claude"
            style="@style/Widget.MaterialComponents.Button.OutlinedButton"
            android:layout_width="0dp"
            android:layout_height="36dp"
            android:layout_weight="1"
            android:layout_marginEnd="6dp"
            android:text="Claude"
            android:textSize="13sp"
            android:insetTop="0dp"
            android:insetBottom="0dp" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/btn_key_provider_codex"
            style="@style/Widget.MaterialComponents.Button.OutlinedButton"
            android:layout_width="0dp"
            android:layout_height="36dp"
            android:layout_weight="1"
            android:layout_marginStart="6dp"
            android:text="Codex"
            android:textSize="13sp"
            android:insetTop="0dp"
            android:insetBottom="0dp" />

    </LinearLayout>
```

- [ ] **Step 2: Add provider fields to `ApiKeyFragment`**

In `app/src/main/java/com/termux/app/ApiKeyFragment.java`, add imports:

```java
import com.google.android.material.button.MaterialButton;
```

Add fields:

```java
private AssistantProvider mProvider = AssistantProvider.CLAUDE;
private MaterialButton mBtnClaude;
private MaterialButton mBtnCodex;
```

- [ ] **Step 3: Initialize provider selector**

In `onViewCreated`, before constructing `ApiKeyStore`, add:

```java
mProvider = new ProviderSettingsStore(requireContext()).getSelectedProvider();
mBtnClaude = view.findViewById(R.id.btn_key_provider_claude);
mBtnCodex = view.findViewById(R.id.btn_key_provider_codex);
mBtnClaude.setOnClickListener(v -> switchProvider(AssistantProvider.CLAUDE));
mBtnCodex.setOnClickListener(v -> switchProvider(AssistantProvider.CODEX));
```

Change store initialization to:

```java
mStore = new ApiKeyStore(requireContext(), mProvider);
```

After `updateCount();`, call:

```java
updateProviderButtons();
```

- [ ] **Step 4: Add fragment helper methods**

Add these methods to `ApiKeyFragment`:

```java
private void switchProvider(AssistantProvider provider) {
    if (provider == mProvider) return;
    mProvider = provider;
    mStore = new ApiKeyStore(requireContext(), mProvider);
    mEntries.clear();
    mEntries.addAll(mStore.loadAll());
    mAdapter.setActiveId(mStore.getActiveId());
    mAdapter.notifyDataSetChanged();
    updateCount();
    updateProviderButtons();
}

private void updateProviderButtons() {
    if (mBtnClaude == null || mBtnCodex == null) return;
    boolean claude = mProvider == AssistantProvider.CLAUDE;
    mBtnClaude.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
        claude ? 0xFF1976D2 : 0xFFFFFFFF));
    mBtnClaude.setTextColor(claude ? 0xFFFFFFFF : 0xFF1976D2);
    mBtnCodex.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
        claude ? 0xFFFFFFFF : 0xFF1976D2));
    mBtnCodex.setTextColor(claude ? 0xFF1976D2 : 0xFFFFFFFF);
}
```

- [ ] **Step 5: Route active key writes by provider**

In `onSetActive`, replace:

```java
a.setActiveApiKey(entry.value, entry.baseUrl);
```

with:

```java
a.setActiveApiKey(mProvider, entry.value, entry.baseUrl);
```

- [ ] **Step 6: Update add dialog hints**

In `showAddDialog`, set base URL hint by provider:

```java
if (mProvider == AssistantProvider.CODEX) {
    etBaseUrl.setHint("API Base URL（可选；当前 Codex CLI 默认使用 OpenAI 官方接口）");
} else {
    etBaseUrl.setHint("API Base URL（留空 = 官方 Anthropic；第三方填 Anthropic 兼容地址）");
}
```

Set dialog title:

```java
.setTitle("添加 " + ProviderProfile.forProvider(mProvider).displayName + " API Key")
```

- [ ] **Step 7: Run compile check**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit API key UI**

```powershell
git add app/src/main/res/layout/fragment_api_key.xml app/src/main/java/com/termux/app/ApiKeyFragment.java
git commit -m "feat(codex): add provider key selector"
```

---

### Task 4: Shared Prompt Builder and Codex Setup Script

**Files:**
- Create: `app/src/main/java/com/termux/app/autotasks/AndroidCapabilityPromptBuilder.java`
- Create: `app/src/main/java/com/termux/app/autotasks/AutoCodexManager.java`
- Modify: `app/src/main/java/com/termux/app/autotasks/AutoClaudeManager.java`
- Modify: `app/src/main/java/com/termux/app/autotasks/AutoTaskCoordinator.java`
- Modify: `app/src/main/java/com/termux/app/autotasks/AutoUbuntuManager.java`
- Test: `app/src/test/java/com/termux/app/autotasks/AndroidCapabilityPromptBuilderTest.java`
- Test: `app/src/test/java/com/termux/app/autotasks/AutoCodexManagerScriptTest.java`

- [ ] **Step 1: Write prompt and Codex setup tests**

Create `app/src/test/java/com/termux/app/autotasks/AndroidCapabilityPromptBuilderTest.java`:

```java
package com.termux.app.autotasks;

import org.junit.Assert;
import org.junit.Test;

public class AndroidCapabilityPromptBuilderTest {

    @Test
    public void claudePromptIncludesAndroidMcpAndMemoryRules() {
        String prompt = AndroidCapabilityPromptBuilder.buildClaudeInstructions();

        Assert.assertTrue(prompt.contains("Android 手机"));
        Assert.assertTrue(prompt.contains("screen.capture"));
        Assert.assertTrue(prompt.contains("ui.tap"));
        Assert.assertTrue(prompt.contains("~/.claude/memory"));
        Assert.assertTrue(prompt.contains("不要修改 `~/CLAUDE.md`"));
    }

    @Test
    public void codexPromptUsesAgentsMdAndDoesNotMentionClaudeMemoryContract() {
        String prompt = AndroidCapabilityPromptBuilder.buildCodexInstructions();

        Assert.assertTrue(prompt.contains("Android 手机"));
        Assert.assertTrue(prompt.contains("screen.capture"));
        Assert.assertTrue(prompt.contains("ui.tap"));
        Assert.assertTrue(prompt.contains("AGENTS.md"));
        Assert.assertFalse(prompt.contains("~/.claude/memory"));
        Assert.assertFalse(prompt.contains("CLAUDE.md"));
    }
}
```

Create `app/src/test/java/com/termux/app/autotasks/AutoCodexManagerScriptTest.java`:

```java
package com.termux.app.autotasks;

import org.junit.Assert;
import org.junit.Test;

public class AutoCodexManagerScriptTest {

    @Test
    public void setupScriptCreatesCodexUserAndInstallsCli() {
        String script = AutoCodexManager.buildInnerScriptForTest();

        Assert.assertTrue(script.contains("id codex >/dev/null 2>&1 || useradd -m -s /bin/bash codex"));
        Assert.assertTrue(script.contains("npm install -g @openai/codex"));
        Assert.assertTrue(script.contains("command -v codex"));
        Assert.assertTrue(script.contains("/home/codex/AGENTS.md"));
    }

    @Test
    public void setupScriptWritesOpenAiKeyWhenUserProvidesOne() {
        String script = AutoCodexManager.buildInnerScriptForTest();

        Assert.assertTrue(script.contains("OpenAI API Key"));
        Assert.assertTrue(script.contains("export OPENAI_API_KEY"));
        Assert.assertFalse(script.contains("ANTHROPIC_API_KEY"));
    }

    @Test
    public void setupScriptCleansOwnHook() {
        String script = AutoCodexManager.buildInnerScriptForTest();

        Assert.assertTrue(script.contains("sed -i '/.codex-setup/d' ~/.bashrc"));
        Assert.assertTrue(script.contains("rm -f ~/.codex-setup.sh"));
    }
}
```

- [ ] **Step 2: Run tests and confirm they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.autotasks.AndroidCapabilityPromptBuilderTest --tests com.termux.app.autotasks.AutoCodexManagerScriptTest
```

Expected: FAIL because the new classes do not exist.

- [ ] **Step 3: Add `AndroidCapabilityPromptBuilder`**

Create `app/src/main/java/com/termux/app/autotasks/AndroidCapabilityPromptBuilder.java`:

```java
package com.termux.app.autotasks;

public final class AndroidCapabilityPromptBuilder {

    private AndroidCapabilityPromptBuilder() {}

    public static String buildClaudeInstructions() {
        return sharedInstructions("Claude Code", "CLAUDE.md")
            + "## 记忆系统\n\n"
            + "**当用户要求「记住」某件事时，必须遵守以下规则：**\n\n"
            + "- 将记忆保存为独立 `.md` 文件，路径：`~/.claude/memory/<描述性名称>.md`\n"
            + "- 文件名用英文小写+下划线，如 `user_preference.md`、`api_key.md`\n"
            + "- **不要修改 `~/CLAUDE.md`**（该文件由系统自动管理，每次启动会被覆盖）\n"
            + "- 每次对话开始时，可读取 `~/.claude/memory/` 目录下的文件了解用户偏好\n";
    }

    public static String buildCodexInstructions() {
        return sharedInstructions("Codex", "AGENTS.md")
            + "## Codex 工作区规则\n\n"
            + "- 当前说明由 Android App 自动写入 `/home/codex/AGENTS.md`。\n"
            + "- 操作手机时优先使用已注册的 MCP/HTTP 工具，不要尝试 Linux 桌面截屏工具。\n"
            + "- 需要长期记忆时，在当前项目目录创建清晰命名的 Markdown 文件，并在回复中说明路径。\n";
    }

    private static String sharedInstructions(String agentName, String fileName) {
        return "# Android 手机运行环境\n\n"
            + "你是 " + agentName + "，正在一台 Android 手机的 Ubuntu proot 容器中运行。\n"
            + "当前说明文件是 `" + fileName + "`，由 Android App 自动管理。\n\n"
            + "## MCP 工具\n\n"
            + "- `screen.capture`：截取当前屏幕。\n"
            + "- `ui.get_accessibility_tree`：获取 UI 元素树。\n"
            + "- `ui.tap` / `ui.swipe` / `ui.click_text` / `ui.input_text`：控制界面。\n"
            + "- `file.read` / `file.list` / `file.check_exists`：读取 App 可访问文件。\n"
            + "- `app.open` / `app.get_current_activity`：打开应用或查看前台 Activity。\n\n"
            + "## 截屏规则\n\n"
            + "`screen.capture` 是获取当前屏幕的唯一正确方式。不要尝试 `scrot`、`grim`、`import`、"
            + "`gnome-screenshot`、`screencap`、`adb shell` 或读取历史截图目录来代替实时截屏。"
            + "如果返回截屏权限未授予，直接告诉用户回到 App 主页点击「授权截图」。\n\n"
            + "## 推荐操作循环\n\n"
            + "1. `screen.capture` 观察当前屏幕。\n"
            + "2. `ui.get_accessibility_tree` 定位元素。\n"
            + "3. `ui.tap` / `ui.click_text` / `ui.input_text` 执行操作。\n"
            + "4. 再次截图确认结果。\n\n";
    }
}
```

- [ ] **Step 4: Add `AutoCodexManager`**

Create `app/src/main/java/com/termux/app/autotasks/AutoCodexManager.java`:

```java
package com.termux.app.autotasks;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class AutoCodexManager {

    static final String INNER_SCRIPT_REL = "home/.codex-inner-setup.sh";

    private final TermuxActivity mActivity;

    public AutoCodexManager(@NonNull TermuxActivity activity) {
        mActivity = activity;
        Thread t = new Thread(this::writeInnerScript, "codex-setup-write");
        t.setDaemon(true);
        t.start();
    }

    @NonNull
    public String getInnerScriptPath() {
        return new File(mActivity.getFilesDir(), INNER_SCRIPT_REL).getAbsolutePath();
    }

    private void writeInnerScript() {
        File scriptFile = new File(mActivity.getFilesDir(), INNER_SCRIPT_REL);
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter w = new FileWriter(scriptFile)) {
                w.write(buildInnerScript());
            }
        } catch (IOException ignored) {}
    }

    public static String buildInnerScriptForTest() {
        return buildInnerScript();
    }

    private static String buildInnerScript() {
        String agents = java.util.Base64.getEncoder().encodeToString(
            AndroidCapabilityPromptBuilder.buildCodexInstructions()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "#!/bin/bash\n"
            + "# Codex auto-setup (sourced from ~/.bashrc)\n\n"
            + "cleanup_hook() {\n"
            + "    sed -i '/.codex-setup/d' ~/.bashrc 2>/dev/null\n"
            + "    rm -f ~/.codex-setup.sh\n"
            + "}\n\n"
            + "id codex >/dev/null 2>&1 || useradd -m -s /bin/bash codex\n"
            + "mkdir -p /home/codex/.codex\n"
            + "printf '%s' '" + agents + "' | base64 -d > /home/codex/AGENTS.md\n"
            + "chown -R codex:codex /home/codex\n\n"
            + "if command -v codex >/dev/null 2>&1; then\n"
            + "    cleanup_hook\n"
            + "    return 0 2>/dev/null || exit 0\n"
            + "fi\n\n"
            + "export DEBIAN_FRONTEND=noninteractive\n"
            + "mkdir -p /var/cache/apt/archives/partial /var/lib/apt/lists/partial /var/log/apt\n"
            + "if ! command -v node >/dev/null 2>&1; then\n"
            + "    apt-get update 2>&1\n"
            + "    apt-get install -y --no-install-recommends nodejs npm curl 2>&1\n"
            + "fi\n"
            + "command -v curl >/dev/null 2>&1 || apt-get install -y --no-install-recommends curl 2>&1\n"
            + "npm config set registry https://registry.npmmirror.com 2>/dev/null || true\n"
            + "npm config delete omit 2>/dev/null || true\n"
            + "npm config set optional true 2>/dev/null || true\n"
            + "echo '[*] Installing Codex CLI...'\n"
            + "npm install -g @openai/codex 2>&1\n\n"
            + "if ! command -v codex >/dev/null 2>&1; then\n"
            + "    echo '[!] Codex CLI install failed. Run: npm install -g @openai/codex'\n"
            + "    return 1 2>/dev/null || exit 1\n"
            + "fi\n\n"
            + "printf 'OpenAI API Key（可留空，稍后在 App API Key 页面设置）: '\n"
            + "read -r _key\n"
            + "if [ -n \"$_key\" ]; then\n"
            + "    sed -i '/OPENAI_API_KEY/d' /home/codex/.bashrc 2>/dev/null || true\n"
            + "    printf '\\n# Codex\\nexport OPENAI_API_KEY=\"%s\"\\n' \"$_key\" >> /home/codex/.bashrc\n"
            + "    chown codex:codex /home/codex/.bashrc\n"
            + "fi\n\n"
            + "cleanup_hook\n"
            + "echo '[*] Codex setup complete.'\n";
    }
}
```

- [ ] **Step 5: Reuse prompt builder in `AutoClaudeManager`**

In `app/src/main/java/com/termux/app/autotasks/AutoClaudeManager.java`, keep the install flow unchanged. Do not move the full setup script. In `AutoUbuntuManager` prompt injection will use the shared builder; this task only verifies the shared builder compiles.

- [ ] **Step 6: Construct `AutoCodexManager`**

In `AutoTaskCoordinator`, add field:

```java
@SuppressWarnings("FieldCanBeLocal")
private final AutoCodexManager mAutoCodexManager;
```

In the constructor after `mAutoClaudeManager = new AutoClaudeManager(activity);`, add:

```java
mAutoCodexManager = new AutoCodexManager(activity);
```

- [ ] **Step 7: Inject Codex setup in `AutoUbuntuManager`**

In `AutoUbuntuManager.launchSetupScript`, near `claudeInnerPath`, add:

```java
String codexInnerPath = new File(mActivity.getFilesDir(),
    AutoCodexManager.INNER_SCRIPT_REL).getAbsolutePath();
```

After the Claude setup hook injection, append Codex hook injection:

```java
.append("_codis=\"").append(codexInnerPath).append("\"; ")
.append("[ -f \"$_codis\" ] && cp \"$_codis\" \"$_ubr/root/.codex-setup.sh\" && ")
.append("{ grep -qF '.codex-setup' \"$_ubr/root/.bashrc\" 2>/dev/null || ")
.append("printf '\\n[ -f ~/.codex-setup.sh ] && . ~/.codex-setup.sh\\n' ")
.append(">> \"$_ubr/root/.bashrc\"; }; ")
```

In `injectClaudeMd`, do not create Codex files. Add a new `injectProviderInstructions()` method and call it before setup script launch:

```java
private void injectProviderInstructions() {
    injectClaudeMd();
    File codexHome = new File(UbuntuSnapshotManager.UBUNTU_ROOTFS + "/home/codex");
    if (codexHome.isDirectory()) {
        writeFile(new File(codexHome, "AGENTS.md"),
            AndroidCapabilityPromptBuilder.buildCodexInstructions());
    }
}
```

Change the existing launch call from:

```java
injectClaudeMd();
```

to:

```java
injectProviderInstructions();
```

- [ ] **Step 8: Run prompt/setup tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.autotasks.AndroidCapabilityPromptBuilderTest --tests com.termux.app.autotasks.AutoCodexManagerScriptTest
```

Expected: PASS.

- [ ] **Step 9: Commit Codex setup**

```powershell
git add app/src/main/java/com/termux/app/autotasks/AndroidCapabilityPromptBuilder.java `
        app/src/main/java/com/termux/app/autotasks/AutoCodexManager.java `
        app/src/main/java/com/termux/app/autotasks/AutoTaskCoordinator.java `
        app/src/main/java/com/termux/app/autotasks/AutoUbuntuManager.java `
        app/src/main/java/com/termux/app/autotasks/AutoClaudeManager.java `
        app/src/test/java/com/termux/app/autotasks/AndroidCapabilityPromptBuilderTest.java `
        app/src/test/java/com/termux/app/autotasks/AutoCodexManagerScriptTest.java
git commit -m "feat(codex): add ubuntu setup hook"
```

---

### Task 5: Codex CLI Runner

**Files:**
- Create: `app/src/main/java/com/termux/app/CodexExecSession.java`
- Test: `app/src/test/java/com/termux/app/CodexExecSessionTest.java`

- [ ] **Step 1: Write runner tests**

Create `app/src/test/java/com/termux/app/CodexExecSessionTest.java`:

```java
package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class CodexExecSessionTest {

    @Test
    public void buildsCodexExecCommandForCodexUser() {
        List<String> cmd = CodexExecSession.command("/usr/bin/proot-distro");

        Assert.assertEquals("/usr/bin/proot-distro", cmd.get(0));
        Assert.assertTrue(cmd.contains("login"));
        Assert.assertTrue(cmd.contains("ubuntu"));
        Assert.assertTrue(cmd.contains("--user"));
        Assert.assertTrue(cmd.contains("codex"));
        Assert.assertEquals("sh", cmd.get(cmd.size() - 3));
        Assert.assertEquals("-lc", cmd.get(cmd.size() - 2));
        Assert.assertTrue(cmd.get(cmd.size() - 1).contains("cd /home/codex"));
        Assert.assertTrue(cmd.get(cmd.size() - 1).contains("codex exec --json --skip-git-repo-check -"));
    }

    @Test
    public void parsesJsonEventsAndPlainOutput() {
        String text = CodexExecSession.parseOutput(
            "{\"type\":\"message\",\"text\":\"hello\"}\nplain fallback\n{\"type\":\"assistant_message\",\"message\":\"world\"}\n");

        Assert.assertTrue(text.contains("hello"));
        Assert.assertTrue(text.contains("plain fallback"));
        Assert.assertTrue(text.contains("world"));
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
}
```

- [ ] **Step 2: Run tests and confirm they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.CodexExecSessionTest
```

Expected: FAIL because `CodexExecSession` does not exist.

- [ ] **Step 3: Add `CodexExecSession`**

Create `app/src/main/java/com/termux/app/CodexExecSession.java`:

```java
package com.termux.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CodexExecSession {

    private static final String TAG = "CodexExecSession";
    private static final String PREFIX = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
    private static final String PROOT_D = PREFIX + "/bin/proot-distro";

    public interface Listener {
        void onSystem(String info);
        void onAssistantText(String text);
        void onResult(boolean isError, String errMsg);
    }

    private final Context mAppCtx;
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final List<ChatMessage> mTranscript = new ArrayList<>();
    private volatile Process mProcess;
    private volatile boolean mRunning;

    public CodexExecSession(@Nullable Context context) {
        mAppCtx = context == null ? null : context.getApplicationContext();
    }

    public boolean isRunning() {
        return mRunning;
    }

    public void addListener(Listener l) {
        if (l != null) mListeners.add(l);
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    public synchronized void resetForNewConversation() {
        interrupt();
        mTranscript.clear();
    }

    public synchronized void interrupt() {
        Process p = mProcess;
        mProcess = null;
        mRunning = false;
        if (p != null) p.destroy();
    }

    public synchronized void send(String userText, String apiKey) {
        if (mRunning) return;
        final String prompt = buildPrompt(userText);
        mTranscript.add(ChatMessage.user(userText));
        mRunning = true;
        new Thread(() -> runOnce(prompt, apiKey), "CodexExec-run").start();
    }

    private void runOnce(String prompt, String apiKey) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command(PROOT_D));
            setupEnv(pb.environment(), apiKey);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            mProcess = p;
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8))) {
                w.write(prompt);
                w.flush();
            }
            String stdout = readAll(p.getInputStream());
            String stderr = readAll(p.getErrorStream());
            int exit = p.waitFor();
            String text = parseOutput(stdout);
            if (exit == 0) {
                if (text.trim().isEmpty()) text = stdout.trim();
                mTranscript.add(ChatMessage.assistant(text));
                emitAssistantText(text);
                emitResult(false, "");
            } else {
                emitResult(true, stderr.trim().isEmpty() ? "Codex exited " + exit : stderr.trim());
            }
        } catch (Exception e) {
            Log.e(TAG, "Codex exec failed", e);
            emitResult(true, e.getMessage());
        } finally {
            mRunning = false;
            mProcess = null;
        }
    }

    public static List<String> command(String proot) {
        List<String> cmd = new ArrayList<>();
        cmd.add(proot);
        cmd.add("login");
        cmd.add("ubuntu");
        cmd.add("--user");
        cmd.add("codex");
        cmd.add("--");
        cmd.add("sh");
        cmd.add("-lc");
        cmd.add("cd /home/codex && codex exec --json --skip-git-repo-check -");
        return cmd;
    }

    public String buildPromptForTest(String userText) {
        return buildPrompt(userText);
    }

    public void recordForTest(ChatMessage message) {
        mTranscript.add(message);
    }

    private String buildPrompt(String userText) {
        StringBuilder sb = new StringBuilder();
        sb.append("Continue this Android assistant conversation.\n\n");
        int start = Math.max(0, mTranscript.size() - 20);
        for (int i = start; i < mTranscript.size(); i++) {
            ChatMessage m = mTranscript.get(i);
            if (m == null || m.content == null || m.content.trim().isEmpty()) continue;
            if (m.type == ChatMessage.Type.USER) sb.append("USER: ");
            else if (m.type == ChatMessage.Type.ASSISTANT) sb.append("ASSISTANT: ");
            else sb.append("SYSTEM: ");
            sb.append(m.content.trim()).append("\n\n");
        }
        sb.append("USER: ").append(userText == null ? "" : userText).append("\n\nASSISTANT:");
        return sb.toString();
    }

    public static String parseOutput(String output) {
        StringBuilder text = new StringBuilder();
        String[] lines = output == null ? new String[0] : output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("{")) {
                try {
                    JSONObject obj = new JSONObject(trimmed);
                    String part = first(obj.optString("text"), obj.optString("message"), obj.optString("content"));
                    if (!part.isEmpty()) appendLine(text, part);
                    continue;
                } catch (Exception ignored) {}
            }
            appendLine(text, trimmed);
        }
        return text.toString().trim();
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static void setupEnv(Map<String, String> env, String apiKey) {
        env.put("PREFIX", PREFIX);
        env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
            + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
        env.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        env.put("LANG", "en_US.UTF-8");
        env.put("OPENAI_API_KEY", apiKey == null ? "" : apiKey);
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (line == null || line.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(line);
    }

    private static String first(String a, String b, String c) {
        if (a != null && !a.isEmpty()) return a;
        if (b != null && !b.isEmpty()) return b;
        return c == null ? "" : c;
    }

    private void emitAssistantText(String text) {
        for (Listener l : mListeners) try { l.onAssistantText(text); } catch (Throwable t) { Log.e(TAG, "listener", t); }
    }

    private void emitResult(boolean isError, String errMsg) {
        for (Listener l : mListeners) try { l.onResult(isError, errMsg); } catch (Throwable t) { Log.e(TAG, "listener", t); }
    }
}
```

- [ ] **Step 4: Run runner tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.CodexExecSessionTest
```

Expected: PASS.

- [ ] **Step 5: Commit Codex runner**

```powershell
git add app/src/main/java/com/termux/app/CodexExecSession.java app/src/test/java/com/termux/app/CodexExecSessionTest.java
git commit -m "feat(codex): add codex exec session"
```

---

### Task 6: Home Provider Selector and Routing

**Files:**
- Modify: `app/src/main/res/layout/fragment_home.xml`
- Modify: `app/src/main/java/com/termux/app/HomeFragment.java`

- [ ] **Step 1: Make Home title clickable**

In `fragment_home.xml`, update `@+id/home_session_title`:

```xml
android:clickable="true"
android:focusable="true"
android:foreground="?attr/selectableItemBackground"
android:paddingTop="6dp"
android:paddingBottom="6dp"
```

- [ ] **Step 2: Add Home provider fields**

In `HomeFragment.java`, add fields near `mClaudeSession`:

```java
private ProviderSettingsStore mProviderStore;
private AssistantProvider mProvider = AssistantProvider.CLAUDE;
private CodexExecSession mCodexSession;
private CodexExecSession.Listener mCodexListener;
```

- [ ] **Step 3: Initialize provider and Codex listener**

In `onViewCreated`, before `mClaudeSession = ClaudeStreamSession.get(requireContext());`, add:

```java
mProviderStore = new ProviderSettingsStore(requireContext());
mProvider = mProviderStore.getSelectedProvider();
mCodexSession = new CodexExecSession(requireContext());
mCodexListener = buildCodexListener();
mCodexSession.addListener(mCodexListener);
```

After `mSessionTitle = view.findViewById(R.id.home_session_title);`, add:

```java
mSessionTitle.setOnClickListener(v -> showProviderDialog());
updateSessionTitle(null);
```

In `onDestroyView`, remove the listener:

```java
if (mCodexSession != null && mCodexListener != null) {
    mCodexSession.removeListener(mCodexListener);
}
```

- [ ] **Step 4: Add provider dialog helpers**

Add to `HomeFragment`:

```java
private void showProviderDialog() {
    String[] labels = {"Claude Code", "Codex"};
    int checked = mProvider == AssistantProvider.CODEX ? 1 : 0;
    new AlertDialog.Builder(requireContext())
        .setTitle("选择助手")
        .setSingleChoiceItems(labels, checked, (dialog, which) -> {
            dialog.dismiss();
            AssistantProvider next = which == 1 ? AssistantProvider.CODEX : AssistantProvider.CLAUDE;
            requestProviderSwitch(next);
        })
        .show();
}

private void requestProviderSwitch(AssistantProvider next) {
    if (next == null || next == mProvider) return;
    boolean busy = mWaitingResponse
        || (mProvider == AssistantProvider.CLAUDE && mClaudeSession != null && mClaudeSession.isWaitingResponse())
        || (mProvider == AssistantProvider.CODEX && mCodexSession != null && mCodexSession.isRunning());
    if (!busy) {
        switchProvider(next);
        return;
    }
    new AlertDialog.Builder(requireContext())
        .setTitle("切换助手")
        .setMessage("当前回复仍在运行。切换到 " + ProviderProfile.forProvider(next).displayName + " 会先打断当前任务。")
        .setPositiveButton("切换", (d, w) -> {
            stopActiveProviderProcess();
            switchProvider(next);
        })
        .setNegativeButton("取消", null)
        .show();
}

private void switchProvider(AssistantProvider next) {
    mProvider = next;
    mProviderStore.setSelectedProvider(next);
    mWaitingResponse = false;
    mCurrentSessionId = null;
    mMessages.clear();
    mAdapter.notifyDataSetChanged();
    updateStatus("● 就绪", 0xFF2E7D32);
    updateSessionTitle(null);
    mAdapter.addMessage(ChatMessage.system("已切换到 " + ProviderProfile.forProvider(next).displayName));
    scrollToBottom();
}

private void stopActiveProviderProcess() {
    if (mProvider == AssistantProvider.CODEX) {
        if (mCodexSession != null) mCodexSession.interrupt();
    } else {
        if (mClaudeSession != null) mClaudeSession.interrupt();
    }
}
```

- [ ] **Step 5: Route send by provider**

At the start of `sendOrConfirm`, keep the existing input/attachment code. Replace the direct Claude key lookup and `mClaudeSession.send(text, apiKey, baseUrl);` section with:

```java
ApiKeyStore store = new ApiKeyStore(requireContext(), mProvider);
String activeId = store.getActiveId();
String apiKey = null;
String baseUrl = "";
if (activeId != null) {
    for (ApiKeyStore.Entry e : store.loadAll()) {
        if (e.id.equals(activeId)) { apiKey = e.value; baseUrl = e.baseUrl; break; }
    }
}
```

Change the missing-key message to:

```java
mAdapter.addMessage(ChatMessage.assistant("⚠ 请先在「API Key」页面添加并激活一个 "
    + ProviderProfile.forProvider(mProvider).displayName + " API Key。"));
```

After `appendChatLog("你", text);`, route:

```java
if (mProvider == AssistantProvider.CODEX) {
    mCodexSession.send(text, apiKey);
} else {
    mClaudeSession.send(text, apiKey, baseUrl);
}
```

- [ ] **Step 6: Route stop and new conversation**

Change `stopClaudeProcess()` body to call active provider:

```java
private void stopClaudeProcess() {
    stopActiveProviderProcess();
    mWaitingResponse = false;
    updateStatus("● 就绪", 0xFF2E7D32);
    FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
    mAdapter.addMessage(ChatMessage.system("● 已打断"));
    scrollToBottom();
}
```

In the new-session button listener, replace `mClaudeSession.resetForNewConversation();` with:

```java
if (mProvider == AssistantProvider.CODEX) {
    mCodexSession.resetForNewConversation();
} else {
    mClaudeSession.resetForNewConversation();
}
```

- [ ] **Step 7: Make title provider-aware**

Replace `updateSessionTitle` with:

```java
private void updateSessionTitle(@Nullable String preview) {
    if (mSessionTitle == null) return;
    if (preview == null || preview.isEmpty()) {
        mSessionTitle.setText(ProviderProfile.forProvider(mProvider).displayName);
    } else {
        mSessionTitle.setText(preview.length() > 30 ? preview.substring(0, 30) + "…" : preview);
    }
}
```

- [ ] **Step 8: Add Codex listener**

Add to `HomeFragment`:

```java
private CodexExecSession.Listener buildCodexListener() {
    return new CodexExecSession.Listener() {
        @Override public void onSystem(String info) {
            mHandler.post(() -> {
                mAdapter.addMessage(ChatMessage.system(info));
                scrollToBottom();
            });
        }

        @Override public void onAssistantText(String text) {
            mHandler.post(() -> {
                mAdapter.updateLastAssistantText(text);
                scrollToBottom();
            });
        }

        @Override public void onResult(boolean isError, String errMsg) {
            mHandler.post(() -> {
                if (isError) {
                    mAdapter.updateLastAssistantText("⚠ " + errMsg);
                } else {
                    dropPlaceholder();
                }
                mWaitingResponse = false;
                updateStatus("● 就绪", 0xFF2E7D32);
                FloatingStatusService.updateStatus("● 就绪", 0xFF2E7D32, "", false);
                ChatMessage last = mAdapter.getLastAssistantMessage();
                if (last != null && last.content != null && !last.content.isEmpty()) {
                    appendChatLog("Codex", last.content);
                }
                scrollToBottom();
            });
        }
    };
}
```

- [ ] **Step 9: Compile Home changes**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit Home provider routing**

```powershell
git add app/src/main/res/layout/fragment_home.xml app/src/main/java/com/termux/app/HomeFragment.java
git commit -m "feat(codex): switch home provider"
```

---

### Task 7: AgentServer Provider Command Builder

**Files:**
- Create: `app/src/main/java/com/termux/app/AgentServerCommandBuilder.java`
- Modify: `app/src/main/java/com/termux/app/AgentServerFragment.java`
- Modify: `app/src/main/res/layout/fragment_agent_server.xml`
- Test: `app/src/test/java/com/termux/app/AgentServerCommandBuilderTest.java`

- [ ] **Step 1: Write command builder tests**

Create `app/src/test/java/com/termux/app/AgentServerCommandBuilderTest.java`:

```java
package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

public class AgentServerCommandBuilderTest {

    @Test
    public void claudeScriptKeepsClaudecodeCommandAndAnthropicEnv() {
        AgentServerCommandBuilder.Config c = new AgentServerCommandBuilder.Config(
            "https://agent.example.com", "sandbox-1", "phone", "sk-ant", "https://api.example.com");

        String script = AgentServerCommandBuilder.connectScript(AssistantProvider.CLAUDE, c, "/data/data/com.termux/files/usr");

        Assert.assertTrue(script.contains("agentserver claudecode"));
        Assert.assertTrue(script.contains("ANTHROPIC_API_KEY='sk-ant'"));
        Assert.assertTrue(script.contains("ANTHROPIC_BASE_URL='https://api.example.com'"));
        Assert.assertTrue(script.contains("--user claude"));
        Assert.assertTrue(script.contains("/home/claude/.local/bin"));
        Assert.assertTrue(script.contains(".agentserver-pipe.jsonl"));
    }

    @Test
    public void codexScriptUsesOpenAiEnvAndSupportProbe() {
        AgentServerCommandBuilder.Config c = new AgentServerCommandBuilder.Config(
            "https://agent.example.com", "", "phone", "sk-openai", "");

        String script = AgentServerCommandBuilder.connectScript(AssistantProvider.CODEX, c, "/data/data/com.termux/files/usr");

        Assert.assertTrue(script.contains("OPENAI_API_KEY='sk-openai'"));
        Assert.assertTrue(script.contains("--user codex"));
        Assert.assertTrue(script.contains("agentserver help"));
        Assert.assertTrue(script.contains("grep -Eq 'codex|codexcode'"));
        Assert.assertTrue(script.contains("agentserver codex"));
        Assert.assertFalse(script.contains("ANTHROPIC_API_KEY"));
    }
}
```

- [ ] **Step 2: Run tests and confirm they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.AgentServerCommandBuilderTest
```

Expected: FAIL because `AgentServerCommandBuilder` does not exist.

- [ ] **Step 3: Add `AgentServerCommandBuilder`**

Create `app/src/main/java/com/termux/app/AgentServerCommandBuilder.java`:

```java
package com.termux.app;

public final class AgentServerCommandBuilder {

    public static final class Config {
        public final String serverUrl;
        public final String resumeId;
        public final String deviceName;
        public final String apiKey;
        public final String baseUrl;

        public Config(String serverUrl, String resumeId, String deviceName, String apiKey, String baseUrl) {
            this.serverUrl = serverUrl == null ? "" : serverUrl;
            this.resumeId = resumeId == null ? "" : resumeId;
            this.deviceName = deviceName == null ? "" : deviceName;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
        }
    }

    private AgentServerCommandBuilder() {}

    public static String connectScript(AssistantProvider provider, Config c, String prefix) {
        ProviderProfile p = ProviderProfile.forProvider(provider);
        String home = prefix + "/../home";
        String logFile = home + (provider == AssistantProvider.CODEX
            ? "/agentserver-codex-agent.log" : "/agentserver-agent.log");
        String pipeFile = prefix + "/var/lib/proot-distro/installed-rootfs/ubuntu/home/claude/.agentserver-pipe.jsonl";
        String pdBin = prefix + "/bin/proot-distro";
        String safeUrl = sq(c.serverUrl);
        String resumeFlag = c.resumeId.isEmpty() ? "" : " --resume " + sq(c.resumeId);
        String nameFlag = c.deviceName.isEmpty() ? "" : " --name " + sq(c.deviceName);
        String subcommand = provider == AssistantProvider.CODEX ? "codex" : "claudecode";
        String supportProbe = provider == AssistantProvider.CODEX
            ? "if ! /usr/local/bin/agentserver help 2>&1 | grep -Eq 'codex|codexcode'; then echo '[!] 当前 AgentServer 不支持 Codex 后端，请更新 AgentServer addon 或切回 Claude'; exit 2; fi; "
            : "";
        String env = provider == AssistantProvider.CODEX
            ? "OPENAI_API_KEY=" + sq(c.apiKey) + " "
            : "ANTHROPIC_API_KEY=" + sq(c.apiKey) + " "
                + (c.baseUrl.isEmpty() ? "" : "ANTHROPIC_BASE_URL=" + sq(c.baseUrl) + " ");
        String path = provider == AssistantProvider.CLAUDE
            ? "export PATH=/home/claude/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH; "
            : "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH; ";
        String clearPipe = provider == AssistantProvider.CLAUDE
            ? "> '" + pipeFile + "' 2>/dev/null || true\n"
            : "";

        return ""
            + "for _p in $(pgrep -f 'agentserver " + subcommand + "' 2>/dev/null); do [ \"$_p\" != \"$$\" ] && kill \"$_p\" 2>/dev/null; done; sleep 1\n"
            + "> '" + logFile + "'\n"
            + clearPipe
            + "echo '[*] 正在启动 AgentServer (" + p.displayName + ")...'\n"
            + "nohup '" + pdBin + "' login --user " + p.user + " ubuntu -- bash -c "
            + "\". " + p.home + "/.bashrc 2>/dev/null; " + path + supportProbe + env
            + "exec /usr/local/bin/agentserver " + subcommand + " --server " + safeUrl
            + resumeFlag + nameFlag + " --skip-open-browser\""
            + " >> '" + logFile + "' 2>&1 &\n"
            + "AS_PID=$!\n"
            + "echo '[*] AgentServer 已启动，等待 OAuth 授权 + tunnel 建立（最多 180s）...'\n"
            + "timeout 180 tail -F -n +1 '" + logFile + "' 2>/dev/null\n"
            + "if kill -0 $AS_PID 2>/dev/null; then echo \"[*] Agent 进程运行中（PID: $AS_PID）\"; else echo '[!] Agent 进程已退出'; fi\n";
    }

    private static String sq(String value) {
        return "'" + (value == null ? "" : value).replace("'", "'\\''") + "'";
    }
}
```

- [ ] **Step 4: Wire selected provider into `AgentServerFragment`**

In `AgentServerFragment`, add field:

```java
private AssistantProvider mProvider = AssistantProvider.CLAUDE;
private TextView mProviderText;
```

In `onCreateView`, bind:

```java
mProviderText = v.findViewById(R.id.agentserver_provider_text);
```

In `loadPrefs()` or after `loadPrefs();`, add:

```java
mProvider = new ProviderSettingsStore(requireContext()).getSelectedProvider();
if (mProviderText != null) {
    mProviderText.setText("当前助手：" + ProviderProfile.forProvider(mProvider).displayName + "（切换后需重新连接）");
}
```

In `doConnect`, replace the manually built launch script with:

```java
ApiKeyStore keyStore = new ApiKeyStore(requireContext(), mProvider);
String activeId = keyStore.getActiveId();
String apiKey = "", apiBaseUrl = "";
if (activeId != null) {
    for (ApiKeyStore.Entry e : keyStore.loadAll()) {
        if (e.id.equals(activeId)) { apiKey = e.value; apiBaseUrl = e.baseUrl; break; }
    }
}
if (apiKey.isEmpty()) {
    Toast.makeText(getContext(), "请先激活 " + ProviderProfile.forProvider(mProvider).displayName + " API Key", Toast.LENGTH_SHORT).show();
    return;
}
AgentServerCommandBuilder.Config cfg = new AgentServerCommandBuilder.Config(
    url, resumeId, device, apiKey, apiBaseUrl);
String script = AgentServerCommandBuilder.connectScript(mProvider, cfg, prefix);
```

Keep the existing `runScript(script, "连接 AgentServer", line -> { ... })` callback, sandbox ID parsing, auth URL parsing, and status update logic.

- [ ] **Step 5: Add provider hint layout**

In `fragment_agent_server.xml`, add a small `TextView` under the info text:

```xml
            <TextView
                android:id="@+id/agentserver_provider_text"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="当前助手：Claude Code"
                android:textColor="#666666"
                android:textSize="12sp"
                android:layout_marginTop="6dp" />
```

- [ ] **Step 6: Run AgentServer tests and compile**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.AgentServerCommandBuilderTest
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: both commands PASS.

- [ ] **Step 7: Commit AgentServer provider support**

```powershell
git add app/src/main/java/com/termux/app/AgentServerCommandBuilder.java `
        app/src/main/java/com/termux/app/AgentServerFragment.java `
        app/src/main/res/layout/fragment_agent_server.xml `
        app/src/test/java/com/termux/app/AgentServerCommandBuilderTest.java
git commit -m "feat(codex): make agentserver provider aware"
```

---

### Task 8: Loom Provider-Aware Configs

**Files:**
- Modify: `app/src/main/java/com/termux/app/loom/LoomSettings.java`
- Modify: `app/src/main/java/com/termux/app/loom/LoomConfigRenderer.java`
- Modify: `app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java`
- Modify: `app/src/main/java/com/termux/app/LoomFragment.java`
- Modify: `app/src/main/res/layout/fragment_loom.xml`
- Test: `app/src/test/java/com/termux/app/loom/LoomConfigRendererTest.java`
- Test: `app/src/test/java/com/termux/app/loom/LoomCommandBuilderTest.java`

- [ ] **Step 1: Add Codex renderer tests**

In `LoomConfigRendererTest`, add:

```java
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
```

In `LoomCommandBuilderTest`, add:

```java
@Test
public void codexSetupWritesCodexRuntimePaths() {
    LoomSettings settings = LoomSettings.defaults()
        .withAgentProvider(com.termux.app.AssistantProvider.CODEX);

    String script = LoomCommandBuilder.setupConfigScript(settings);

    Assert.assertTrue(script.contains("proot-distro login --user codex ubuntu"));
    Assert.assertTrue(script.contains("/home/codex/.loom/observer-local/observer.yaml"));
    Assert.assertTrue(script.contains("/home/codex/loom-driver/config.yaml"));
    Assert.assertTrue(script.contains("/home/codex/.loom/slave-local/config.yaml"));
    Assert.assertFalse(script.contains("/home/claude/loom-driver/config.yaml"));
}
```

- [ ] **Step 2: Run tests and confirm they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.LoomConfigRendererTest --tests com.termux.app.loom.LoomCommandBuilderTest
```

Expected: FAIL because Loom settings and command builder are still Claude-only.

- [ ] **Step 3: Add provider to `LoomSettings`**

In `LoomSettings.java`, add:

```java
public static final String KEY_AGENT_PROVIDER = "agent_provider";
```

Add field:

```java
public final com.termux.app.AssistantProvider agentProvider;
```

Update constructor and `defaults()` to pass `com.termux.app.AssistantProvider.CLAUDE`.

Add method:

```java
public LoomSettings withAgentProvider(com.termux.app.AssistantProvider agentProvider) {
    return copy(roleMode, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
        observerName, driverName, slaveName, tags, agentProvider);
}
```

Update `copy(...)` to include provider and default null to Claude:

```java
com.termux.app.AssistantProvider safeProvider =
    agentProvider == null ? com.termux.app.AssistantProvider.CLAUDE : agentProvider;
```

- [ ] **Step 4: Render provider-specific backend YAML**

In `LoomConfigRenderer`, add helper:

```java
private static String renderAgentBackend(LoomSettings s, String workdir) {
    if (s.agentProvider == com.termux.app.AssistantProvider.CODEX) {
        return ""
            + "agent:\n"
            + "  kind: codex\n"
            + "codex:\n"
            + "  bin: codex\n"
            + "  workdir: " + q(workdir) + "\n"
            + "  extra_args: []\n";
    }
    return ""
        + "agent:\n"
        + "  kind: claude\n"
        + "claude:\n"
        + "  bin: claude\n"
        + "  workdir: " + q(workdir) + "\n"
        + "  extra_args: []\n";
}
```

Replace hard-coded driver backend block with:

```java
+ renderAgentBackend(s, projectDir)
```

Replace hard-coded slave backend block with:

```java
yaml.append(renderAgentBackend(s, slaveHome));
```

- [ ] **Step 5: Make `LoomCommandBuilder` profile-aware**

Replace constants:

```java
public static final String PROOT_USER = "claude";
public static final String OBSERVER_HOME = "/home/claude/.loom/observer-local";
public static final String DRIVER_HOME = "/home/claude/.loom/driver-local";
public static final String DRIVER_PROJECT = "/home/claude/loom-driver";
public static final String SLAVE_HOME = "/home/claude/.loom/slave-local";
```

with Claude defaults and profile helpers:

```java
public static final String PROOT_USER = "claude";
public static final String OBSERVER_HOME = "/home/claude/.loom/observer-local";
public static final String DRIVER_HOME = "/home/claude/.loom/driver-local";
public static final String DRIVER_PROJECT = "/home/claude/loom-driver";
public static final String SLAVE_HOME = "/home/claude/.loom/slave-local";

private static final class Paths {
    final String user;
    final String observerHome;
    final String driverHome;
    final String driverProject;
    final String slaveHome;
    Paths(com.termux.app.AssistantProvider provider) {
        com.termux.app.ProviderProfile p = com.termux.app.ProviderProfile.forProvider(provider);
        user = p.user;
        observerHome = p.home + "/.loom/observer-local";
        driverHome = p.home + "/.loom/driver-local";
        driverProject = p.home + "/loom-driver";
        slaveHome = p.home + "/.loom/slave-local";
    }
}

private static Paths paths(LoomSettings settings) {
    return new Paths(settings == null ? com.termux.app.AssistantProvider.CLAUDE : settings.agentProvider);
}
```

Add overloads that keep existing callers compiling:

```java
public static String startObserverScript(String prefix) {
    return startObserverScript(prefix, LoomSettings.defaults());
}

public static String startObserverScript(String prefix, LoomSettings settings) { ... }

public static String startSlaveScript(String prefix) {
    return startSlaveScript(prefix, LoomSettings.defaults());
}

public static String startSlaveScript(String prefix, LoomSettings settings) { ... }
```

Inside provider-aware methods use `Paths p = paths(settings);` and call `proot(command, p.user)`.

Change `proot` signature:

```java
private static String proot(String innerCommand, String user) {
    return "proot-distro login --user " + user + " ubuntu -- bash -lc "
        + shellQuote(innerCommand);
}
```

In `setupConfigScript`, use provider paths:

```java
Paths p = paths(settings);
String observer = b64(LoomConfigRenderer.renderObserverConfig(settings, p.observerHome));
String driver = b64(LoomConfigRenderer.renderDriverConfig(settings, p.driverProject, p.driverHome));
String slave = b64(LoomConfigRenderer.renderSlaveConfig(settings, p.slaveHome, 1, "aarch64", 1));
```

Write `.mcp.json` and copied `driver-agent` under `p.driverProject`.

- [ ] **Step 6: Wire LoomFragment selected provider**

In `LoomFragment.currentSettings()`, add:

```java
.withAgentProvider(new ProviderSettingsStore(requireContext()).getSelectedProvider())
```

In button handlers, pass settings to start methods:

```java
LoomSettings settings = currentSettings();
runScript(LoomCommandBuilder.startObserverScript(prefix(), settings), "启动 Observer", null);
runScript(LoomCommandBuilder.startSlaveScript(prefix(), settings), "启动 Slave", this::maybeShowAuthUrl);
```

In `loadPrefs`, read saved provider id:

```java
AssistantProvider selected = new ProviderSettingsStore(requireContext()).getSelectedProvider();
```

Use selected global provider for setup; do not store a separate Loom override in the first implementation.

- [ ] **Step 7: Add Loom provider hint layout**

In `fragment_loom.xml`, add a small provider hint under `loom_info`:

```xml
                <TextView
                    android:id="@+id/loom_provider_text"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="当前助手：Claude Code"
                    android:textColor="#666666"
                    android:textSize="12sp"
                    android:layout_marginTop="6dp" />
```

Bind in `LoomFragment`:

```java
private TextView mProviderText;
```

In `bindViews`:

```java
mProviderText = v.findViewById(R.id.loom_provider_text);
```

In `loadPrefs`:

```java
AssistantProvider provider = new ProviderSettingsStore(requireContext()).getSelectedProvider();
if (mProviderText != null) {
    mProviderText.setText("当前助手：" + ProviderProfile.forProvider(provider).displayName + "（启动前会按它生成配置）");
}
```

- [ ] **Step 8: Run Loom tests and compile**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.LoomConfigRendererTest --tests com.termux.app.loom.LoomCommandBuilderTest
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: both commands PASS.

- [ ] **Step 9: Commit Loom provider support**

```powershell
git add app/src/main/java/com/termux/app/loom/LoomSettings.java `
        app/src/main/java/com/termux/app/loom/LoomConfigRenderer.java `
        app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java `
        app/src/main/java/com/termux/app/LoomFragment.java `
        app/src/main/res/layout/fragment_loom.xml `
        app/src/test/java/com/termux/app/loom/LoomConfigRendererTest.java `
        app/src/test/java/com/termux/app/loom/LoomCommandBuilderTest.java
git commit -m "feat(codex): render loom provider configs"
```

---

### Task 9: Full Verification and Device Checks

**Files:**
- No source files required.
- Optional update after verification: `README.md`

- [ ] **Step 1: Run focused unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.termux.app.ProviderProfileTest `
  --tests com.termux.app.ProviderSettingsStoreTest `
  --tests com.termux.app.ApiKeyStoreProviderTest `
  --tests com.termux.app.ProviderEnvironmentWriterTest `
  --tests com.termux.app.CodexExecSessionTest `
  --tests com.termux.app.AgentServerCommandBuilderTest `
  --tests com.termux.app.autotasks.AndroidCapabilityPromptBuilderTest `
  --tests com.termux.app.autotasks.AutoCodexManagerScriptTest `
  --tests com.termux.app.loom.LoomConfigRendererTest `
  --tests com.termux.app.loom.LoomCommandBuilderTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run existing Loom/installer regression tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.* --tests com.termux.app.autotasks.AutoLoomManagerScriptTest --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build debug APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL and APK under `app/build/outputs/apk/debug/`.

- [ ] **Step 4: Install on connected device**

Run:

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\claude-code-test-app_apt-android-7-debug_universal.apk
```

Expected: device is listed and install succeeds.

- [ ] **Step 5: Verify Claude backward compatibility**

Manual device checks:

```text
1. Open app Home.
2. Confirm title shows Claude Code.
3. Send a short Claude message using existing active Claude key.
4. Confirm response appears.
5. Open API Key page, select Claude, confirm old Claude keys are still listed.
6. Open AgentServer page, confirm provider hint says Claude Code.
7. Open Loom page, confirm provider hint says Claude Code.
```

Expected: Claude flow behaves like the pre-Codex build.

- [ ] **Step 6: Verify Codex setup and Home route**

Manual device checks:

```text
1. Open Ubuntu terminal once and let setup hooks run.
2. Confirm setup prints Codex setup complete or manually run `command -v codex`.
3. Open API Key page, select Codex, add an OpenAI key, set active.
4. Open Home, tap title, select Codex.
5. Send a short message.
6. Confirm response or a clear Codex CLI/setup error appears.
7. Tap title, switch back to Claude.
```

Expected: Codex uses `/home/codex` and Claude still works after switching back.

- [ ] **Step 7: Verify AgentServer provider behavior**

Manual device checks:

```text
1. With provider Claude, open AgentServer and start connection as before.
2. Stop AgentServer.
3. Switch Home provider to Codex.
4. Open AgentServer and start connection.
5. If current AgentServer supports Codex, confirm it starts with Codex logs.
6. If current AgentServer does not support Codex, confirm the page shows the unsupported backend message.
```

Expected: no silent failure and no Claude regression.

- [ ] **Step 8: Verify Loom provider behavior**

Manual device checks:

```text
1. With provider Claude, run Loom All-in-one and confirm existing behavior.
2. Stop Loom observer/slave.
3. Switch Home provider to Codex.
4. Open Loom and run setup/all-in-one.
5. Inspect log for `/home/codex` paths and `kind: codex`.
6. If packaged Loom supports Codex, confirm observer/slave start.
7. If packaged Loom does not support Codex, confirm the error is visible and Claude can still run after switching back.
```

Expected: Loom config generation follows selected provider and does not overwrite Claude paths when Codex is selected.

- [ ] **Step 9: Commit verification docs if README changed**

If README is updated with Codex usage:

```powershell
git add README.md
git commit -m "docs(codex): document provider switching"
```

If README is not changed, do not create a commit for this task.

---

## Plan Self-Review

Spec coverage:

- Global provider setting is covered by Task 1.
- Separate Claude/Codex keys are covered by Task 2 and Task 3.
- Codex user and setup hook are covered by Task 4.
- Home title switch and routing are covered by Task 5 and Task 6.
- AgentServer provider adaptation is covered by Task 7.
- Loom provider adaptation is covered by Task 8.
- Build and device verification are covered by Task 9.

Risk controls:

- Claude remains default in `AssistantProvider.fromId` and `ProviderSettingsStore`.
- Existing `new ApiKeyStore(context)` remains Claude-compatible.
- AgentServer Codex launch includes a support probe and clear failure message.
- Loom has provider-specific paths, reducing accidental cross-provider process kills.
- Codex Home route does not modify Claude history files.
