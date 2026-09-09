# Closer to Orgzly: a feature-by-feature survey

A design study. Nothing implemented.

Orgzly's behaviour is transcribed from its source and resources
(`orgzly-revived/orgzly-android-revived`, chiefly `res/values/strings.xml`,
`res/menu/`, and the `query/` and `reminders/` packages). Vikunja's is from
`pkg/models/` and `pkg/routes/routes.go`. Nothing here has been run.

Searches and the widget are the largest piece and have their own document:
[`saved-searches.md`](saved-searches.md). Descriptions, attributes and reminders
are in [`task-attributes.md`](task-attributes.md) and
[`notifications.md`](notifications.md).

---

## Three structural mismatches

Everything else is detail; these three decide how close the app can get.

### 1. States: Orgzly has keywords, Vikunja has a boolean

Orgzly's note state is a **word** from two configurable sets — `todo_states` and
`done_states` — so `TODO`, `NEXT`, `WAITING`, `STARTED`, `CANCELLED` are all
first-class, and the query language can ask for them (`i.NEXT`, `it.todo`).

Vikunja has `done: bool`. That is the whole vocabulary.

**Options.** Labels could stand in for states — Vikunja has real labels and they
are filterable — but a label is a set, not a state machine: nothing stops a task
being `NEXT` and `WAITING` at once, and nothing clears the old one when you set
the new one. Enforcing single-membership client-side would be a convention this
app invents and the web UI immediately breaks.

**Verdict: do not fake it.** A "state" that only one client understands is worse
than an honest boolean. What *is* worth taking from org-mode here is cheap and
real: priority already gives urgency, labels already give context (`@home`,
`@errand` — GTD contexts are what most people use `WAITING` for anyway), and a
saved search can name any combination. If states are ever genuinely needed, the
right move is upstream, not a local fiction.

### 2. Order: in Orgzly it is the document; here it is nothing

In Orgzly, the order of notes in a notebook **is** the order in the file. You
move a note up, down, promote, demote, refile — and that is the structure.

Vikunja stores `position` **per view**, and its own field documentation says:

> Positions are always saved per view. They will automatically be set if you
> request the tasks through a view endpoint, otherwise they will always be 0.

We fetch `GET /api/v1/tasks`, which is not a view endpoint. So **every task we
hold has `position = 0.0`**, and this comparator:

```kotlin
// OutlineNode.kt:10
private val outlineOrder = compareBy<Task>({ it.done }, { it.position }, { it.title })
```

is really "not-done first, then alphabetical". The position term is dead.

Nothing is broken today — alphabetical is a defensible order — but it means
manual ordering is not merely unimplemented, it is *unrepresentable*: there is
nowhere to put the answer. Real positions need fetching through a view
(`GET /projects/{p}/views/{v}/tasks`, after `GET /projects/{p}/views`), and
writing them needs `POST /tasks/{t}/position`, which is also per-view.

**Verdict: worth doing, but as a deliberate project, not a side effect.** It
changes the shape of the sync (per-project, per-view, instead of one flat fetch)
and it is the prerequisite for move-up/move-down ever meaning anything. Until
then the honest thing is to stop pretending: either sort by something we actually
have, or fetch views.

### 3. Search: client-side language vs. server-side filters

Orgzly's saved searches are local queries over local notes. Vikunja's saved
filters are server-side objects, expressed in a different language, and shared
with the web UI. Which one wins decides whether searches work offline and whether
they are visible from the desktop. Covered in [`saved-searches.md`](saved-searches.md).

---

## The survey

| Orgzly feature | Vikunja's position | Verdict |
|---|---|---|
| Saved searches | has its own, server-side | **adapt** — Vikunja's language, evaluated locally |
| Widget bound to a saved search | — | **copy** — the widget's real purpose |
| Query language | different syntax, similar power | **adapt** — do not port the syntax |
| Simple/advanced search editor | — | **copy** — the best UX idea in Orgzly |
| Agenda (`ad.N`) | — | **have it**; add "hide empty days" |
| TODO/NEXT/DONE keywords | boolean only | **skip** — see above |
| Promote / demote | relations (`parenttask`) | **copy** — one relation call each |
| Move up / down | per-view `position` | **blocked** on mismatch 2 |
| Refile to another notebook | `project_id` on update | **copy** — trivial, and useful |
| Cut / copy / paste notes | duplicate endpoint exists | **partial** — paste-as-move is refile; paste-as-copy is `PUT /tasks/{t}/duplicate` |
| Multi-select + bulk actions | `POST /tasks/bulk` | **copy** — the endpoint is already there |
| Folding, start folded | — | **have collapse**; add "start folded" and persistence |
| Content preview in the list | description is HTML | **copy** — needs the parser first |
| Inline images in the list | attachments behind auth | **copy**, after the parser |
| Checkmarks in the list | — | **copy** — cheap |
| Quick capture (share intent) | — | **copy** — a real gap |
| Capture templates | — | **adapt** — a local preference, not a server concept |
| Sort order per search | `sort_by[]`/`order_by[]` | comes free with searches |
| Font size, monospaced font | — | **copy** — cheap, and this is a text app |
| Show notebook name in results | — | **copy** with searches |
| Sharing a note out | — | **copy** — cheap |
| Keep screen on | — | **copy** — one line, oddly useful |
| Clock in / out, time tracking | Vikunja has time entries | **defer** — different model, low demand |
| Org links, id links | plain HTML links | **partial** — see below |
| Notebook encoding, org file sync | irrelevant | **skip** |

---

## The ones I would actually build, with sketches

### Deleting a task — first, and overdue

`DELETE /api/v1/tasks/{id}` exists; nothing in the app calls it. Already flagged
in [`task-attributes.md`](task-attributes.md), and it became urgent when conflict
copies started producing pairs that exist *to* have one of them deleted.

Sketch: `deleteTask` in `:core`; a new pending-edit type so it works offline
(delete the local row immediately, queue the call, restore it if the server
refuses); a confirm dialog; and a swipe gesture, which is where Orgzly puts it.
The rollback case needs care — the local row has to be kept somewhere until the
server agrees, or an offline delete of an unsynced task has nothing to undo.

### Quick capture from outside the app

Orgzly's is a share target plus a widget button. Two intent filters
(`ACTION_SEND` for `text/plain`, `ACTION_PROCESS_TEXT`) and a small dialog
activity that reuses `TaskEditRepository.createTask` — which already works
offline. Orgzly's capture *templates* preset notebook, state and priority;
without states, ours reduces to "which project, which priority", which is a
`SettingsRepository` value, not a new server concept.

The one design decision: a shared link should become a task whose title is the
page title and whose description holds the URL, not a task titled with a URL.
Orgzly has `create_org_links_from_shared_links` for exactly this.

### Promote, demote, refile

All three are single calls we nearly have already:

- **demote** — make the task a child of its previous sibling:
  `PUT /tasks/{t}/relations` with `parenttask`, which `:core` already does for
  subtask creation.
- **promote** — remove that relation and add one to the grandparent:
  `DELETE /tasks/{t}/relations/parenttask/{other}` then a `PUT`.
- **refile** — set `project_id` on a task update. Vikunja explicitly supports
  moving a task between projects that way; it needs write access to the target.

The queue makes these interesting offline, since a relation change is not a field
change and does not fit the current `pending_edits` shape. That is the actual
work, not the API calls.

### Multi-select and bulk actions

`POST /api/v1/tasks/bulk` takes a set of task ids and one set of changes. Marking
twelve tasks done in one request beats twelve queued edits, and long-press to
select is the interaction people expect from a list. Worth doing *after* delete
and refile exist, since "select several, then do X" is only useful once there are
several X's.

### The list, made denser and more org-like

Four small display settings, all of which Orgzly has and all of which are cheap
once the description parser exists:

- **`content_line_count_displayed`** — N lines of the body under the title. This
  is the single biggest visual difference between our outline and an org buffer.
- **`display_inline_images`** — thumbnails in the list. Needs the authenticated
  image loading from `task-attributes.md`.
- **`display_checkmarks`** — checkbox counts from the body (`2/5`).
- **font size and a monospaced option** — this is a text app; Orgzly is right to
  offer it.

### Folding that persists

We collapse, but the collapsed set lives in a `rememberSaveable` and dies with
the screen. Orgzly persists it, and has `notebooks_start_folded`. Moving the set
into Room (or DataStore) is small and makes a large outline usable.

### Links in descriptions

Once descriptions render, an `<a href>` should open the browser — but Vikunja's
web UI also writes **internal task links**, and those should open the task in the
app rather than a browser. Worth recognising the URL shape when the parser is
written, rather than retrofitting it.

---

## Suggested order

1. **Delete a task**, plus the swipe gesture — closes the conflict-copy loop.
2. **Saved filters as filters, not projects** — a live bug ([`saved-searches.md`](saved-searches.md) §3).
3. **Description rendering**, read-only, with the `:core` parser — the biggest
   single addition to what the app shows.
4. **List display settings** — content preview, checkmarks, font — which the
   parser makes nearly free.
5. **Quick capture** — a share target and a widget button.
6. **Reminders and notifications** ([`notifications.md`](notifications.md)).
7. **Searches**, then the widget bound to them ([`saved-searches.md`](saved-searches.md)).
8. **Promote / demote / refile**, then multi-select.
9. **Views and real positions** — only when manual ordering is genuinely wanted,
   because it reshapes the sync.

The first two are small and fix things that are wrong today. Three and four are
where the app stops feeling like a task list and starts feeling like an outliner.
