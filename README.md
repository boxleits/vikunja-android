# Vikunja Outline

An Android client for [Vikunja](https://vikunja.io) built to *feel* like
[Orgzly](https://www.orgzly.com): an org-mode-style outline of your tasks,
an agenda view of what's due, and a home screen widget — instead of a plain
flat to-do list.

This is v1, scoped deliberately narrow: **read-only**. It syncs projects,
tasks and labels from a Vikunja instance into a local database and renders
them; it does not create, edit, or complete tasks yet (see [Roadmap](#roadmap)).

## Why it looks the way it does

Vikunja's data model is flatter than org-mode's: tasks have `done`,
`priority` (0-5), `due_date`/`start_date`, and labels, but no native
heading hierarchy or multi-state workflow. The mapping this app uses:

| Orgzly / org-mode | Vikunja | This app |
|---|---|---|
| Notebook | Project | Project |
| Outline heading | Task | Task |
| Nested headings | — | Client-side tree built from the `parenttask` relation in `related_tasks` |
| `[#A]`/`[#B]`/`[#C]` priority | `priority` (0-5) | Same marker style, derived from the numeric priority |
| `SCHEDULED`/`DEADLINE` | `start_date`/`due_date` | Agenda view, due date wins if both are set |
| `:tag:` | Label | Label chip |
| Agenda view | — | Computed client-side across all projects |

Since Vikunja has no subtask hierarchy of its own, the outline tree is
reconstructed from task relations rather than stored that way on the
server — see `core/.../outline/OutlineNode.kt`.

Orgzly is GPLv3; this project takes UX inspiration from it (outline +
agenda + widget) but contains no code or assets from it.

## Module layout

```
core/   Pure Kotlin/JVM. No Android dependency.
        - domain models (Task, Project, Label, Priority)
        - Vikunja API DTOs + Retrofit interface + OkHttp/kotlinx.serialization client
        - mappers (DTO -> domain)
        - outline tree builder, agenda date-bucketing
        - fully unit tested (MockWebServer for the network layer)

app/    Android application module (depends on :core)
        - Room database (local cache, source of truth for the UI)
        - DataStore-backed settings (instance URL + API token)
        - WorkManager background sync + a Glance home screen widget
        - Jetpack Compose UI (Hilt for DI)
```

The split is deliberate: everything that doesn't need the Android
framework — parsing, mapping, the outline/agenda algorithms, the HTTP
client — lives in `:core`, where it's fast to test and easy to reason
about. `:app` is the thin Android shell around it.

## Authentication

Connect with a **personal API token**, generated in your Vikunja instance
under *Settings → API Tokens*. The app never asks for your password.

The token is stored in a private (unencrypted) DataStore file. `allowBackup`
is disabled in the manifest specifically so this file is never swept into
Android's automatic cloud backup. Hardening it further with
`androidx.security.crypto` (Keystore-backed encryption at rest) is a
reasonable follow-up but was left out of v1 to avoid depending on that
library's alpha releases.

## Building

Requires the Android SDK (compileSdk 35, minSdk 26) and network access to
Google's Maven repository (`dl.google.com`) for the Android Gradle Plugin
and androidx artifacts — see [note below](#a-note-on-how-this-was-built)
for why that matters here.

```
./gradlew :app:assembleDebug
```

Run just the pure-Kotlin module (no SDK required):

```
./gradlew :core:test
```

## A note on how this was built

This project was scaffolded and written in a sandboxed environment whose
network egress blocks `dl.google.com` — the host for the Android SDK and
for most `androidx.*`/AGP artifacts on Google's Maven. Maven Central and
the Gradle Plugin Portal were reachable.

Practical effect:
- **`:core`** is pure Kotlin/JVM (Retrofit, OkHttp, kotlinx.serialization/
  coroutines/datetime — all Maven Central). It was fully compiled and its
  **20 unit tests were run and pass** in that environment
  (`./gradlew :core:test`).
- **`:app`** needs AGP and androidx (Compose, Room, Hilt, WorkManager,
  Glance), which could not be resolved or compiled there. Its code was
  written carefully against known-stable APIs and reviewed by hand, but it
  has **not been compiled or run** anywhere. Treat the first `./gradlew
  :app:assembleDebug` you run as the real first build, and expect to fix
  a handful of small issues (an import, a nullability mismatch) that only
  a real compiler pass against the actual libraries would catch.

## Roadmap

Roughly in order:

1. Fix up whatever the first real `:app` build surfaces.
2. Editing: toggle done, change priority/labels/dates from the outline.
3. Two-way sync with an offline edit queue and conflict handling.
4. Quick-capture (an "Inbox" project, fast add from outside the app).
5. Swipe gestures for state/priority changes, notifications for due tasks.
6. Encrypted token storage.
