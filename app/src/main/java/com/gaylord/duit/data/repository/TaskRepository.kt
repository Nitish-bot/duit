package com.gaylord.duit.data.repository

import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.TaskIdentifier
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    /**
     * Atomically accepts a creation into the local durable store by stable Task identity.
     * Command replay returns the creation already present there.
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
}
