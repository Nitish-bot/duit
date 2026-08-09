# Duit Product and Android Architecture Plan

## 1. Product promise and boundaries

Duit should help approved individuals manage their own days and, when they choose a Partner, share what they are comfortable sharing, encourage each other, and leave. It is a polished personal tool with optional one-to-one Pairing, not a collaborative productivity platform. It supports important personal obligations but is not a medical, emergency, or safety-critical alarm system.

The first production version includes task titles, plain-text notes, reorderable subtasks, all required date/recurrence forms, history, Focus, reminders, Google sign-in plus administrator approval, offline use, cloud recovery, manual export/restore, optional two-Member Pairs, Shared and Private Tasks, and lightweight preset Cheers. It deliberately excludes projects, tags, priorities, attachments, shared ownership, task assignment, free-form messaging, Pairs larger than two Members, multiple active Partners per Member, streaks, scores, analytics, arbitrary calendar rules, guest mode, and a biometric app lock.

Each approved Member has an independently owned private workspace and normally uses one primary phone. A Member may remain unpaired or join at most one active Pair; each Pair contains exactly two distinct approved Members, and many independent Pairs may coexist. Every Task starts Private and has one Owner: only an explicit Owner choice creates a Pair-bound Shared View, which the Partner may read but never edit, complete, reschedule, Focus, archive, or delete.

## 2. Experience architecture

### Navigation

- **Today** is the default home and emotional center.
- **Together** is the stable second destination. Before pairing it explains the relationship model and offers Create code / Enter code; while paired it contains the Partner's Shared work and opportunities for encouragement.
- **Plan** is a week-first agenda with a secondary month picker for navigation.
- **Tasks** contains Active, Repeating, Anytime, Paused, and Archived views.
- A contextual **Add** action defaults to today, the selected Plan date, or Anytime depending on entry point.
- History lives in task details; settings, sync state, backup, Pair management, and account actions live behind the profile control. There is no Stats destination.

### Today

Render these sections in order:

1. **Focus** — zero to three manually selected tasks for the current local day.
2. **On your plan** — fixed tasks, today's recurring occurrences, and flexible tasks planned for today.
3. **Needs a decision** — unresolved dated work, compact and neutral.
4. **Could do** — flexible work available before a deadline, collapsed by default.
5. **Done today** — completed work, collapsed after completion.

Do not headline remaining counts, percentages, streaks, or “perfect days.” Prefer copy such as “Three things on deck,” “This one is still open,” and “You made room for the rest of your day.” When the day is resolved, the UI should offer closure rather than another engagement loop.

Focus is emphasis only: it creates no deadline or priority, cannot contain more than three items, and is stored against a local date. It expires with that day.

Today is always personal. The Partner's work never enters Today, Focus, Needs a decision, reminder counts, or the resolved-day calculation. This separation keeps visibility from becoming shared control or emotional pressure.

### Together and encouragement

Together is a calm window into the Pair, not an activity feed. It shows:

1. **Their day** — the Partner's Shared Tasks relevant today, without an overdue counter or completion percentage.
2. **Coming up** — Shared Tasks in the next seven days, collapsed when empty.
3. **Recent moments** — at most ten Shared completions from the last seven days, with calendar dates but no exact times, unread badges, rankings, totals, or infinite scroll.
4. **All shared Tasks** — a direct route to the Partner's readable Shared Task library.

Opening a Shared Task reveals its title, note, Subtask labels, schedule, current/future actionable Occurrences, and date-level Complete outcomes created during the current Share Grant. It never reveals reminder configuration, Focus, checked Subtask progress, Missed, Skip, Not Doing, Remove Occurrence, exact completion instants/zones, corrections, or sync/fork diagnostics. All task controls are absent: the Partner cannot mark work complete, move it, remind the Owner, or see a dedicated overdue list.

A Member may send one preset **Cheer** on the Partner's current/future actionable Standalone Task or Occurrence, or on a Shared completion. Use encouragement presets such as “Rooting for you” for open work and celebration presets such as “Proud of you” or “Nice work” for completed work. Never offer a Cheer on Needs a decision, overdue, Missed, Skip, Not Doing, paused, ended, removed, or archived work. The sender may replace or remove their own Cheer, but only the first eligible Cheer per target in a rolling 24-hour window may create a push; replacement and tombstone writes update in-app state without another notification.

Each recipient has a Pair-level **Allow Cheers** control plus a separate push-notification toggle. Disabling Cheers hides the action, rejects new Cheer writes, and locally dismisses current Cheers without sending an announcement; dismissed Cheers do not reappear. A received Cheer may also be dismissed individually with no read receipt. There are no free-form comments, counts, streak comparisons, leaderboards, “poke” actions, or prompts to cheer more often. A Cheer never changes Task state.

### Creation and editing

Do not expose eight technical task types. The editor composes two understandable choices:

- **When:** On a date / By a deadline / Anytime.
- **Repeat:** Never / Daily / Selected weekdays / Monthly dates / Yearly dates / Custom.

Fast add requires only a title. Notes, Subtasks, recurrence, miss policy, and one optional reminder appear through progressive disclosure. Every new Task defaults to **Only me**. While paired, the compact fast-add surface itself always shows **Only me** or **Share with [Partner name]** before submission, including with TalkBack and 200% text; privacy must not be hidden behind expansion or an overflow menu.

For a recurring occurrence, editing offers only **This occurrence** and **This and future**. The former creates an exception; the latter starts a new effective series revision. Past occurrence snapshots do not change when the definition changes.

Subtasks are lightweight checklist rows without their own dates or reminders. A recurring series supplies a template, but each occurrence gets fresh independent checked state. Checking every subtask does not auto-complete the parent, and the parent may be completed with unchecked subtasks.

### Pairing and privacy behavior

- Pairing is optional. A Member may belong to at most one active Pair, each Pair has exactly two distinct approved Members, and many independent Pairs may coexist. One Member creates a short-lived, one-use Pair Invite; entering the code creates a pending match, not an active Pair. Both people see the other's Google display identity and explicitly confirm while online before the Pair activates.
- Every Task starts Private, whether its Owner is unpaired or paired. Sharing requires the Owner to choose **Share with [Partner]**, which creates `Shared(pairId, shareGeneration)` deliberately.
- Existing Tasks never become Shared automatically when a Pair forms. Pairing ends with an explicit review where the Owner may select older Tasks to share; this prevents accidentally exposing history.
- Shared is bound to the active `pairId`, not to “whoever is paired later.” Unpairing immediately revokes cross-account server access. Previously Shared Tasks are treated as Private and are not automatically exposed to a future Pair.
- Every Private-to-Shared transition creates a new Share Grant generation. Cheers from an earlier grant stay hidden if the Task is re-shared, even to the same Partner, unless the Partner deliberately sends a new Cheer.
- Changing Shared to Private revokes future server reads of the Task tree as soon as the write reaches Firestore. The Partner UI removes it after reconnecting, but Duit cannot retract information already seen, erase screenshots, or guarantee secure deletion of bytes already held in Firestore's offline cache. This is accepted for the stated normal-personal-data threat model; state it in confirmation copy instead of promising remote erasure.
- Changing Private to Shared publishes the current Shared View and starts a new prospective Share Grant. Earlier Private outcomes and prior-grant Cheers stay private. Confirmation says **Share this Task with [name] from now on** and lists the fields the Partner can see.
- On unpairing, each Member keeps all of their own Tasks and history. Partner content disappears and Together returns to its neutral connect screen; Pair Invites and cross-account reads are revoked, and Cheers tied to the old Pair are no longer accessible in the app. Either Member may later form a different Pair, which inherits none of the old Pair's access or Cheers. Both people see **Together connection ended. Your Tasks and history are still yours.** Duit never names the initiator, and failed queued Cheers resolve as unavailable without blame.

### Humane date behavior

- A flexible task has an optional planned date and a firm deadline; normally, moving it changes only the planned date.
- If a flexible Planned Date passes before its Deadline, clear the stale plan and return the Task to Could do; it is not overdue until the Deadline passes.
- If a flexible task is already overdue, choosing a future day uses explicit copy such as “Move task and deadline to Friday” and updates both together. The previous definition remains available to history.
- A stale planned date on an unfinished Anytime task is cleared at the next local day boundary and the task returns to Anytime without becoming overdue.
- Fixed tasks and expired flexible deadlines retain their original commitment and appear under Needs a decision. They are never silently rolled forward.
- Needs a decision offers Complete, Do today/Choose another day, Skip for an Occurrence, and Not doing this for a Standalone Task.
- Each recurring series chooses **Let it pass** or **Keep it open**. Let it pass produces neutral Missed history; Keep it open retains the occurrence until resolution. Today groups repeated misses instead of rendering an unbounded guilt backlog.
- Skip is visible and neutral. Remove occurrence means the expectation was removed and is hidden from ordinary history. End series suppresses all future occurrences but preserves the past.
- Not doing is a terminal Standalone Task outcome. A Recurring Series uses Skip for one Occurrence or End series for its future. Archiving an active Recurring Series atomically ends future Occurrences and then hides it from active views.
- Pausing creates a date span whose implied occurrences count as skipped. History shows one expandable summary such as “Paused March–August · 184 skipped”; resuming closes the span and creates no overdue backlog.
- Pause spans are start-inclusive and resume-date-exclusive. An explicit completion or corrected outcome on a paused date overrides the implied skip for that occurrence.

### Visual and emotional system

Material 3 supplies behavior and accessible component foundations, not the finished identity. Build a custom `DuitTheme` with:

- warm cream and paper-like neutral surfaces;
- sage structural colors and restrained coral celebration accents;
- rounded organic geometry, confident typography, and generous spacing;
- warm botanical illustrations without a mascot;
- first-class light and dark palettes with verified contrast;
- brief, interruptible motion and system-respecting haptics.

Celebration is tiered: a normal completion gets immediate checkbox motion and a light haptic; completing a Focus item gets a short sprout/bloom flourish; resolving the day reveals a richer but non-modal botanical card. Sound is off by default. Reduced-motion behavior replaces flourishes with a simple state transition. Decorative art is excluded from accessibility traversal.

Treat 48dp touch targets, TalkBack state/action labels, visible alternatives to swipe gestures, non-color status cues, 200% font scaling, one-handed completion/rescheduling, and localized string resources as acceptance criteria.

## 3. Domain and calendar model

### Separate concepts

- **Member** is an administrator-approved individual with an independently owned private workspace; Google Authentication identifies the individual but does not grant membership by itself.
- **Pair** is an optional server-authorized relationship between exactly two distinct approved Member UIDs, with an active or ended lifecycle. Many independent Pairs may coexist, while one Member may belong to at most one active Pair.
- **Task** is the stable identity and lifecycle envelope with two variants: Standalone Task and Recurring Series.
- **Owner** is the immutable Member UID controlling a Task. **Visibility** is either `Private` or `Shared(pairId, shareGeneration)` and controls Partner reads without changing ownership. Each new Share Grant increments the generation.
- **Task Revision** and **Series Revision** are immutable, effective-dated snapshots of title, notes, subtask template, timing, recurrence, and miss policy. Reminder configuration is a separate Owner-only concern and is never embedded in Partner-readable Revisions.
- **Occurrence** is generated deterministically from a series revision for a bounded date window; future occurrences are not pre-created indefinitely.
- **Occurrence State** is persisted only when an occurrence is interacted with. It contains independent subtask progress, a directly editable outcome, and a Completion Epoch that increments on each transition into Complete. A completed outcome maps to the domain's Completion Record.
- **Schedule Exception** changes one occurrence or a bounded pause span without rewriting the series.
- **Shared View** is a derived, whitelisted read model for one Share Grant. It contains sanitized definition snapshots, current/future schedule exceptions, and date-level Complete projections; it is not a second editable Task or source of calendar truth.
- **Cheer** is a separate, durable preset reaction with a sender, Share Grant, target, and revision. It may target a Partner's Shared Task or a specific Completion Epoch but never participates in scheduling or outcome resolution.

This keeps definitions, generated occurrences, completion data, and exceptions separate in the domain while allowing one state document to atomically enforce mutually exclusive outcomes: Complete, Skip, or Open for an Occurrence; Complete, Not doing, or Open for a Standalone Task.

### Recurrence algebra

Use a pure Kotlin sealed model, not RRULE:

- `Daily(everyDays: Int)` anchored to the rule's start date
- `Weekly(everyWeeks: Int, weekdays: Set<DayOfWeek>)` anchored to the ISO Monday week containing the start date
- `Monthly(everyMonths: Int, monthDays: Set<Int>)` anchored to the start `YearMonth`
- `Yearly(everyYears: Int, dates: Set<MonthDay>)` anchored to the start year
- common start date and optional inclusive end date
- per-occurrence add, remove, move, and content override
- pause spans

V1 excludes nth-weekday/business-day expressions and arbitrary nested rules. RRULE can become a future import/export adapter, never the persisted source model.

An occurrence key is derived from `(taskId, nominalLocalDate)` and remains stable if the occurrence moves. At most one occurrence from a series exists for a nominal date. Moving onto a date that already contains another occurrence does not merge identities; both may appear with their source dates made clear.

Selected calendar dates are literal. A monthly rule for the 31st has no occurrence in a shorter month; February 29 yearly rules occur only in leap years.

### Time semantics

- Calendar commitments use `LocalDate`; reminder clock values use `LocalTime`.
- Format a `LocalTime` with each device's current 12/24-hour system preference at display time. Observe configuration/time-format changes and re-render; never rewrite the stored value or reschedule merely because presentation changed.
- Future schedules follow the phone's current local zone. Never encode a task date as UTC midnight.
- Actual actions store `Instant` plus the observed zone and local date for faithful history.
- A reminder in a DST gap fires at the first valid instant after the gap. In an overlap, use the earlier offset and post only once.
- Time-zone, wall-clock, and date changes trigger future reminder recalculation. Historical outcomes never shift zones retroactively.
- Day-boundary behavior is derived when state is read as well as by background reconciliation, so a delayed worker cannot make Today logically incorrect.
- Nominal Date and Effective Date are never converted for a Partner. The Owner app publishes only its current `LocalDate` to the Shared View; it never exposes the raw zone or action-zone history. Together uses that date only to group **Their day** and label when the Members are on different calendar dates.
- Owner-local occurrence identity remains `(taskId, nominalLocalDate)` inside the Owner path. Every cross-account reference adds `ownerUid`, becoming `(ownerUid, taskId, nominalLocalDate)`.

### Core invariants

- A server-controlled approval record is required for a UID to be a Member or access its private workspace. An authenticated but unapproved UID may read only its own approval record and may read or write no workspace, Task, Pair, or other Member data.
- A Pair contains exactly two distinct approved Member UIDs, many independent Pairs may coexist, and a Member belongs to at most one active Pair.
- Every Task starts `Private`; only its Owner may create `Shared(pairId, shareGeneration)` through an explicit sharing choice.
- A Task has one immutable Owner. Only that Owner may mutate its Task tree; the Partner is read-only even when the Task is Shared.
- Persisted `Private` requires `sharedWithPairId == null`. Persisted `Shared` requires a positive `shareGeneration` and a Pair ID whose immutable membership contains the Owner; Visibility and these fields change atomically. Partner access additionally requires that Pair to be the Owner's current active membership. An ended/stale binding is accepted for offline Owner writes but is effectively Private and normalized after synchronization.
- A Share Grant grants Partner reads only while its exact Pair and generation are active. It never grants access to a later Pair or later re-share, and a Task that has never been Shared is never delivered to the Partner.
- Visibility changes access and Shared View projection state without mutating the Owner's canonical history. Partner rules never expose canonical Occurrence State, reminder, diagnostic, or historical-outcome documents.
- A task-level Cheer is keyed to the current Share Grant. A completion-level Cheer additionally targets `(ownerUid, taskId, occurrenceKey-or-standalone-marker, completionEpoch)`. Completion-time/Subtask corrections preserve the epoch; leaving Complete, Remove Occurrence, or Not Doing invalidates it; completing again creates a new epoch.
- A Cheer must come from the other Member, target a currently Shared Task or valid Completion Epoch, use an allowed preset, and never target the sender's own work.
- Revision effective ranges do not overlap for the chosen active branch.
- Definition edits never mutate historical snapshots.
- `plannedDate <= deadline` for flexible tasks; the explicit overdue-move command changes both atomically.
- Resolve Task state in this total order: Remove Occurrence; explicit outcome; Pause Span by Nominal Date; one-occurrence move/content override; Series Revision default; then Miss Policy derivation. Resolve Owner-only reminder rules separately after the final Effective Date is known. An Added Occurrence is rejected when that Nominal Date already exists and is otherwise subject to Pause like any other Occurrence.
- One occurrence has one current outcome; direct history corrections replace that outcome in place.
- Direct history editing may change the outcome, completion instant, and checked Subtask state; title, notes, Nominal/Effective Dates, and definition snapshot remain read-only. Clearing an explicit outcome recomputes Pause and Miss Policy derivations.
- A recurrence rule's inclusive end date and a later End series boundary may coexist; the earlier effective boundary wins. Explicit persisted outcomes remain in history even if a later End suppresses their unresolved future range.
- A Remove or deletion tombstone is never physically discarded during normal synchronization.
- Per-occurrence subtask state never leaks into a later occurrence.
- All generation and commands are idempotent under deterministic IDs.

## 4. Android architecture and stack

### Technology choices

- Kotlin, Jetpack Compose, Material 3, Coroutines, and Flow remain the right foundation.
- Set `minSdk 28`, and compile/target the stable Android 16 / API 36 toolchain for the initial build. Run compatibility tests on an Android 17 / API 37 emulator, but do not make the production build depend on its preview SDK label and release-candidate AGP; move compile/target to 37 only after the SDK, AGP, and Firebase/Compose dependencies are all stable ([Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk)). Confirm the target phone models before device QA.
- Use `com.gaylord.duit` as the default application ID and `Duit` as the app label; change the ID only before Firebase and release signing are configured.
- Use Firebase Authentication with Google through Android Credential Manager, Cloud Firestore, Firebase App Check with Play Integrity, Firebase Cloud Messaging, DataStore for small device-local preferences, WorkManager, and AlarmManager.
- Use a version catalog and Firebase BoM; pin stable dependency versions at implementation time rather than copying transient prerelease versions into this plan.
- Add only three narrow Cloud Functions 2nd gen boundaries: Pair Invite creation/acceptance, Unpair, and idempotent Cheer notification fan-out. Do not add Room, a general-purpose API, an RRULE library, or an analytics SDK in v1.

Firebase's current Android guidance uses Credential Manager for Google sign-in, and Firestore provides persistent offline reads/writes with automatic reconnection; same-document conflicts are last-write-wins ([Firebase Auth](https://firebase.google.com/docs/auth/android/google-signin), [Firestore offline behavior](https://firebase.google.com/docs/firestore/manage-data/enable-offline)). Callable Functions automatically receive available Authentication and App Check tokens, which makes them the smallest trustworthy boundary for Pair membership changes ([Firebase callable Functions](https://firebase.google.com/docs/functions/callable)).

### One module, deep package boundaries

Begin with one Android Gradle module, `:app`. Multiple Android modules would add build configuration and public APIs before any measured build-time or ownership problem exists. Keep the small Cloud Functions 2nd gen TypeScript package outside the Gradle build under `functions/`; it is a deployment boundary, not a reason to split the Android application. Keep Android boundaries explicit in packages:

- `app` — application composition, navigation, and dependency container
- `domain.model` and `domain.recurrence` — pure Kotlin rules and generation
- `data.firestore`, `data.repository`, and `data.mapping` — persistence DTOs and repository implementations
- `auth`, `pairing`, `notifications`, and `export` — platform boundaries
- `feature.today`, `feature.plan`, `feature.tasks`, `feature.together`, `feature.editor`, and `feature.settings` — UI slices
- `designsystem` — Duit tokens, components, motion, and illustration contracts

Use constructor injection through a small manual `AppContainer`, including a custom `WorkerFactory`. Inject `Clock`, zone provider, ID generator, repositories, and reminder gateway so temporal and offline behavior are deterministic in tests. Extract `domain.recurrence` into a separate JVM module only if build speed or reuse later demonstrates a benefit.

Compose screens use unidirectional data flow: immutable UI state flows from ViewModels through `StateFlow`; typed UI events flow back. Composables do not call Firebase or Android schedulers. Keep domain operations cohesive, but do not create a one-class “use case” wrapper for every trivial repository method. Android's current Compose guidance likewise recommends state-down/events-up UDF ([Compose architecture](https://developer.android.com/develop/ui/compose/architecture)).

## 5. Firestore, offline use, and synchronization

### Source of truth decision

Use one logical store: Firestore. The cloud document set is the durable source of truth; the Firestore Android SDK's persistent disk cache is the immediate offline store exposed by repositories. Keep a bounded 100 MiB persistent cache, enable local query index creation, and hydrate every active definition plus the history/exceptions needed by the app. An unlimited cache is no longer appropriate once Partner data may later be revoked.

Do **not** add Room plus a custom outbox. Android's generic offline-first guidance recommends a local source of truth, but Firestore already supplies the local persisted cache, queued writes, snapshot listeners, and retry mechanism needed here. Duplicating it would require custom reconciliation for every schema change ([Android offline-first guidance](https://developer.android.com/topic/architecture/data-layer/offline-first), [Firestore cache configuration](https://firebase.google.com/docs/firestore/manage-data/enable-offline)).

A fresh install must be online for Google sign-in, administrator approval verification, and initial hydration. An unapproved identity receives no workspace data. After an approved Member hydrates, creating, editing, completing, skipping, changing Visibility, and reviewing cached own history works offline. Pair creation, Pair Invite acceptance, and Unpairing require a connection because their exactly-two-member and one-active-Pair-per-Member invariants are server-authoritative. Cheers may be queued offline and become visible or notify only after synchronization.

On a replacement phone, a hydration coordinator performs server-sourced reads for the Member's complete owner tree before editing is enabled, so an empty cache cannot overwrite real data. Failure keeps that workspace locked with Retry and Sign out actions; only a completed server read may establish a genuinely empty workspace. Pair and Partner-shared hydration is a separate projection: its failure may show **Together is still updating**, but it must not block the Owner from using already-hydrated personal Tasks.

### Document layout

Keep ownership in the path and group each Task's authorization-sensitive children beneath it:

```text
/users/{ownerUid}
  /workspace/main
  /pairProfile/main
  /tasks/{taskId}
    /revisions/{revisionId}
    /occurrenceStates/{occurrenceKey}
    /exceptions/{occurrenceKey}
    /pauseSpans/{pauseId}
    /sharedDefinitions/{sharedRevisionId}
    /sharedExceptions/{occurrenceKey}
    /sharedCompletions/{completionTargetKey}
    /cheerTargets/{targetKey}
    /cheers/{cheerId}
  /taskReminders/{taskId}
    /revisions/{reminderRevisionId}
    /occurrenceOverrides/{occurrenceKey}
  /dailyPlans/{localDate}
  /settings/main
  /devices/{deviceId}
  /syncDiagnostics/{recordId}

/pairs/{pairId}
/pairMemberships/{uid}

/pairInvites/{inviteHash}
/inviteRateLimits/{uid}
/cheerDeliveries/{deliveryKey}
/memberApprovals/{uid}
```

- `workspace/main` contains schema version and last successful owner hydration metadata.
- `pairProfile/main` exposes only display name, optional avatar URL, the Owner's current `LocalDate`, and `allowCheers` to the active Partner. It exposes no raw zone, last-active time, or last-sync timestamp. The date is used only to group **Their day**; Nominal and Effective Dates are never converted.
- `tasks/{taskId}` contains stable identity, immutable `ownerUid`, kind, lifecycle, active revision pointer, `visibility`, nullable `sharedWithPairId`, positive `shareGeneration` while Shared, and end/archive/delete tombstones.
- Canonical `revisions`, `occurrenceStates`, `exceptions`, and `pauseSpans` remain Owner-only. They contain the complete truthful Task model, including Completion Epochs and negative outcomes.
- `sharedDefinitions` contains only definition snapshots created during the current Share Grant: title, note, Subtask labels, timing, recurrence, and current/future lifecycle data. `sharedExceptions` contains current/future sanitized overrides or suppressions without negative-outcome reasons. `sharedCompletions` contains stable target keys and completion `LocalDate` only. Every projection record carries `shareGeneration`; old generations are not queryable by the Partner.
- `cheerTargets` materializes a bounded 30-day set of current/future actionable Standalone Task/Occurrence targets plus valid Shared Completion targets. Each document carries type, generation, optional Completion Epoch, and expiry. Rules and Functions authorize Cheers only through these whitelisted target documents instead of attempting to evaluate recurrence or overdue semantics.
- Owner commands update canonical state and the current Shared View projection in the same offline-persisted write batch. Projection IDs are deterministic and tombstoned idempotently; the projection may be rebuilt from canonical owner data for the current generation after hydration.
- Reconcile the Shared View and 30-day Cheer-target horizon after relevant Task/outcome/Visibility changes, app start, local date/zone change, and a daily unique WorkManager job. The worker writes locally and never substitutes for Firestore synchronization.
- `cheers` lives beneath the target Owner's Task. The Partner may write only their own deterministic Cheer while the parent Share Grant and target are current; this avoids unsafe broad Cheer queries.
- `taskReminders/{taskId}` contains Owner-only effective-dated reminder rules and per-occurrence reminder overrides. Reminder changes use This occurrence or This and future semantics without entering Partner-visible Task history.
- `dailyPlans` and `settings` are always Owner-only. Focus is never partner-visible.
- `devices` contains FCM registration tokens and is readable only by trusted server code; one Member must never read the Partner's token.
- `syncDiagnostics` contains device IDs, local edit versions, write errors, and branch-review metadata. The Partner never reads it.
- `pairs/{pairId}` contains exactly two distinct immutable approved Member UIDs and active/ended timestamps. Only callable Functions mutate Pair lifecycle.
- `pairMemberships/{uid}` is the server-owned, deterministic lock and active-Pair pointer for that UID. Pair acceptance transactionally creates both membership records; Unpair ends the Pair and clears both locks so concurrent invites cannot create overlapping Pairs.
- A Cheer contains sender/recipient UID, Share Grant generation, optional Occurrence key and Completion Epoch, preset ID, client version, timestamps, and tombstone. It contains no Task title, note, or arbitrary text.
- `pairInvites` stores a hash of an eight-character Crockford Base32 code, creator UID, expiry, and consumed state. Codes expire after 15 minutes, are single-use, and are rate-limited per UID by callable Functions. Clients never read or write invite documents directly.
- `inviteRateLimits` persists server-only attempt/cooldown state and is updated transactionally; rate limiting never relies on Function instance memory.
- `cheerDeliveries` is a server-only idempotency/cooldown ledger keyed by Cheer target and version. Client Cheer writes cannot modify delivery state or recursively retrigger delivery.
- `memberApprovals/{uid}` is a scalable, server-controlled per-UID registry maintained manually by an administrator. An authenticated identity may read only its own approval record, while rules and Functions require `approved == true` before granting any workspace or Pair access. Clients may neither list nor mutate approvals.

Do not materialize an infinite canonical Occurrence collection. The Owner generates bounded agenda windows from canonical Revisions and merges Occurrence State and exceptions by deterministic key. A Partner first queries the other Owner's Task access envelopes using `visibility == SHARED` and `sharedWithPairId == activePairId`, reads each Task's current `shareGeneration`, then constrains that Task's Shared View and Cheer queries to the same generation. The small denormalized projection is deliberate: Firestore cannot redact fields from a readable document, and exposing canonical outcome/history documents would violate the product's anti-monitoring promise.

Core offline commands use client-generated IDs and Firestore write batches, which are persisted offline; do not depend on Firestore transactions because transactions fail offline ([Firestore atomic operations](https://firebase.google.com/docs/firestore/manage-data/transactions), [offline WriteBatch behavior](https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/WriteBatch)).

### Conflict policy

The product invariant is one primary phone per Member, so optimize for immediate local writes and rare exceptional conflicts. The Partner is not another writer to the Task tree:

- Changes to different documents merge naturally.
- Mutable occurrence state, Focus, and settings use Firestore's whole-document last-write-wins behavior. Direct history correction therefore remains simple and intentional.
- Definition edits append an immutable child revision and update the task's active pointer in one batch. If two children share a parent, retain both and follow the pointer that won synchronization. Mark sync state Needs attention and show a Task-detail review that compares schedule summaries; choosing a branch creates a new resolving Revision and retains both earlier branches diagnostically.
- All removals, ends, archives, and reopened outcomes use explicit state/tombstones; physical deletion is reserved for the deliberate Delete all data flow.
- Record `deviceId`, monotonically increasing local edit version, client edit instant, and branch-review metadata in Owner-only sync diagnostics, not in Partner-readable Task documents or calendar semantics.
- Before moving to a replacement phone, the normal flow is: verify “Backed up,” sign out or retire the old phone, then hydrate the new one.
- A deterministic Cheer ID represents one sender/target combination. Repeating the same preset is idempotent; replacing the preset increments its version; removing it writes a tombstone. Cloud notification delivery may repeat, but it never creates a second Cheer.

Firestore's built-in same-document policy is last-write-wins; this plan accepts that tradeoff for directly mutable outcomes because simultaneous editing was explicitly excluded as a normal use case ([Firestore offline behavior](https://firebase.google.com/docs/firestore/manage-data/enable-offline)).

Expose synchronization honestly but quietly: Settings shows the Owner's **On device**, **Backing up**, **Backed up at …**, or **Needs attention** using snapshot metadata and write failures. Together shows only generic **May be out of date while offline** copy and the sender's own pending Cheer state—never the Partner's last-sync time, online status, or activity freshness. Never block ordinary offline Task actions on network state.

Account switching/sign-out requires a connection: warn about and flush pending writes, delete this installation's device-token document and FCM token, then clear Firebase auth, Credential Manager state, and all Firestore persistence before another account can enter. This avoids delivering a former account's Cheer to the next person using the installation.

When either phone observes an ended Pair, enter a non-editable `RevokingPairData` state, immediately remove Partner content, stop Partner listeners, and reject new commands. Drain the complete pending-write queue before terminating Firestore; if a write fails or connectivity drops, keep personal data read-only and retry rather than clearing. Only with zero pending writes may the app terminate Firestore, clear the whole persistent cache, restart it, and hydrate the signed-in Owner's data. Firestore exposes only whole-cache `clearPersistence()`, requires the instance to be stopped, drops pending writes, and does not promise secure overwrite; if cleanup is interrupted, keep Partner surfaces disabled and resume it on next launch ([Firestore Android reference](https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/FirebaseFirestore#clearPersistence())).

## 6. Authentication and backend security

Require one online Google sign-in and an administrator-created `memberApprovals/{uid}` record with `approved == true` before creating or hydrating a workspace; there is no guest/local-only merge path. Use separate Firebase projects for development and production, each with its own server-controlled approval registry. App Check proves a request comes from an accepted app installation; it does not prove that an authenticated Google identity is an approved Duit Member, so both controls are required.

Firestore rules deny by default and first require both an authenticated UID and its own Member Approval with `approved == true`. An unapproved identity may read only its own approval record so the app can explain its state; it may not read a prospective workspace, enumerate approvals, inspect Pair data, or write any Duit document. Rules then enforce these capabilities:

- An approved path `ownerUid` may read and write their own Task and child documents, subject to strict field/type/size/schema validation. Owner writes remain valid offline even if Unpair happens concurrently: a stale Shared binding may refer to an active or ended Pair that contains the Owner, then is normalized to effective Private after synchronization. It never grants Partner reads unless the Pair is active.
- The other Member may read only the Shared Task access envelope, `sharedDefinitions`, `sharedExceptions`, `sharedCompletions`, `cheerTargets`, and current-generation Cheers when they are in the same active Pair, `visibility == SHARED`, `sharedWithPairId` equals that Pair, and projection `shareGeneration` matches the parent. Canonical Revisions, Occurrence States, exceptions, Pause Spans, reminders, and diagnostics are always denied.
- The other Member may read only the active Partner's narrow `pairProfile`; every other user-level document remains Owner-only. A Member may read only their own membership lock. A Pair document is readable by its two immutable Members even after it ends so both phones can observe revocation, but only Functions may write Pair and membership documents.
- A Partner may never write the Owner's canonical Task or Shared View documents. The next bullet's tightly constrained Cheer is the sole cross-owner write exception; Daily Plans, settings, reminders, device tokens, backup state, fork diagnostics, and restore state remain Owner-only.
- The sole cross-owner write exception is the Partner's own deterministic Cheer document. Rules require the current Share Grant, a valid actionable/shared-completion target, recipient `allowCheers`, known preset ID, exact allowed fields, and no free-form content. Cheer queries are per already-authorized Task and current generation; no client may read or write the server-only delivery ledger.
- Ended Pairs grant no Partner-profile, Task, Shared View, or Cheer access; only the Pair tombstone remains readable by its former Members. A new Pair ID never satisfies an old Task's `sharedWithPairId`.

Partner Task-list queries must include `visibility == SHARED` and `sharedWithPairId == activePairId`; each Shared View/Cheer query is scoped to one authorized parent Task and constrains `shareGeneration`. Firestore Security Rules evaluate a query's possible results and are not filters ([secure Firestore queries](https://firebase.google.com/docs/firestore/security/rules-query)). Test every Task child collection, query shape, direct document read, and collection-group attempt in the Emulator Suite.

Use callable Cloud Functions with Auth and enforced App Check for Pair Invite creation/mutual confirmation and Unpair. These online functions require active approval for both identities, transactionally contend on both deterministic `pairMemberships/{uid}` locks, and reject self-pairing, expired or replayed codes, concurrent acceptance, a third Member, a Member already paired, and unapproved UIDs. Either Member may Unpair; the function ends the Pair irreversibly, invalidates invites, and clears both locks before reporting success. Either may then form a new Pair, whose new Pair ID cannot satisfy an old Share Grant. This is the deliberate exception to the rule that ordinary Task commands must not depend on online transactions.

Although Google Auth can identify any Google account, only UIDs with active server-controlled Member Approval receive data access. Also protect the backend from copied or modified APKs with Firebase App Check using Play Integrity. Register the release signing certificate, configure the provider for distribution outside Google Play (do not require Play-recognized or licensed verdicts), use the debug provider only in the development project, monitor metrics on registered sideloaded test phones, and only then enable Firestore and Functions enforcement. Firebase documents this outside-Play configuration explicitly ([App Check for Android](https://firebase.google.com/docs/app-check/android/play-integrity-provider)).

Production Cloud Functions deployment requires the pay-as-you-go Blaze plan even when early usage should remain inside no-cost quotas. Link billing deliberately, configure a low budget alert and usage monitoring, and understand that alerts do not cap charges ([Functions setup](https://firebase.google.com/docs/functions/get-started), [Firebase pricing plans](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans)). Firebase API keys identify a project but do not authorize data, so Member Approval, rules, Auth, and App Check—not key secrecy—are the controls ([Firestore quotas](https://firebase.google.com/docs/firestore/quotas), [Firebase API keys](https://firebase.google.com/docs/projects/api-keys)).

Security Rules document-access calls are limited per operation and per atomic batch. Keep ordinary cross-document batches scoped to one Task, begin restore/import batches at no more than ten writes, and increase that cap only after the final Ruleset proves its access-call budget in the Emulator. A nominal 500-write Firestore batch is not a safe design target when each write may consult Member Approval, parent Task, Pair, or target documents ([Rules access-call limits](https://firebase.google.com/docs/firestore/security/rules-conditions#access_call_limits)).

## 7. Reminder architecture

One occurrence may have one configured base reminder and at most one active alarm at a time. A Later action replaces that alarm rather than adding a second reminder. Ask for notification permission only when the user creates the first reminder or explicitly enables Encouragement notifications, after explaining the requested value. Ask for Alarms & reminders special access only when exact timing is requested. If exact access is unavailable, preserve the reminder and label it as approximate.

- **AlarmManager** schedules one-shot user-facing reminders. Use `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()` is true; otherwise use an inexact allow-while-idle alarm. Never use a repeating alarm for recurrence.
- **NotificationManager** only posts/cancels the actual notification and owns separate Task reminder and optional Daily summary channels.
- **WorkManager** performs non-urgent alarm/Shared View horizon reconciliation and idempotent notification-action writes. It never provides exact reminder timing or replaces Firestore synchronization.

Android requires user-granted exact-alarm access for fresh modern installs, can revoke it, and recommends rescheduling after the permission-state broadcast; inexact alarms may be delayed substantially under power saving ([Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms)).

Maintain a 30-day future alarm horizon and extend it after alarms fire and during a daily unique reconciliation worker. Every alarm is keyed by occurrence ID. On receipt, load current cached state before posting and suppress stale, completed, skipped, removed, moved, ended, or archived occurrences.

For fixed and recurring work, the reminder fires on the effective date. For a flexible task it fires on the planned date when present, otherwise on the deadline. Setting a reminder on an Anytime task also assigns its planned date. Task titles appear in notifications by default and rely on Android's lock-screen privacy controls; Duit adds no separate app-lock or notification-privacy subsystem in v1.

Reconcile after task/occurrence edits, completion, pause/resume, app start, boot, date/time/time-zone change, exact-alarm permission change, and `MY_PACKAGE_REPLACED`. DST and time-zone conversion always happen at scheduling time.

Notifications offer **Done** and **Later**. Done enqueues an idempotent, network-unconstrained worker so completion reaches Firestore's local queue offline. Later replaces the same reminder with a one-hour snooze; it is user-requested and may be repeated. No notification repeatedly nags merely because a task is overdue. A morning summary is opt-in and inexact.

Settings includes a small reminder diagnostic: notification permission, exact-alarm availability, next scheduled reminder, and last reconciliation. Test on the real OnePlus under screen-off, Doze, reboot, and OxygenOS battery optimization. Do not request unrestricted battery access automatically. Explain that force-stopping the app disables reminders until it is reopened.

### Partner encouragement notifications

Task reminders remain Owner-only and local: AlarmManager determines timing, NotificationManager displays them, and WorkManager repairs them. FCM is used only to tell the other phone that a synchronized Cheer exists.

An idempotent Firestore-triggered Function revalidates the active Pair, Share Grant, target, recipient `allowCheers`, and preset with Admin credentials. Only the first active Cheer for that sender/target in a rolling 24-hour window sends a normal-priority FCM message; preset replacement, removal, tombstones, invalidated outcomes, and cooldown repeats never send another push. Firestore triggers are delivered at least once and are not ordered, so the server-only `cheerDeliveries` ledger owns processed versions and cooldown timestamps, and Android reuses a stable target notification ID ([Firestore event triggers](https://firebase.google.com/docs/functions/firestore-events)). Refresh device tokens on rotation and remove them on sign-out or invalid-token response.

Use a separate optional **Encouragement** notification channel. The payload contains `recipientUid`, Pair/Task/Share Grant/target IDs, and a preset ID only; it never contains a Task title, note, schedule, or user-authored text. Before posting, the receiver verifies that `recipientUid` equals the currently authenticated UID and that the Pair/Share Grant are still active; otherwise it silently discards the message. Lock-screen copy is generic, such as **A little cheer from [name]** / **Open Duit when you have a moment**. On tap, the app re-authorizes and fetches the current Shared target; if it became Private or the Pair ended, open Together with a neutral unavailable message. FCM is best-effort transport, never the source of truth: unseen Cheers come from Firestore even when push is late, duplicated, denied, or lost. Android 13 and later still require notification permission before either reminder or FCM notifications can be shown ([FCM for Android](https://firebase.google.com/docs/cloud-messaging/android/get-started)).

Never send automatic partner notifications for completion, non-completion, overdue status, Skip, Missed, rescheduling, Focus changes, or activity. The Partner must intentionally open Together and choose a Cheer.

## 8. Recovery, export, updates, and migration

Firestore is the normal recovery mechanism. A reinstall or replacement phone signs into the same Google account, hydrates all documents, rebuilds agenda projections, and reschedules reminders. Be explicit that edits made on a lost phone that never reached any network cannot be recovered; the last successful cloud-backup time makes that boundary visible.

Android Auto Backup is **not** an additional recovery mechanism. Disable it for v1 and provide explicit data-extraction exclusions for Firestore cache, Firebase auth/install state, DataStore state, and scheduled-alarm state; stale internal state is less safe than a clean cloud hydration. Android documents that Auto Backup otherwise includes much app-private data by default ([Android Auto Backup](https://developer.android.com/identity/data/autobackup)).

Add a versioned `.duitbackup` manual export/import as the independent recovery path. For v1 it is a portable, unencrypted UTF-8 JSON document selected through Android's Storage Access Framework; warn **This unencrypted file contains your complete Task data and history** and never place it in shared storage automatically. It includes only the exporting Owner's canonical Task/revision/state/exception/pause/reminder/settings records plus format metadata. It excludes derived Shared View projections as well as Partner-owned cached Tasks, Pair membership or Invites, Cheers, device tokens, and Partner profile data.

Restore only into an empty owner workspace: parse and validate the whole file before any write, preview counts/date, and reject unsupported or malformed data. A restored Shared binding is coerced to Private unless that exact Pair is currently active and the Owner explicitly creates a fresh Share Grant; a backup must never grant access by itself. Starting restore creates a durable restore-session ID and progress record; its idempotent batches may resume only that same session, while a different import into the now non-empty workspace remains rejected. Partition imports by Task and start with at most ten writes per batch so Rules access-call limits are respected. Password encryption can be added later if real sensitivity justifies its recovery and key-management cost.

Every schema document and backup has an explicit version. Mappers tolerate additive fields and reject unsupported future versions; destructive migrations require a tested export first. New APKs must be signed with the same release key. Store the keystore and recovery material in two secure locations; losing it prevents in-place upgrades even though cloud data can still be restored after reinstalling a differently signed package.

## 9. Test strategy and acceptance scenarios

### Pure domain tests

- Pair lifecycle, `Private` versus Pair/generation-bound Share Grants, no automatic re-sharing, immutable Task ownership, Completion Epoch transitions, deterministic Cheer identity/versioning, and Partner agenda grouping from the Owner-published current date without date conversion.
- Property/table tests for every-N daily/weekly/monthly/yearly rules, leap years, missing dates 29–31, inclusive bounds, and deterministic IDs.
- DST gaps/overlaps, travel across zones and the date line, manual clock changes, and completion near midnight.
- This-occurrence overrides combined with later this-and-future revisions.
- Move collisions, pause spans, explicit corrections inside pauses, end-series boundaries, and delete tombstones.
- Let-it-pass versus keep-open missed policy, grouped backlog projection, stale Anytime plans, and overdue flexible moves that update deadline.
- Fresh subtask state per occurrence and parent completion with unchecked subtasks.

### Data and security tests

- Repository contract tests against fakes and the Firestore Emulator.
- Offline create/edit/complete/reopen/skip/remove, process death, reconnect, replay, duplicate commands, same-document LWW, and concurrent revision fork detection.
- Rules tests proving approved Owner CRUD; Partner access only to explicitly shared current-generation Shared View/Cheers; denial of canonical outcomes, reminders, diagnostics, Private/old-generation Tasks, and Task writes; cross-Pair, outsider, unauthenticated, and unapproved denial; approval-record self-read but no listing or client writes; required query constraints; maximum rule-access-cost batches; and rejection of forged-path, malformed, oversized, and immutable-field writes.
- Projection contract tests prove reminders, exact instants/zones, Subtask checked state, Missed/Skip/Not Doing, corrections, and diagnostics can never enter Shared View documents; Private Tasks contribute no placeholder, count, discrepancy, or activity hint.
- Function tests cover approved and unapproved identities, self-invite, two-sided identity confirmation, expired/replayed code, simultaneous acceptance against membership locks, persisted rate limits, third-member attempts, already-paired Members, simultaneous independent Pairs, cross-Pair isolation, Unpair followed by a new Pair, Unpair races, duplicate/out-of-order Cheer triggers, replacement/tombstone cooldown suppression, invalid presets/epochs/grants, and stale FCM tokens.
- Offline privacy tests cover queued Shared-to-Private changes, an offline Partner's disclosed stale-cache window, convergence after reconnect, a queued Cheer racing privacy revocation or Unpair, and no inheritance by a later Pair.
- Account switching and Unpairing clear cached Partner projections; neither person ever sees another account's Private snapshot.
- Manual export/import covers round trip, corruption, unsupported version, interrupted restore, duplicate replay, non-empty-workspace rejection, exclusion of Partner/Pair/Cheer data, and coercion of stale Shared bindings to Private.

### UI, accessibility, and device tests

- Compose state/render tests for Today section order, maximum-three Focus invariant, editor scope prompts, Needs a decision actions, direct history correction, capped Together sections, read-only/redacted Partner details, fast add defaulting to Only me with the audience control visible before submission at 200% text, two-sided Pairing review, Cheer eligibility/Undo/dismiss/Allow Cheers/mute, exact neutral Unpair copy, device-derived 12/24-hour display changes, and generic offline freshness copy.
- TalkBack semantics/order, 48dp targets, 200% font scale, light/dark contrast, reduced motion, one-handed layouts, and visible alternatives to gestures.
- Exact permission granted/denied/revoked, notification permission denied, offline Done/Later actions, stale alarm suppression, reboot, zone/time change, 12/24-hour preference changes without reminder drift, process death, and APK replacement.
- FCM token rotation, recipient-UID mismatch suppression, duplicate/cooldown Cheer delivery, no push on replacement/tombstone, notification denial, generic lock-screen content, deep-link reauthorization after privacy revocation, and in-app Cheer discovery when push is lost.
- Physical OnePlus drills under battery optimization and Doze.
- Clean-device recovery drill: sign in, hydrate once, show no duplicates, preserve historical snapshots, and rebuild alarms.

## 10. Delivery sequence and first vertical slice

### First vertical slice: one Shared daily Task, end to end

Build a single polished path that proves ownership, privacy, offline behavior, recovery, reminders, and encouragement before broadening features:

1. Create the one-module Compose shell, Duit theme primitives, manual dependency container, development Firebase configuration, Google sign-in, the server-controlled per-UID Member Approval registry, Security Rules, and App Check debug/monitor mode. Production setup may follow after the development slice is proven.
2. Implement Pair Invite create/mutual confirmation, deterministic membership locks, persisted rate limits, Pair hydration, and Unpair in the Emulator. Form two independent Pairs among four approved test accounts; prove cross-Pair access is denied, and reject a concurrent or third Member and an unapproved account.
3. Implement only Daily recurrence, the stable Task/Revision/Occurrence/Completion Epoch model, owner-scoped canonical Firestore repository, current-generation Shared View projection, persistent cache, Today, and minimal Together.
4. Let one Member create a default-Private Daily Task with title, one optional Subtask, and one optional reminder. Explicitly choose **Share with [Partner]**, show it immediately while offline, and show it read-only on the Partner's Together after synchronization.
5. Leave an equivalent Task **Only me** and prove it cannot be read directly, queried, deep-linked, exported by, cached onto a newly hydrated Partner installation, or inferred through a placeholder/count.
6. Complete the Shared Occurrence offline, allow immediate Undo and later direct correction, preserve its canonical definition/Subtask snapshot, and synchronize only a date-level completion into Recent moments. Let the Partner send one eligible preset Cheer offline; deliver it in-app and once through a generic, idempotent FCM notification while proving replacement/tombstone writes do not push again.
7. Schedule and fire the Owner's reminder with Done/Later, including exact-to-inexact fallback and stale-alarm suppression. Never schedule that reminder on the Partner's phone.
8. Reconnect both phones and show honest backup plus generic Together freshness state; then hydrate the Owner on a clean device, rebuild the reminder, and Unpair online through the non-editable cache-revocation state without losing either Member's owned data or leaving cross-account access.

The slice is done only when approved/unapproved and multi-Pair Rules tests plus airplane-mode, process-death, reboot, FCM-loss, privacy-revocation, Unpair/re-pair, and clean-install restore drills pass. Do not postpone privacy, recovery, or reminder behavior to a later architecture phase; they are the architectural proof.

After that slice, add the remaining recurrence variants and exceptions; standalone/flexible/Anytime Tasks; notes and full Subtasks; full Together/Focus/Plan/Tasks surfaces; pause/history/export; then illustration, motion, accessibility, and physical-device hardening.

## 11. Five biggest ways this project could fail

1. **Privacy is enforced in UI but leaks through Firestore.** A missing child rule, unconstrained query, stale Pair binding, or deep link exposes a Private Task. Mitigate with Owner-path authority, explicit Pair-scoped grants, deny-by-default Rules, per-UID Member Approval, and Emulator tests for every path and query.
2. **Calendar trust collapses.** A DST, missing-date, exception, zone-sharing, or series-edit bug creates duplicate or misleading work. Mitigate with a pure recurrence engine, immutable Revisions, deterministic Occurrence IDs, Owner-zone semantics, and property tests before UI breadth.
3. **Recovery is promised but never rehearsed.** Firestore appears synced while a clean phone restores incomplete/duplicated owner data or accidentally imports Partner data. Mitigate with visible backup state, separate owner/Partner hydration, release-blocking clean-device drills, and owner-only export round trips.
4. **Android background delivery feels unreliable.** OxygenOS suppresses local reminders, or Functions/FCM duplicate, delay, or lose Cheers. Mitigate by treating Firestore as Cheer truth, making triggers idempotent, reconciling alarms after every system event, and testing both real phones under Doze and notification denial.
5. **Together becomes surveillance or a second social app.** Overdue exposure, nudges, metrics, chat, or automatic activity alerts turn encouragement into pressure. Mitigate with read-only sharing, Private-by-default Tasks, explicit Share Grants, a separate personal Today, preset one-shot Cheers, no negative activity alerts, and hard v1 scope boundaries.

## 12. Architecture readiness checklist

- [ ] Confirm target phone models and Android versions before device QA; test 12-hour, 24-hour, and live preference changes on each relevant device rather than recording one fixed format.
- [ ] Confirm `com.gaylord.duit` as the application ID, complete the development Firebase setup, and create `memberApprovals/{uid}` records with `approved == true` for the development testers. Production setup and approvals may follow after the development slice is proven.
- [ ] Create and securely duplicate the release signing keystore before registering Firebase SHA fingerprints.
- [ ] Configure Google sign-in, Firestore indexes/rules, Functions, FCM, App Check outside-Play settings, the required Blaze billing account, budget alerts that are understood not to cap spend, quota monitoring, and Emulator Suite tests.
- [ ] Approve the glossary in `CONTEXT.md`, especially Pair, Owner, Visibility, Share Grant, Shared View, Completion Epoch, Cheer, Occurrence, Skip versus Remove, Planned Date versus Deadline, and Miss Policy.
- [ ] Freeze v1 recurrence fixtures and expected results before implementing the engine.
- [ ] Freeze the owner-scoped canonical schema, redacted Shared View contract, membership/invite/rate-limit/Cheer-delivery documents, Completion/Share generation rules, Rules/query/access-call matrix, tombstone policy, and owner-only backup format before writing repositories.
- [ ] Approve Today/Together/Plan/Tasks wireframes, the always-visible audience control, Allow Cheers/dismiss controls, two-sided Pairing confirmation, exact privacy/Unpair/freshness copy, warm botanical tokens, notification permission copy, and all Needs a decision actions.
- [ ] Explicitly accept the offline privacy limit: revocation blocks server reads after sync but cannot erase previously viewed content or a disconnected cache immediately.
- [ ] Define the sync-state truth table and sign-out behavior for pending writes.
- [ ] Script approved/unapproved identity, four-account/two-Pair isolation, pairing, airplane-mode, privacy transition, Unpair/re-pair, FCM-loss/duplication, reboot, timezone, 12/24-hour preference, exact-permission, process-death, APK-update, and clean-install recovery drills.
- [ ] Make recurrence, cross-account Security Rules, Functions, backup/restore, accessibility, and physical-device reminder/FCM tests release gates—not post-MVP polish.

Once those items are settled, an implementer should not need to invent product semantics or architectural policy while coding.
