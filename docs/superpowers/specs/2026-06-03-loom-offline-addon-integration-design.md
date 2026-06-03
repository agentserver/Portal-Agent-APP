# Loom Offline Addon Integration Design

Date: 2026-06-03
Project: `Claude_code_test_app`
Source repo: `https://github.com/agentserver/loom`

## Goal

Add Loom to the Android app as a first-class control surface, similar to the current AgentServer page, while splitting release packages so future AgentServer or Loom updates do not require rebuilding the full Ubuntu snapshot.

The app will keep one shared Ubuntu proot runtime, but package mutable components as independent addons:

- Ubuntu base snapshot: stable base environment.
- AgentServer addon: AgentServer binary and setup script.
- Loom addon: Loom binaries, templates, skills, setup script, and role configs.

This gives the maintenance benefit of separated updates without duplicating the large Ubuntu rootfs in the APK.

## Current Context

The app currently contains:

- `ubuntu-snapshot/ubuntu-claude-aarch64-20260512.tar.xz` as the large shared Ubuntu/Claude base.
- `agentserver-linux-arm64.tgz` as an AgentServer asset, extracted by `AutoAgentServerManager`.
- `AutoUbuntuManager`, which installs proot/Ubuntu, injects Claude configuration, the Android MCP configuration, and AgentServer setup hooks.
- `AgentServerFragment`, which manages AgentServer status, connect/disconnect, OAuth QR handling, and logs.

Loom is not a single AgentServer replacement binary. It is a multi-agent system with these core roles:

- `observer-server`: HTTP telemetry/control plane backed by SQLite.
- `driver-agent`: local driver and MCP server launched by Claude Code or Codex.
- `slave-agent`: worker node that executes chat/bash/file/register_mcp work.
- `mcp-userspace`: optional helper CLI for publishing/installing generated capabilities.

The latest observed Loom release is `v0.0.2`, with these relevant assets:

- `observer-server.linux-arm64`
- `driver-agent.linux-arm64`
- `slave-agent.linux-arm64`
- `bootstrap-observer.sh`
- `bootstrap-driver.sh`
- `bootstrap-slave.sh`
- `driver-skills.tar.gz`
- `driver-codex-prompts.tar.gz`
- `sha256sums.txt`

The observed release does not currently include a separate `mcp-userspace.linux-arm64` asset.

## Design Decision: Split Packages, Share Runtime

Do split:

- Release package ownership.
- APK assets.
- Version checks.
- Update paths.
- Setup scripts.
- Logs and config directories.

Do not split:

- Ubuntu rootfs.
- Claude CLI installation.
- Android MCP registration.
- API key storage.
- The base proot environment.

Reasoning:

- Full rootfs duplication would dominate APK size and installation time.
- Loom and AgentServer both benefit from the same Ubuntu + Claude + Android MCP base.
- The problem seen in earlier AgentServer updates was caused by coupling mutable binaries to the environment package, not by sharing one runtime.
- Independent addon packages allow fast updates and safer rollback while avoiding rootfs duplication.

## Package Layout

APK assets:

```text
assets/
  ubuntu-snapshot/
    ubuntu-claude-aarch64-YYYYMMDD.tar.xz
  agentserver-linux-arm64.tgz
  loom-linux-arm64.tgz
```

`loom-linux-arm64.tgz` should contain:

```text
loom/
  manifest.json
  bin/
    observer-server
    driver-agent
    slave-agent
    mcp-userspace        # optional; include if built locally or release asset exists
  deploy/
    observer/
      config.yaml.template
    driver/
      config.yaml.template
      .mcp.json.template
      codex-mcp.toml.template
    slave/
      config.yaml.template
  skills/
    multiagent/
      SKILL.md
      references/...
  prompts-codex/
    AGENTS.md            # if available
```

`manifest.json`:

```json
{
  "name": "loom",
  "version": "v0.0.2",
  "source": "https://github.com/agentserver/loom",
  "arch": "linux-arm64",
  "required": [
    "bin/observer-server",
    "bin/driver-agent",
    "bin/slave-agent"
  ],
  "optional": [
    "bin/mcp-userspace"
  ]
}
```

The APK may either package this tgz directly or generate it from release assets during release preparation. The Android app should not build Loom from source on-device.

## Runtime Layout

Shared base:

```text
/usr/local/bin/claude
/home/claude/.claude/
/home/claude/CLAUDE.md
/home/claude/.local/bin/claude        # wrapper for AgentServer task capture
```

AgentServer:

```text
/usr/local/bin/agentserver
/home/claude/.agentserver-pipe.jsonl
Termux home/agentserver-agent.log
```

Loom:

```text
/usr/local/bin/observer-server
/usr/local/bin/driver-agent
/usr/local/bin/slave-agent
/usr/local/bin/mcp-userspace          # if available

/home/claude/.loom/observer-local/
  observer.yaml
  observer.db
  observer.log

/home/claude/.loom/slave-local/
  config.yaml
  observer.token
  slave.log
  data.db
  journal/
  dynamic_mcp.yaml

/home/claude/loom-driver/
  driver-agent
  config.yaml
  .mcp.json
  .codex/config.toml                  # only if Codex role mode is enabled later
  .claude/skills/multiagent/
  logs/
```

Termux-side logs for Android UI tailing:

```text
$PREFIX/../home/loom-observer.log
$PREFIX/../home/loom-driver-register.log
$PREFIX/../home/loom-slave.log
$PREFIX/../home/loom-install.log
```

## New Android Components

### `AutoLoomManager`

New class under:

```text
app/src/main/java/com/termux/app/autotasks/AutoLoomManager.java
```

Responsibilities:

- Extract `assets/loom-linux-arm64.tgz` to Termux filesDir.
- Write a Loom inner setup script to Termux filesDir.
- Expose paths to `AutoUbuntuManager` for injection.
- Detect asset size/hash changes and update installed binaries.
- Use `.new + mv -f` for binary replacement, matching the AgentServer fix for Android/proot ELF replacement.
- Fall back to online download when the local addon package is missing or corrupt.

It should mirror the structure of `AutoAgentServerManager`, but keep all Loom-specific paths and scripts separate.

### `AutoUbuntuManager` Changes

`AutoUbuntuManager` will receive a Loom manager reference, similar to the existing AgentServer manager reference:

```java
setLoomManager(AutoLoomManager mgr)
```

During Ubuntu setup, after Claude and AgentServer setup injection, it copies:

- `loom-linux-arm64.tgz` to `/tmp/loom-linux-arm64.tgz`
- `.loom-setup.sh` to `/root/.loom-setup.sh`

It appends a guarded `.bashrc` hook:

```bash
[ -f ~/.loom-setup.sh ] && . ~/.loom-setup.sh
```

The Loom setup script must be idempotent. If binaries already match the installed manifest version and `/tmp/loom-linux-arm64.tgz` is absent, it removes its hook and exits.

### `AutoTaskCoordinator`

Instantiate managers in this order:

1. `AutoAgentServerManager`
2. `AutoLoomManager`
3. `AutoUbuntuManager`

Then inject both addon managers into `AutoUbuntuManager`.

### `LoomFragment`

New Fragment:

```text
app/src/main/java/com/termux/app/LoomFragment.java
app/src/main/res/layout/fragment_loom.xml
```

Bottom navigation gains a fifth item:

```text
主页 | 终端 | 密钥 | Agent | Loom
```

`LoomFragment` should follow the same visual pattern as `AgentServerFragment`:

- Header title and status badge.
- Config fields.
- Action buttons.
- Monospace execution log at the bottom.

Role selector:

- `All-in-one`
- `Observer`
- `Driver`
- `Slave`

Default: `All-in-one`.

The selector controls which grouped actions are shown and which start action runs. Internally, roles still have separate status and process management.

## LoomFragment Fields

Shared fields:

- Observer URL
- Workspace ID
- Workspace API key
- AgentServer URL
- Device/agent name prefix

Observer fields:

- Listen address, default `127.0.0.1:8090`
- Instance name, default `observer-phone`

Driver fields:

- Driver name, default `driver-phone`
- Driver project path, default `/home/claude/loom-driver`
- Backend, default `claude`

Slave fields:

- Slave name, default `slave-phone`
- Backend, default `claude`
- Tags, default `android,phone,aarch64`

Prefs should be stored in `SharedPreferences` under a new name:

```text
loom_config
```

Do not reuse `agentserver_config`.

## Process Lifecycle

Observer:

- Start command runs inside Ubuntu as `claude` user:

```bash
cd /home/claude/.loom/observer-local
nohup observer-server --config observer.yaml >> "$TERMUX_HOME/loom-observer.log" 2>&1 &
```

- Stop command kills only observer processes:

```bash
pkill -f 'observer-server --config .*observer-local/observer.yaml'
```

Slave:

- Start command:

```bash
cd /home/claude/.loom/slave-local
nohup slave-agent config.yaml >> "$TERMUX_HOME/loom-slave.log" 2>&1 &
```

- Stop command kills only the configured slave instance:

```bash
pkill -f 'slave-agent .*\\.loom/slave-local/config.yaml'
```

Driver:

- Driver is not a long-running daemon.
- `LoomFragment` should provide:
  - Setup driver project.
  - Register driver.
  - Test `driver-agent serve-mcp --config ...` in a foreground smoke mode.
- If HomeFragment integration is enabled later, `ClaudeStreamSession` will need to run Claude in a workdir where the driver MCP config is visible. This spec keeps that integration as a later subtask, not a prerequisite for Loom runtime management.

All-in-one:

- Ensure observer config exists.
- Start observer.
- Ensure driver project exists.
- Ensure slave config exists.
- Start slave.
- Print exact driver registration and usage hints in the log.

## Registration and OAuth Handling

Both driver and slave use AgentServer device-code registration.

The existing `AgentServerFragment` QR dialog pattern should be reused:

- Parse verification URLs from process output.
- Show QR code dialog.
- Offer copy/open-browser actions.
- Dismiss dialog when registration succeeds.

Driver registration:

```bash
/home/claude/loom-driver/driver-agent register --config /home/claude/loom-driver/config.yaml
```

Slave registration is triggered by first slave start when config credentials are empty. The first-run URL appears in slave stderr/log.

Observer registration uses workspace API key, not device-code OAuth.

## Online Fallback

Local addon install is preferred:

```text
/tmp/loom-linux-arm64.tgz
```

If unavailable or corrupt, setup attempts release download:

```text
https://github.com/agentserver/loom/releases/latest/download/observer-server.linux-arm64
https://github.com/agentserver/loom/releases/latest/download/driver-agent.linux-arm64
https://github.com/agentserver/loom/releases/latest/download/slave-agent.linux-arm64
https://github.com/agentserver/loom/releases/latest/download/driver-skills.tar.gz
https://github.com/agentserver/loom/releases/latest/download/driver-codex-prompts.tar.gz
https://github.com/agentserver/loom/releases/latest/download/sha256sums.txt
```

Download rules:

- Download to `.part`.
- Verify non-empty file.
- If `sha256sums.txt` is available, verify checksums.
- Move into place only after validation.
- If a binary is already installed and update download fails, keep the installed binary.

`mcp-userspace` fallback:

- If the local addon package contains `mcp-userspace`, install it.
- If the local addon does not contain it and latest release does not publish it, log a clear optional-skip message.
- Do not fail the whole Loom setup because `mcp-userspace` is absent.

## Interaction With Existing AgentServer

AgentServer and Loom remain separate:

- Separate bottom navigation items.
- Separate Fragments.
- Separate prefs.
- Separate logs.
- Separate setup scripts.
- Separate addon packages.

They share:

- Ubuntu proot.
- Claude CLI.
- API keys loaded from `ApiKeyStore`.
- Android MCP configuration.

They must not share:

- `.agentserver-pipe.jsonl`
- task stores
- runtime process kill patterns
- status badges

AgentServer's Claude wrapper should continue to protect HomeFragment with `CLAUDE_DIRECT=1`. Loom should not write HomeFragment output into AgentServer task pipe.

## HomeFragment Integration

Initial Loom integration should not rewrite HomeFragment.

HomeFragment stays focused on local Claude chat through `ClaudeStreamSession`.

The only allowed first-pass interaction is documentation/config hints showing how to launch Claude in the driver project if the user wants to use Loom driver MCP manually:

```bash
cd /home/claude/loom-driver
claude
```

Automatic driver MCP injection into HomeFragment is deferred until Loom runtime management is stable.

## Error Handling

Loom setup must report these cases clearly in `LoomFragment` logs:

- Ubuntu/proot not initialized.
- Loom addon asset missing.
- Local addon extraction failed.
- Release download failed.
- Checksum mismatch.
- Missing required binary.
- Observer already running.
- Slave already running.
- Device-code registration pending.
- Device-code registration failed.
- Observer API key missing.
- Observer URL unreachable.

Do not delete existing working configs when setup fails. Failed updates should preserve installed binaries and config files.

## Security and Secrets

- Workspace API keys are stored in `SharedPreferences`, same practical trust model as current AgentServer fields.
- Rendered Loom `config.yaml` files are chmod `0600` inside Ubuntu.
- Observer tokens are stored under `.loom/*/observer.token`.
- The UI should not print full API keys after saving. Logs may show whether a key is present, not the key value.
- Device-code URLs may be displayed because user approval is required.

## Testing

Build verification:

- `./gradlew :app:assembleDebug`

Installation verification:

- Fresh install with local `loom-linux-arm64.tgz`.
- Fresh install with missing local addon and working network fallback.
- Update install where existing Loom binaries are replaced with `.new + mv -f`.

Role verification:

- Observer start/stop/status.
- Slave start/stop/status.
- Driver project creation.
- Driver registration QR parsing.
- Slave registration QR parsing.
- All-in-one start on one device.

Isolation verification:

- AgentServer connect still works.
- HomeFragment Claude chat still works.
- AgentServer task list still receives only AgentServer tasks.
- Loom logs do not write into `.agentserver-pipe.jsonl`.
- Stop Loom does not kill AgentServer.
- Stop AgentServer does not kill Loom.

## Implementation Scope

In scope:

- Package split design.
- `AutoLoomManager`.
- Loom addon setup script.
- Fifth bottom navigation item.
- `LoomFragment`.
- Role configs and start/stop/status/log actions.
- Offline-first install with online fallback.

Out of scope for the first implementation pass:

- Running separate Ubuntu rootfs instances.
- Building Loom from source on-device.
- Rewriting HomeFragment to automatically use Loom driver MCP.
- Packaging x86/x86_64 Loom assets for Android emulator.
- Full observer web UI embedded in Android WebView.

## Acceptance Criteria

The feature is accepted when:

- The APK contains a separate Loom addon package, not a second Ubuntu snapshot.
- A fresh ARM64 device installs Ubuntu, AgentServer, and Loom without repacking the Ubuntu snapshot for Loom.
- The bottom navigation has a fifth `Loom` item.
- `LoomFragment` can configure role mode and run observer/slave/driver setup actions.
- `All-in-one` can start local observer and slave, and prepare/register driver.
- AgentServer and HomeFragment behavior remain unchanged.
- Updating only `loom-linux-arm64.tgz` can update Loom binaries without changing Ubuntu snapshot or AgentServer package.
