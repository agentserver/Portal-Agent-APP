# Loom Offline Addon Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Loom as an offline-first addon with a fifth bottom navigation item and a LoomFragment that can configure and manage observer, driver, and slave roles inside the shared Ubuntu proot runtime.

**Architecture:** Keep the Ubuntu/Claude base shared. Add a separate Loom addon package and `AutoLoomManager` for install/update, plus testable pure Java helpers for config and shell command generation. Add `LoomFragment` as the Android control surface, leaving AgentServer and HomeFragment isolated.

**Tech Stack:** Android Java, Gradle, JUnit/Robolectric, PowerShell packaging script, shell scripts executed through Termux `bash` and Ubuntu `proot-distro`.

---

## File Structure

Create:

- `scripts/prepare_loom_addon.ps1` - downloads Loom release assets and builds `loom-linux-arm64.tgz`.
- `app/src/main/java/com/termux/app/loom/LoomSettings.java` - immutable-ish settings object and preference keys.
- `app/src/main/java/com/termux/app/loom/LoomConfigRenderer.java` - renders observer, driver, and slave config files.
- `app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java` - builds Termux shell scripts for status/start/stop/register/log actions.
- `app/src/main/java/com/termux/app/autotasks/AutoLoomManager.java` - extracts addon asset, writes Ubuntu setup script, updates installed binaries.
- `app/src/main/java/com/termux/app/LoomFragment.java` - Android UI controller for role management.
- `app/src/main/res/layout/fragment_loom.xml` - Loom UI.
- `app/src/test/java/com/termux/app/loom/LoomConfigRendererTest.java`
- `app/src/test/java/com/termux/app/loom/LoomCommandBuilderTest.java`
- `app/src/test/java/com/termux/app/autotasks/AutoLoomManagerScriptTest.java`
- `app/src/main/assets/loom-linux-arm64.tgz` - generated binary addon package.

Modify:

- `app/src/main/java/com/termux/app/autotasks/AutoTaskCoordinator.java` - instantiate `AutoLoomManager`.
- `app/src/main/java/com/termux/app/autotasks/AutoUbuntuManager.java` - inject Loom setup package and hook.
- `app/src/main/java/com/termux/app/TermuxActivity.java` - show/hide `LoomFragment`.
- `app/src/main/res/menu/bottom_nav_menu.xml` - add fifth `Loom` item.

Do not modify in the first pass:

- `HomeFragment.java`
- `ClaudeStreamSession.java`
- `AgentServerFragment.java`, except if a shared QR helper is intentionally extracted during a small follow-up task. This plan keeps duplication acceptable for first integration.

---

## Task 1: Add Loom Settings And Config Renderer

**Files:**
- Create: `app/src/main/java/com/termux/app/loom/LoomSettings.java`
- Create: `app/src/main/java/com/termux/app/loom/LoomConfigRenderer.java`
- Test: `app/src/test/java/com/termux/app/loom/LoomConfigRendererTest.java`

- [ ] **Step 1: Write failing tests for generated configs**

Create `app/src/test/java/com/termux/app/loom/LoomConfigRendererTest.java`:

```java
package com.termux.app.loom;

import org.junit.Assert;
import org.junit.Test;

public class LoomConfigRendererTest {

    @Test
    public void observerConfigContainsListenDbAndApiKey() {
        LoomSettings s = LoomSettings.defaults()
            .withObserverListenAddr("127.0.0.1:8090")
            .withWorkspaceApiKey("secret-key");

        String yaml = LoomConfigRenderer.renderObserverConfig(s, "/home/claude/.loom/observer-local");

        Assert.assertTrue(yaml.contains("listen_addr: 127.0.0.1:8090"));
        Assert.assertTrue(yaml.contains("db_path: /home/claude/.loom/observer-local/observer.db"));
        Assert.assertTrue(yaml.contains("id: bootstrap"));
        Assert.assertTrue(yaml.contains("key: \"secret-key\""));
    }

    @Test
    public void driverConfigContainsAgentServerObserverAndClaudeBackend() {
        LoomSettings s = LoomSettings.defaults()
            .withAgentServerUrl("https://agent.example.com")
            .withObserverUrl("http://127.0.0.1:8090")
            .withWorkspaceId("ws-phone")
            .withWorkspaceApiKey("secret-key")
            .withDriverName("driver-phone");

        String yaml = LoomConfigRenderer.renderDriverConfig(s,
            "/home/claude/loom-driver", "/home/claude/.loom/driver-local");

        Assert.assertTrue(yaml.contains("url: https://agent.example.com"));
        Assert.assertTrue(yaml.contains("name: driver-phone"));
        Assert.assertTrue(yaml.contains("kind: claude"));
        Assert.assertTrue(yaml.contains("workdir: /home/claude/loom-driver"));
        Assert.assertTrue(yaml.contains("url: http://127.0.0.1:8090"));
        Assert.assertTrue(yaml.contains("workspace_id: ws-phone"));
        Assert.assertTrue(yaml.contains("agent_id: driver-phone"));
        Assert.assertTrue(yaml.contains("api_key: \"secret-key\""));
        Assert.assertTrue(yaml.contains("token_state_path: /home/claude/.loom/driver-local/observer.token"));
    }

    @Test
    public void slaveConfigContainsSkillsResourcesAndTags() {
        LoomSettings s = LoomSettings.defaults()
            .withAgentServerUrl("https://agent.example.com")
            .withObserverUrl("http://127.0.0.1:8090")
            .withWorkspaceId("ws-phone")
            .withWorkspaceApiKey("secret-key")
            .withSlaveName("slave-phone")
            .withTags("android,phone,aarch64");

        String yaml = LoomConfigRenderer.renderSlaveConfig(s,
            "/home/claude/.loom/slave-local", 8, "aarch64", 6);

        Assert.assertTrue(yaml.contains("name: slave-phone"));
        Assert.assertTrue(yaml.contains("- chat"));
        Assert.assertTrue(yaml.contains("- bash"));
        Assert.assertTrue(yaml.contains("- file"));
        Assert.assertTrue(yaml.contains("- register_mcp"));
        Assert.assertTrue(yaml.contains("cores: 8"));
        Assert.assertTrue(yaml.contains("arch: aarch64"));
        Assert.assertTrue(yaml.contains("memory_gb: 6"));
        Assert.assertTrue(yaml.contains("- android"));
        Assert.assertTrue(yaml.contains("- phone"));
        Assert.assertTrue(yaml.contains("- aarch64"));
    }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.LoomConfigRendererTest
```

Expected: compilation fails because `LoomSettings` and `LoomConfigRenderer` do not exist.

- [ ] **Step 3: Implement `LoomSettings`**

Create `app/src/main/java/com/termux/app/loom/LoomSettings.java`:

```java
package com.termux.app.loom;

public final class LoomSettings {
    public static final String PREFS_NAME = "loom_config";

    public static final String KEY_ROLE_MODE = "role_mode";
    public static final String KEY_OBSERVER_URL = "observer_url";
    public static final String KEY_OBSERVER_LISTEN_ADDR = "observer_listen_addr";
    public static final String KEY_WORKSPACE_ID = "workspace_id";
    public static final String KEY_WORKSPACE_API_KEY = "workspace_api_key";
    public static final String KEY_AGENTSERVER_URL = "agentserver_url";
    public static final String KEY_OBSERVER_NAME = "observer_name";
    public static final String KEY_DRIVER_NAME = "driver_name";
    public static final String KEY_SLAVE_NAME = "slave_name";
    public static final String KEY_TAGS = "tags";

    public final String roleMode;
    public final String observerUrl;
    public final String observerListenAddr;
    public final String workspaceId;
    public final String workspaceApiKey;
    public final String agentServerUrl;
    public final String observerName;
    public final String driverName;
    public final String slaveName;
    public final String tags;

    private LoomSettings(String roleMode, String observerUrl, String observerListenAddr,
                         String workspaceId, String workspaceApiKey, String agentServerUrl,
                         String observerName, String driverName, String slaveName, String tags) {
        this.roleMode = roleMode;
        this.observerUrl = observerUrl;
        this.observerListenAddr = observerListenAddr;
        this.workspaceId = workspaceId;
        this.workspaceApiKey = workspaceApiKey;
        this.agentServerUrl = agentServerUrl;
        this.observerName = observerName;
        this.driverName = driverName;
        this.slaveName = slaveName;
        this.tags = tags;
    }

    public static LoomSettings defaults() {
        return new LoomSettings(
            "all",
            "http://127.0.0.1:8090",
            "127.0.0.1:8090",
            "ws-phone",
            "",
            "https://agent.cs.ac.cn",
            "observer-phone",
            "driver-phone",
            "slave-phone",
            "android,phone,aarch64"
        );
    }

    public LoomSettings withObserverUrl(String v) {
        return new LoomSettings(roleMode, v, observerListenAddr, workspaceId, workspaceApiKey,
            agentServerUrl, observerName, driverName, slaveName, tags);
    }

    public LoomSettings withObserverListenAddr(String v) {
        return new LoomSettings(roleMode, observerUrl, v, workspaceId, workspaceApiKey,
            agentServerUrl, observerName, driverName, slaveName, tags);
    }

    public LoomSettings withWorkspaceId(String v) {
        return new LoomSettings(roleMode, observerUrl, observerListenAddr, v, workspaceApiKey,
            agentServerUrl, observerName, driverName, slaveName, tags);
    }

    public LoomSettings withWorkspaceApiKey(String v) {
        return new LoomSettings(roleMode, observerUrl, observerListenAddr, workspaceId, v,
            agentServerUrl, observerName, driverName, slaveName, tags);
    }

    public LoomSettings withAgentServerUrl(String v) {
        return new LoomSettings(roleMode, observerUrl, observerListenAddr, workspaceId,
            workspaceApiKey, v, observerName, driverName, slaveName, tags);
    }

    public LoomSettings withDriverName(String v) {
        return new LoomSettings(roleMode, observerUrl, observerListenAddr, workspaceId,
            workspaceApiKey, agentServerUrl, observerName, v, slaveName, tags);
    }

    public LoomSettings withSlaveName(String v) {
        return new LoomSettings(roleMode, observerUrl, observerListenAddr, workspaceId,
            workspaceApiKey, agentServerUrl, observerName, driverName, v, tags);
    }

    public LoomSettings withTags(String v) {
        return new LoomSettings(roleMode, observerUrl, observerListenAddr, workspaceId,
            workspaceApiKey, agentServerUrl, observerName, driverName, slaveName, v);
    }

    static String yamlQuote(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
```

- [ ] **Step 4: Implement `LoomConfigRenderer`**

Create `app/src/main/java/com/termux/app/loom/LoomConfigRenderer.java`:

```java
package com.termux.app.loom;

public final class LoomConfigRenderer {
    private LoomConfigRenderer() {}

    public static String renderObserverConfig(LoomSettings s, String observerHome) {
        return "listen_addr: " + s.observerListenAddr + "\n"
            + "db_path: " + observerHome + "/observer.db\n"
            + "api_keys:\n"
            + "  - id: bootstrap\n"
            + "    key: " + LoomSettings.yamlQuote(s.workspaceApiKey) + "\n"
            + "    note: Android app bootstrap key\n";
    }

    public static String renderDriverConfig(LoomSettings s, String projectDir, String tokenDir) {
        return "server:\n"
            + "  url: " + s.agentServerUrl + "\n"
            + "  name: " + s.driverName + "\n\n"
            + "credentials:\n"
            + "  sandbox_id: \"\"\n"
            + "  tunnel_token: \"\"\n"
            + "  proxy_token: \"\"\n"
            + "  workspace_id: \"\"\n"
            + "  short_id: \"\"\n\n"
            + "discovery:\n"
            + "  display_name: " + s.driverName + "\n"
            + "  description: Android Loom driver (" + s.driverName + ")\n"
            + "  skills: []\n\n"
            + "listen_addr: 127.0.0.1:0\n\n"
            + "agent:\n"
            + "  kind: claude\n\n"
            + "claude:\n"
            + "  bin: claude\n"
            + "  workdir: " + projectDir + "\n"
            + "  extra_args: []\n\n"
            + "planner:\n"
            + "  bin: \"\"\n"
            + "  timeout_sec: 300\n"
            + "  extra_args: []\n\n"
            + "fanout:\n"
            + "  max_concurrency: 2\n"
            + "  default_policy: \"\"\n"
            + "  policy_by_skill: {}\n"
            + "  subtask_defaults:\n"
            + "    timeout_sec: 600\n"
            + "    max_budget_usd: 0\n\n"
            + "driver_defaults:\n"
            + "  target_display_name: \"\"\n"
            + "  task_timeout_sec: 600\n"
            + "  audit_log_dir: " + projectDir + "/logs\n"
            + "  disable_uid_check: true\n"
            + "  max_dir_cache_entries: 50000\n"
            + "  artifact_transport: observer_lazy\n\n"
            + "observer:\n"
            + "  enabled: true\n"
            + "  url: " + s.observerUrl + "\n"
            + "  workspace_id: " + s.workspaceId + "\n"
            + "  agent_id: " + s.driverName + "\n"
            + "  api_key: " + LoomSettings.yamlQuote(s.workspaceApiKey) + "\n"
            + "  token_state_path: " + tokenDir + "/observer.token\n";
    }

    public static String renderSlaveConfig(LoomSettings s, String slaveHome,
                                           int cpuCores, String arch, int memoryGb) {
        StringBuilder tagsYaml = new StringBuilder();
        String[] tags = (s.tags == null ? "" : s.tags).split(",");
        for (String tag : tags) {
            String t = tag.trim();
            if (!t.isEmpty()) tagsYaml.append("    - ").append(t).append("\n");
        }
        if (tagsYaml.length() == 0) tagsYaml.append("    - android\n");

        return "server:\n"
            + "  url: " + s.agentServerUrl + "\n"
            + "  name: " + s.slaveName + "\n\n"
            + "credentials:\n"
            + "  sandbox_id: \"\"\n"
            + "  tunnel_token: \"\"\n"
            + "  proxy_token: \"\"\n"
            + "  short_id: \"\"\n\n"
            + "agent:\n"
            + "  kind: claude\n\n"
            + "claude:\n"
            + "  bin: claude\n"
            + "  workdir: " + slaveHome + "\n"
            + "  extra_args: []\n\n"
            + "mcp_servers: {}\n\n"
            + "discovery:\n"
            + "  display_name: " + s.slaveName + "\n"
            + "  description: Android Loom slave (" + s.slaveName + ")\n"
            + "  skills:\n"
            + "    - chat\n"
            + "    - bash\n"
            + "    - permissions\n"
            + "    - register_mcp\n"
            + "    - file\n\n"
            + "planner:\n"
            + "  bin: \"\"\n"
            + "  timeout_sec: 300\n"
            + "  extra_args: []\n\n"
            + "fanout:\n"
            + "  max_concurrency: 1\n"
            + "  default_policy: best_effort\n"
            + "  policy_by_skill: {}\n\n"
            + "resources:\n"
            + "  cpu:\n"
            + "    cores: " + cpuCores + "\n"
            + "    arch: " + arch + "\n"
            + "  memory_gb: " + memoryGb + "\n"
            + "  tags:\n" + tagsYaml
            + "\nobserver:\n"
            + "  enabled: true\n"
            + "  url: " + s.observerUrl + "\n"
            + "  workspace_id: " + s.workspaceId + "\n"
            + "  agent_id: " + s.slaveName + "\n"
            + "  api_key: " + LoomSettings.yamlQuote(s.workspaceApiKey) + "\n"
            + "  token_state_path: " + slaveHome + "/observer.token\n";
    }
}
```

- [ ] **Step 5: Run tests and commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.LoomConfigRendererTest
```

Expected: tests pass.

Commit:

```powershell
git add app/src/main/java/com/termux/app/loom/LoomSettings.java `
        app/src/main/java/com/termux/app/loom/LoomConfigRenderer.java `
        app/src/test/java/com/termux/app/loom/LoomConfigRendererTest.java
git commit -m "feat(loom): add config renderers"
```

---

## Task 2: Add Loom Command Builder

**Files:**
- Create: `app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java`
- Test: `app/src/test/java/com/termux/app/loom/LoomCommandBuilderTest.java`

- [ ] **Step 1: Write failing command tests**

Create `app/src/test/java/com/termux/app/loom/LoomCommandBuilderTest.java`:

```java
package com.termux.app.loom;

import org.junit.Assert;
import org.junit.Test;

public class LoomCommandBuilderTest {
    @Test
    public void statusChecksAllRoleBinariesAndProcesses() {
        String script = LoomCommandBuilder.statusScript("/data/data/com.termux/files/usr");

        Assert.assertTrue(script.contains("command -v proot-distro"));
        Assert.assertTrue(script.contains("command -v observer-server"));
        Assert.assertTrue(script.contains("command -v driver-agent"));
        Assert.assertTrue(script.contains("command -v slave-agent"));
        Assert.assertTrue(script.contains("pgrep -f 'observer-server --config .*observer-local/observer.yaml'"));
        Assert.assertTrue(script.contains("pgrep -f 'slave-agent .*\\.loom/slave-local/config.yaml'"));
    }

    @Test
    public void startObserverUsesNohupAndSpecificConfig() {
        String script = LoomCommandBuilder.startObserverScript("/data/data/com.termux/files/usr");

        Assert.assertTrue(script.contains("cd /home/claude/.loom/observer-local"));
        Assert.assertTrue(script.contains("nohup observer-server --config observer.yaml"));
        Assert.assertTrue(script.contains("loom-observer.log"));
    }

    @Test
    public void stopScriptsUseNarrowKillPatterns() {
        Assert.assertTrue(LoomCommandBuilder.stopObserverScript()
            .contains("pkill -f 'observer-server --config .*observer-local/observer.yaml'"));
        Assert.assertTrue(LoomCommandBuilder.stopSlaveScript()
            .contains("pkill -f 'slave-agent .*\\.loom/slave-local/config.yaml'"));
    }

    @Test
    public void registerDriverUsesExpectedProjectPath() {
        String script = LoomCommandBuilder.registerDriverScript();

        Assert.assertTrue(script.contains("/home/claude/loom-driver/driver-agent register"));
        Assert.assertTrue(script.contains("--config /home/claude/loom-driver/config.yaml"));
    }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.LoomCommandBuilderTest
```

Expected: compilation fails because `LoomCommandBuilder` does not exist.

- [ ] **Step 3: Implement command builder**

Create `app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java`:

```java
package com.termux.app.loom;

public final class LoomCommandBuilder {
    private LoomCommandBuilder() {}

    public static final String PROOT_USER = "claude";
    public static final String OBSERVER_HOME = "/home/claude/.loom/observer-local";
    public static final String DRIVER_HOME = "/home/claude/.loom/driver-local";
    public static final String DRIVER_PROJECT = "/home/claude/loom-driver";
    public static final String SLAVE_HOME = "/home/claude/.loom/slave-local";

    private static String pd(String inner) {
        return "proot-distro login --user " + PROOT_USER + " ubuntu -- bash -lc '"
            + escapeSingleQuotes(inner) + "'\n";
    }

    public static String statusScript(String prefix) {
        return "if ! command -v proot-distro >/dev/null 2>&1; then echo '[!] proot-distro missing'; exit 1; fi\n"
            + pd("echo '== Loom binaries =='; "
            + "for b in observer-server driver-agent slave-agent mcp-userspace; do "
            + "if command -v $b >/dev/null 2>&1; then echo \"[ok] $b $(command -v $b)\"; "
            + "else echo \"[-] $b missing\"; fi; done; "
            + "echo; echo '== Loom processes =='; "
            + "pgrep -a -f 'observer-server --config .*observer-local/observer.yaml' || echo 'observer: stopped'; "
            + "pgrep -a -f 'slave-agent .*\\.loom/slave-local/config.yaml' || echo 'slave: stopped'; "
            + "test -f " + DRIVER_PROJECT + "/config.yaml && echo 'driver: project ready' || echo 'driver: not configured'");
    }

    public static String startObserverScript(String prefix) {
        return pd("mkdir -p " + OBSERVER_HOME + "; "
            + "cd " + OBSERVER_HOME + "; "
            + "pgrep -f 'observer-server --config .*observer-local/observer.yaml' >/dev/null && "
            + "{ echo '[*] observer already running'; exit 0; }; "
            + "nohup observer-server --config observer.yaml >> '" + prefix + "/../home/loom-observer.log' 2>&1 & "
            + "echo '[*] observer started'");
    }

    public static String stopObserverScript() {
        return "pkill -f 'observer-server --config .*observer-local/observer.yaml' 2>/dev/null "
            + "&& echo '[*] observer stopped' || echo '[-] observer not running'\n";
    }

    public static String startSlaveScript(String prefix) {
        return pd("mkdir -p " + SLAVE_HOME + "; "
            + "cd " + SLAVE_HOME + "; "
            + "pgrep -f 'slave-agent .*\\.loom/slave-local/config.yaml' >/dev/null && "
            + "{ echo '[*] slave already running'; exit 0; }; "
            + "nohup slave-agent config.yaml >> '" + prefix + "/../home/loom-slave.log' 2>&1 & "
            + "echo '[*] slave started'");
    }

    public static String stopSlaveScript() {
        return "pkill -f 'slave-agent .*\\.loom/slave-local/config.yaml' 2>/dev/null "
            + "&& echo '[*] slave stopped' || echo '[-] slave not running'\n";
    }

    public static String registerDriverScript() {
        return pd("cd " + DRIVER_PROJECT + "; "
            + DRIVER_PROJECT + "/driver-agent register --config " + DRIVER_PROJECT + "/config.yaml");
    }

    private static String escapeSingleQuotes(String s) {
        return s.replace("'", "'\\''");
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.LoomCommandBuilderTest
```

Expected: tests pass.

Commit:

```powershell
git add app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java `
        app/src/test/java/com/termux/app/loom/LoomCommandBuilderTest.java
git commit -m "feat(loom): add role command builder"
```

---

## Task 3: Add Loom Addon Packaging Script

**Files:**
- Create: `scripts/prepare_loom_addon.ps1`
- Output: `app/src/main/assets/loom-linux-arm64.tgz`

- [ ] **Step 1: Create packaging script**

Create `scripts/prepare_loom_addon.ps1`:

```powershell
param(
    [string]$Version = "latest",
    [string]$OutFile = "app/src/main/assets/loom-linux-arm64.tgz",
    [string]$WorkDir = "build/loom-addon"
)

$ErrorActionPreference = "Stop"
$repo = "https://github.com/agentserver/loom"
$downloadBase = "$repo/releases/$Version/download"
if ($Version -eq "latest") {
    $downloadBase = "$repo/releases/latest/download"
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$work = Join-Path $root $WorkDir
$stage = Join-Path $work "loom"
$bin = Join-Path $stage "bin"
$deploy = Join-Path $stage "deploy"
$skills = Join-Path $stage "skills"
$prompts = Join-Path $stage "prompts-codex"

Remove-Item -Recurse -Force -LiteralPath $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $bin, $deploy, $skills, $prompts | Out-Null

function Download-Asset([string]$name, [string]$path, [bool]$required = $true) {
    $url = "$downloadBase/$name"
    $part = "$path.part"
    Remove-Item -LiteralPath $part -Force -ErrorAction SilentlyContinue
    try {
        Invoke-WebRequest -Uri $url -OutFile $part -Headers @{ "User-Agent" = "Codex" }
        if ((Get-Item $part).Length -le 0) { throw "empty asset $name" }
        Move-Item -Force -LiteralPath $part -Destination $path
    } catch {
        Remove-Item -LiteralPath $part -Force -ErrorAction SilentlyContinue
        if ($required) { throw }
        Write-Host "Optional asset not available: $name"
    }
}

Download-Asset "observer-server.linux-arm64" (Join-Path $bin "observer-server")
Download-Asset "driver-agent.linux-arm64" (Join-Path $bin "driver-agent")
Download-Asset "slave-agent.linux-arm64" (Join-Path $bin "slave-agent")
Download-Asset "mcp-userspace.linux-arm64" (Join-Path $bin "mcp-userspace") $false
Download-Asset "driver-skills.tar.gz" (Join-Path $work "driver-skills.tar.gz") $false
Download-Asset "driver-codex-prompts.tar.gz" (Join-Path $work "driver-codex-prompts.tar.gz") $false
Download-Asset "sha256sums.txt" (Join-Path $stage "sha256sums.txt") $false

if (Test-Path (Join-Path $work "driver-skills.tar.gz")) {
    tar -xzf (Join-Path $work "driver-skills.tar.gz") -C $skills
}
if (Test-Path (Join-Path $work "driver-codex-prompts.tar.gz")) {
    tar -xzf (Join-Path $work "driver-codex-prompts.tar.gz") -C $prompts
}

$manifest = [ordered]@{
    name = "loom"
    version = $Version
    source = "https://github.com/agentserver/loom"
    arch = "linux-arm64"
    required = @("bin/observer-server", "bin/driver-agent", "bin/slave-agent")
    optional = @("bin/mcp-userspace")
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $stage "manifest.json")

New-Item -ItemType Directory -Force -Path (Split-Path (Join-Path $root $OutFile)) | Out-Null
Push-Location $work
tar -czf (Join-Path $root $OutFile) "loom"
Pop-Location

Write-Host "Wrote $OutFile"
Get-Item (Join-Path $root $OutFile) | Format-List FullName,Length
```

- [ ] **Step 2: Run packaging script**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\prepare_loom_addon.ps1 -Version v0.0.2
```

Expected:

- `app/src/main/assets/loom-linux-arm64.tgz` exists.
- Output lists non-zero length.
- Script logs an optional-skip for `mcp-userspace.linux-arm64` if the release still lacks that asset.

- [ ] **Step 3: Inspect package contents**

Run:

```powershell
tar -tzf .\app\src\main\assets\loom-linux-arm64.tgz | Select-String "loom/bin|manifest.json|skills"
```

Expected: contains `loom/bin/observer-server`, `loom/bin/driver-agent`, `loom/bin/slave-agent`, and `loom/manifest.json`.

- [ ] **Step 4: Commit**

Commit:

```powershell
git add scripts/prepare_loom_addon.ps1 app/src/main/assets/loom-linux-arm64.tgz
git commit -m "build(loom): add offline addon package"
```

---

## Task 4: Add Loom Installer Script Builder And AutoLoomManager

**Files:**
- Create: `app/src/main/java/com/termux/app/autotasks/AutoLoomManager.java`
- Test: `app/src/test/java/com/termux/app/autotasks/AutoLoomManagerScriptTest.java`

- [ ] **Step 1: Write failing installer script tests**

Create `app/src/test/java/com/termux/app/autotasks/AutoLoomManagerScriptTest.java`:

```java
package com.termux.app.autotasks;

import org.junit.Assert;
import org.junit.Test;

public class AutoLoomManagerScriptTest {
    @Test
    public void innerScriptInstallsRequiredBinariesAtomically() {
        String script = AutoLoomManager.buildInnerScriptForTest(true);

        Assert.assertTrue(script.contains("_tgz='/tmp/loom-linux-arm64.tgz'"));
        Assert.assertTrue(script.contains("observer-server"));
        Assert.assertTrue(script.contains("driver-agent"));
        Assert.assertTrue(script.contains("slave-agent"));
        Assert.assertTrue(script.contains("/usr/local/bin/observer-server.new"));
        Assert.assertTrue(script.contains("mv -f /usr/local/bin/observer-server.new /usr/local/bin/observer-server"));
        Assert.assertTrue(script.contains("mv -f /usr/local/bin/driver-agent.new /usr/local/bin/driver-agent"));
        Assert.assertTrue(script.contains("mv -f /usr/local/bin/slave-agent.new /usr/local/bin/slave-agent"));
    }

    @Test
    public void innerScriptHasOnlineFallbackAndPartFiles() {
        String script = AutoLoomManager.buildInnerScriptForTest(false);

        Assert.assertTrue(script.contains("github.com/agentserver/loom/releases/latest/download"));
        Assert.assertTrue(script.contains(".part"));
        Assert.assertTrue(script.contains("driver-skills.tar.gz"));
        Assert.assertTrue(script.contains("sha256sums.txt"));
    }

    @Test
    public void innerScriptCreatesRuntimeDirectories() {
        String script = AutoLoomManager.buildInnerScriptForTest(true);

        Assert.assertTrue(script.contains("/home/claude/.loom/observer-local"));
        Assert.assertTrue(script.contains("/home/claude/.loom/slave-local"));
        Assert.assertTrue(script.contains("/home/claude/loom-driver"));
        Assert.assertTrue(script.contains("chown -R claude:claude /home/claude/.loom /home/claude/loom-driver"));
    }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.autotasks.AutoLoomManagerScriptTest
```

Expected: compilation fails because `AutoLoomManager` does not exist.

- [ ] **Step 3: Implement AutoLoomManager skeleton and script builder**

Create `app/src/main/java/com/termux/app/autotasks/AutoLoomManager.java` based on `AutoAgentServerManager`:

```java
package com.termux.app.autotasks;

import android.content.res.AssetManager;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;

public class AutoLoomManager {
    static final String ASSET_TGZ_NAME = "loom-linux-arm64.tgz";
    static final String ASSET_TGZ_REL = "home/.loom-addon/" + ASSET_TGZ_NAME;
    static final String INNER_SCRIPT_REL = "home/.loom-setup.sh";

    private final TermuxActivity mActivity;
    private volatile boolean mExtractionDone = false;

    public AutoLoomManager(@NonNull TermuxActivity activity) {
        mActivity = activity;
        Thread t = new Thread(this::prepare, "loom-setup-prepare");
        t.setDaemon(true);
        t.start();
    }

    public void awaitExtraction(long timeoutMs) {
        if (mExtractionDone) return;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!mExtractionDone && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
        }
    }

    @NonNull
    public String getInnerScriptPath() {
        return new File(mActivity.getFilesDir(), INNER_SCRIPT_REL).getAbsolutePath();
    }

    @NonNull
    public String getTgzPath() {
        return new File(mActivity.getFilesDir(), ASSET_TGZ_REL).getAbsolutePath();
    }

    private void prepare() {
        boolean ok = extractAsset();
        writeInnerScript(ok);
        mExtractionDone = true;
    }

    private boolean extractAsset() {
        File dest = new File(mActivity.getFilesDir(), ASSET_TGZ_REL);
        long assetSize = getAssetSize();
        if (dest.exists() && dest.length() > 0 && dest.length() == assetSize) return true;
        if (dest.exists()) dest.delete();
        dest.getParentFile().mkdirs();
        AssetManager am = mActivity.getAssets();
        File tmp = new File(dest.getParent(), ASSET_TGZ_NAME + ".tmp");
        try (InputStream in = am.open(ASSET_TGZ_NAME);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            return false;
        }
        return dest.exists() && dest.length() > 0;
    }

    private long getAssetSize() {
        try (InputStream in = mActivity.getAssets().open(ASSET_TGZ_NAME)) {
            long size = 0;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) size += n;
            return size;
        } catch (IOException e) {
            return -1;
        }
    }

    private void writeInnerScript(boolean extractionOk) {
        File scriptFile = new File(mActivity.getFilesDir(), INNER_SCRIPT_REL);
        try {
            scriptFile.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(scriptFile)) {
                w.write(buildInnerScript(extractionOk));
            }
        } catch (IOException ignored) {}
    }

    static String buildInnerScriptForTest(boolean localTgzAvailable) {
        return buildInnerScript(localTgzAvailable);
    }

    private static String buildInnerScript(boolean localTgzAvailable) {
        StringBuilder s = new StringBuilder();
        s.append("#!/bin/bash\n");
        s.append("# Loom auto-setup\n");
        s.append("set -e\n\n");
        s.append("echo '[*] Loom setup starting'\n");
        s.append("_tgz='/tmp/loom-linux-arm64.tgz'\n");
        s.append("_tmpdir=$(mktemp -d)\n");
        if (!localTgzAvailable) {
            s.append("echo '[!] local Loom addon unavailable; downloading release assets'\n");
        }
        s.append("install_bin() { _src=\"$1\"; _dst=\"$2\"; cp \"$_src\" \"$_dst.new\" && chmod +x \"$_dst.new\" && mv -f \"$_dst.new\" \"$_dst\"; }\n");
        s.append("mkdir -p /usr/local/bin /home/claude/.loom/observer-local /home/claude/.loom/slave-local /home/claude/.loom/driver-local /home/claude/loom-driver\n");
        s.append("if [ -f \"$_tgz\" ]; then\n");
        s.append("  tar -xzf \"$_tgz\" -C \"$_tmpdir\"\n");
        s.append("  install_bin \"$_tmpdir/loom/bin/observer-server\" /usr/local/bin/observer-server\n");
        s.append("  install_bin \"$_tmpdir/loom/bin/driver-agent\" /usr/local/bin/driver-agent\n");
        s.append("  install_bin \"$_tmpdir/loom/bin/slave-agent\" /usr/local/bin/slave-agent\n");
        s.append("  [ -f \"$_tmpdir/loom/bin/mcp-userspace\" ] && install_bin \"$_tmpdir/loom/bin/mcp-userspace\" /usr/local/bin/mcp-userspace || true\n");
        s.append("  cp /usr/local/bin/driver-agent /home/claude/loom-driver/driver-agent\n");
        s.append("  [ -d \"$_tmpdir/loom/skills\" ] && mkdir -p /home/claude/loom-driver/.claude && cp -R \"$_tmpdir/loom/skills\" /home/claude/loom-driver/.claude/\n");
        s.append("else\n");
        s.append("  _base='https://github.com/agentserver/loom/releases/latest/download'\n");
        s.append("  dl() { _n=\"$1\"; _o=\"$2\"; rm -f \"$_o.part\"; curl -fL --progress-bar -o \"$_o.part\" \"$_base/$_n\" && [ -s \"$_o.part\" ] && mv \"$_o.part\" \"$_o\"; }\n");
        s.append("  dl observer-server.linux-arm64 /tmp/observer-server\n");
        s.append("  dl driver-agent.linux-arm64 /tmp/driver-agent\n");
        s.append("  dl slave-agent.linux-arm64 /tmp/slave-agent\n");
        s.append("  dl driver-skills.tar.gz /tmp/driver-skills.tar.gz || true\n");
        s.append("  dl driver-codex-prompts.tar.gz /tmp/driver-codex-prompts.tar.gz || true\n");
        s.append("  dl sha256sums.txt /tmp/sha256sums.txt || true\n");
        s.append("  install_bin /tmp/observer-server /usr/local/bin/observer-server\n");
        s.append("  install_bin /tmp/driver-agent /usr/local/bin/driver-agent\n");
        s.append("  install_bin /tmp/slave-agent /usr/local/bin/slave-agent\n");
        s.append("  cp /usr/local/bin/driver-agent /home/claude/loom-driver/driver-agent\n");
        s.append("fi\n");
        s.append("chown -R claude:claude /home/claude/.loom /home/claude/loom-driver\n");
        s.append("rm -rf \"$_tmpdir\" \"$_tgz\"\n");
        s.append("sed -i '/.loom-setup/d' ~/.bashrc 2>/dev/null || true\n");
        s.append("rm -f ~/.loom-setup.sh\n");
        s.append("echo '[*] Loom setup complete'\n");
        return s.toString();
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.autotasks.AutoLoomManagerScriptTest
```

Expected: tests pass.

Commit:

```powershell
git add app/src/main/java/com/termux/app/autotasks/AutoLoomManager.java `
        app/src/test/java/com/termux/app/autotasks/AutoLoomManagerScriptTest.java
git commit -m "feat(loom): add offline installer manager"
```

---

## Task 5: Inject Loom Setup Into Ubuntu Bootstrapping

**Files:**
- Modify: `app/src/main/java/com/termux/app/autotasks/AutoTaskCoordinator.java`
- Modify: `app/src/main/java/com/termux/app/autotasks/AutoUbuntuManager.java`

- [ ] **Step 1: Modify `AutoTaskCoordinator`**

Add field:

```java
@SuppressWarnings("FieldCanBeLocal")
private final AutoLoomManager mAutoLoomManager;
```

In constructor, after `mAutoAgentServerManager = new AutoAgentServerManager(activity);`:

```java
mAutoLoomManager = new AutoLoomManager(activity);
```

After `mAutoUbuntuManager.setAgentServerManager(mAutoAgentServerManager);`:

```java
mAutoUbuntuManager.setLoomManager(mAutoLoomManager);
```

- [ ] **Step 2: Modify `AutoUbuntuManager` fields and setter**

Add field:

```java
private AutoLoomManager mLoomManager;
```

Add setter near `setAgentServerManager`:

```java
/** Inject Loom manager so Ubuntu setup can copy the offline Loom addon. */
public void setLoomManager(@NonNull AutoLoomManager mgr) {
    mLoomManager = mgr;
}
```

- [ ] **Step 3: Copy Loom addon and hook in setup command**

In `buildUbuntuCommand()`, find the existing AgentServer copy/hook block. After that block append:

```java
String loomTgzPath = mLoomManager == null ? "" :
    new File(mLoomManager.getTgzPath()).getAbsolutePath();
String loomInnerPath = mLoomManager == null ? "" :
    new File(mLoomManager.getInnerScriptPath()).getAbsolutePath();
```

Before script execution waits finish, add:

```java
if (mLoomManager != null) {
    mLoomManager.awaitExtraction(5000);
}
```

In the command string after AgentServer setup injection:

```java
.append("if [ -n '").append(loomTgzPath).append("' ] && [ -f '").append(loomTgzPath).append("' ]; then ")
.append("cp '").append(loomTgzPath).append("' \"$_ubr/tmp/loom-linux-arm64.tgz\" && ")
.append("echo \"[*] Loom addon copied to Ubuntu /tmp/\"; ")
.append("else echo \"[!] Loom addon not ready; setup will use network fallback\"; fi; ")
.append("if [ -n '").append(loomInnerPath).append("' ] && [ -f '").append(loomInnerPath).append("' ]; then ")
.append("cp '").append(loomInnerPath).append("' \"$_ubr/root/.loom-setup.sh\" && ")
.append("{ grep -qF '.loom-setup' \"$_ubr/root/.bashrc\" 2>/dev/null || ")
.append("printf '\\n[ -f ~/.loom-setup.sh ] && . ~/.loom-setup.sh\\n' >> \"$_ubr/root/.bashrc\"; }; ")
.append("fi; ")
```

Use local variable names consistent with the existing method body.

- [ ] **Step 4: Run focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.autotasks.AutoLoomManagerScriptTest
```

Expected: tests still pass.

- [ ] **Step 5: Run compile check**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: Java compilation succeeds.

- [ ] **Step 6: Commit**

Commit:

```powershell
git add app/src/main/java/com/termux/app/autotasks/AutoTaskCoordinator.java `
        app/src/main/java/com/termux/app/autotasks/AutoUbuntuManager.java
git commit -m "feat(loom): inject setup into Ubuntu bootstrap"
```

---

## Task 6: Add LoomFragment Layout And Navigation Shell

**Files:**
- Create: `app/src/main/res/layout/fragment_loom.xml`
- Create: `app/src/main/java/com/termux/app/LoomFragment.java`
- Modify: `app/src/main/res/menu/bottom_nav_menu.xml`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java`

- [ ] **Step 1: Add bottom navigation item**

Modify `app/src/main/res/menu/bottom_nav_menu.xml`:

```xml
<item
    android:id="@+id/nav_loom"
    android:icon="@android:drawable/ic_menu_upload"
    android:title="Loom" />
```

Place it after `nav_agentserver`.

- [ ] **Step 2: Add Loom layout**

Create `app/src/main/res/layout/fragment_loom.xml` with the same structure as `fragment_agent_server.xml`: header, scrollable config area, and fixed log pane. Use these required ids:

```xml
@+id/loom_status_text
@+id/loom_info
@+id/loom_role_mode
@+id/loom_observer_url
@+id/loom_workspace_id
@+id/loom_workspace_api_key
@+id/loom_agentserver_url
@+id/loom_observer_name
@+id/loom_driver_name
@+id/loom_slave_name
@+id/loom_tags
@+id/btn_loom_save
@+id/btn_loom_status
@+id/btn_loom_setup
@+id/btn_loom_start_observer
@+id/btn_loom_stop_observer
@+id/btn_loom_register_driver
@+id/btn_loom_start_slave
@+id/btn_loom_stop_slave
@+id/btn_loom_monitor
@+id/btn_loom_clear_log
@+id/loom_log_label
@+id/loom_log_scroll
@+id/loom_log
```

Use `Spinner` for `loom_role_mode` with entries in Java for first pass. Keep text sizes and colors aligned with `fragment_agent_server.xml`.

- [ ] **Step 3: Add LoomFragment skeleton**

Create `app/src/main/java/com/termux/app/LoomFragment.java`:

```java
package com.termux.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.loom.LoomSettings;

public class LoomFragment extends Fragment {
    private Spinner mRoleMode;
    private TextView mStatusText;
    private TextView mInfoText;
    private TextView mLogText;
    private TextView mLogLabel;
    private ScrollView mLogScroll;
    private EditText mObserverUrl;
    private EditText mWorkspaceId;
    private EditText mWorkspaceApiKey;
    private EditText mAgentServerUrl;
    private EditText mObserverName;
    private EditText mDriverName;
    private EditText mSlaveName;
    private EditText mTags;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_loom, container, false);
        bindViews(v);
        setupRoleSpinner();
        loadPrefs();
        v.findViewById(R.id.btn_loom_save).setOnClickListener(b -> {
            savePrefs();
            Toast.makeText(getContext(), "Loom settings saved", Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.btn_loom_clear_log).setOnClickListener(b -> clearLog());
        return v;
    }

    private void bindViews(View v) {
        mRoleMode = v.findViewById(R.id.loom_role_mode);
        mStatusText = v.findViewById(R.id.loom_status_text);
        mInfoText = v.findViewById(R.id.loom_info);
        mLogText = v.findViewById(R.id.loom_log);
        mLogLabel = v.findViewById(R.id.loom_log_label);
        mLogScroll = v.findViewById(R.id.loom_log_scroll);
        mObserverUrl = v.findViewById(R.id.loom_observer_url);
        mWorkspaceId = v.findViewById(R.id.loom_workspace_id);
        mWorkspaceApiKey = v.findViewById(R.id.loom_workspace_api_key);
        mAgentServerUrl = v.findViewById(R.id.loom_agentserver_url);
        mObserverName = v.findViewById(R.id.loom_observer_name);
        mDriverName = v.findViewById(R.id.loom_driver_name);
        mSlaveName = v.findViewById(R.id.loom_slave_name);
        mTags = v.findViewById(R.id.loom_tags);
    }

    private void setupRoleSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
            android.R.layout.simple_spinner_item,
            new String[] {"All-in-one", "Observer", "Driver", "Slave"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mRoleMode.setAdapter(adapter);
    }

    private void loadPrefs() {
        LoomSettings d = LoomSettings.defaults();
        android.content.SharedPreferences p = requireContext()
            .getSharedPreferences(LoomSettings.PREFS_NAME, android.content.Context.MODE_PRIVATE);
        mObserverUrl.setText(p.getString(LoomSettings.KEY_OBSERVER_URL, d.observerUrl));
        mWorkspaceId.setText(p.getString(LoomSettings.KEY_WORKSPACE_ID, d.workspaceId));
        mWorkspaceApiKey.setText(p.getString(LoomSettings.KEY_WORKSPACE_API_KEY, d.workspaceApiKey));
        mAgentServerUrl.setText(p.getString(LoomSettings.KEY_AGENTSERVER_URL, d.agentServerUrl));
        mObserverName.setText(p.getString(LoomSettings.KEY_OBSERVER_NAME, d.observerName));
        mDriverName.setText(p.getString(LoomSettings.KEY_DRIVER_NAME, d.driverName));
        mSlaveName.setText(p.getString(LoomSettings.KEY_SLAVE_NAME, d.slaveName));
        mTags.setText(p.getString(LoomSettings.KEY_TAGS, d.tags));
    }

    private void savePrefs() {
        requireContext().getSharedPreferences(LoomSettings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(LoomSettings.KEY_OBSERVER_URL, mObserverUrl.getText().toString().trim())
            .putString(LoomSettings.KEY_WORKSPACE_ID, mWorkspaceId.getText().toString().trim())
            .putString(LoomSettings.KEY_WORKSPACE_API_KEY, mWorkspaceApiKey.getText().toString().trim())
            .putString(LoomSettings.KEY_AGENTSERVER_URL, mAgentServerUrl.getText().toString().trim())
            .putString(LoomSettings.KEY_OBSERVER_NAME, mObserverName.getText().toString().trim())
            .putString(LoomSettings.KEY_DRIVER_NAME, mDriverName.getText().toString().trim())
            .putString(LoomSettings.KEY_SLAVE_NAME, mSlaveName.getText().toString().trim())
            .putString(LoomSettings.KEY_TAGS, mTags.getText().toString().trim())
            .apply();
    }

    private void clearLog() {
        if (mLogText != null) mLogText.setText("");
    }
}
```

- [ ] **Step 4: Wire TermuxActivity navigation**

In `setupBottomNav()`, add:

```java
} else if (id == R.id.nav_loom) {
    showLoomMode();
    return true;
}
```

Add `showLoomMode()` mirroring `showAgentServerMode()` with tag `"loom"` and fragment `new LoomFragment()`.

Update `showHomeMode()`, `showApiKeyMode()`, `showAgentServerMode()`, `showAgentTaskDetailMode()`, and `showTerminalMode()` to hide the `"loom"` fragment when showing other modes.

- [ ] **Step 5: Compile and commit**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: Java and resource compilation succeeds.

Commit:

```powershell
git add app/src/main/res/layout/fragment_loom.xml `
        app/src/main/java/com/termux/app/LoomFragment.java `
        app/src/main/res/menu/bottom_nav_menu.xml `
        app/src/main/java/com/termux/app/TermuxActivity.java
git commit -m "feat(loom): add Loom navigation shell"
```

---

## Task 7: Implement LoomFragment Actions

**Files:**
- Modify: `app/src/main/java/com/termux/app/LoomFragment.java`
- Modify: `app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java`

- [ ] **Step 1: Add script runner fields and lifecycle cleanup**

In `LoomFragment`, add:

```java
private Thread mActiveThread;
private boolean mMonitoring;
```

Add:

```java
@Override
public void onDestroyView() {
    super.onDestroyView();
    cancelActiveThread();
}
```

Add `cancelActiveThread`, `appendLog`, `setStatus`, `setInfo`, `post`, and `runScript` by adapting the same methods from `AgentServerFragment`. Use thread name `"loom-cmd"` and log label text `"执行日志"`.

- [ ] **Step 2: Build current settings**

Add:

```java
private LoomSettings currentSettings() {
    return LoomSettings.defaults()
        .withObserverUrl(mObserverUrl.getText().toString().trim())
        .withWorkspaceId(mWorkspaceId.getText().toString().trim())
        .withWorkspaceApiKey(mWorkspaceApiKey.getText().toString().trim())
        .withAgentServerUrl(mAgentServerUrl.getText().toString().trim())
        .withDriverName(mDriverName.getText().toString().trim())
        .withSlaveName(mSlaveName.getText().toString().trim())
        .withTags(mTags.getText().toString().trim());
}
```

- [ ] **Step 3: Wire buttons**

In `onCreateView`, add listeners:

```java
v.findViewById(R.id.btn_loom_status).setOnClickListener(b ->
    runScript(LoomCommandBuilder.statusScript(prefix()), "Loom 状态", null));
v.findViewById(R.id.btn_loom_setup).setOnClickListener(b ->
    runScript(LoomCommandBuilder.setupConfigScript(currentSettings()), "生成 Loom 配置", null));
v.findViewById(R.id.btn_loom_start_observer).setOnClickListener(b ->
    runScript(LoomCommandBuilder.startObserverScript(prefix()), "启动 Observer", null));
v.findViewById(R.id.btn_loom_stop_observer).setOnClickListener(b ->
    runScript(LoomCommandBuilder.stopObserverScript(), "停止 Observer", null));
v.findViewById(R.id.btn_loom_register_driver).setOnClickListener(b ->
    runScript(LoomCommandBuilder.registerDriverScript(), "注册 Driver", this::maybeShowAuthUrl));
v.findViewById(R.id.btn_loom_start_slave).setOnClickListener(b ->
    runScript(LoomCommandBuilder.startSlaveScript(prefix()), "启动 Slave", this::maybeShowAuthUrl));
v.findViewById(R.id.btn_loom_stop_slave).setOnClickListener(b ->
    runScript(LoomCommandBuilder.stopSlaveScript(), "停止 Slave", null));
v.findViewById(R.id.btn_loom_monitor).setOnClickListener(b -> monitorLogs());
```

Add:

```java
private String prefix() {
    String p = System.getenv("PREFIX");
    return (p == null || p.isEmpty()) ? "/data/data/com.termux/files/usr" : p;
}
```

- [ ] **Step 4: Add config setup command**

Extend `LoomCommandBuilder` with:

```java
public static String setupConfigScript(LoomSettings settings) {
    String observer = b64(LoomConfigRenderer.renderObserverConfig(settings, OBSERVER_HOME));
    String driver = b64(LoomConfigRenderer.renderDriverConfig(settings, DRIVER_PROJECT, DRIVER_HOME));
    String slave = b64(LoomConfigRenderer.renderSlaveConfig(settings, SLAVE_HOME, 1, "aarch64", 1));
    return pd("mkdir -p " + OBSERVER_HOME + " " + DRIVER_HOME + " " + SLAVE_HOME + " "
        + DRIVER_PROJECT + "/logs " + DRIVER_PROJECT + "/.claude/skills; "
        + "printf '%s' '" + observer + "' | base64 -d > " + OBSERVER_HOME + "/observer.yaml; "
        + "printf '%s' '" + driver + "' | base64 -d > " + DRIVER_PROJECT + "/config.yaml; "
        + "printf '%s' '" + slave + "' | base64 -d > " + SLAVE_HOME + "/config.yaml; "
        + "cp /usr/local/bin/driver-agent " + DRIVER_PROJECT + "/driver-agent; "
        + "chmod 600 " + OBSERVER_HOME + "/observer.yaml " + DRIVER_PROJECT + "/config.yaml "
        + SLAVE_HOME + "/config.yaml; "
        + "chown -R claude:claude /home/claude/.loom " + DRIVER_PROJECT + "; "
        + "echo '[*] Loom configs written'");
}

private static String b64(String s) {
    return android.util.Base64.encodeToString(
        s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        android.util.Base64.NO_WRAP);
}
```

- [ ] **Step 5: Add log monitor**

Add `monitorLogs()` in `LoomFragment`:

```java
private void monitorLogs() {
    String p = prefix();
    String script =
        "echo '== Loom logs =='\n"
        + "tail -n 60 '" + p + "/../home/loom-observer.log' 2>/dev/null || true\n"
        + "tail -n 60 '" + p + "/../home/loom-slave.log' 2>/dev/null || true\n"
        + "tail -n 60 '" + p + "/../home/loom-driver-register.log' 2>/dev/null || true\n"
        + "tail -f -n 0 '" + p + "/../home/loom-observer.log' '"
        + p + "/../home/loom-slave.log' '"
        + p + "/../home/loom-driver-register.log' 2>/dev/null\n";
    runScript(script, "实时监控", null);
}
```

- [ ] **Step 6: Add auth URL detection**

Copy the `AUTH_URL_PATTERN` and QR dialog approach from `AgentServerFragment`, but keep it local to `LoomFragment`. Method name:

```java
private void maybeShowAuthUrl(String line)
```

Trigger when a line contains `http` and either `register` or `authenticate` or `device`.

- [ ] **Step 7: Run tests and compile**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.LoomCommandBuilderTest
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: tests and compile pass.

- [ ] **Step 8: Commit**

Commit:

```powershell
git add app/src/main/java/com/termux/app/LoomFragment.java `
        app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java
git commit -m "feat(loom): implement role actions"
```

---

## Task 8: Add All-In-One Flow

**Files:**
- Modify: `app/src/main/java/com/termux/app/LoomFragment.java`
- Modify: `app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java`

- [ ] **Step 1: Add command builder method**

Add:

```java
public static String startAllInOneScript(String prefix, LoomSettings settings) {
    return setupConfigScript(settings)
        + "\n"
        + startObserverScript(prefix)
        + "\n"
        + startSlaveScript(prefix)
        + "\n"
        + "echo '[*] Driver project is ready at " + DRIVER_PROJECT + "'\n"
        + "echo '[*] Run driver registration from Loom page if config.yaml has empty credentials.'\n";
}
```

- [ ] **Step 2: Add a button or reuse setup button based on role**

If the selected role is `All-in-one`, make `btn_loom_setup` run:

```java
runScript(LoomCommandBuilder.startAllInOneScript(prefix(), currentSettings()),
    "启动 All-in-one", this::maybeShowAuthUrl);
```

For other roles, keep `btn_loom_setup` as config generation.

- [ ] **Step 3: Add focused test**

Extend `LoomCommandBuilderTest`:

```java
@Test
public void allInOneStartsObserverAndSlave() {
    String script = LoomCommandBuilder.startAllInOneScript(
        "/data/data/com.termux/files/usr", LoomSettings.defaults());

    Assert.assertTrue(script.contains("observer-server --config observer.yaml"));
    Assert.assertTrue(script.contains("slave-agent config.yaml"));
    Assert.assertTrue(script.contains("Driver project is ready"));
}
```

- [ ] **Step 4: Run tests and commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.LoomCommandBuilderTest
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected: tests and compile pass.

Commit:

```powershell
git add app/src/main/java/com/termux/app/LoomFragment.java `
        app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java `
        app/src/test/java/com/termux/app/loom/LoomCommandBuilderTest.java
git commit -m "feat(loom): add all-in-one startup"
```

---

## Task 9: Full Build And Local Verification

**Files:**
- No source changes unless verification exposes compile/resource issues.

- [ ] **Step 1: Run unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.*
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.autotasks.AutoLoomManagerScriptTest
```

Expected: all targeted tests pass.

- [ ] **Step 2: Run full debug build**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: build succeeds and emits the debug APK.

- [ ] **Step 3: Inspect APK assets**

Run:

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = Get-ChildItem -Path .\app\build\outputs\apk -Recurse -Filter "*debug*universal*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
[IO.Compression.ZipFile]::OpenRead($apk.FullName).Entries | Where-Object { $_.FullName -like "*loom-linux-arm64.tgz*" } | Select-Object FullName,Length
```

Expected: one `assets/loom-linux-arm64.tgz` entry with non-zero length.

- [ ] **Step 4: Commit verification fixes if any**

If source changes were required:

```powershell
git add <changed-files>
git commit -m "fix(loom): address build verification issues"
```

If no source changes were required, do not create a commit.

---

## Task 10: Device Verification

**Files:**
- No source changes unless device testing exposes implementation defects.

- [ ] **Step 1: Install APK**

Run:

```powershell
.\gradlew.bat :app:installDebug
```

Expected: install succeeds on the connected ARM64 device.

- [ ] **Step 2: Launch and check navigation**

Run:

```powershell
adb shell monkey -p com.termux 1
```

Expected:

- App opens.
- Bottom navigation shows `Loom` as fifth item.
- Tapping `Loom` opens LoomFragment.

- [ ] **Step 3: Verify offline addon installation**

In the app:

- Open Terminal once so Ubuntu setup runs.
- Wait for setup to finish.
- Open Loom page.
- Tap status.

Expected log:

- `observer-server` found.
- `driver-agent` found.
- `slave-agent` found.

- [ ] **Step 4: Verify all-in-one**

In Loom page:

- Set role to `All-in-one`.
- Set observer URL to `http://127.0.0.1:8090`.
- Set workspace id to `ws-phone`.
- Set workspace API key to a non-empty local test value.
- Tap setup/start all-in-one.
- Tap status.

Expected:

- Observer process is running.
- Slave process starts or prints a device-code registration URL.
- Driver project exists.

- [ ] **Step 5: Verify isolation**

In the app:

- Open Agent page and use existing AgentServer status.
- Open Home page and send a small Claude message.
- Open Loom page and stop slave/observer.

Expected:

- AgentServer page still works.
- HomeFragment still receives Claude responses.
- Stopping Loom does not kill AgentServer.
- `.agentserver-pipe.jsonl` does not receive Loom logs.

- [ ] **Step 6: Commit device fixes if any**

If source changes were required:

```powershell
git add <changed-files>
git commit -m "fix(loom): address device verification issues"
```

If no source changes were required, do not create a commit.

---

## Self-Review Checklist

Spec coverage:

- Package split is covered by Task 3 and Task 4.
- `AutoLoomManager` is covered by Task 4 and Task 5.
- Fifth bottom navigation item is covered by Task 6.
- `LoomFragment` is covered by Task 6 through Task 8.
- Observer, driver, slave roles are covered by Task 7 and Task 8.
- Offline-first and online fallback are covered by Task 3 and Task 4.
- AgentServer/Home isolation is covered by Task 10.

Type consistency:

- `LoomSettings`, `LoomConfigRenderer`, and `LoomCommandBuilder` live in `com.termux.app.loom`.
- `AutoLoomManager` lives in `com.termux.app.autotasks`.
- `LoomFragment` lives in `com.termux.app`.
- `LoomCommandBuilder` constants are used by fragment commands and tests.

Verification commands:

- Unit tests: `.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.loom.*`
- Installer script test: `.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.autotasks.AutoLoomManagerScriptTest`
- Build: `.\gradlew.bat :app:assembleDebug`
- Device install: `.\gradlew.bat :app:installDebug`
