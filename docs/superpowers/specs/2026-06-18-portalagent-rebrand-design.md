# PortalAgent Rebrand Design

## Goal

Rename the app-facing product from Claude Code Test App to PortalAgent, isolate its installed Android package from upstream Termux, and keep the Termux runtime bundled as an internal execution layer.

## Package Boundary

The installed Android package will become `com.portalagent`. The Java package namespace and most source paths stay under `com.termux` for this phase, because the app still depends on Termux internals, bootstrap patching, shared utilities, and resource references. Manifest placeholders will carry the new app package into permissions, providers, task affinities, and run-command actions.

`com.portalagent` is intentionally short. Termux bootstrap binaries contain fixed-length `$PREFIX` paths, so a longer package such as `com.portalagent.app` would require rebuilding the whole Termux package set instead of patching the bundled bootstrap.

This means PortalAgent installs separately from the original `com.termux` app. Existing app data under the old package will not be migrated automatically in this change.

## Branding

The visible app name becomes `PortalAgent`. Plugin-style labels are renamed to `PortalAgent:API`, `PortalAgent:Boot`, `PortalAgent:Float`, `PortalAgent:Styling`, `PortalAgent:Tasker`, and `PortalAgent:Widget`.

The PNG originally placed in `app/APP Logo/` is preserved as `art/portalagent_logo_source.png`. It is copied into tracked resources under `app/src/main/res/drawable-nodpi/` and used to generate launcher icons for all mipmap densities plus adaptive icon foreground/background resources.

## Splash

The launch experience should be conservative for this pass: use a static branded splash with PortalAgent iconography and existing blue-white app tones. No custom animation is added until a motion direction is chosen.

## Project Layout Cleanup

Root-level project notes move into `docs/architecture/` or `docs/notes/`. Local test screenshots, local task logs, and local rootfs bundles move into `local_private/artifacts/` so the repository root is reserved for build files, modules, and documentation entry points.

## Repository Rename

Repository rename is separate from code changes. The two GitHub remotes can be renamed after the app rebrand:

- Personal repo: `Zeta112233/Claude_code_Android_app` -> `Zeta112233/PortalAgent`
- AgentServer org repo: `agentserver/Claude_Code_Android_APP` -> `agentserver/PortalAgent`

GitHub keeps redirects, but local remotes should be updated after the rename so future pushes use the canonical URLs.

## Verification

Build verification must include unit tests and debug APK assembly. Device verification should install on Xreal, confirm the new package appears as `com.portalagent`, and confirm it does not replace or conflict with any installed `com.termux` package.
