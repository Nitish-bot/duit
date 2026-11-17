package com.gaylord.duit.data.repository

import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.OccurrenceState
import com.gaylord.duit.domain.model.RecurringSeriesCreation
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    /**
     * Atomically accepts a creation into the local durable store by stable Task identity.
     * Command replay returns the creation already present there.
     *
     * The caller must first complete owner hydration or restore the same durable offline session,
     * making local absence authoritative while disconnected.
     */
    suspend fun putStandaloneTaskIfAbsent(
        creation: StandaloneTaskCreation,
    ): StandaloneTaskCreation

    fun observeStandaloneTasks(
        ownerIdentifier: MemberIdentifier,
        plannedDate: LocalDate,
    ): Flow<List<StandaloneTaskCreation>>

    fun observeStandaloneTask(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<StandaloneTaskCreation?>

    /**
     * Atomically accepts a Recurring Series into the local durable store by stable Task identity.
     * Occurrences are derived from the stored Series and are not materialized by this operation.
     *
     * The caller must first complete owner hydration or restore the same durable offline session,
     * making local absence authoritative while disconnected.
     */
    suspend fun putRecurringSeriesIfAbsent(
        creation: RecurringSeriesCreation,
    ): RecurringSeriesCreation

    /**
     * Durably changes the Visibility of one Owner's Recurring Series, leaving its definition and
     * history untouched. Returns the stored creation, or null when the Owner has no such Series.
     */
    suspend fun putRecurringSeriesVisibility(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
        visibility: TaskVisibility,
    ): RecurringSeriesCreation?

    fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
    ): Flow<List<RecurringSeriesCreation>>

    fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<RecurringSeriesCreation?>

    /**
     * Replaces one interacted Occurrence State as a whole document. Expected Occurrences with no
     * interaction are derived from their Series and have no stored state.
     */
    suspend fun putOccurrenceState(
        ownerIdentifier: MemberIdentifier,
        state: OccurrenceState,
    ): OccurrenceState

    fun observeOccurrenceState(
        ownerIdentifier: MemberIdentifier,
        occurrenceKey: OccurrenceKey,
    ): Flow<OccurrenceState?>

    fun observeOccurrenceStates(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<List<OccurrenceState>>
}
