---
name: release-build-1-0
title: Clean versioned 1.0.0 release jar for the free plugin
scope: post-mvp
size: small
created: 2026-06-16
wip-owner: hungrydev
links:
  - "[[release-smoke-test]]"
  - "[[release-license-hygiene]]"
---

# Clean versioned 1.0.0 release jar for the free plugin

## What & why

To ship the free StructuralIntegrity plugin publicly (the release plan), we need a clean,
presentable release artifact — a real `1.0.0`, not a `0.1.0-SNAPSHOT-all` jar. Right
now the gradle version is `0.1.0-SNAPSHOT` while `plugin.yml` already says `1.0.0`
(mismatch), and the shaded jar is named `adapter-minecraft-0.1.0-SNAPSHOT-all.jar`
(not upload-presentable). Outcome: `./gradlew :adapter-minecraft:shadowJar` produces
`StructuralIntegrity-1.0.0.jar`, version-consistent and ready to upload to Modrinth.

## Analysis

- **Dependencies:** none. Gates [[release-smoke-test]] (test the artifact this makes)
  and ships alongside [[release-license-hygiene]].
- **Sizing (INVEST):** **small** — build-config + metadata only, no logic change.
- **Risk:** version string referenced by a test (unlikely); the shaded jar name change
  could break any script that globs `*-all.jar`. Low.
- **Alternatives considered:** keep `-SNAPSHOT` and rename on upload — rejected, sloppy
  and error-prone for a public release.
- **Scope decision:** `post-mvp` — release engineering for the free plugin, not part of
  the original gamemode MVP.

### Open questions

_None._

## Spec

- Set the project version to `1.0.0` in `build.gradle.kts:10` (`version = "1.0.0"`).
- In `adapter-minecraft/build.gradle.kts` `tasks.shadowJar`, set
  `archiveBaseName.set("StructuralIntegrity")` and `archiveClassifier.set("")` so the
  output is `StructuralIntegrity-1.0.0.jar` (not `adapter-minecraft-1.0.0-all.jar`).
- Ensure the bundled `plugin.yml` `version` is `1.0.0` (already is) and fix the
  `website:` field to the real listing URL (Modrinth) or remove the dead
  `github.com/gesp/...` link.
- No change to physics, features, or the shaded relocations already in place.

## Acceptance criteria

1. **Given** the repo at version 1.0.0, **when** `./gradlew :adapter-minecraft:shadowJar`
   runs, **then** `adapter-minecraft/build/libs/StructuralIntegrity-1.0.0.jar` exists
   (no `-SNAPSHOT`, no `-all`, no `adapter-minecraft-` prefix).
2. **Given** that jar, **when** its bundled `plugin.yml` is read, **then** `version` is
   `1.0.0` and `website` is a live URL (or absent), not the dead github link.
3. **Given** a Paper 1.21 server, **when** the jar is loaded, **then** the plugin
   enables without error (full run-through is [[release-smoke-test]]).

## Examples

- `./gradlew :adapter-minecraft:shadowJar` → `build/libs/StructuralIntegrity-1.0.0.jar`
- `unzip -p StructuralIntegrity-1.0.0.jar plugin.yml | grep version` → `version: '1.0.0'`

## Scope boundaries

- ✅ **In scope:** project version, shaded jar naming, `plugin.yml` version/website.
- 🚫 **Out of scope:** feature changes, the Modrinth listing copy (the release plan), free-tier
  gating ([[free-tier-scope]]).
- ⛔ **Never touch:** core snapshots, `StruxMetrics` budgets, the physics, the
  host-agnostic core rule.

## Implementation surface

- **Files:** `build.gradle.kts` (root, version), `adapter-minecraft/build.gradle.kts`
  (`tasks.shadowJar` archive name/classifier), `adapter-minecraft/src/main/resources/plugin.yml`.
- **Reuse:** the existing `tasks.shadowJar` block (relocations stay as-is).

## Verification

- `./gradlew :adapter-minecraft:shadowJar` then `ls adapter-minecraft/build/libs/` shows
  `StructuralIntegrity-1.0.0.jar` → proves AC #1.
- `unzip -p .../StructuralIntegrity-1.0.0.jar plugin.yml` → proves AC #2.

## E2E scenario

A server admin lands on the Modrinth page, downloads `StructuralIntegrity-1.0.0.jar`,
sees a clean version number that matches what shows in `/plugins`, and drops it in.

## Affected audiences & doc pages

- **Developers** — note the release build command in the dev docs; `docs/changelog.md`
  `[Unreleased]` dev entry for the 1.0.0 artifact.
- **Admins** — the download is the 1.0.0 jar (changelog admin line).

## Risk note

Bumping the project version touches all modules — run `./gradlew build` once to
confirm nothing asserts the old version. The jar-name change can break any local script
globbing `*-all.jar`. No physics/snapshot impact; `*Metrics` budgets untouched.

## Red evidence

Filled when work starts.

## Outcome

Filled at done/.
