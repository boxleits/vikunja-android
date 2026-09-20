# Notifications, close to Orgzly

A design study. Nothing implemented.

Orgzly's behaviour below was read out of `orgzly-revived/orgzly-android-revived`
(`reminders/RemindersScheduler.kt`, `reminders/RemindersNotifications.kt`,
`reminders/NoteReminders.kt`, `reminders/RemindersBroadcastReceiver.kt`), not
recalled. The Vikunja side comes from `pkg/models/task_reminder.go` and
`pkg/routes/routes.go`. Nothing here has been run.

---

## 1. What Orgzly actually does

Seven decisions worth copying, and one worth skipping.

**One alarm at a time, not one per task.** `RemindersScheduler` builds its
`PendingIntent` with request code 0 and no data URI, so every schedule replaces
the previous one. It computes the whole sorted list of upcoming reminders, then
sets a single alarm for the head of it. Everything else is recomputed when that
alarm fires. A thousand tasks cost one alarm.

**Rescheduled on four events**, handled by one receiver: `BOOT_COMPLETED`,
"data changed", "reminder fired", "snooze ended".

**Time-of-day decides the alarm type.**

```kotlin
if (hasTime) {                        // exact — the user named a time
    if (useAlarmClock) setAlarmClock(...) else setExactAndAllowWhileIdle(...)
} else {                              // inexact — this is the daily reminder
    set(...)
}
```

A task with a time gets an exact alarm. A task with only a date gets picked up by
the **daily reminder** at a configured time of day, on an inexact alarm. That
distinction is the heart of the org-mode feel: a date without a time is not an
appointment, and does not deserve to interrupt you at midnight.

**Missed reminders still fire.** `NoteReminders` computes the interval to
consider from `now` *and* `lastRun`, so times that passed while the phone was off
are not silently skipped.

**Two actions on every notification:** *Mark as done* and *Snooze*. The done
action's label changes when the note has a repeater ("Mark as done" vs. a
repeater-aware string).

**Snooze is configurable in two dimensions:** duration, and whether it counts
from the button press or from the original alarm time. The second keeps a
repeatedly-snoozed reminder on the original grid rather than drifting.

**Done tasks are excluded** at the source, when the reminder set is computed.

**One thing I would skip:** Orgzly's persistent ongoing notification. It exists
as a quick-capture affordance — new note, sync, search — and quick capture is a
separate item on our roadmap. Adding a permanent notification before there is
anything useful behind it is just a permanent notification.

---

## 2. Where Vikunja differs — and it differs in our favour

Org-mode derives reminders implicitly from `SCHEDULED:` and `DEADLINE:`.
**Vikunja has an explicit `reminders[]` array on the task**, written inline with
an ordinary task update:

```json
{"reminder": "2026-09-12T08:00:00Z", "relative_period": -3600, "relative_to": "due_date"}
```

`relative_to` names a date field, `relative_period` is seconds relative to it
(negative = before). Set `relative_to` and the server computes `reminder` itself;
otherwise you give an absolute time.

That is strictly more expressive than org's convention, and it is shared: the web
UI, every other client and the server's own notification machinery agree on it.

**So: two sources, and explicit wins.**

| Task has | Reminder comes from |
|---|---|
| entries in `reminders[]` | those entries, exactly — no second-guessing |
| no entries, a due/start date **with a time** | that time, Orgzly-style, if the setting is on |
| no entries, a date **without a time** | the daily reminder |
| nothing dated | nothing |

Honouring `reminders[]` first is the whole point: if you set "an hour before it's
due" in the web UI, the phone must not invent something else. The derived rules
exist so that tasks nobody has configured still behave the way Orgzly would —
which is most tasks.

**One thing to verify before building:** our `TaskDto` does not model `reminders`,
so we drop it on read. Writes are safe — the update path round-trips the server's
own JSON, so reminders survive untouched — but I have not confirmed whether the
*collection* endpoint populates the array or only the single-task endpoint does.
If it is single-task only, that changes the sync shape considerably and needs
knowing early.

Vikunja also has a server-side notification system (`GET /api/v1/notifications`,
plus an Atom feed). That is about "somebody assigned you a task", not about "your
task is due in an hour", and it needs the app to be polling to be of any use. Not
the mechanism for reminders.

---

## 3. The Android reality

`targetSdk = 35`, `minSdk = 26`, so all of this applies:

**`POST_NOTIFICATIONS`** is a runtime permission from API 33. Ask when the user
turns reminders on, not at first launch — a permission dialog for a feature
nobody has asked for yet is how you get denied permanently.

**Exact alarms.** API 31–32 need `SCHEDULE_EXACT_ALARM`, which the user grants
through a system settings screen. API 33+ has `USE_EXACT_ALARM`, granted on
install — but Play policy restricts it to alarm and calendar apps.

**This app is sideloaded from GitHub releases, so that policy does not apply
to it.** Worth saying plainly, because it is the constraint that would otherwise
push the design toward inexact alarms and a worse product. If this ever went to
Play, it would need revisiting.

**Doze.** `setExactAndAllowWhileIdle` fires in doze. `setAlarmClock` is the most
reliable of all and puts an alarm icon in the status bar — which is honest for a
reminder that must not be missed, and intrusive for one that need not be. Orgzly
offers it as a setting; so should we.

**Reschedule on more than boot.** `ACTION_TIME_CHANGED` and
`ACTION_TIMEZONE_CHANGED` matter for an app whose whole job is times — flying
somewhere should not silently move every reminder. Plus: after every sync, and
after every local edit.

**Manufacturer battery optimisation** (Xiaomi, Samsung, Huawei and friends) will
kill alarms regardless of what the API promises. This is not winnable in code.
The honest response is a settings entry that explains it and links to the system
screen, rather than pretending reliability we cannot deliver.

---

## 4. Proposed shape

Split along the line this project already uses, and for the same reason: `:core`
is the only module that can be compiled and tested here, so the part that can be
*wrong* belongs there.

### `:core` — pure, and where all the decisions live

```kotlin
data class ReminderSettings(
    val enabled: Boolean,
    val useDueDates: Boolean,
    val useStartDates: Boolean,
    val dailyReminderAt: LocalTime?,     // null = no daily reminder
    val deadlineWarningDays: Int,        // 0 = none
)

data class ScheduledReminder(
    val taskId: Long,
    val at: Instant,
    val source: Source,                  // EXPLICIT, DUE_DATE, START_DATE, DAILY
    val hasTimeOfDay: Boolean,           // decides exact vs. inexact
)

/**
 * Every reminder that should have fired since [since], plus everything upcoming,
 * sorted. Passing [since] is what makes a phone that was switched off catch up
 * instead of silently skipping.
 */
fun remindersFor(
    tasks: List<Task>,
    settings: ReminderSettings,
    now: Instant,
    since: Instant,
    timeZone: TimeZone,
): List<ScheduledReminder>
```

Everything interesting is in there and unit-testable without a device: the
precedence of explicit reminders, the daily-reminder rule for date-only tasks,
the deadline warning period, done-task exclusion, catch-up after downtime,
timezone handling.

### `:app` — thin, and boring on purpose

- **`ReminderScheduler`** — takes the head of that list and sets exactly one
  alarm. Exact when `hasTimeOfDay`, inexact otherwise, `setAlarmClock` behind a
  setting.
- **`ReminderReceiver`** — one receiver for fired / boot / time changed /
  timezone changed / snooze ended. Each one: recompute, notify what is due,
  reschedule the next.
- **`ReminderNotifier`** — one notification per task, grouped with a summary,
  channel `reminders`, category `CATEGORY_REMINDER`.
- A `snoozed_until` column in Room, so a snooze survives a reboot.

### The two actions

**Mark as done** goes straight through `TaskEditRepository.setDone`. That is the
neat part: it already works offline, already queues, already handles conflicts.
Ticking a task off from the lock screen on a train with no signal is a case this
app has already solved without knowing it.

**Snooze** is local only — Vikunja has no such concept, and inventing a
server-side one would put something in `reminders[]` that other clients would
read as a real reminder. Keep it in Room, and let it expire.

---

## 5. Settings

Mirroring Orgzly's, trimmed to what this app can honour:

- Reminders on / off (asks for `POST_NOTIFICATIONS` here)
- Remind about due dates · Remind about start dates
- Daily reminder at `HH:MM` — for tasks with a date but no time
- Deadline warning: none / 1 day / 2 days / a week
- Snooze: duration, and counted from the button or from the original time
- Sound · Vibration
- Use the alarm clock for timed reminders (more reliable, shows the alarm icon)
- Battery optimisation — an explanation and a link to the system screen

---

## 6. Where this touches what already exists

**Repeating tasks.** If repeaters get built, marking a repeating task done from a
notification re-opens it server-side with a new due date, which produces a new
reminder. Correct — but the notification action should say so, the way Orgzly's
does, or it will look like the tick did not take.

**Conflict copies.** A `[conflict]` copy carrying a due date will produce its own
reminder. I think that is right — it is a real task and it is genuinely
unresolved — but it is worth knowing rather than discovering.

**The honest limitation.** Reminders only fire for tasks this device has synced.
Add a task in the web UI due in ten minutes and the phone will not know until its
next sync. Orgzly has exactly the same property. Syncing shortly before the daily
reminder fires helps; the 30-minute periodic sync covers the rest. Anything
better needs the push channel this project deliberately does not have.

---

## 7. Decisions I would want from you

1. **Explicit `reminders[]` beat derived ones** — I recommend yes, but it means a
   task configured in the web UI stops obeying the app's own due-date setting.
2. **Do date-only tasks get a daily reminder at all**, or stay silent? Orgzly says
   daily reminder; I would match it.
3. **Catch-up after downtime** — fire reminders missed while the phone was off, or
   drop them? Orgzly fires them. Dropping is quieter, firing is safer.
4. **Writing reminders**, not just reading them — worth having, but it is a
   date-picker-shaped piece of UI on top of an edit dialog that is already
   getting full. Probably wants the detail screen from the other document first.
