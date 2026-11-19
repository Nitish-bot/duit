package com.gaylord.duit.feature.tasks

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.gaylord.duit.R
import com.gaylord.duit.domain.model.OccurrenceOutcome
import com.gaylord.duit.domain.model.OccurrenceSubtaskState
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.workspace.AgendaTask
import com.gaylord.duit.workspace.RecurringSeriesDetail
import com.gaylord.duit.workspace.RecurringSeriesLibraryEntry
import com.gaylord.duit.feature.common.completionLocalTime
import com.gaylord.duit.feature.common.completionTimeFormatter
import com.gaylord.duit.feature.common.parseCompletionTime
import com.gaylord.duit.feature.common.withCorrectedLocalTime

@Composable
fun TasksScreen(
    modifier: Modifier = Modifier,
    state: TasksUiState = TasksUiState(),
    use24HourTime: Boolean? = null,
    onEvent: TasksEventHandler = {},
) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    var correctionTaskIdentifier by rememberSaveable { mutableStateOf<String?>(null) }
    var correctionNominalDate by rememberSaveable { mutableStateOf<String?>(null) }
    var observedCorrectionSuccessKey by rememberSaveable {
        mutableStateOf(state.correctionSuccessKey)
    }
    val shouldUse24HourTime = use24HourTime ?: DateFormat.is24HourFormat(LocalContext.current)

    LifecycleStartEffect(Unit) {
        currentOnEvent(TasksEvent.Started)
        onStopOrDispose { currentOnEvent(TasksEvent.Stopped) }
    }

    LaunchedEffect(state.correctionSuccessKey) {
        if (state.correctionSuccessKey > observedCorrectionSuccessKey) {
            correctionTaskIdentifier = null
            correctionNominalDate = null
            observedCorrectionSuccessKey = state.correctionSuccessKey
        }
    }

    val selectedTask = state.selectedTask
    if (selectedTask == null) {
        TaskLibrary(
            state = state,
            modifier = modifier,
            onSelect = { currentOnEvent(TasksEvent.SelectTask(it.detail.identifier)) },
        )
    } else {
        RecurringTaskDetail(
            state = state,
            use24HourTime = shouldUse24HourTime,
            modifier = modifier,
            onBack = { currentOnEvent(TasksEvent.BackToLibrary) },
            onEvent = currentOnEvent,
            onCorrect = { task ->
                correctionTaskIdentifier = task.identifier.value
                correctionNominalDate = task.occurrence?.nominalDate?.toString()
            },
        )
    }

    val correctionTask =
        state.history.completions.firstOrNull { task ->
            task.identifier.value == correctionTaskIdentifier &&
                task.occurrence?.nominalDate?.toString() == correctionNominalDate
        }
    correctionTask?.let { task ->
        TasksCompletionCorrectionDialog(
            task = task,
            use24HourTime = shouldUse24HourTime,
            enabled = !state.isCommandPending,
            onDismiss = {
                correctionTaskIdentifier = null
                correctionNominalDate = null
            },
            onEvent = currentOnEvent,
        )
    }
}

@Composable
private fun TaskLibrary(
    state: TasksUiState,
    modifier: Modifier,
    onSelect: (RecurringSeriesLibraryEntry) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = TasksContentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.destination_tasks),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(R.string.tasks_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        if (state.isLibraryLoading && state.library.isEmpty()) {
            item { TasksLoadingIndicator(R.string.tasks_library_loading) }
        } else if (state.library.isEmpty()) {
            item {
                TasksCard {
                    Text(
                        text = stringResource(R.string.tasks_empty_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(R.string.tasks_empty_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            item {
                Text(
                    text = stringResource(R.string.tasks_repeating_heading),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            items(
                items = state.library,
                key = { item -> item.detail.identifier.value },
            ) { item ->
                RecurringSeriesCard(item = item, onSelect = { onSelect(item) })
            }
        }
    }
}

@Composable
private fun RecurringSeriesCard(
    item: RecurringSeriesLibraryEntry,
    onSelect: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.detail.initialRevision.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.tasks_daily_from, item.detail.initialRevision.startsOn),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (item.detail.visibility is TaskVisibility.Shared) {
                Text(
                    text = stringResource(R.string.tasks_library_shared),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RecurringTaskDetail(
    state: TasksUiState,
    use24HourTime: Boolean,
    modifier: Modifier,
    onBack: () -> Unit,
    onEvent: TasksEventHandler,
    onCorrect: (AgendaTask) -> Unit,
) {
    val selectedTask = requireNotNull(state.selectedTask)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = TasksContentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.tasks_back_to_library))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = selectedTask.initialRevision.title,
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(
                        R.string.tasks_daily_from,
                        selectedTask.initialRevision.startsOn,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        item {
            SharingCard(
                task = selectedTask,
                partnerName = state.partnerName,
                enabled = !state.isCommandPending,
                onEvent = onEvent,
            )
        }
        item {
            Text(
                text = stringResource(R.string.tasks_completion_history_heading),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        if (state.isHistoryLoading) {
            item { TasksLoadingIndicator(R.string.tasks_history_loading) }
        } else if (state.history.completions.isEmpty()) {
            item {
                TasksCard {
                    Text(
                        text = stringResource(R.string.tasks_history_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            items(
                items = state.history.completions,
                key = { task ->
                    val occurrence = requireNotNull(task.occurrence)
                    "${task.identifier.value}-${occurrence.nominalDate}"
                },
            ) { task ->
                HistoricalCompletionCard(
                    task = task,
                    use24HourTime = use24HourTime,
                    enabled = !state.isCommandPending,
                    onCorrect = { onCorrect(task) },
                )
            }
        }
    }
}

@Composable
private fun SharingCard(
    task: RecurringSeriesDetail,
    partnerName: String?,
    enabled: Boolean,
    onEvent: TasksEventHandler,
) {
    TasksCard {
        if (task.visibility is TaskVisibility.Shared) {
            Text(
                text = stringResource(R.string.tasks_visibility_shared),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.tasks_shared_scope),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.tasks_make_private_offline_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { onEvent(TasksEvent.MakePrivate(task.identifier)) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("tasks-make-private"),
            ) {
                Text(stringResource(R.string.tasks_make_private))
            }
        } else {
            Text(
                text = stringResource(R.string.tasks_visibility_private),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            if (partnerName != null) {
                Text(
                    text = stringResource(R.string.tasks_share_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = { onEvent(TasksEvent.ShareWithPartner(task.identifier)) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tasks-share-with-partner"),
                ) {
                    Text(stringResource(R.string.tasks_share_with_partner, partnerName))
                }
            } else {
                Text(
                    text = stringResource(R.string.tasks_share_no_pair),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun HistoricalCompletionCard(
    task: AgendaTask,
    use24HourTime: Boolean,
    enabled: Boolean,
    onCorrect: () -> Unit,
) {
    val completion =
        (task.occurrence?.outcome as? OccurrenceOutcome.Complete)?.record ?: return
    val locale = LocalConfiguration.current.locales[0]
    val timeFormatter =
        remember(use24HourTime, locale) {
            completionTimeFormatter(use24HourTime, locale)
        }
    val completionTime =
        completion.completedAt.instant
            .atZone(completion.completedAt.zoneId)
            .toLocalTime()
            .format(timeFormatter)

    TasksCard {
        Text(
            text = stringResource(
                R.string.tasks_completed_at,
                completion.completedAt.localDate,
                completionTime,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.tasks_occurrence_dates,
                completion.nominalDate,
                completion.effectiveDate,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        completion.seriesRevision.subtaskTemplate.forEach { template ->
            val checked = completion.subtasks.single { it.identifier == template.identifier }.isChecked
            Text(
                text = stringResource(
                    if (checked) R.string.tasks_checked_subtask else R.string.tasks_unchecked_subtask,
                    template.label,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(
            onClick = onCorrect,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.tasks_correct_completion))
        }
    }
}

@Composable
private fun TasksCompletionCorrectionDialog(
    task: AgendaTask,
    use24HourTime: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onEvent: TasksEventHandler,
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
        title = { Text(stringResource(R.string.tasks_correction_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        R.string.tasks_recorded_task,
                        completion.seriesRevision.title,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(
                        R.string.tasks_occurrence_dates,
                        completion.nominalDate,
                        completion.effectiveDate,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.tasks_correction_read_only),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = completionTime,
                    onValueChange = { completionTime = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(TasksCompletionTimeInputTag),
                    enabled = enabled,
                    label = { Text(stringResource(R.string.tasks_completion_time)) },
                    supportingText = {
                        if (parsedCompletionTime == null) {
                            Text(
                                stringResource(
                                    R.string.tasks_completion_time_invalid,
                                    if (use24HourTime) "08:05" else "8:05 AM",
                                ),
                            )
                        }
                    },
                    singleLine = true,
                )
                completion.seriesRevision.subtaskTemplate.forEach { template ->
                    Row(
                        modifier =
                            Modifier
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
                                "$TasksCorrectionSubtaskTagPrefix${template.identifier.value}",
                            ),
                        )
                        Text(template.label)
                    }
                }
                TextButton(
                    onClick = {
                        onEvent(
                            TasksEvent.CorrectToActionable(
                                occurrenceKey = occurrence.key,
                                completionEpoch = completion.epoch,
                                checkedSubtaskIdentifiers = checkedSubtasks,
                            ),
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.tasks_mark_not_done))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && parsedCompletionTime != null,
                onClick = {
                    val correctedLocalTime = requireNotNull(parsedCompletionTime)
                    onEvent(
                        TasksEvent.CorrectCompletion(
                            occurrenceKey = occurrence.key,
                            completionEpoch = completion.epoch,
                            correctedCompletedAt =
                                completion.completedAt.withCorrectedLocalTime(correctedLocalTime),
                            checkedSubtaskIdentifiers = checkedSubtasks,
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.tasks_save_correction))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tasks_cancel_correction))
            }
        },
    )
}

@Composable
private fun TasksLoadingIndicator(labelResource: Int) {
    val description = stringResource(labelResource)
    TasksCard {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = description },
        )
        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TasksCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

private fun Set<SubtaskIdentifier>.toggle(identifier: SubtaskIdentifier): Set<SubtaskIdentifier> =
    if (identifier in this) this - identifier else this + identifier

private val TasksContentPadding =
    PaddingValues(
        start = 24.dp,
        top = 24.dp,
        end = 24.dp,
        bottom = 32.dp,
    )

internal const val TasksCompletionTimeInputTag = "tasks-completion-time"
internal const val TasksCorrectionSubtaskTagPrefix = "tasks-correction-subtask-"
