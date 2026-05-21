# Codex History Migration Design

## Goal

Add a `migrate-to-codex` path that turns existing Claude Code conversations into Codex-continuable conversations while preserving the original Claude Code context files unchanged.

The migration must keep these source files immutable:

- `/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu/home/claude/.claude/projects/-home-claude/<session_id>.jsonl`
- Existing `SessionStore` entries backed by the `claude_sessions` shared preference.

## Current State

The app currently treats Claude Code as the only chat agent.

- `SessionStore` stores the history drawer metadata: session id, timestamp, and preview.
- `HomeFragment` resumes Claude conversations with `claude --resume <session_id>`.
- Full replay comes from Claude Code jsonl files in `/home/claude/.claude/projects/-home-claude/`.
- Deleting a Claude history entry removes the matching Claude jsonl to avoid disk leaks.

Codex support is not implemented yet. There is no Codex history store, no Codex session mapping, and no safe way to assume Codex internal history files are stable.

## Design

Migration creates a new Codex-side session from a read-only Claude source session.

The app parses the Claude jsonl, extracts displayable user and assistant turns, filters out oversized or non-portable payloads, and builds a migration prompt for Codex. The first Codex run receives that prompt through the Codex CLI so Codex creates its own native session data. The app then records a mapping from the Claude source id to the Codex session id.

The original Claude jsonl is never modified. If migration fails, the Claude history remains usable through the existing Claude path.

## Session Model

Introduce an agent-aware history layer without breaking the old `SessionStore`.

Claude history remains in `SessionStore` and the Claude jsonl directory.

Codex migration metadata is stored in a new `CodexMigrationStore` backed by a `codex_migrated_sessions` shared preference:

- `source_agent`: `claude`
- `source_session_id`
- `codex_session_id`
- `created_at`
- `updated_at`
- `preview`
- `status`: `migrating`, `ready`, or `failed`
- `error`: empty string when there is no error; otherwise a short user-visible message

The store also writes an app-owned migrated transcript archive under `getFilesDir()/codex-migrations/<source_session_id>.jsonl`. That archive is the app's stable fallback for future Codex continuation and UI replay.

This makes migration idempotent. Running migration twice for the same Claude session opens the existing mapped Codex session or archive. Creating a new migrated copy requires an explicit remigrate action.

## Migration Context

The migration prompt is generated from a normalized transcript, not copied raw jsonl.

Included:

- User text messages.
- Assistant text messages.
- Small, readable tool results if they are necessary to understand the conversation.
- Attachment references as paths when still meaningful.

Excluded or summarized:

- Base64 screenshots.
- Large file contents.
- Raw tool call payloads that would cause Codex to rerun old actions.
- Claude-specific metadata, internal ids, and stream bookkeeping.

For long sessions, the app builds a bounded context:

1. Include a concise migration header explaining that the conversation came from Claude Code.
2. Include a summary of older turns.
3. Include the most recent turns verbatim within a size limit.
4. Tell Codex not to rerun historical tool calls automatically.

## Codex Invocation

Use the Codex CLI as the contract boundary. Do not write Codex internal session files directly.

The initial migrated session is created by running Codex with a generated prompt through non-interactive CLI mode. The app always stores the normalized migrated transcript in `getFilesDir()/codex-migrations/<source_session_id>.jsonl`.

If structured CLI output exposes a stable Codex session id, the app stores it in `CodexMigrationStore` and uses Codex native resume for follow-up messages. If the CLI output does not expose a stable id, follow-up messages use the app-owned migrated transcript archive plus the new user message, bounded by the same size rules. The implementation never blocks migration solely because a Codex session id is unavailable.

## UI Behavior

The History tab shows migrated state clearly:

- `Claude`: original Claude conversation.
- `Claude -> Codex`: migrated conversation that can continue in Codex.
- `Migration failed`: source conversation is still intact; retry is available.

Opening a migrated entry defaults to the Codex version. The user can still view the original Claude transcript.

Deleting a migrated Codex entry deletes only the Codex migration metadata and `getFilesDir()/codex-migrations/<source_session_id>.jsonl`. It must not delete the original Claude jsonl. Deleting the original Claude history continues to use the existing Claude cleanup path, but if a migrated Codex mapping exists the app warns that only the source will be removed.

## Error Handling

Migration fails closed:

- Missing Claude jsonl: mark migration failed and keep the SessionStore entry.
- Parse error: import readable turns up to the first unrecoverable error and include a warning in the migration header.
- Codex CLI unavailable: show install/auth guidance and leave status as failed.
- Codex auth missing: show auth guidance without altering source data.
- Oversized transcript: summarize and truncate deterministically.

## Testing

Unit-level tests cover:

- Claude jsonl parsing for user, assistant, and malformed lines.
- Filtering base64 and oversized payloads.
- Building a bounded migration prompt.
- Idempotent mapping behavior.
- Delete behavior that preserves Claude source files.

Manual verification covers:

- A Claude session migrates to a Codex-marked history row.
- The original Claude history still opens and resumes with Claude.
- The migrated Codex row opens as Codex and can receive a follow-up prompt.
- Failed migration leaves the original Claude history untouched.

## Non-Goals

- Do not replace Claude Code.
- Do not rewrite existing Claude Code jsonl.
- Do not depend on undocumented Codex internal file formats.
- Do not migrate screenshots or large binary payloads as raw content.
- Do not force all existing histories to migrate automatically on app startup.
