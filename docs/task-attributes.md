# Task attributes: description, and everything else that is still missing

A design study. Nothing implemented. Every route, field name and format below was
read out of the Vikunja source rather than assumed — but **nothing here has been
tried against a live server**, so treat the shapes as verified and the behaviour
as expected.

Sources: `pkg/models/tasks.go`, `pkg/models/task_attachment.go`,
`pkg/models/task_reminder.go`, `pkg/routes/routes.go`,
`pkg/routes/api/v1/task_attachment.go`, `frontend/src/helpers/attachments.ts`,
`frontend/src/components/input/editor/TipTap.vue`.

---

## Part 1 — The description

### What it actually is

`description` is **HTML**, not Markdown and not a custom markup. The web UI edits
it with TipTap (ProseMirror) and saves `editor.getHTML()` straight into the field.
So the client will meet everything TipTap's starter kit emits: headings,
paragraphs, `<strong>`/`<em>`, ordered and unordered lists, task lists with
checkboxes, blockquotes, code blocks, tables, horizontal rules, links — and images.

Two facts that shape everything below:

1. **The server does not sanitise it.** `dompurify` is a *frontend* dependency;
   the API stores whatever HTML it is handed. Sanitising is the client's job,
   every client's, separately.
2. **We already have it.** `description` is fetched by the sync, mapped into
   `Task`, and stored in `TaskEntity` — and then never displayed anywhere. The
   data has been sitting in the database this whole time.

### The image problem — this is the interesting part

An image inside a description is a plain absolute URL:

```html
<img src="https://vikunja.example.com/api/v1/tasks/42/attachments/7">
```

built by `generateAttachmentUrl(taskId, attachmentId)` in the frontend. That
endpoint is **authenticated** — it needs `Authorization: Bearer …` like every
other call.

The consequence: any renderer that fetches `src` on its own gets a 401. A
`WebView` will not send our token. `HtmlCompat.fromHtml`'s `ImageGetter` fetches
whatever we tell it to, so that one is fine *if* we do the fetching.

So images must be loaded through our own authenticated client. The good news is
that we already have exactly that — `VikunjaApiClient` builds an OkHttp client
with the token interceptor, and `VikunjaConnection` now exposes it (added for the
reconnect fix). Coil takes an `OkHttpClient`, so:

```kotlin
ImageLoader.Builder(context)
    .okHttpClient { connection.client }   // the one that already carries the token
    .build()
```

Two things worth taking from the API while we are here:

- **`?preview_size=` — `sm` 100px, `md` 200px, `lg` 400px, `xl` 800px.** The
  server renders the thumbnail. On a phone, inline images should ask for `lg` and
  only fetch the original when the user opens it full-screen. This is free and
  saves a lot of bytes over someone's 12-megapixel receipt photo.
- Attachments are listed separately (`GET /tasks/{task}/attachments`), so the
  description's `<img>` tags are not the only way to know they exist.

**Offline:** images will simply be missing unless Coil's disk cache happens to
hold them. Prefetching every image in every description on each sync is not
worth it. I would render what can be loaded, show a placeholder for what cannot,
and leave it there for a first version.

### Rendering: three options

| | Fidelity | Effort | Testable here | Notes |
|---|---|---|---|---|
| **A. `AndroidView` + `HtmlCompat.fromHtml`** | poor | very low | no | Bold/italic/links/simple lists only. Tables, code blocks, nested lists and checkboxes come out mangled or vanish. `ImageGetter` handles images. |
| **B. `WebView`** | perfect | low | no | Real browser: everything renders. But it nests badly in a scrolling column, needs CSS injected to follow the app theme, has no text selection continuity with the rest of the screen, and is a script-execution surface on unsanitised HTML. |
| **C. Parse to a block model in `:core`, render in Compose** | good, and ours | high | **yes** | An HTML→blocks parser with real unit tests, then a dumb renderer. |

**I would go with C**, for a reason specific to this project: `:core` is the only
module I can compile and test in this sandbox, and everything in `:app` has been
tested exclusively by you on the device. A description parser is the kind of code
that fails on the fifth nested list, not the first — precisely what unit tests are
for. Putting it in `:core` moves it into the part of the codebase where mistakes
get caught before they reach your phone.

It also disposes of the sanitising problem for free: a renderer that only draws
the block types it understands cannot execute a `<script>` it never modelled.
With B, we would be relying on `javaScriptEnabled = false` and on nobody ever
flipping it.

The parser does not need to be complete. A first version covering paragraphs,
headings, bold/italic/code, links, both list types, task-list checkboxes, block
quotes, code blocks, horizontal rules and images would cover essentially every
description a person actually writes. Tables can render as a horizontally
scrollable monospace block until someone complains.

**A pragmatic middle path** if you want to see it working sooner: ship A first,
look at your real descriptions on the device, and let what breaks decide the
parser's scope. The risk is that A's failure mode is silent — content quietly
disappears rather than looking broken — which for a notes app is the worst kind.

### Editing: I would not, at first

TipTap round-trips its own HTML. Anything we write has to survive being reopened
in the web editor.

| | Round-trip risk | Effort |
|---|---|---|
| Plain-text editing of raw HTML | none, but brutal to use | trivial |
| Markdown editing, converted on save | **high** — HTML the web editor made will not survive a lossy Markdown round trip | medium |
| A Compose rich-text editor (e.g. `compose-rich-editor`, which emits HTML) | low | high |
| **Read-only** | none | none |

**Recommendation: read-only first.** Seeing the note body is most of the value —
it is the part of Orgzly this app is currently missing entirely — and it carries
zero risk of destroying something you wrote in the web editor. Editing can follow
once rendering has proven itself, with a real rich-text editor rather than a
lossy conversion.

If read-only feels too thin, the honest escape hatch is a plain-text HTML editor
behind a clearly-labelled "edit source" — ugly, but it cannot mangle anything by
accident.

### What editing would touch, when it comes

Worth knowing in advance, because it is more than it looks:

- `TaskEdits` grows a `description` field, and `updateTask` sends it. Cheap.
- `pending_edits` grows `description` and `previousDescription`. Descriptions can
  be large; that is a reason to think about it, not a reason to skip it —
  correctness over row size.
- **The conflict copy has to carry the description**, or the one thing worth
  preserving is exactly the thing that gets lost. `createTask` in `:core` would
  need to accept it too.
- Descriptions stay small on the wire even with images, because images are
  attachment URLs rather than embedded base64. The sync does not get heavier.

---

## Part 2 — Every other attribute

### The gap I did not expect to find

**There is no way to delete a task.** `DELETE /api/v1/tasks/{id}` exists; we do
not call it. Nothing in the app removes a task.

That was tolerable until last night. The conflict-copy feature I just built
*creates tasks whose whole purpose is for you to look at them and delete one of
the two* — and the app cannot do the second half. Right now you would have to
finish every conflict in the web UI.

I would treat this as a bug in the feature that just shipped rather than as a new
attribute, and fix it first.

### The full field list

Read from `pkg/models/tasks.go`. "Have" means we parse and store it.

| Field | Have | Show | Edit | Route to write | Verdict |
|---|---|---|---|---|---|
| `title` | ✅ | ✅ | ✅ | task update | done |
| `description` | ✅ | ❌ | ❌ | task update | **Part 1** |
| `done`, `done_at` | ✅ | ✅ | ✅ | task update | done (`done_at` server-set) |
| `priority` | ✅ | ✅ | ✅ | task update | done |
| `due_date` | ✅ | ✅ | ✅ | task update | done |
| `start_date` | ✅ | agenda only | ❌ | task update | small gap — add to the edit dialog |
| `end_date` | ✅ | ❌ | ❌ | task update | low value; add beside start date |
| `labels` | ✅ | ✅ chips | ❌ | `PUT/DELETE /tasks/{t}/labels` | **worth doing** — read-only chips are frustrating |
| `related_tasks` | parent only | as hierarchy | parent on create | `PUT /tasks/{t}/relations` | show the other kinds as links; `copiedfrom` already matters for conflict copies |
| `reminders[]` | ❌ | ❌ | ❌ | task update (inline array) | **high value** — see below |
| `repeat_after`, `repeat_mode` | ❌ | ❌ | ❌ | task update | **high value for org-mode** — see below |
| `attachments[]` | ❌ | ❌ | ❌ | `PUT/GET/DELETE /tasks/{t}/attachments` | needed for images anyway |
| `cover_image_attachment_id` | ❌ | ❌ | ❌ | task update | low value in a list-first UI |
| `assignees[]` | ❌ | ❌ | ❌ | `PUT/DELETE /tasks/{t}/assignees` | only matters on shared projects |
| `percent_done` | ❌ | ❌ | ❌ | task update | easy, low value; org-mode has no equivalent |
| `hex_color` | ❌ | ❌ | ❌ | task update | easy, cosmetic |
| `is_favorite` | ❌ | ❌ | ❌ | task update | cheap, and maps onto a "starred" view |
| `identifier`, `index` | ❌ | ❌ | read-only | — | free to display (`PROJ-12`), nice for referring to a task out loud |
| `created`, `created_by` | ❌ | ❌ | read-only | — | free to display in a detail view |
| `comments` | ❌ | ❌ | ❌ | `PUT/POST/DELETE /tasks/{t}/comments` | medium value; own screen |
| `position` | ✅ | ordering | ❌ | `POST /tasks/{t}/position` | only meaningful inside a view; leave alone |
| `bucket_id`, `buckets` | ❌ | — | — | — | Kanban; out of scope for an outline app |
| `subscription`, `reactions`, `is_unread`, `comment_count`, `time_entries_count` | ❌ | — | — | — | out of scope |

### The two that would change how the app feels

**Reminders.** `reminders[]` is an array on the task, written inline with a normal
task update:

```json
{"reminder": "2026-09-12T08:00:00Z", "relative_period": -3600, "relative_to": "due_date"}
```

`relative_to` names a date field and `relative_period` is seconds relative to it
(negative = before). If `relative_to` is set the server computes `reminder`
itself; otherwise you give an absolute time.

This matters because "notifications for due tasks" is on the roadmap, and the
naive version — the app inventing its own alarms from `due_date` — would disagree
with what the server and every other client think a reminder is. Reading the
server's reminders and scheduling `AlarmManager` from them keeps one source of
truth. It also gets relative reminders ("an hour before it is due") for free,
which is the useful kind.

**Repeating tasks.** `repeat_after` (seconds) plus `repeat_mode` (0 = after that
interval, 1 = monthly, 2 = from the current date rather than the last one). This
is org-mode's `+1w` repeater, and its absence is a real gap for the Orgzly feel.

The server does the work: marking a repeating task done re-opens it and bumps the
dates. But that has a consequence for us that is easy to miss — **our optimistic
tick will be contradicted by the server's response.** We write `done = true`, the
server hands back `done = false` with a new due date, and `updateDoneAndVersion`
faithfully stores that. The checkbox will visibly bounce back. That is arguably
correct behaviour, but it will look like a bug unless the UI says something like
"moved to next Monday". Worth designing rather than discovering.

### Suggested order

1. **Deleting a task.** Small, and the conflict-copy feature is incomplete without
   it. Also the natural home for a first swipe gesture.
2. **Description, read-only**, with the `:core` parser and authenticated image
   loading. The largest single addition to what the app can show.
3. **Editing labels.** They are already displayed and already synced; only the two
   write endpoints are missing. Small effort, immediately noticeable.
4. **Reminders**, read and write — and then notifications built on them rather
   than on invented alarms.
5. **Repeating tasks**, including the "it bounced back because it repeats" message.
6. **Start and end date** in the edit dialog. Trivial once the dialog exists.
7. **Attachments** as a list you can open and add to — the plumbing is already
   there from step 2.
8. Everything else — favourite, colour, percent, identifier, comments, assignees —
   as a detail screen fills out. None of it changes how the app feels.

### One thing to decide before step 2

A read-only description needs somewhere to live. The app currently has no task
**detail screen** — the outline and agenda are lists, and editing happens in a
dialog. A description does not fit in a dialog.

So step 2 quietly implies a detail screen, which then becomes the obvious home for
everything in step 8. That is not a reason to avoid it; it is a reason to design
the detail screen once, deliberately, rather than growing the edit dialog until it
becomes one by accident.
