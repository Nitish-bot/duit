package com.gaylord.duit.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gaylord.duit.domain.model.CompletionEpoch
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.workspace.CommandIdentifier
import com.gaylord.duit.workspace.DuitWorkspace
import com.gaylord.duit.workspace.MemberCommand
import com.gaylord.duit.workspace.MemberCommandOutcome
import com.gaylord.duit.workspace.PersonalAgenda
import java.time.LocalDate
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    val personalAgenda: PersonalAgenda =
        PersonalAgenda(
            date = LocalDate.MIN,
            tasks = emptyList(),
        ),
    val isCommandPending: Boolean = false,
    val taskAdditionSuccess: TodayTaskAdditionSuccess? = null,
    val correctionSuccessKey: Long = 0,
    val completionFeedback: TodayCompletionFeedback? = null,
)

data class TodayTaskAdditionSuccess(
    val key: Long,
    val commandIdentifier: CommandIdentifier,
)

data class TodayCompletionFeedback(
    val title: String,
    val occurrenceKey: OccurrenceKey,
    val completionEpoch: CompletionEpoch,
)

sealed interface TodayEvent {
    data class AddPrivateTaskToToday(
        val commandIdentifier: CommandIdentifier,
        val title: String,
    ) : TodayEvent

    data class AddPrivateDailyTaskToToday(
        val commandIdentifier: CommandIdentifier,
        val title: String,
        val subtask: String?,
    ) : TodayEvent

    data class Occurrence(
        val action: TodayOccurrenceAction,
    ) : TodayEvent
}

typealias TodayEventHandler = (TodayEvent) -> Unit

sealed interface TodayEffect {
    data object WorkspaceActionFailed : TodayEffect

    data object CorrectionBecameStale : TodayEffect
}

private data class TodayWorkflowState(
    val isCommandPending: Boolean = false,
    val taskAdditionSuccess: TodayTaskAdditionSuccess? = null,
    val correctionSuccessKey: Long = 0,
    val completionFeedback: TodayCompletionFeedback? = null,
)

class TodayViewModel(
    private val workspace: DuitWorkspace,
) : ViewModel() {
    private val workflowState = MutableStateFlow(TodayWorkflowState())

    val state: StateFlow<TodayUiState> =
        combine(
            workspace.observePersonalAgenda(),
            workflowState,
        ) { personalAgenda, workflow ->
            TodayUiState(
                personalAgenda = personalAgenda,
                isCommandPending = workflow.isCommandPending,
                taskAdditionSuccess = workflow.taskAdditionSuccess,
                correctionSuccessKey = workflow.correctionSuccessKey,
                completionFeedback = workflow.completionFeedback,
            )
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = TodayUiState(),
            )

    private val effectChannel = Channel<TodayEffect>(capacity = Channel.BUFFERED)
    val effects: Flow<TodayEffect> = effectChannel.receiveAsFlow()

    private var feedbackExpiryJob: Job? = null
    private var successKey = 0L

    fun onEvent(event: TodayEvent) {
        if (workflowState.value.isCommandPending) return

        val command = event.toMemberCommand()
        workflowState.update { it.copy(isCommandPending = true) }
        viewModelScope.launch {
            try {
                val outcome = workspace.executeWithOutcome(command)
                applySuccessfulOutcome(event, outcome)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                workflowState.update { it.copy(isCommandPending = false) }
                effectChannel.send(TodayEffect.WorkspaceActionFailed)
            }
        }
    }

    private fun applySuccessfulOutcome(
        event: TodayEvent,
        outcome: MemberCommandOutcome,
    ) {
        val addition = event as? TodayEvent.AddPrivateTaskToToday
        val dailyAddition = event as? TodayEvent.AddPrivateDailyTaskToToday
        val correction = outcome as? MemberCommandOutcome.OccurrenceCorrected
        val undo = (event as? TodayEvent.Occurrence)?.action as? TodayOccurrenceAction.Undo
        val appliedCompletion =
            (outcome as? MemberCommandOutcome.OccurrenceCompleted)
                ?.takeIf(MemberCommandOutcome.OccurrenceCompleted::applied)
                ?.completion

        if (appliedCompletion != null) {
            val feedback =
                TodayCompletionFeedback(
                    title = appliedCompletion.seriesRevision.title,
                    occurrenceKey =
                        OccurrenceKey(
                            taskIdentifier = appliedCompletion.seriesRevision.taskIdentifier,
                            nominalDate = appliedCompletion.nominalDate,
                        ),
                    completionEpoch = appliedCompletion.epoch,
                )
            workflowState.update {
                it.copy(
                    isCommandPending = false,
                    completionFeedback = feedback,
                )
            }
            scheduleFeedbackExpiry(feedback)
            return
        }

        val additionSuccess =
            when {
                addition != null ->
                    TodayTaskAdditionSuccess(
                        key = nextSuccessKey(),
                        commandIdentifier = addition.commandIdentifier,
                    )
                dailyAddition != null ->
                    TodayTaskAdditionSuccess(
                        key = nextSuccessKey(),
                        commandIdentifier = dailyAddition.commandIdentifier,
                    )
                else -> null
            }
        workflowState.update { current ->
            val feedback = current.completionFeedback
            current.copy(
                isCommandPending = false,
                taskAdditionSuccess = additionSuccess ?: current.taskAdditionSuccess,
                correctionSuccessKey =
                    if (correction?.applied == true) {
                        current.correctionSuccessKey + 1
                    } else {
                        current.correctionSuccessKey
                    },
                completionFeedback =
                    if (
                        undo != null &&
                        feedback?.occurrenceKey == undo.occurrenceKey &&
                        feedback.completionEpoch == undo.completionEpoch
                    ) {
                        null
                    } else {
                        current.completionFeedback
                    },
            )
        }
        if (correction?.applied == false) {
            viewModelScope.launch {
                effectChannel.send(TodayEffect.CorrectionBecameStale)
            }
        }
    }

    private fun scheduleFeedbackExpiry(feedback: TodayCompletionFeedback) {
        feedbackExpiryJob?.cancel()
        feedbackExpiryJob =
            viewModelScope.launch {
                delay(CompletionUndoWindowMillis)
                workflowState.update { current ->
                    if (current.completionFeedback == feedback) {
                        current.copy(completionFeedback = null)
                    } else {
                        current
                    }
                }
            }
    }

    private fun nextSuccessKey(): Long {
        successKey += 1
        return successKey
    }

    class Factory(
        private val workspace: DuitWorkspace,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TodayViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return TodayViewModel(workspace) as T
        }
    }
}

private fun TodayEvent.toMemberCommand(): MemberCommand =
    when (this) {
        is TodayEvent.AddPrivateTaskToToday ->
            MemberCommand.AddPrivateTaskToToday(
                commandIdentifier = commandIdentifier,
                title = title,
            )
        is TodayEvent.AddPrivateDailyTaskToToday ->
            MemberCommand.AddPrivateDailyTaskToToday(
                commandIdentifier = commandIdentifier,
                title = title,
                subtask = subtask,
            )
        is TodayEvent.Occurrence -> action.toMemberCommand()
    }

private fun TodayOccurrenceAction.toMemberCommand(): MemberCommand =
    when (this) {
        is TodayOccurrenceAction.Complete ->
            MemberCommand.CompleteOccurrence(
                commandIdentifier = commandIdentifier,
                occurrenceKey = occurrenceKey,
                checkedSubtaskIdentifiers = checkedSubtaskIdentifiers,
            )
        is TodayOccurrenceAction.Undo ->
            MemberCommand.UndoOccurrenceCompletion(
                occurrenceKey = occurrenceKey,
                completionEpoch = completionEpoch,
            )
        is TodayOccurrenceAction.CorrectCompletion ->
            MemberCommand.CorrectOccurrenceCompletion(
                occurrenceKey = occurrenceKey,
                completionEpoch = completionEpoch,
                correctedCompletedAt = correctedCompletedAt,
                checkedSubtaskIdentifiers = checkedSubtaskIdentifiers,
            )
        is TodayOccurrenceAction.CorrectToActionable ->
            MemberCommand.CorrectOccurrenceToActionable(
                occurrenceKey = occurrenceKey,
                completionEpoch = completionEpoch,
                checkedSubtaskIdentifiers = checkedSubtaskIdentifiers,
            )
        is TodayOccurrenceAction.SetSubtaskChecked ->
            MemberCommand.SetOccurrenceSubtaskChecked(
                occurrenceKey = occurrenceKey,
                subtaskIdentifier = subtaskIdentifier,
                isChecked = isChecked,
            )
    }
