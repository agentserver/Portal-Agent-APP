# PortalAgent Rebrand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebrand the Android app as PortalAgent while preserving Termux internals as the bundled runtime layer.

**Architecture:** Change Android installation identity through Gradle `applicationId` and manifest placeholders, not by renaming Java packages. Generate Android launcher/splash assets from the provided PNG and keep local-only clutter under ignored `local_private/`.

**Tech Stack:** Android Gradle Plugin, Java, Android XML resources, PowerShell asset generation with `System.Drawing`, ADB verification.

---

### Task 1: Organize Project Files

**Files:**
- Move: root design docs into `docs/architecture/`
- Move: root session notes into `docs/notes/`
- Move: ignored local artifacts into `local_private/artifacts/`

- [ ] Create the target directories with `New-Item -ItemType Directory -Force`.
- [ ] Move root architecture markdown files into `docs/architecture/`.
- [ ] Move project notes into `docs/notes/`.
- [ ] Move ignored screenshots, task logs, and local rootfs bundles into `local_private/artifacts/`.
- [ ] Run `git status --short --ignored` to confirm tracked changes are intentional and local artifacts stay ignored.

### Task 2: Change App Identity

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/xml/shortcuts.xml`
- Modify: `README.md`

- [ ] Set `applicationId` and `manifestPlaceholders.TERMUX_PACKAGE_NAME` to `com.portalagent`.
- [ ] Rename manifest app labels from Claude Code Test variants to PortalAgent variants.
- [ ] Rename APK output prefix from `claude-code-test-app_` to `portal-agent_`.
- [ ] Update shortcuts `targetPackage` values to `com.portalagent` while keeping target classes under `com.termux`.
- [ ] Update README commands, APK path, package checks, and runtime log paths to the PortalAgent package.

### Task 3: Add PortalAgent Icon And Splash

**Files:**
- Create or replace: `app/src/main/res/mipmap-*/ic_launcher.png`
- Create or replace: `app/src/main/res/mipmap-*/ic_launcher_round.png`
- Create: `app/src/main/res/drawable/portalagent_logo.png`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/res/values-night/themes.xml`

- [ ] Copy the provided PNG into a stable tracked drawable resource.
- [ ] Generate density-specific square and round launcher icons.
- [ ] Use the brand foreground in adaptive launcher icon XML.
- [ ] Add a splash theme that uses the launcher icon and PortalAgent background color.
- [ ] Apply the splash theme to the launcher activity.

### Task 4: Verify

**Files:**
- No source files expected beyond Tasks 1-3.

- [ ] Run `.\gradlew.bat :app:testDebugUnitTest`.
- [ ] Run `.\gradlew.bat :app:assembleDebug`.
- [ ] Install the debug APK to Xreal with ADB.
- [ ] Confirm package isolation with `adb shell pm list packages | findstr /i "portalagent termux"`.
- [ ] Launch the app and capture a quick screen check.
