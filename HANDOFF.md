# Duit implementation handoff

Snapshot date: 2026-11-15 (Asia/Kolkata)

This document is for a fresh implementation chat continuing the GitHub issue backlog. Verify the snapshot commands below before editing because GitHub Issues remain the specification and dependency source of truth.

## Start here

1. Read `AGENTS.md`, `CONTEXT.md`, `docs/agents/domain.md`, the relevant ADRs under `docs/adr/`, and the full GitHub issue including comments.
2. Use the `$implement` workflow and TDD at the public seam named by the issue. Use parallel sub-agents where file ownership is separable.
3. Work only against the development environment. Do not provision, configure, deploy, or add credentials for production without renewed user authorization.
4. Respect each issue's `Blocked by` section. Most downstream issues are labelled `ready-for-agent` even while their dependencies remain open.
5. Keep `#1` open as the umbrella. Comment evidence on each implemented issue and close it only after every acceptance criterion is met.

The user does not currently need to verify anything before work resumes. Stop for them only when an external decision or human-owned setup is actually required.

## Repository snapshot

- Repository: `Nitish-bot/duit`
- Workspace: use the repository root supplied by the current Codex task.
- Branch: `main`
- Implementation baseline: `273fb2ed08dcf96e297d0c3c41c18c3e0bf2179f`
- The implementation worktree was clean at the baseline above. This handoff refresh is the intended subsequent documentation commit.
- `main` says `[origin/main: gone]`; do not assume these commits exist remotely. Inspect the remote before pushing, and do not push unless the user asks.

Recent commits, newest first:

```text
273fb2e fix: hide raw text when the Pairing backend is unavailable (#7)
c3c9b2b fix: let the repository harness start when the dev app is absent (#6)
aaea9f3 test: keep the emulator correction fixture in the past (#6)
b5d6dcc docs: refresh implementation handoff after #6 and #7
6a8cbe5 feat: form an optional Pair from Together (#7)
0fc5c01 feat: complete, undo, and correct Daily Occurrences (#6)
eaa29cc docs: add implementation handoff
fdd68bb test: align Pairing fixture terminology (#7)
a1c930a refactor: align Pairing domain terminology (#7)
b515902 feat: add transactional Pairing backend core (#7)
7ced9e9 feat: create private Daily Tasks offline (#5)
9e8f7e5 test: harden physical Android acceptance harness
5f0450b feat: add approved Member workspaces and private Tasks (#2, #4)
97ce08c feat: bootstrap executable Duit workspace (#3)
```

Tracker state:

- Closed: #2, #3, #4, #5.
- #6 is closed. Device evidence: `connectedDevDebugAndroidTest` 39 tests, 0 failed; the Firestore repository harness passed 7/7 twice.
- #7 device acceptance is done (2026-11-17). Pairing moved from Cloud Functions to a Supabase Edge Function on the free plan (ADR-0007; Firestore, Auth, Rules and App Check are unchanged) and is deployed to the dev Supabase project. The two-phone drill passed: an unknown code and accepting one's own invite were refused, and an invite created on the Nothing Phone (4a) was accepted on the OnePlus with mutual confirmation, leaving both phones in an active Pair. Evidence: [issue #7 comment](https://github.com/Nitish-bot/duit/issues/7#issuecomment-6950024642). #7 is still open; closing it is the user's call. #8 (previously blocked by #7) has its Android and Rules half implemented (see "Shared Daily View"), awaiting the two-phone drill; #10 (Cheer delivery; no Firestore triggers off Cloud Functions, so it becomes a client-invoked edge call, design in #10) follows #8.
- Watch item: Supabase pauses free projects after about a week of low activity. A paused project returns errors until resumed by hand in the dashboard, and Android shows the connection-required state. Check it is awake before any device session, and revisit hosting before production (#34, #36).
- The dev build was installed on the test phone on 2026-11-15 and launches without a crash; Together was not exercised (no sign-in).
- Dependency-unblocked next: #9.
- #1 remains the open umbrella.
- #35 and #36 are human-owned. #34 and all production work are outside the current dev-only authorization.

Useful links:

- [Issue #5 completion evidence](https://github.com/Nitish-bot/duit/issues/5#issuecomment-5256141571)
- [Issue #7 backend handoff comment](https://github.com/Nitish-bot/duit/issues/7#issuecomment-5256152161)

## Product and domain constraints

Use the canonical terms in `CONTEXT.md` in code and tests.

- A Member is an administrator-approved individual with an independently owned workspace. Authentication alone does not make an identity a Member.
- Pairing is optional. A Pair contains exactly two distinct approved Members; many independent Pairs may coexist; a Member may belong to at most one active Pair.
- A Pair is an access relationship, not shared Task ownership. Pairing alone never grants Task reads.
- Every Task starts Private. Only an explicit Owner action may publish a redacted Shared View to the current Partner.
- A Partner never mutates canonical Owner Task state.
- Firestore is the only durable Task store. Its Android persistent cache is the offline store; do not introduce Room or a second synchronization layer.
- One primary phone per Member is the conflict model. Mutable occurrence state is whole-document last-write-wins; immutable definition revisions preserve history.
- Store semantic `LocalDate`, `LocalTime`, zone, and instant values. Each device derives its own local day and 12/24-hour presentation; display preference changes must never change stored time or duplicate work.
- Production is deliberately unavailable. The current `prod` flavor has no Firebase configuration and must remain untouched.

## What currently works

### Authentication and private workspace

- Dev Google sign-in, per-UID Member Approval, locked hydration, safe sign-out, and owner-isolated Firestore Rules are implemented.
- An approved unpaired Member can use the complete current private workspace.
- Approval documents are administrator-created; clients can read only their own and cannot list or mutate them.

### Private Tasks and Daily recurrence

- A hydrated Owner can create a Private standalone Task or Private Daily Recurring Series offline.
- Daily creation accepts a normalized title and one optional Subtask Template.
- Today derives Occurrences; it does not materialize an infinite occurrence collection.
- Occurrence identity is `(taskId, nominalDate)`. A new local day creates one fresh Occurrence with unchecked Subtask state.
- Firestore persistence is owner-scoped and reconstructs from queued cache or clean server hydration without replacing stable identity.
- `RecurrenceEngine` is the pure calendar seam; `DuitWorkspace` is the primary product-behavior seam.

Relevant files:

- `app/src/main/java/com/gaylord/duit/workspace/DuitWorkspace.kt`
- `app/src/main/java/com/gaylord/duit/domain/recurrence/RecurrenceEngine.kt`
- `app/src/dev/java/com/gaylord/duit/platform/firebase/FirestoreTaskRepository.kt`
- `app/src/main/java/com/gaylord/duit/feature/today/TodayScreen.kt`

### Daily Occurrence outcomes (#6)

- Complete, Undo, and correct are `DuitWorkspace` commands.
- Interacted state is one whole-document Occurrence State under the Task's `occurrenceStates/{nominalDate}`.
- Correction preserves the Completion Epoch; leaving Complete invalidates it, and completing again creates a distinct epoch.
- Future Nominal Dates and future completion times are rejected.
- Today and Tasks are driven by `TodayViewModel` and `TasksViewModel`.

### Shared Daily View (#8, Android and Rules done; two-phone drill pending)

- Rules: `pairShares/{pairId}/owners/{ownerUid}/tasks/{taskId}` plus `.../grants/{generation}/completions/{date}` (committed earlier, covered by the Firestore Rules emulator tests). The Owner writes and deletes; only the current active Partner reads.
- Domain: `TaskVisibility.Shared(pairIdentifier, shareGeneration, partnerIdentifier)` (the Partner id is stored so offline completion mirroring does not need a live Pairing call), `SharedDailyView` (title, Subtask labels, every-N-days, start/end, `sharedOn`; no reminders, Focus, checked state, negative outcomes, corrections, or times/zones), `PartnerSharedDailyTask` (view plus date-only completions).
- `DuitWorkspace`: `ShareDailyTaskWithPartner` (needs an ACTIVE status with a known Partner id and a gateway; the command id is the new `ShareGeneration`), `MakeTaskPrivate`, date-only mirroring on Complete / Undo / correct-to-actionable, `observePartnerSharedTasks()`. Every Task still starts Private. Time-only completion corrections mirror nothing.
- Offline order: sharing writes the Owner's visibility first, then publishes; going Private revokes remotely first, then flips local visibility (in a `finally`), so a crash between the two writes never leaves a published view the Owner cannot see. Remote writes are enqueued in Firestore's offline queue and not awaited.
- `FirestoreSharedViewGateway` and `SharedViewCodec` live in the dev flavor only; the prod flavor has no gateway.
- The Pairing backend now returns `partnerUid` in ACTIVE status. **Redeploy the `pairing` Edge Function** (`supabase functions deploy pairing --use-api`) before the drill; until then Android reports sharing as unavailable (Active status without a Partner id).
- UI: Tasks detail has Share with Partner / Make Private (share only when paired; Make Private always when Shared) with honest stale-cache copy; Together ActivePair lists the Partner's Shared Tasks read-only. No Cheers (#10).
- Remaining for #8: the two-phone drill (share, Partner sees it, complete/undo, Make Private while the Owner is offline then reconnect, re-share shows no old completions), and re-verifying that a former Partner cannot read after Unpair (Unpair itself is not built yet; a re-pair gets a fresh generation by construction).

### Pairing (#7, device acceptance done)

Shared implementation: `supabase/functions/_shared/pairing.ts`

Callable contract in region `us-central1`:

```text
createPairInvite({})       -> { code, expiresAtEpochMillis }
acceptPairInvite({ code }) -> { pairId, partnerIdentity, state }
confirmPair({ pairId })    -> status with both confirmation flags
getPairingStatus({})       -> UNPAIRED | PENDING_CONFIRMATION | ACTIVE
```

Implemented guarantees:

- Google-provider authentication plus active Member Approval.
- 15-minute, one-use codes stored only by SHA-256 hash.
- Both identities remain visible only to the two participants through callable results.
- Both Members explicitly confirm before activation.
- Transactional `/pairMemberships/{uid}` locks enforce exclusivity across both concurrency-race shapes.
- Self, malformed, expired, replayed, unapproved, already-paired, and third-Member attempts fail closed.
- Persistent invite-creation limits and failed-code cooldowns.
- Expired pending Pairs tombstone and release only matching membership locks when `getPairingStatus` or `confirmPair` observes the expiry; cleanup is intentionally lazy rather than scheduled.
- Multiple independent active Pairs remain isolated.

Server-only collections are `/pairs`, `/pairMemberships`, `/pairInvites`, and `/inviteRateLimits`. Firestore Rules intentionally deny clients direct access. Do not make pending Pair documents client-readable; `getPairingStatus` is the participant-scoped identity/status seam.

Android and development deployment pieces:

- Android gateway and `PairCommand` handling in `DuitWorkspace`, kept orthogonal to workspace access.
- `TogetherViewModel` and `TogetherScreen` cover the full Together state set with lifecycle-bound polling.
- Dev-only Pairing client (Firebase callable protocol by URL, `getHttpsCallableFromUrl`) and App Check debug provider (dev flavor only). The URL comes from `duit.pairingBackendUrl` in the ignored `local.properties` or `-Pduit.pairingBackendUrl`; empty means an unavailable backend, and CI needs the property if it should exercise Pairing.
- The server handler is `supabase/functions/_shared`, deployed as the `pairing` Edge Function (always `supabase functions deploy pairing` by name). Firestore Rules for every environment remain the approval-gated `firestore.rules`; `firebase.json` ships no Functions.
- Partner identity is taken from the Google provider record, not editable profile claims.

`functions/emulator/src/server.ts` is a Node host for the same handler with App Check disabled, because Firebase has no App Check emulator; the deployed `pairing` entry always enforces it. `supabase/functions/pairing-local` serves the App Check-free handler on the Deno runtime for local runs and must never be deployed.

## Immediate next work

1. Start #9 (the only dependency-unblocked issue).
2. Close #7 if the user agrees (evidence is on the issue), then start #8; #10 follows #8. The setup that made #7 pass is in the README's "Pairing backend (Supabase Edge Function) and App Check": `pairing` is deployed with `supabase functions deploy pairing --use-api`, `FIREBASE_SERVICE_ACCOUNT` is set, `duit.pairingBackendUrl` is in `local.properties`, and App Check debug tokens are registered for both phones (a call with no App Check token returns UNAUTHENTICATED). The dev app is still "Not registered" for Play Integrity; debug tokens work without it.

Decision note: development self-admission was drafted and deliberately dropped on 2026-11-14. Member Approval stays administrator-created in every environment (ADR-0006). If `firestore.development.rules` was ever deployed to the development project, redeploy `firestore.rules` with `firebase.json`.

Run Firebase emulator suites serially. The normal Functions suite and dedicated Android repository harness contend for hub/websocket ports even though the repository Firestore instance uses port 8081.

## Dependency-aware backlog order

Do not choose by label alone. Use this execution graph and re-read every issue before starting:

1. #6 and remaining #7 in parallel.
2. After #6, start #9. After both #6 and #7, start #8; #8 and #9 can run in parallel.
3. #8 -> #10.
4. After #8, #9, and #10: #11 -> #12 -> #13.
5. After #13, split into two branches:
   - Recurrence: #14 -> #15 -> #16; then #17 and #18 in parallel; #17 -> #19 -> #20.
   - Standalone planning: #21 -> #22 -> #23.
6. After #20 and #23: #24.
7. Then #25, #26, and #27 in parallel.
8. After #27: #28 -> #29, while #30 can run in parallel.
9. After #28, #29, and #30: #31 -> #32.
10. After #25 through #30 and #32: #33.
11. #34 requires #33 plus human-owned production issue #36. #35 is human-owned and follows #34.

#13 is the reference milestone. Do not broaden Task types before its privacy, recovery, reminder, Cheer, Unpair, multi-account, and physical-device drills pass.

## Development environment

Development Firebase:

- The development project ID is available from the ignored `app/src/dev/google-services.json`; do not copy project-specific configuration into tracked files.
- Android package: `com.gaylord.duit.dev`
- `app/src/dev/google-services.json` exists and is intentionally ignored.
- Google sign-in, Firestore, debug SHA-1/SHA-256 certificates, and Firestore Rules are configured in dev.
- Two Google identities are approved Members in dev; a third authenticated identity is intentionally unapproved for rejection tests. Do not record their emails or UIDs in commits/issues.
- The Pairing Edge Function and App Check are not deployed/configured for Android acceptance yet.
- Production remains untouched.

Local tools:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH" # the emulator suite needs JDK 21 on PATH
source ~/.nvm/nvm.sh && nvm use system # Node 22; plain `nvm use` fails on this machine
```

Physical phone at handoff:

- OnePlus CPH2467, Android 15 (labelled "OnePlus DN2101" in the Firebase console). A second device, a Nothing Phone (4a, model A069), is also registered for App Check and was used in the two-phone drill.
- adb executable: `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`
- Connected and authorized over USB on 2026-11-17.
- Resolve the current serial with `adb devices -l`; do not commit hardware identifiers.
- `stay_on_while_plugged_in=7`; **Keep screen on while charging** is sufficient for instrumentation.
- No stale `adb reverse` mappings remained.
- Instrumentation may have removed the dev APK; run `./gradlew :app:installDevDebug` before manual acceptance and do not assume any account remains signed in.

USB access and local emulator port binding may require sandbox escalation. Keep the phone unlocked during connected tests.

## Verification commands and last known results

Focused Android domain work:

```sh
./gradlew :app:testDevDebugUnitTest --tests com.gaylord.duit.workspace.DuitWorkspaceTest
```

Broad Android gate used after #5:

```sh
./gradlew test \
  :app:lintDevDebug :app:lintProdDebug \
  :app:assembleDevDebug :app:assembleProdDebug \
  :app:compileDevDebugAndroidTestKotlin \
  :app:compileProdDebugAndroidTestKotlin
```

Last result (2026-11-17, after the Supabase move): `BUILD SUCCESSFUL`, 90 devDebug and 79 prodDebug unit tests, 0 failures.

Connected UI:

```sh
./gradlew :app:connectedDevDebugAndroidTest
```

Last result (2026-11-17, OnePlus CPH2467): 39 passed, 0 failed, 7 skipped by design (the repository-only emulator tests).

Physical-device Firestore repository conformance:

```sh
npm --prefix functions run test:android-repository
```

Last known result (before #6 and #7): 5/5, including offline creation, termination, cleared persistent cache, server hydration, and repository reconstruction. NOT run on 2026-11-14.

Functions and Rules:

```sh
npm --prefix functions run typecheck
npm --prefix functions test
```

Last result (2026-11-17): typecheck passed, boundary tests 12/12, Auth + Firestore emulator suite with the Node Pairing host 28/28 (19 Pairing cases); the same 19 Pairing cases also passed 19/19 against `supabase functions serve` (Deno Edge runtime) via `pairing-local`. Android `./gradlew test :app:lintDevDebug :app:lintProdDebug :app:assembleDevDebug :app:assembleProdDebug` passed (90 devDebug and 79 prodDebug unit tests). Connected and physical-device suites were NOT run on 2026-11-17 (no device attached).

The emulator suite uses ports 8080/9099 and 5002 for the Pairing host, plus hub/logging/websocket ports 4400/4500/9150. Do not run it concurrently with another Firebase emulator harness.

## Known non-blocking design debt

The final review found no remaining hard standards violations. It recorded three judgment-call refactor opportunities; do not let them distract from the vertical slice unless the touched code makes the cleanup cheap:

- `AgendaTask` represents Standalone and Recurring variants using coupled nullable fields. A sealed variant model would make invalid combinations unrepresentable.
- The Daily creation callback tuple is threaded through `TodayScreen`, `DuitShell`, `DuitApp`, and `MainActivity`; a small task-creation action/request boundary could reduce shotgun edits.
- `FirestoreTaskRepository` duplicates some standalone/recurring listener and cache-read structure; extract only if the helper remains domain-readable.
- Correcting a past completion to "not done" leaves that Occurrence with no screen to complete it again until Needs a Decision (#19) exists.
- `DuitWorkspace.pairingStatus` has no production consumer yet.
- The in-memory `TaskRepository` test double is duplicated across four test files.

## Completion discipline

For each issue:

1. Establish a failing test at the agreed public seam.
2. Implement the smallest vertical slice, preserving offline-first behavior and privacy.
3. Run targeted tests regularly and the broad suite once at the end.
4. Run the two-axis Standards/Spec review against the pre-issue commit; fix hard findings and distinguish optional smells from spec gaps.
5. Commit with the issue number, post acceptance evidence, remove the triage label, and close only when complete.
6. Keep the worktree clean and preserve ignored dev Firebase configuration.
