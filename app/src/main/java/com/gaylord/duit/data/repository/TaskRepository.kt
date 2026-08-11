package com.gaylord.duit.data.repository

import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.RecurringSeriesCreation
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.TaskIdentifier
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

    fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
    ): Flow<List<RecurringSeriesCreation>>

    fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<RecurringSeriesCreation?>
}
