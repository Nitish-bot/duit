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
- #7 stays open and blocked. On 2026-11-15 the user decided not to enable Blaze on the development project, so the `development-only` Functions codebase cannot be deployed and real two-account acceptance cannot run. #8 (blocked by #7) and #10 (Cheer delivery via Functions/FCM) are blocked with it until that changes or ADR-0005 is revisited.
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

### Pairing (#7, awaiting device acceptance)

Shared implementation: `functions/src/pairing.ts`

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
- Dev-only Functions client and App Check debug provider (dev flavor only).
- `functions/development` and `firebase.development.json` deploy Functions only; Firestore Rules for every environment remain the approval-gated `firestore.rules`.
- Partner identity is taken from the Google provider record, not editable profile claims.

`functions/emulator/src/index.ts` exports the callables with App Check disabled because Firebase has no App Check emulator. `functions/src/index.ts` is intentionally empty, and the production-boundary tests require it to stay that way until a deliberate deploy boundary exists.

## Immediate next work

1. Start #9 (the only dependency-unblocked issue).
2. #7 is blocked: it needs Blaze on the development project (declined 2026-11-15) or a revisit of ADR-0005. If that changes, deploy with the README's "Development Functions and App Check" command, register the App Check debug token, then run the two-account drill (create, enter, mutual identity review, both confirmations, active Together on both phones, rejection paths). Until then #8 and #10 stay blocked.

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
- Functions and App Check are not deployed/configured for Android acceptance yet.
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

- OnePlus DN2101, Android 13
- adb executable: `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`
- Connected and authorized over USB when last checked.
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

Last result (2026-11-14): `BUILD SUCCESSFUL`, 182 tasks, 87 devDebug unit tests, 0 failures.

Connected UI:

```sh
./gradlew :app:connectedDevDebugAndroidTest
```

Last known result (before #6 and #7): 17 passed; the five repository-only tests skipped as intended. NOT run on 2026-11-14.

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

Last result (2026-11-14): typecheck passed, boundary tests 9/9, Auth + Functions + Firestore emulator suite 28/28. Connected and physical-device suites were NOT run on 2026-11-14.

The Functions emulator suite uses ports 5001/8080/9099 plus hub/logging/websocket ports 4400/4500/9150. Do not run it concurrently with another Firebase emulator harness.

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
