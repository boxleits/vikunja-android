# Saved searches, filters, and the widget

A design study. Nothing implemented.

Orgzly's query language below is transcribed from
`com/orgzly/android/query/user/DottedQueryParser.kt`, `query/Condition.kt` and
`query/QueryInterval.kt`. Vikunja's is from `pkg/models/task_collection.go`,
`task_collection_filter.go` and `saved_filters.go`. Nothing has been run.

---

## 1. Orgzly's query language, in full

Every operator, from the parser. A leading `.` negates any of them.

| Operator | Meaning |
|---|---|
| `b.NAME` | in notebook |
| `i.STATE` | has state (`i.TODO`, `i.NEXT`, …) |
| `it.todo` `it.done` `it.none` | has a state *of that type* |
| `p.A` / `ps.A` | has priority / has priority set to |
| `t.TAG` / `tn.TAG` | has tag (inherited) / has own tag |
| `s.` `d.` `e.` `c.` `cr.` | scheduled, deadline, event, closed, created |
| `.eq .ne .lt .le .gt .ge` | optional relation on a date, e.g. `d.ge.today` |
| `o.KEY` | sort: `book title scheduled deadline event closed created priority state position` |
| `ad.N` | render as an agenda spanning N days |
| `and` `or` `(` `)` | boolean structure |
| bare words | full-text |

Date values: `today` `tomorrow` `yesterday` `now` `none`, or `[-+]N[hdwmy]`
(`d.le.+3d`). Without an explicit relation, `c` and `e` default to *equals* and
everything else to *less-or-equal* — which is why `d.today` means "due by today",
not "due exactly today".

So `.it.done (d.le.today or s.le.today) o.priority ad.7` is a week's agenda of
everything not done and due or scheduled by today, by priority.

**The part most people miss:** Orgzly does not force this on anyone. The search
editor has a *simple* mode — chips for today / tomorrow / future / past / this
week / this month, "exclude done", "show as agenda", a sort picker — and a
**swap to advanced** button that drops you into the raw string
(`search_filter_swap_to_simple` / `..._advanced` in `strings.xml`). It can also
refuse to swap back when the query is too complex to represent as chips
(`search_filter_unable_to_switch_to_simple`). That two-tier design is worth
copying wholesale, whatever language sits underneath.

---

## 2. Vikunja's filter language

Different in kind: it is **server-side**, and it is a real expression grammar
(the `fexpr` package) over the task's own fields.

| | |
|---|---|
| Boolean | `&&`, `\|\|`, `(`, `)` |
| Comparators | `=` `!=` `>` `>=` `<` `<=` `like` `in` `not in` |
| Fields | any field on the task, snake_case — `done`, `priority`, `due_date`, `start_date`, `percent_done`, `labels`, `assignees`, `project`, `reminders`, `created_by`, … |
| Dates | Elasticsearch-style date math via `go-datemath`: `now`, `now/d`, `now+7d`, `now/w+1w`, or absolute |

`done = false && due_date < now/d+1d && priority >= 3`

Alongside `filter`, the collection endpoint takes `s` (title search), `sort_by[]`
+ `order_by[]`, `filter_timezone`, `filter_include_nulls` and `expand[]`.

**It is at least as expressive as Orgzly's** for everything Vikunja actually
models. What it cannot express is what Vikunja does not have: state *keywords*
(there is only `done`), tag inheritance, and org's notion of an "event"
timestamp.

---

## 3. Saved filters — and a bug we already have

Vikunja stores saved filters server-side:

```
PUT    /api/v1/filters          create
GET    /api/v1/filters/{id}     read one
POST   /api/v1/filters/{id}     update
DELETE /api/v1/filters/{id}     delete
```

**There is no list endpoint.** They are discovered a different way: saved filters
are appended to `GET /api/v1/projects` as **pseudo-projects with negative ids**.

```go
// saved_filters.go
func getProjectIDFromSavedFilterID(filterID int64) int64 { return filterID*-1 - 1 }
// project.go, getAllProjects…
prs = append(prs, savedFiltersProject...)
```

Filter 1 arrives as project −2, filter 2 as project −3.

### The bug

Our sync stores everything `GET /projects` returns. We filter archived projects
and nothing else:

```kotlin
// TaskQueryRepository.kt:43
.filter { !it.isArchived }
```

So **if you have any saved filters in Vikunja, they are already in the app,
pretending to be projects.** They show up as empty headings in the outline, and
worse, in the "New task" project picker — where choosing one sends
`PUT /api/v1/projects/-2/tasks` and fails.

I have not seen this happen because I cannot run the app; if you have no saved
filters yet, you would not have seen it either. It is a two-line fix in the
mapper, and the negative id is exactly the signal needed to treat them as what
they are.

---

## 4. Three ways to build searches, and which one I would pick

### A. Orgzly's language, evaluated locally

Implement `.it.done d.le.today` against Room.

*For:* exactly Orgzly's syntax, so muscle memory transfers. Works offline.
*Against:* a second query language nobody else in the Vikunja world speaks. Saved
searches would be app-local, invisible to the web UI. Concepts that do not map
(`i.NEXT`, `t.` inheritance) would have to be faked or dropped. And a language is
a permanent maintenance obligation.

### B. Vikunja's filters, evaluated server-side

Send `filter=` to the API and show what comes back.

*For:* no language to write. Saved filters are shared with the web UI — create
one on the desktop, it appears on the phone. Nothing to keep in sync.
*Against:* **needs the network.** Every search becomes a request, so the app
stops working offline exactly where it is currently strongest. It also fights the
existing architecture, which syncs everything into Room and reads only from
there.

### C. Vikunja's language, evaluated locally

Parse Vikunja's filter syntax in `:core` and run it against the synced tasks.

*For:* one language, shared with the web UI and with saved filters. Works
offline. Saved filters sync as data like everything else. And the parser and
evaluator are pure functions — **testable in `:core`**, which is the only module
that can be tested in this sandbox at all.
*Against:* the most work. And a local evaluator can disagree with the server's:
SQL `like`, collation, timezone rounding in date math, `filter_include_nulls`.

**I would take C**, with B as a deliberate fallback: when a filter uses something
the local evaluator does not implement, say so and offer to run it against the
server instead of silently returning wrong results. That keeps the honest
failure mode — "I can't evaluate this one offline" — instead of the dishonest
one.

And copy Orgzly's two-tier editor on top of it. Most searches people actually
save are "not done, due this week, high priority", which is four chips; the raw
string is there for the rest.

### On disagreement between the two evaluators

Worth stating plainly because it will happen: a local evaluator is an
approximation of the server's SQL. The mitigations are to keep the implemented
subset small and explicit, to round dates the same way (`filter_timezone` is a
parameter for a reason), and to treat any unsupported operator as "cannot
evaluate" rather than "matches nothing".

---

## 5. The widget

### What Orgzly's does

- Each placed instance is **bound to a saved search** (`list_widget_select_search`),
  chosen when the widget is added and changeable later.
- A scrollable `ListView`, no item cap.
- A header row: open the app, sync, and **add a note** — the last one driven by a
  **capture template** (`widget_capture_template_default_note`), which presets
  the target notebook, state and priority.
- Ordering comes from the saved search's own `o.` clause. There is no separate
  widget sort setting, because the search already carries one.

### What ours does

One widget, one hardwired agenda, configured through the app's Settings, sharing
its horizon and sort with the Agenda screen. That was the right call when there
was nothing else to point it at.

### What I would change, in order

1. **Bind each instance to a saved search.** This is the whole idea: two widgets,
   one showing "due today", one showing "waiting on someone", is the thing people
   actually want from a home screen.
2. **Drop the widget's own sort setting** once searches carry their own order.
   Two places to configure one thing is how the current settings screen got
   confusing enough to need renaming.
3. **A capture button in the header**, once quick capture exists at all. Vikunja
   has no capture-template concept, but "which project, which priority" is a
   small local preference and does not need one.
4. **Keep the existing agenda as a built-in search**, so nothing regresses for a
   widget that is already placed.

`WIDGET_MAX_ITEMS = 100` can stay. It exists so a runaway list cannot blow the
`RemoteViews` transaction budget, which is a real limit rather than a design
choice.

---

## 6. A plan, if this gets built

**Phase 1 — stop the bleeding (small).** Recognise negative project ids as saved
filters instead of projects: keep them out of the outline and out of the project
picker. Fixes a live bug and is a prerequisite for everything below.

**Phase 2 — read filters (`:core`, testable).** Model `SavedFilter`, sync them
from the pseudo-projects, store them in Room. Nothing renders yet.

**Phase 3 — the evaluator (`:core`, testable, the bulk of the work).**
A parser for the fexpr-shaped grammar, a `go-datemath` subset, and an evaluator
over `Task`. Explicitly return "unsupported" rather than guessing. This is where
the unit tests earn their keep — every operator, every date expression, every
timezone boundary.

**Phase 4 — the search screen.** Simple chips plus an advanced string, Orgzly's
two-tier design. Run a search, show the results, save it back to the server.

**Phase 5 — the widget picks up saved searches.** Per-instance configuration,
which is where the widget wanted to be all along.

Phases 1 and 2 are worth doing even if 3 never happens: the bug is real, and
knowing which projects are filters is information the UI needs regardless.
