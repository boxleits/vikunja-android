# Vikunja Outline

An Android client for [Vikunja](https://vikunja.io) built to *feel* like
[Orgzly](https://www.orgzly.com): an org-mode-style outline of your tasks,
an agenda view of what's due, and a home screen widget — instead of a plain
flat to-do list.

Scope so far: it syncs projects, tasks and labels from a Vikunja instance
into a local database and renders them. The **one edit it supports is
ticking a task done or not-done from the outline** — creating tasks and
editing anything else is still to come (see [Roadmap](#roadmap)).

Edits are applied locally first so the UI reacts immediately, then queued
and pushed. A change that can't be sent right now — no connection, server
down — stays applied and waits in the queue; only a change the server
actually rejects is rolled back. The queue survives a restart, and a sync
re-applies anything still in it so the full-snapshot refresh can't
overwrite work that hasn't been sent yet.

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
| Agenda in the home screen widget | — | Same data, with a configurable horizon (Settings → Widget) |

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

## How syncing and editing fit together

Reads are one-way and wholesale: each sync fetches every project, label and
task and replaces the local database in a single transaction. The UI only
ever observes Room, so the app works offline with the last synced data.

Writes go through a queue (`pending_edits`):

1. The edit is applied to the local row, so the UI reacts at once.
2. It's queued, with the row's previous value recorded alongside it.
3. It's pushed immediately if possible.

What happens next depends on *why* a push failed, which
`VikunjaSyncException.isRetryable()` decides:

| Outcome | Queue | Local row |
|---|---|---|
| Accepted | dropped | overwritten with the server's version |
| Offline, 5xx, 429, 401 | kept, retried later | keeps the edit |
| 4xx (task gone, payload refused) | dropped | rolled back to the recorded previous value |

Two details keep the queue and the wholesale replace from fighting:

- A sync **re-applies queued edits** after replacing the tables, inside the
  same transaction, so the server snapshot can't undo a change that hasn't
  been sent.
- A repeated toggle of the same task **collapses into one queued edit**
  (unique index on task + kind), and the recorded "previous" value stays the
  one from before the first edit, so a rollback lands where it should.

Retries ride the existing sync work: a queued edit schedules a
connectivity-constrained one-shot sync, and the periodic sync flushes the
queue too. `flushPending()` deliberately schedules nothing itself, so a
failing flush can't re-trigger the sync that called it.

Not handled yet: a genuine conflict. If a task changed on the server *and*
locally, the queued edit is pushed on top without noticing.

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

Requires **JDK 17** and the Android SDK (compileSdk 35, minSdk 26), plus
network access to Google's Maven repository (`dl.google.com`) for the
Android Gradle Plugin and androidx artifacts — see
[note below](#a-note-on-how-this-was-built) for why that matters here.

```
./gradlew :app:assembleDebug
```

Run just the pure-Kotlin module (no Android SDK required):

```
./gradlew :core:test
```

> **JDK version matters.** Gradle 8.14 / AGP 8.6 / Kotlin 2.0 don't support
> the newest JDKs — building with a JDK 25 or 27-ea toolchain fails. Point
> Gradle at a 17 (or 21) JDK via `JAVA_HOME`, or `org.gradle.java.home` in
> `gradle.properties`.

### CI

`.github/workflows/build.yml` runs on every push to `master` and on every PR:
one job runs `:core:test`, another assembles the debug APK. Both pin JDK 17.

The APK is published two ways. The **`debug-latest` pre-release** always
carries the newest build, at a stable direct-download URL:

<https://github.com/boxleits/vikunja-android/releases/download/debug-latest/app-debug.apk>

It's also attached to each run as a build artifact, which keeps per-run
history but downloads as a zip — the release asset is the one to grab by
hand.

Debug builds are signed with the checked-in `app/debug.keystore`, so
successive builds install over one another instead of forcing an
uninstall. That key is not a secret and must never sign a release build.

### Dev container

`.devcontainer/` provides a container with JDK 17 and the Android SDK
preinstalled, which sidesteps both the JDK-version issue and having to
layer an SDK onto an immutable host. It's set up for **Fedora Atomic +
Podman + natively-installed VS Code**; see
[`.devcontainer/README.md`](.devcontainer/README.md) for host setup and
for the one-line change needed if you use Docker instead of Podman.

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

  Reaching that point needed AGP kept out of the root `plugins {}` block,
  which turned out to break the real build: it splits AGP and the Kotlin
  plugin across two buildscript classloaders, and KGP then can't load
  AGP's `BaseVariant`. The workaround is gone now that CI builds the
  project properly, so configuring *any* module needs Google's Maven
  reachable — the Android SDK itself is still only needed for `:app`.
- **`:app`** needs AGP and androidx (Compose, Room, Hilt, WorkManager,
  Glance), which could not be resolved or compiled there, so it was
  written against known-stable APIs and reviewed by hand rather than
  compiled. CI has since given it a real compiler pass: **it now builds a
  debug APK**, so Kotlin compilation, KSP codegen (Room, Hilt), resource
  processing and packaging all pass. That first pass found 49 errors in
  three groups — a missing `api` dependency, one wrong Glance package, and
  one overload mismatch — all fixed.

  Still untested: the app has **never been run**. Nothing below the
  compiler has been exercised — no screen has rendered, no sync has hit a
  real Vikunja instance, no widget has been placed. Expect runtime issues.
- **`.devcontainer/`** has its JSON and shell validated, but the image was
  never built there either (no container runtime, and the SDK download it
  performs targets the blocked host). The first `Reopen in Container` is
  its first real run.

## Roadmap

Roughly in order:

1. More editing: change priority, labels and dates from the outline
   (toggling done is in, and rides the same queue).
2. Conflict handling. The queue protects local edits from being overwritten
   by a sync, but the server still wins on a genuine conflict — if a task
   changed on both sides, the queued edit is pushed on top without noticing.
3. Quick-capture (an "Inbox" project, fast add from outside the app).
4. Swipe gestures for state/priority changes, notifications for due tasks.
5. Encrypted token storage.
