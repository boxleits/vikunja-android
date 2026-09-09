# Vikunja Outline

An Android client for [Vikunja](https://vikunja.io) built to *feel* like
[Orgzly](https://www.orgzly.com): an org-mode-style outline of your tasks,
an agenda view of what's due, and a home screen widget — instead of a plain
flat to-do list.

Scope so far: it syncs projects, tasks and labels from a Vikunja instance
into a local database and renders them. It can **create a task** and **tick one
done or not-done**; editing anything else — title, dates, priority, labels — is
still to come (see [Roadmap](#roadmap)).

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
| Agenda in the home screen widget | — | Same data and same settings as the Agenda screen, scrollable |

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

Creating a task is the awkward case, and worth stating plainly. An edit names
a task the server already knows; a creation has to invent an identity and later
reconcile it. A new task is written immediately under a **negative placeholder
id** — Vikunja's ids are positive, so the sign alone says whether a row has ever
reached the server — and queued. When the server accepts it, the placeholder row
is deleted and the server's version inserted in its place. A sync re-inserts
placeholders after the wholesale replace, which would otherwise delete a task
the server has never heard of. A creation the server *refuses* takes its
placeholder with it: there is no earlier state to roll back to.

Editing a task — its title, priority or due date — goes through the same
queue, and takes the whole form each time rather than only the fields that
changed. That reads like it would clobber a concurrent change, but it can't:
the push fetches the server's own copy of the task, merges the form into it,
and posts that back, so description, labels, assignees and everything else this
app doesn't show are carried over rather than blanked. Clearing a due date is
the one field that has to be said out loud — an omitted `due_date` keeps the
old value and Vikunja refuses `null` — so "no date" is written as the zero date
`0001-01-01T00:00:00Z` that the reader already treats as unset.

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

Three details keep the queue, the wholesale replace, and edits of different
kinds from fighting:

- A sync **re-applies queued edits** after replacing the tables, inside the
  same transaction, so the server snapshot can't undo a change that hasn't
  been sent.
- A repeated edit of the same kind to the same task **collapses into one
  queued edit** (unique index on task + kind), and the recorded "previous"
  value stays the one from before the first edit, so a rollback lands where it
  should.
- Edits of *different* kinds do coexist — a task can have both a tick and a
  field edit waiting — so a successful push **re-bases the others** onto the
  version it just produced. Without that, the second one would report a
  conflict against this device's own first one. A genuine conflict still
  surfaces, because a third party's change lands on a stamp nobody here has
  seen. The same mechanism moves edits queued against a **placeholder** onto
  the real id once its creation is accepted, so editing a task you just
  captured offline works before it has ever reached the server.

Retries ride the existing sync work: a queued edit schedules a
connectivity-constrained one-shot sync, and the periodic sync flushes the
queue too. `flushPending()` deliberately schedules nothing itself, so a
failing flush can't re-trigger the sync that called it.

### What triggers a sync

| Trigger | When |
|---|---|
| Onboarding | Right after connecting |
| Pull to refresh, *Sync now* | On demand |
| Foreground | Opening the app, if the cache is more than 30 seconds old |
| Periodic | Every 30 minutes, with a connection |
| Queued edit | As soon as there's a connection, to flush the backlog |

The foreground sync runs directly rather than through WorkManager. The
scheduler's immediate-sync work is unique with KEEP, so a run left in backoff
after a failure would silently swallow later requests — precisely when the user
is looking at the screen.

There is deliberately no push channel. Instant sync would mean either Google's
push service, or a separate distributor app on the phone to hold the
connection — Android gives a normal app no cheap way to keep one open. Since
opening the app already refreshes it, the remaining window that push would
cover is small: the app sitting open in front of you while something changes
elsewhere.

### Conflicts

**The server keeps the task; this device's version becomes a `[conflict]` copy.**

Vikunja has nothing to build on server-side: tasks carry no version or ETag,
and the update endpoint has no `If-Match`, so it will always accept a stale
write. Detection therefore happens client-side, using the task's `updated`
timestamp as its version.

A queued edit records the `updated` stamp the task had when the edit was made.
The write is already a read-modify-write — fetch the task, apply the change,
post it back — so the fetch doubles as the check, and detecting a conflict
costs no extra request:

| At flush time | Result |
|---|---|
| Server's `updated` matches the recorded one | The write goes ahead |
| It differs | **No POST is sent.** The server's version takes the task back, and this device's version becomes a `[conflict]` task of its own |
| No recorded base (an edit made against a task this device created, or one queued by an older build) | Check skipped — nothing to conflict with, or can't tell |

The comparison is made to **whole seconds**, which is not a tolerance but the
precision the server has. Vikunja's `updated` column is a DATETIME that xorm
writes formatted to seconds, while the task in a create or update *response* is
serialised from the in-memory struct, where the full-precision `time.Now()` is
still sitting. The same write therefore reports `…:12.345678901Z` in its
response and reads back as `…:12Z` on the next fetch. Comparing exactly made
this device's own successful write look like somebody else's change — which is
how a task created offline, edited offline, and then synced came back as a
conflict with itself.

#### Nothing is discarded: the losing version becomes a task

Borrowed from Syncthing, which never resolves a conflict by throwing a version
away — it keeps both and renames one. Here the version that loses is created as
a new task, prefixed `[conflict] `, carrying the native Vikunja label
`sync-conflict` and a `copiedfrom` relation pointing at the original. Because
it lands on the server, the web UI and every other client see the conflict too,
rather than it being a notice on one phone.

**One rule, no field inspection:** if the `updated` stamp differs, a copy is
made. That is deliberate. An earlier design merged field by field — apply the
local title if nobody else touched the title, and so on — which fails on the
first example anyone tries:

```
Base:    "Buy milk"        ☐
Phone:   "Buy milk"        ☑   (bought, offline)
Server:  "Buy oat milk"    ☐   (somebody changed the plan)
```

Merging those fields gives "Buy oat milk ☑" — oat milk was bought, which
nobody claimed. `done` is not a column, it is an assertion *about the title*,
and renaming the task pulls the ground out from under it. Fields are not
independent, so a rule that treats them as independent produces statements
nobody made.

Field-dependent rules also age badly: every new attribute multiplies the
combinations to reason about, and behaviour that differs per field is
unpredictable for exactly the sort of user who runs a self-hosted Vikunja. So
the rule stays one sentence long, and the semantic judgement — is this the same
errand or a different one? — goes to the person who can actually make it.

**Which side loses is structural, not chronological.** The version that has not
reached the server yet becomes the copy. No clock comparison: the server's
stamp and this device's are two different clocks, and making the outcome depend
on them agreeing would be a real fragility bought for a cosmetic decision —
both versions survive either way. It is also one write instead of two, and it
never rewrites a task somebody else is looking at.

**What can still fail:** the copy is queued, and dropping the edit that lost
happens in the same transaction, so this device's version never exists nowhere.
The label and the relation are best-effort afterwards and are not retried — a
copy without its label is still a task titled `[conflict] …`, which is
recoverable; a copy that was never created would not be.

Notices are stored in the database, not raised in the moment: the flush that
finds a conflict usually runs in a background worker with no UI attached, so
the news has to wait until the app is next opened. It then appears as a dialog
naming the affected tasks — a dialog rather than a snackbar, because the user
has a decision to make about two tasks and a message that vanishes on its own is the
wrong way to say so.

Repeated toggles of one task collapse into a single queued edit, and the
recorded base version stays the one from before the *first* edit — the version
the user was actually looking at.

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

The **`debug-latest` pre-release** always carries the newest build, at stable
direct-download URLs — two of them:

| | |
|---|---|
| [`app-debug.apk`](https://github.com/boxleits/vikunja-android/releases/download/debug-latest/app-debug.apk) | Unminified. Everyday testing. |
| [`app-release.apk`](https://github.com/boxleits/vikunja-android/releases/download/debug-latest/app-release.apk) | Minified by R8. **Use this to judge performance.** |

The distinction matters more than it looks. Compose in a debug build is
materially slower than in a minified one, so scroll smoothness measured on a
debug APK largely reports the build type rather than the code — Google's own
guidance is to profile release builds only. Both are attached to each run as
build artifacts too, which keeps per-run history but downloads as a zip.

Both are signed with the checked-in `app/debug.keystore`, so they install over
one another and over previous builds without losing local data. That key is not
a secret; a genuinely distributable release needs a real key kept out of the
repository, so `app-release.apk` here is a measurement tool, not a shippable
artifact.

R8 can break what a compiler cannot see. `app/proguard-rules.pro` keeps the
serializable DTOs, the Retrofit interface and the widget classes for that
reason; if the minified build misbehaves where the debug one doesn't, that file
is the first place to look.

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
  **58 unit tests were run and pass** in that environment
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

## The agenda

One setting governs both the Agenda screen and the home screen widget
(Settings → Agenda), so the two cannot disagree about what "my agenda" is:

- **Range** — today, today and tomorrow, within a week, any time. Overdue is
  always included, and if nothing falls inside the range the widget shows what
  is next rather than sitting empty.
- **Order** — date, priority, or title. Applied across the whole list rather
  than within each bucket, since the widget shows one flat list.

Every row carries a second line with the due (or scheduled) date *and time*,
formatted in the device's locale. Overdue items say so and are drawn in the
error colour — they are in every range, so without a marker they were
indistinguishable from anything else due soon.

Bucketing is by date, not by the minute: a task due today at 09:00 still counts
as due today at 14:00, matching how an org-mode agenda reads. The time on each
row is what makes the difference visible.

The widget collects its data inside the Glance composition rather than reading
it once beforehand. `provideContent` starts a session that outlives a single
draw, and a later `update()` recomposes *that* session — so anything captured
before it is frozen for the session's lifetime. Reading it up front is why the
widget used to ignore a settings change.

Orgzly does this differently and better in one respect: its widget is
configured *per placed instance*, and what it shows is a saved search, with the
ordering carried in the query itself (`o.priority`, `o.deadline`). That is the
right model once there is something like a saved search to point at; until
then, an explicit order setting is the honest substitute. There is no item
limit in either — the list scrolls.

## Roadmap

Roughly in order:

1. Creating subtasks. Creating top-level tasks is in; hanging one under a
   parent needs a second request (Vikunja keeps hierarchy in relations, not on
   the task) and, when the parent is itself an unsynced placeholder, a way to
   rewrite the reference once the real id arrives.
2. More editing: labels, description and start dates. Title, priority and due
   date are in — tap a heading in the outline or a row in the agenda.
3. Notifications for due tasks, and swipe gestures for state and priority.
4. Quick-capture from outside the app — a share target and a widget button.
5. Encrypted token storage.
