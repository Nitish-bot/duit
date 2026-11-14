package com.gaylord.duit.feature.today

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gaylord.duit.R
import com.gaylord.duit.designsystem.BotanicalMark
import com.gaylord.duit.feature.common.completionLocalTime
import com.gaylord.duit.feature.common.completionTimeFormatter
import com.gaylord.duit.feature.common.parseCompletionTime
import com.gaylord.duit.feature.common.withCorrectedLocalTime
import com.gaylord.duit.domain.model.CompletionEpoch
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.OccurrenceOutcome
import com.gaylord.duit.domain.model.OccurrenceSubtaskState
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.workspace.AgendaOccurrence
import com.gaylord.duit.workspace.AgendaSubtask
import com.gaylord.duit.workspace.AgendaTask
import com.gaylord.duit.workspace.CommandIdentifier
import com.gaylord.duit.workspace.PersonalAgenda
import java.time.LocalDate
import java.util.UUID

sealed interface TodayOccurrenceAction {
    data class Complete(
        val commandIdentifier: CommandIdentifier,
        val occurrenceKey: OccurrenceKey,
        val checkedSubtaskIdentifiers: Set<SubtaskIdentifier>,
    ) : TodayOccurrenceAction

    data class Undo(
        val occurrenceKey: OccurrenceKey,
        val completionEpoch: CompletionEpoch,
    ) : TodayOccurrenceAction

    data class CorrectCompletion(
        val occurrenceKey: OccurrenceKey,
        val completionEpoch: CompletionEpoch,
        val correctedCompletedAt: MemberActionMoment,
        val checkedSubtaskIdentifiers: Set<SubtaskIdentifier>,
    ) : TodayOccurrenceAction

    data class CorrectToActionable(
        val occurrenceKey: OccurrenceKey,
        val completionEpoch: CompletionEpoch,
        val checkedSubtaskIdentifiers: Set<SubtaskIdentifier>,
    ) : TodayOccurrenceAction

    data class SetSubtaskChecked(
        val occurrenceKey: OccurrenceKey,
        val subtaskIdentifier: SubtaskIdentifier,
        val isChecked: Boolean,
    ) : TodayOccurrenceAction
}

@Composable
fun TodayScreen(
    modifier: Modifier = Modifier,
    state: TodayUiState = TodayUiState(),
    use24HourTime: Boolean? = null,
    onEvent: TodayEventHandler = {},
) {
    var taskTitle by rememberSaveable { mutableStateOf("") }
    var taskSubtask by rememberSaveable { mutableStateOf("") }
    var taskRepeat by rememberSaveable { mutableStateOf(TodayTaskRepeat.Never) }
    var pendingCommandIdentifier by rememberSaveable { mutableStateOf<String?>(null) }
    var consumedTaskAdditionSuccessKey by rememberSaveable { mutableStateOf(0L) }
    var doneTodayExpanded by rememberSaveable { mutableStateOf(false) }
    var correctionTaskIdentifier by rememberSaveable { mutableStateOf<String?>(null) }
    var correctionNominalDate by rememberSaveable { mutableStateOf<String?>(null) }
    var observedCorrectionSuccessKey by rememberSaveable {
        mutableStateOf(state.correctionSuccessKey)
    }
    val shouldUse24HourTime = use24HourTime ?: DateFormat.is24HourFormat(LocalContext.current)
    val normalizedTitle = taskTitle.trim()

    LaunchedEffect(state.taskAdditionSuccess) {
        val success = state.taskAdditionSuccess ?: return@LaunchedEffect
        if (success.key <= consumedTaskAdditionSuccessKey) return@LaunchedEffect

        if (pendingCommandIdentifier == success.commandIdentifier.value) {
            taskTitle = ""
            taskSubtask = ""
            taskRepeat = TodayTaskRepeat.Never
            pendingCommandIdentifier = null
        }
        consumedTaskAdditionSuccessKey = success.key
    }

    LaunchedEffect(state.correctionSuccessKey) {
        if (state.correctionSuccessKey > observedCorrectionSuccessKey) {
            correctionTaskIdentifier = null
            correctionNominalDate = null
            observedCorrectionSuccessKey = state.correctionSuccessKey
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = 24.dp,
            end = 24.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            TodayHeading()
        }

        state.completionFeedback?.let { feedback ->
            item(key = "completion-feedback") {
                CompletionFeedbackCard(
                    feedback = feedback,
                    enabled = !state.isCommandPending,
                    onUndo = {
                        onEvent(
                            TodayEvent.Occurrence(
                                TodayOccurrenceAction.Undo(
                                    occurrenceKey = feedback.occurrenceKey,
                                    completionEpoch = feedback.completionEpoch,
                                ),
                            ),
                        )
                    },
                )
            }
        }

        item {
            AddPrivateTaskCard(
                title = taskTitle,
                onTitleChange = {
                    if (it != taskTitle) pendingCommandIdentifier = null
                    taskTitle = it
                },
                subtask = taskSubtask,
                onSubtaskChange = {
                    if (it != taskSubtask) pendingCommandIdentifier = null
                    taskSubtask = it
                },
                repeat = taskRepeat,
                onRepeatChange = {
                    if (it != taskRepeat) pendingCommandIdentifier = null
                    taskRepeat = it
                },
                enabled = !state.isCommandPending,
                canAdd = normalizedTitle.isNotEmpty() && !state.isCommandPending,
                onAdd = {
                    val submittedTitle = normalizedTitle
                    val commandIdentifier =
                        pendingCommandIdentifier
                            ?.let(::CommandIdentifier)
                            ?: CommandIdentifier(UUID.randomUUID().toString()).also { identifier ->
                                pendingCommandIdentifier = identifier.value
                            }
                    val submittedSubtask = taskSubtask.trim().takeIf(String::isNotEmpty)
                    when (taskRepeat) {
                        TodayTaskRepeat.Never ->
                            onEvent(
                                TodayEvent.AddPrivateTaskToToday(
                                    commandIdentifier = commandIdentifier,
                                    title = submittedTitle,
                                ),
                            )
                        TodayTaskRepeat.Daily ->
                            onEvent(
                                TodayEvent.AddPrivateDailyTaskToToday(
                                    commandIdentifier = commandIdentifier,
                                    title = submittedTitle,
                                    subtask = submittedSubtask,
                                ),
                            )
                    }
                },
            )
        }

        if (state.personalAgenda.tasks.isEmpty() && state.personalAgenda.doneToday.isEmpty()) {
            item {
                TodayEmptyState()
            }
        } else {
            items(
                items = state.personalAgenda.tasks,
                key = { task -> task.identifier.value },
            ) { task ->
                AgendaTaskCard(
                    task = task,
                    enabled = !state.isCommandPending,
                    onSubtaskChecked = { occurrenceKey, subtaskIdentifier, isChecked ->
                        onEvent(
                            TodayEvent.Occurrence(
                                TodayOccurrenceAction.SetSubtaskChecked(
                                    occurrenceKey = occurrenceKey,
                                    subtaskIdentifier = subtaskIdentifier,
                                    isChecked = isChecked,
                                ),
                            ),
                        )
                    },
                    onComplete = { occurrence, checkedSubtaskIdentifiers ->
                        onEvent(
                            TodayEvent.Occurrence(
                                TodayOccurrenceAction.Complete(
                                    commandIdentifier =
                                        CommandIdentifier(UUID.randomUUID().toString()),
                                    occurrenceKey = occurrence.key,
                                    checkedSubtaskIdentifiers = checkedSubtaskIdentifiers,
                                ),
                            ),
                        )
                    },
                )
            }
        }

        if (state.personalAgenda.doneToday.isNotEmpty()) {
            item(key = "done-today-heading") {
                DoneTodayHeading(
                    count = state.personalAgenda.doneToday.size,
                    expanded = doneTodayExpanded,
                    onToggle = { doneTodayExpanded = !doneTodayExpanded },
                )
            }
            if (doneTodayExpanded) {
                items(
                    items = state.personalAgenda.doneToday,
                    key = { task -> "done-${task.identifier.value}" },
                ) { task ->
                    CompletedAgendaTaskCard(
                        task = task,
                        enabled = !state.isCommandPending,
                        onCorrect = {
                            correctionTaskIdentifier = task.identifier.value
                            correctionNominalDate = task.occurrence?.nominalDate?.toString()
                        },
                    )
                }
            }
        }

    }

    val correctionTask =
        state.personalAgenda.doneToday.firstOrNull { task ->
            task.identifier.value == correctionTaskIdentifier &&
                task.occurrence?.nominalDate?.toString() == correctionNominalDate
        }
    correctionTask?.let { task ->
        CompletionCorrectionDialog(
            task = task,
            use24HourTime = shouldUse24HourTime,
            enabled = !state.isCommandPending,
            onDismiss = {
                correctionTaskIdentifier = null
                correctionNominalDate = null
            },
            onAction = { action ->
                onEvent(TodayEvent.Occurrence(action))
            },
        )
    }
}

@Composable
private fun TodayHeading() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.destination_today),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = stringResource(R.string.today_intro),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun AddPrivateTaskCard(
    title: String,
    onTitleChange: (String) -> Unit,
    subtask: String,
    onSubtaskChange: (String) -> Unit,
    repeat: TodayTaskRepeat,
    onRepeatChange: (TodayTaskRepeat) -> Unit,
    enabled: Boolean,
    canAdd: Boolean,
    onAdd: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TodayTaskTitleInputTag),
                enabled = enabled,
                label = { Text(stringResource(R.string.today_task_title_label)) },
                supportingText = { Text(stringResource(R.string.today_task_private_note)) },
                singleLine = true,
            )
            Text(
                text = stringResource(R.string.today_task_repeat_label),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterChip(
                    selected = repeat == TodayTaskRepeat.Never,
                    onClick = { onRepeatChange(TodayTaskRepeat.Never) },
                    label = { Text(stringResource(R.string.today_task_repeat_never)) },
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TodayTaskRepeatNeverTag),
                )
                FilterChip(
                    selected = repeat == TodayTaskRepeat.Daily,
                    onClick = { onRepeatChange(TodayTaskRepeat.Daily) },
                    label = { Text(stringResource(R.string.today_task_repeat_daily)) },
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TodayTaskRepeatDailyTag),
                )
            }
            if (repeat == TodayTaskRepeat.Daily) {
                OutlinedTextField(
                    value = subtask,
                    onValueChange = onSubtaskChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TodayTaskSubtaskInputTag),
                    enabled = enabled,
                    label = { Text(stringResource(R.string.today_task_subtask_label)) },
                    supportingText = {
                        Text(stringResource(R.string.today_task_subtask_optional_note))
                    },
                    singleLine = true,
                )
            }
            Button(
                onClick = onAdd,
                enabled = canAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.today_add_task))
            }
        }
    }
}

internal const val TodayTaskTitleInputTag = "today-private-task-title"
internal const val TodayTaskSubtaskInputTag = "today-private-task-subtask"
internal const val TodayTaskRepeatNeverTag = "today-private-task-repeat-never"
internal const val TodayTaskRepeatDailyTag = "today-private-task-repeat-daily"

private enum class TodayTaskRepeat {
    Never,
    Daily,
}

@Composable
private fun AgendaTaskCard(
    task: AgendaTask,
    enabled: Boolean,
    onSubtaskChecked: (
        OccurrenceKey,
        SubtaskIdentifier,
        Boolean,
    ) -> Unit,
    onComplete: (AgendaOccurrence, Set<SubtaskIdentifier>) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = task.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            task.occurrence?.let { occurrence ->
                val checkedSubtasks =
                    occurrence.subtasks
                        .filter(AgendaSubtask::isChecked)
                        .map(AgendaSubtask::identifier)
                        .toSet()
                Text(
                    text = stringResource(R.string.today_daily_occurrence),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                occurrence.subtasks.forEach { subtask ->
                    val isChecked = subtask.identifier in checkedSubtasks
                    val toggleSubtask = {
                        onSubtaskChecked(
                            occurrence.key,
                            subtask.identifier,
                            !isChecked,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled, onClick = toggleSubtask)
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange =
                                if (enabled) {
                                    { toggleSubtask() }
                                } else {
                                    null
                                },
                            modifier = Modifier.testTag(
                                "$TodayActiveSubtaskTagPrefix${subtask.identifier.value}",
                            ),
                        )
                        Text(
                            text =
                                stringResource(
                                    if (isChecked) {
                                        R.string.today_checked_subtask
                                    } else {
                                        R.string.today_unchecked_subtask
                                    },
                                    subtask.label,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Button(
                    onClick = { onComplete(occurrence, checkedSubtasks) },
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.today_complete_occurrence))
                }
            }
        }
    }
}

@Composable
private fun CompletionFeedbackCard(
    feedback: TodayCompletionFeedback,
    enabled: Boolean,
    onUndo: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TodayCompletionFeedbackTag)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.today_completion_feedback, feedback.title),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(
                onClick = onUndo,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.today_undo_completion))
            }
        }
    }
}

@Composable
private fun DoneTodayHeading(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(
            text = stringResource(R.string.today_done_section, count),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            textAlign = TextAlign.Start,
        )
        Text(
            text =
                stringResource(
                    if (expanded) R.string.today_collapse else R.string.today_expand,
                ),
        )
    }
}

@Composable
internal fun CompletedAgendaTaskCard(
    task: AgendaTask,
    enabled: Boolean,
    onCorrect: () -> Unit,
) {
    val completion =
        (task.occurrence?.outcome as? OccurrenceOutcome.Complete)?.record ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = task.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.today_completed_on,
                    completion.completedAt.localDate.toString(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onCorrect,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.today_correct_completion))
            }
        }
    }
}

@Composable
internal fun CompletionCorrectionDialog(
    task: AgendaTask,
    use24HourTime: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onAction: (TodayOccurrenceAction) -> Unit,
) {
    val occurrence = task.occurrence ?: return
    val completion = (occurrence.outcome as? OccurrenceOutcome.Complete)?.record ?: return
    val locale = LocalConfiguration.current.locales[0]
    val timeFormatter =
        remember(use24HourTime, locale) {
            completionTimeFormatter(use24HourTime, locale)
        }
    var checkedSubtaskValues by rememberSaveable(task.identifier.value, completion.epoch.value) {
        mutableStateOf(
            completion.subtasks
                .filter(OccurrenceSubtaskState::isChecked)
                .map { subtask -> subtask.identifier.value },
        )
    }
    val checkedSubtasks = checkedSubtaskValues.map(::SubtaskIdentifier).toSet()
    val originalLocalTime = completion.completedAt.completionLocalTime()
    var completionTime by rememberSaveable(
        task.identifier.value,
        completion.epoch.value,
        use24HourTime,
    ) {
        mutableStateOf(originalLocalTime.format(timeFormatter))
    }
    val parsedCompletionTime = parseCompletionTime(completionTime, timeFormatter)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.today_correction_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        R.string.today_correction_snapshot,
                        completion.seriesRevision.title,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(
                        R.string.today_correction_dates,
                        completion.nominalDate.toString(),
                        completion.effectiveDate.toString(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.today_correction_read_only),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = completionTime,
                    onValueChange = { completionTime = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TodayCompletionTimeInputTag),
                    enabled = enabled,
                    label = { Text(stringResource(R.string.today_correction_time)) },
                    supportingText = {
                        if (parsedCompletionTime == null) {
                            Text(
                                stringResource(
                                    R.string.today_correction_time_invalid,
                                    if (use24HourTime) "08:05" else "8:05 AM",
                                ),
                            )
                        }
                    },
                    singleLine = true,
                )
                completion.seriesRevision.subtaskTemplate.forEach { template ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                checkedSubtaskValues =
                                    checkedSubtasks.toggle(template.identifier).map {
                                        identifier -> identifier.value
                                    }
                            }
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = template.identifier in checkedSubtasks,
                            onCheckedChange =
                                if (enabled) {
                                    {
                                        checkedSubtaskValues =
                                            checkedSubtasks.toggle(template.identifier).map {
                                                identifier -> identifier.value
                                            }
                                    }
                                } else {
                                    null
                                },
                            modifier = Modifier.testTag(
                                "$TodayCorrectionSubtaskTagPrefix${template.identifier.value}",
                            ),
                        )
                        Text(template.label)
                    }
                }
                TextButton(
                    onClick = {
                        onAction(
                            TodayOccurrenceAction.CorrectToActionable(
                                occurrenceKey = occurrence.key,
                                completionEpoch = completion.epoch,
                                checkedSubtaskIdentifiers = checkedSubtasks,
                            ),
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.today_mark_not_done))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && parsedCompletionTime != null,
                onClick = {
                    val correctedLocalTime = requireNotNull(parsedCompletionTime)
                    val correctedCompletedAt =
                        completion.completedAt.withCorrectedLocalTime(correctedLocalTime)
                    onAction(
                        TodayOccurrenceAction.CorrectCompletion(
                            occurrenceKey = occurrence.key,
                            completionEpoch = completion.epoch,
                            correctedCompletedAt = correctedCompletedAt,
                            checkedSubtaskIdentifiers = checkedSubtasks,
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.today_save_correction))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.today_cancel_correction))
            }
        },
    )
}

private fun Set<SubtaskIdentifier>.toggle(identifier: SubtaskIdentifier): Set<SubtaskIdentifier> =
    if (identifier in this) this - identifier else this + identifier

internal const val TodayCompletionTimeInputTag = "today-completion-time"
internal const val TodayCorrectionSubtaskTagPrefix = "today-correction-subtask-"
internal const val TodayActiveSubtaskTagPrefix = "today-active-subtask-"
internal const val TodayCompletionFeedbackTag = "today-completion-feedback"
internal const val CompletionUndoWindowMillis = 5_000L

@Composable
private fun TodayEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BotanicalMark(modifier = Modifier.size(112.dp))
            Text(
                text = stringResource(R.string.today_empty_title),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.today_empty_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal val EmptyPersonalAgenda =
    PersonalAgenda(
        date = LocalDate.MIN,
        tasks = emptyList(),
    )
