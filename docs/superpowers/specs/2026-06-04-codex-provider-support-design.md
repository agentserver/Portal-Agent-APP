# Codex Provider Support Design

## Goal

Add Codex support to the Android app while preserving the existing Claude behavior as the default. Users can switch between Claude and Codex from the Home title, manage separate API keys, and have AgentServer and Loom use the selected provider after configuration regeneration or restart.

## Context

The current app is centered on Claude Code:

- `HomeFragment` uses `ClaudeStreamSession` to run `claude --input-format stream-json --output-format stream-json` as Ubuntu user `claude`.
- `ApiKeyStore` stores one Claude-oriented key list and writes `ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL`.
- `AutoClaudeManager` installs Claude Code and registers Android MCP for the `claude` user.
- `AutoUbuntuManager` injects `/home/claude/CLAUDE.md`, `.claude/settings.json`, `/phone`, and a `/home/claude/.local/bin/claude` wrapper for AgentServer task capture.
- `AgentServerFragment` starts `agentserver claudecode` and injects Anthropic environment variables.
- `LoomConfigRenderer` writes `agent.kind: claude` and `claude:` sections in driver/slave YAML.
- The Loom addon already packages Codex prompt material under `prompts-codex`, but the Android renderer does not yet expose Codex as a backend.

The implementation should avoid splitting the Ubuntu rootfs. Ubuntu, Node, AgentServer, and Loom binaries remain shared; provider-specific state is separated by Linux user and app storage.

## Chosen Architecture

Use a single Ubuntu environment with two provider users:

| Provider | Ubuntu user | Home | CLI | Key env |
|---|---|---|---|---|
| Claude | `claude` | `/home/claude` | `claude` | `ANTHROPIC_API_KEY` |
| Codex | `codex` | `/home/codex` | `codex` | `OPENAI_API_KEY` |

Add an app-level provider setting:

```text
assistant_provider
  selected_provider = claude | codex
```

Claude remains the default for backwards compatibility. Existing Claude keys, sessions, uploads, memory, skills, AgentServer config, and Loom config continue to work when the selected provider stays Claude.

## Provider Model

Create a focused provider model, not scattered string checks:

- `AssistantProvider`: enum values `CLAUDE`, `CODEX`.
- `ProviderSettingsStore`: persists selected provider and exposes change helpers.
- `ProviderProfile`: immutable metadata for each provider:
  - display name
  - Ubuntu username
  - home directory
  - CLI binary name
  - key environment variable
  - optional base URL environment variable
  - memory directory
  - commands/prompts directory
  - session/history handling mode

This lets Home, API keys, AgentServer, Loom, and installation code read from one provider definition.

## Home Behavior

The top-left `Claude Code` title becomes the provider selector.

- Tapping the title opens a small selection dialog: `Claude Code` and `Codex`.
- The title shows the provider name when no chat preview is active.
- If the user switches while a response is running, show a confirmation dialog. Confirming stops the current provider process and switches.
- Switching provider resets only the visible active conversation state for Home. It does not delete any provider history.
- The "打断" and "新建对话" buttons route to the active provider session.

Claude route:

- Keep `ClaudeStreamSession` as the Claude implementation.
- Continue to read Claude history from `/home/claude/.claude/projects/-home-claude`.
- Continue to use existing memory and slash command directories under `/home/claude/.claude`.

Codex route:

- Add a new Codex session runner. First implementation should use non-interactive Codex CLI execution rather than an interactive terminal PTY.
- Run as Ubuntu user `codex`.
- Use `/home/codex` as the working directory.
- Pass `OPENAI_API_KEY` through the process environment and shell environment.
- Store app-side Codex chat metadata separately from Claude metadata because Codex history format and resume behavior differ from Claude's JSONL.
- If Codex CLI is missing, Home shows a system message telling the user to enter Ubuntu and finish Codex setup.

The Home UI should not try to merge Claude and Codex history in the first implementation. Provider-specific history avoids accidental data corruption and keeps the initial rollout testable.

## API Key Management

Add a provider selector at the top of `ApiKeyFragment`:

```text
[ Claude ] [ Codex ]
```

Behavior:

- The list, count, add dialog, active key badge, delete action, and "设为当前" action all apply to the selected provider.
- Claude keeps the current storage keys and migration-free behavior.
- Codex uses separate storage, for example `codex_api_keys`.
- The item UI can stay the same. The add dialog text changes per provider:
  - Claude: Base URL hint describes Anthropic-compatible endpoints.
  - Codex: Base URL is optional and should be labeled as OpenAI-compatible only if the CLI runner supports it. Otherwise save it but do not inject it until verified.

When activating a key:

- Claude writes `ANTHROPIC_API_KEY` and optional `ANTHROPIC_BASE_URL` to `/home/claude/.bashrc`.
- Codex writes `OPENAI_API_KEY` to `/home/codex/.bashrc`.
- Both should remove stale lines for the same provider before appending new values.
- Activating a key does not automatically switch the global provider.

## Ubuntu Setup

Add `AutoCodexManager`, mirroring the shape of `AutoClaudeManager` but keeping provider-specific files under `/home/codex`.

Responsibilities:

- Write a `.codex-setup.sh` inner script into app private files.
- `AutoUbuntuManager` injects that script into Ubuntu rootfs and adds a `.bashrc` hook.
- Ensure Linux user `codex` exists.
- Ensure Node.js, npm, and curl are available, reusing the existing install path where possible.
- Install Codex CLI with npm.
- Write `/home/codex/AGENTS.md` with the Android MCP capability instructions adapted from the existing `CLAUDE.md`.
- Prepare `/home/codex/.codex/` if the Codex CLI expects it.
- Register or document Android MCP for Codex depending on Codex CLI MCP support in the installed version.
- Self-remove the setup hook after successful install.

The existing `claude` user remains the default login user for terminal sessions unless a later UI change adds a terminal provider switch. This feature is scoped to Home/API Key/AgentServer/Loom provider execution.

## Android Capability Prompts

The Claude prompt currently lives in `CLAUDE.md`; Codex should use `AGENTS.md`.

Shared content should be extracted conceptually into a reusable builder:

- Android runtime description
- MCP tool list
- screenshot permission rule
- file/app/system status guidance
- recommended operation loop

Provider-specific filenames and wording:

- Claude writes `/home/claude/CLAUDE.md` and `/home/claude/.claude/commands/phone.md`.
- Codex writes `/home/codex/AGENTS.md`.
- Memory rules remain Claude-only in the first implementation unless Codex memory storage is explicitly added later.

## AgentServer Adaptation

`AgentServerFragment` becomes provider-aware when starting an agent.

Claude:

- Keep current `agentserver claudecode` command.
- Keep current wrapper capture through `/home/claude/.local/bin/claude`.
- Continue to inject Anthropic key variables.

Codex:

- Start from selected provider metadata.
- Inject `OPENAI_API_KEY`.
- Use `/home/codex` and place provider-specific logs under Termux home with clear names, for example `agentserver-codex-agent.log`.
- Before launching, detect whether the installed AgentServer binary supports a Codex subcommand. The UI should fail clearly if it does not.
- The exact Codex subcommand must be verified from the installed AgentServer version during implementation. Do not hard-code an unverified command into production behavior without a capability check.

Provider switching:

- If AgentServer is running and the user switches providers in Home, show a message that AgentServer must be restarted for the new provider.
- A future enhancement can auto-restart AgentServer, but the first implementation should keep restart explicit to avoid killing a remote task unexpectedly.

Task capture:

- Claude task capture stays on the current `.agentserver-pipe.jsonl` wrapper.
- Codex task capture is out of scope for the first implementation unless AgentServer exposes comparable JSON events. Codex AgentServer logs should still be visible from the AgentServer page.

## Loom Adaptation

`LoomSettings` gains provider/backend information:

```text
agent_provider = claude | codex
```

By default, Loom follows the global provider at setup time. The UI should show the selected provider in the Loom page status/info text so the user knows which backend will be rendered.

Claude YAML:

```yaml
agent:
  kind: claude
claude:
  bin: claude
  workdir: /home/claude/...
  extra_args: []
```

Codex YAML:

```yaml
agent:
  kind: codex
codex:
  bin: codex
  workdir: /home/codex/...
  extra_args: []
```

The exact Codex YAML schema must be verified against the packaged Loom version. If the installed Loom binary does not support `agent.kind: codex`, setup should fail with a clear message rather than silently writing an invalid config.

Runtime layout for Codex Loom:

```text
/home/codex/.loom/observer-local/
/home/codex/.loom/slave-local/
/home/codex/.loom/driver-local/
/home/codex/loom-driver/
/home/codex/loom-driver/AGENTS.md
```

The observer role is provider-neutral, but using provider-specific paths keeps all generated configs and tokens easy to reason about. Starting/stopping observer and slave should use provider-specific process patterns so Claude and Codex configurations do not accidentally kill each other.

When the provider changes:

- Loom must regenerate config before starting roles.
- If roles are already running, the Loom page should ask the user to stop and restart roles.
- All-in-one should run setup using the current provider before starting observer/slave.

## Packaging

Do not split Ubuntu rootfs for this feature.

Keep the existing package strategy:

- Ubuntu snapshot / rootfs remains the shared base.
- AgentServer stays an addon asset.
- Loom stays an addon asset.
- Codex CLI is installed into the shared Ubuntu environment by `AutoCodexManager`.

If a future release wants full offline Codex installation, add a separate Codex addon or npm cache asset rather than embedding it into the Ubuntu base. This preserves deduplication and keeps Claude, AgentServer, Loom, and Codex update paths independent.

## Error Handling

Home:

- Missing active key: show provider-specific key warning.
- Missing CLI: show provider-specific setup warning.
- Unsupported Codex stream format: display raw summarized output and keep the session idle.
- Process exits: same pattern as Claude, but provider-specific wording.

API Key:

- Empty key is rejected.
- Provider switch in the key page does not discard unsaved dialog state because the add dialog is modal.

AgentServer:

- Missing provider key prevents launch.
- Unsupported Codex backend displays an actionable message: update AgentServer addon or switch to Claude.
- Existing running AgentServer is not auto-killed on global provider switch.

Loom:

- Unsupported Codex backend displays an actionable message: update Loom addon or switch to Claude.
- Config generation should be idempotent and overwrite only the selected provider's Loom directories.

## Testing

Unit tests:

- `ProviderProfile` returns correct user, home, CLI, and key variables.
- `ProviderSettingsStore` defaults to Claude and persists Codex.
- `ApiKeyStore` or replacement provider-aware key store keeps Claude and Codex entries isolated.
- `LoomConfigRenderer` renders Claude YAML as before.
- `LoomConfigRenderer` renders Codex YAML with `kind: codex`, Codex workdirs, and no accidental `claude:` section.
- AgentServer command builder renders Claude command unchanged.
- AgentServer command builder injects `OPENAI_API_KEY` for Codex and performs support checks.
- `AutoCodexManager` setup script creates `codex` user, installs Codex CLI, writes `OPENAI_API_KEY`, and writes `AGENTS.md`.

Build checks:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.*Provider* --tests com.termux.app.loom.*
.\gradlew.bat :app:testDebugUnitTest --tests com.termux.app.autotasks.*Codex*
.\gradlew.bat :app:assembleDebug --no-daemon
```

Device checks:

- Launch app with existing Claude setup; Claude Home still sends messages.
- Add and activate Codex key; switch Home title to Codex; Codex command runs as user `codex`.
- Switch back to Claude; Claude session flow still works.
- AgentServer Claude flow still starts with `agentserver claudecode`.
- AgentServer Codex flow either starts with a verified Codex backend or shows unsupported backend clearly.
- Loom Claude all-in-one remains working.
- Loom Codex setup writes Codex paths and either starts successfully or reports unsupported backend clearly.

## Non-Goals

- Do not migrate existing Claude sessions into Codex in this feature.
- Do not split Ubuntu into separate Claude and Codex rootfs packages.
- Do not make terminal sessions switch login users.
- Do not implement Codex AgentServer task capture unless the upstream AgentServer exposes reliable JSON events.
- Do not change the existing Claude wrapper behavior except where shared provider abstractions require small call-site updates.

## Open Verification Items

These are implementation-time checks, not design blockers:

- Confirm the exact Codex CLI non-interactive command and JSON event schema on the installed version.
- Confirm whether Codex CLI MCP registration is supported through config or CLI commands.
- Confirm whether the packaged AgentServer supports a Codex backend subcommand and its exact name.
- Confirm whether the packaged Loom version accepts `agent.kind: codex` and the exact `codex:` YAML shape.

If any of those are unsupported in the current packaged binaries, the Android UI should degrade clearly and keep Claude behavior intact.
