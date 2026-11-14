package com.gaylord.duit.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gaylord.duit.domain.model.CompletionEpoch
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.workspace.DuitWorkspace
import com.gaylord.duit.workspace.MemberCommand
import com.gaylord.duit.workspace.MemberCommandOutcome
import com.gaylord.duit.workspace.PersonalHistory
import com.gaylord.duit.workspace.RecurringSeriesDetail
import com.gaylord.duit.workspace.RecurringSeriesLibraryEntry
import com.gaylord.duit.workspace.WorkspaceAccessState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TasksUiState(
    val library: List<RecurringSeriesLibraryEntry> = emptyList(),
    val selectedTaskIdentifier: TaskIdentifier? = null,
    val selectedTask: RecurringSeriesDetail? = null,
    val history: PersonalHistory = PersonalHistory(),
    val isLibraryLoading: Boolean = true,
    val isHistoryLoading: Boolean = false,
    val isCommandPending: Boolean = false,
    val correctionSuccessKey: Long = 0,
)

sealed interface TasksEvent {
    data object Started : TasksEvent

    data object Stopped : TasksEvent

    data class SelectTask(
        val taskIdentifier: TaskIdentifier,
    ) : TasksEvent

    data object BackToLibrary : TasksEvent

    data class CorrectCompletion(
        val occurrenceKey: OccurrenceKey,
        val completionEpoch: CompletionEpoch,
        val correctedCompletedAt: MemberActionMoment,
        val checkedSubtaskIdentifiers: Set<SubtaskIdentifier>,
    ) : TasksEvent

    data class CorrectToActionable(
        val occurrenceKey: OccurrenceKey,
        val completionEpoch: CompletionEpoch,
        val checkedSubtaskIdentifiers: Set<SubtaskIdentifier>,
    ) : TasksEvent
}

typealias TasksEventHandler = (TasksEvent) -> Unit

sealed interface TasksEffect {
    data object WorkspaceActionFailed : TasksEffect

    data object CorrectionBecameStale : TasksEffect
}

class TasksViewModel internal constructor(
    private val workspace: DuitWorkspace,
    coroutineScope: CoroutineScope?,
) : ViewModel() {
    constructor(workspace: DuitWorkspace) : this(workspace, coroutineScope = null)

    private val scope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(TasksUiState())
    private val effectChannel = Channel<TasksEffect>(capacity = Channel.BUFFERED)
    private var started = false
    private var libraryJob: Job? = null
    private var historyJob: Job? = null
    private var commandJob: Job? = null
    private var observedMemberIdentifier =
        (workspace.accessState.value as? WorkspaceAccessState.Ready)?.memberIdentifier

    val state: StateFlow<TasksUiState> = mutableState.asStateFlow()
    val effects: Flow<TasksEffect> = effectChannel.receiveAsFlow()

    init {
        scope.launch {
            workspace.accessState.collect { accessState ->
                val memberIdentifier =
                    (accessState as? WorkspaceAccessState.Ready)?.memberIdentifier
                if (memberIdentifier != observedMemberIdentifier) {
                    observedMemberIdentifier = memberIdentifier
                    resetOwnerState(memberIdentifier != null)
                }
            }
        }
    }

    fun onEvent(event: TasksEvent) {
        when (event) {
            TasksEvent.Started -> start()
            TasksEvent.Stopped -> stop()
            is TasksEvent.SelectTask -> selectTask(event.taskIdentifier)
            TasksEvent.BackToLibrary -> showLibrary()
            is TasksEvent.CorrectCompletion -> executeCorrection(event.toMemberCommand())
            is TasksEvent.CorrectToActionable -> executeCorrection(event.toMemberCommand())
        }
    }

    private fun start() {
        if (started) return
        started = true
        if (observedMemberIdentifier == null) {
            resetOwnerState(hasReadyMember = false)
            return
        }
        mutableState.update { it.copy(isLibraryLoading = true) }
        startLibraryObservation()
        mutableState.value.selectedTaskIdentifier?.let(::startHistoryObservation)
    }

    private fun stop() {
        if (!started) return
        started = false
        libraryJob?.cancel()
        libraryJob = null
        historyJob?.cancel()
        historyJob = null
    }

    private fun resetOwnerState(hasReadyMember: Boolean) {
        libraryJob?.cancel()
        libraryJob = null
        historyJob?.cancel()
        historyJob = null
        commandJob?.cancel()
        commandJob = null
        mutableState.value =
            TasksUiState(
                isLibraryLoading = started && hasReadyMember,
            )
        if (started && hasReadyMember) startLibraryObservation()
    }

    private fun startLibraryObservation() {
        libraryJob?.cancel()
        libraryJob =
            scope.launch {
                workspace.observeRecurringTaskLibrary().collect { library ->
                    val selectedIdentifier = mutableState.value.selectedTaskIdentifier
                    val selectedTask =
                        selectedIdentifier?.let { identifier ->
                            library.firstOrNull { it.detail.identifier == identifier }?.detail
                        }
                    mutableState.update { current ->
                        current.copy(
                            library = library,
                            selectedTask = selectedTask,
                            isLibraryLoading = false,
                        )
                    }
                    if (selectedIdentifier != null && selectedTask == null) {
                        showLibrary()
                    }
                }
            }
    }

    private fun selectTask(taskIdentifier: TaskIdentifier) {
        val selectedTask =
            mutableState.value.library
                .firstOrNull { it.detail.identifier == taskIdentifier }
                ?.detail
                ?: return
        mutableState.update {
            it.copy(
                selectedTaskIdentifier = taskIdentifier,
                selectedTask = selectedTask,
                history = PersonalHistory(),
                isHistoryLoading = started,
            )
        }
        if (started) startHistoryObservation(taskIdentifier)
    }

    private fun showLibrary() {
        historyJob?.cancel()
        historyJob = null
        mutableState.update {
            it.copy(
                selectedTaskIdentifier = null,
                selectedTask = null,
                history = PersonalHistory(),
                isHistoryLoading = false,
            )
        }
    }

    private fun startHistoryObservation(taskIdentifier: TaskIdentifier) {
        historyJob?.cancel()
        mutableState.update { it.copy(isHistoryLoading = true) }
        historyJob =
            scope.launch {
                workspace.observePersonalHistory(taskIdentifier).collect { history ->
                    if (mutableState.value.selectedTaskIdentifier == taskIdentifier) {
                        mutableState.update {
                            it.copy(
                                history = history,
                                isHistoryLoading = false,
                            )
                        }
                    }
                }
            }
    }

    private fun executeCorrection(command: MemberCommand) {
        val selectedTaskIdentifier = mutableState.value.selectedTaskIdentifier ?: return
        if (
            !started ||
            mutableState.value.isCommandPending ||
            command.taskIdentifier() != selectedTaskIdentifier
        ) {
            return
        }
        mutableState.update { it.copy(isCommandPending = true) }
        commandJob =
            scope.launch {
                try {
                    val outcome = workspace.executeWithOutcome(command)
                    val correction = outcome as MemberCommandOutcome.OccurrenceCorrected
                    mutableState.update { current ->
                        current.copy(
                            isCommandPending = false,
                            correctionSuccessKey =
                                if (correction.applied) {
                                    current.correctionSuccessKey + 1
                                } else {
                                    current.correctionSuccessKey
                                },
                        )
                    }
                    if (!correction.applied) {
                        effectChannel.send(TasksEffect.CorrectionBecameStale)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    mutableState.update { it.copy(isCommandPending = false) }
                    effectChannel.send(TasksEffect.WorkspaceActionFailed)
                }
            }
    }

    class Factory(
        private val workspace: DuitWorkspace,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TasksViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return TasksViewModel(workspace) as T
        }
    }
}

private fun TasksEvent.CorrectCompletion.toMemberCommand(): MemberCommand =
    MemberCommand.CorrectOccurrenceCompletion(
        occurrenceKey = occurrenceKey,
        completionEpoch = completionEpoch,
        correctedCompletedAt = correctedCompletedAt,
        checkedSubtaskIdentifiers = checkedSubtaskIdentifiers,
    )

private fun TasksEvent.CorrectToActionable.toMemberCommand(): MemberCommand =
    MemberCommand.CorrectOccurrenceToActionable(
        occurrenceKey = occurrenceKey,
        completionEpoch = completionEpoch,
        checkedSubtaskIdentifiers = checkedSubtaskIdentifiers,
    )

private fun MemberCommand.taskIdentifier(): TaskIdentifier =
    when (this) {
        is MemberCommand.CorrectOccurrenceCompletion -> occurrenceKey.taskIdentifier
        is MemberCommand.CorrectOccurrenceToActionable -> occurrenceKey.taskIdentifier
        else -> error("Tasks only executes correction commands")
    }
