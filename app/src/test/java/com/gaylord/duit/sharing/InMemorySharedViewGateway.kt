package com.gaylord.duit.sharing

import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.sharing.PartnerSharedDailyTask
import com.gaylord.duit.domain.sharing.SharedDailyView
import com.gaylord.duit.domain.sharing.SharedViewReference
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Models the server store: Owners write by reference; a Member reads the views their Partner
 * (the Owner) published into the Pair, with only the current generation's completions.
 */
class InMemorySharedViewGateway : SharedViewGateway {
    private data class Stored(
        val view: SharedDailyView,
        val completedDates: Set<LocalDate>,
    )

    private val stored = MutableStateFlow<Map<TaskIdentifier, Stored>>(emptyMap())

    val publishedViews: List<SharedDailyView>
        get() = stored.value.values.map(Stored::view)

    fun completedDates(taskIdentifier: TaskIdentifier): Set<LocalDate> =
        stored.value[taskIdentifier]?.completedDates.orEmpty()

    override suspend fun publish(view: SharedDailyView) {
        stored.value = stored.value + (view.reference.taskIdentifier to Stored(view, emptySet()))
    }

    override suspend fun revoke(reference: SharedViewReference) {
        stored.value =
            stored.value.filterValues { it.view.reference != reference }
    }

    override suspend fun setCompletedDate(
        reference: SharedViewReference,
        date: LocalDate,
    ) {
        update(reference) { it + date }
    }

    override suspend fun clearCompletedDate(
        reference: SharedViewReference,
        date: LocalDate,
    ) {
        update(reference) { it - date }
    }

    override fun observePartnerSharedViews(
        pairIdentifier: PairIdentifier,
        partnerIdentifier: MemberIdentifier,
    ): Flow<List<PartnerSharedDailyTask>> =
        stored.map { all ->
            all.values
                .filter {
                    it.view.reference.pairIdentifier == pairIdentifier &&
                        it.view.reference.ownerIdentifier == partnerIdentifier
                }.map { PartnerSharedDailyTask(it.view, it.completedDates) }
        }

    private fun update(
        reference: SharedViewReference,
        change: (Set<LocalDate>) -> Set<LocalDate>,
    ) {
        val current = stored.value[reference.taskIdentifier] ?: return
        if (current.view.reference != reference) return
        stored.value =
            stored.value + (reference.taskIdentifier to current.copy(completedDates = change(current.completedDates)))
    }
}
