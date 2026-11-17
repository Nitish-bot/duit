package com.gaylord.duit.domain.sharing

import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.ShareGeneration
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.pairing.PairIdentifier
import java.time.LocalDate

/** Names one Share Grant: one Task, shared by its Owner to one Partner, in one generation. */
data class SharedViewReference(
    val ownerIdentifier: MemberIdentifier,
    val pairIdentifier: PairIdentifier,
    val partnerIdentifier: MemberIdentifier,
    val taskIdentifier: TaskIdentifier,
    val shareGeneration: ShareGeneration,
)

/**
 * The privacy-limited definition of a Shared Daily Task. By construction it carries no
 * reminders, Focus, checked Subtask state, negative outcomes, corrections, or exact times/zones.
 */
data class SharedDailyView(
    val reference: SharedViewReference,
    val title: String,
    val subtaskLabels: List<String>,
    val everyDays: Int,
    val startsOn: LocalDate,
    val endsOnInclusive: LocalDate?,
    val sharedOn: LocalDate,
) {
    init {
        require(everyDays > 0) { "Shared recurrence interval must be positive" }
    }
}

/** A Partner's read-only view: the definition plus date-level completions of the current grant. */
data class PartnerSharedDailyTask(
    val view: SharedDailyView,
    val completedDates: Set<LocalDate>,
)
