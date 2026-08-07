# Duit Task Planning

Duit helps two people manage their own days, see what the other chooses to share, and encourage each other without punishment, monitoring, or engagement pressure.

## People and privacy

**Pair**:
The private relationship between exactly two authenticated Member accounts. A Member may belong to at most one active Pair.
_Avoid_: Team, workspace, household

**Member**:
One authenticated, allowlisted Duit account. A Member keeps an independently owned task collection, normally uses one primary phone, and may be unpaired or belong to one active Pair.
_Avoid_: Collaborator, teammate

**Partner**:
The other Member in the current Pair, described from one Member's point of view.
_Avoid_: Follower, observer

**Owner**:
The Member who created and controls a Task. Only the Owner may edit its definition, change its schedule, record or correct its outcome, or delete it.
_Avoid_: Assignee

**Visibility**:
The Task-level choice between Shared and Private. Its current value governs whether the Partner receives the Task's deliberately limited Shared View.
_Avoid_: Permission level

**Shared**:
Visibility that lets the Partner read a Task's Shared View while the Pair and Share Grant are active. New Tasks default to Shared after pairing, but sharing never grants control.
_Avoid_: Collaborative, public

**Share Grant**:
The Pair-bound generation that makes one Task Shared. Making the Task Private revokes that grant; sharing it again creates a new grant so old Pair access and Cheers cannot reappear automatically.
_Avoid_: Role, workspace permission

**Shared View**:
The privacy-limited form of a Shared Task: its title, note, Subtask labels, schedule, current actionable state, and date-level completions recorded during the current Share Grant. It excludes reminders, Focus, checked Subtask progress, negative outcomes, corrections, exact action times/zones, and diagnostics.
_Avoid_: Public copy, activity log

**Private**:
Visibility that restricts a Task and its history to its Owner. Tasks created before pairing remain Private until the Owner deliberately shares them.
_Avoid_: Hidden

**Pair Invite**:
A short-lived, one-use invitation that lets the intended second Member form a Pair while both people are online.
_Avoid_: Team invite, friend request

**Cheer**:
One lightweight preset expression of support a Member may attach to the Partner's Shared Task or Completion Record. A Cheer is not a message, comment, reminder, or demand.
_Avoid_: Poke, reaction score

## Language

**Task**:
A stable piece of work the user may intend, plan, or commit to doing. A Task is either a Standalone Task or a Recurring Series.
_Avoid_: Todo, item

**Standalone Task**:
A task that does not repeat. It may be fixed to a date, flexible until a deadline, or available at any time.
_Avoid_: One-off instance

**Recurring Series**:
A continuing task whose recurrence rule produces expected occurrences.
_Avoid_: Repeating task definition, recurring task instance

**Series Revision**:
The definition of a recurring series that applies from a particular nominal date onward. A later revision supersedes it prospectively without changing earlier history.
_Avoid_: Edited series, current rule

**Task Revision**:
The definition of a Standalone Task at a point in its history. A later revision changes the current Task without rewriting an earlier completion or commitment.
_Avoid_: Edited task

**Recurrence Rule**:
The calendar pattern that determines which nominal dates belong to a recurring series.
_Avoid_: Cron, RRULE

**Occurrence**:
One expected performance of a recurring series on a nominal date. Moving or overriding it does not change which occurrence it is.
_Avoid_: Copy, generated task

**Nominal Date**:
The local calendar date on which a recurrence rule originally produces an occurrence.
_Avoid_: Original timestamp

**Effective Date**:
The local calendar date on which an occurrence is currently intended to be done after any one-off move.
_Avoid_: Due date

**Planned Date**:
An optional local date on which the user intends to attempt a flexible or anytime task. It is not a commitment and may be changed or cleared.
_Avoid_: Due date, deadline

**Deadline**:
The last local date by which a flexible task is intended to be completed.
_Avoid_: Planned date

**Schedule Exception**:
A deliberate change to one occurrence or a bounded span without changing the recurring series for all future dates.
_Avoid_: Series edit

**Added Occurrence**:
A Schedule Exception that creates one expected Occurrence on a date the Recurrence Rule would not otherwise produce. It never duplicates an existing Occurrence for the same Nominal Date.
_Avoid_: Extra task

**Pause Span**:
A date interval during which a recurring series is intentionally inactive. Expected dates within it count as skipped and are summarized together in history.
_Avoid_: Disabled series

## Outcomes

**Complete**:
An outcome stating that the task or occurrence was done, including when it was recorded and the subtask state at that time.
_Avoid_: Success

**Completion Record**:
The current historical record of a Complete outcome, including its task-definition snapshot, completion time, observed local date and zone, and Subtask state. It may be corrected directly without changing the historical definition snapshot.
_Avoid_: Completion event log

**Completion Epoch**:
The stable generation of one Complete outcome. Correcting its time or Subtask state preserves the epoch; leaving Complete invalidates it, and completing again creates a new epoch.
_Avoid_: Completion count, streak

**Skip**:
A visible, neutral outcome stating that an expected occurrence was intentionally not done.
_Avoid_: Fail, delete

**Remove Occurrence**:
A schedule exception stating that an occurrence should not have been expected. It is hidden from ordinary history.
_Avoid_: Skip

**Missed**:
A neutral derived outcome for an occurrence whose series is allowed to let unfinished occurrences pass.
_Avoid_: Failed, overdue

**Not Doing**:
An explicit terminal resolution of an outstanding Standalone Task that the user no longer intends to complete. A Recurring Series uses Skip for one Occurrence or End Series for its future.
_Avoid_: Failure

**Archive**:
The lifecycle state that hides a Task from active views while preserving its history. Archiving an active Recurring Series also ends its future Occurrences.
_Avoid_: Delete

**Miss Policy**:
The per-series choice between letting an unfinished occurrence become Missed and keeping it open until the user resolves it.
_Avoid_: Strictness

## Experience

**Today**:
The home destination showing the current local day, its Focus tasks, planned work, unresolved commitments, available flexible work, and completed work.
_Avoid_: App name, dashboard

**Together**:
The destination showing the Partner's Shared Tasks and recent Shared completions without mixing them into the Member's own Today agenda.
_Avoid_: Feed, leaderboard, shared Today

**Focus**:
Up to three tasks the user chooses to emphasize for the current local day. Focus adds no deadline, score, or obligation.
_Avoid_: Priority, goal

**Needs a Decision**:
The calm holding area for commitments whose dates have passed but which still require completion, rescheduling, skipping, or abandonment.
_Avoid_: Failure list, backlog

**Anytime**:
The pool of tasks with no deadline and no current planned date.
_Avoid_: Backlog

**Subtask Template**:
The checklist structure copied into each new occurrence of a recurring series. Checked state never carries from one occurrence to the next.
_Avoid_: Shared checklist
