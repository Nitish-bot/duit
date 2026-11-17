package com.gaylord.duit.feature.together

import com.gaylord.duit.domain.pairing.PairInvite
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.pairing.PartnerIdentity
import com.gaylord.duit.domain.sharing.PartnerSharedDailyTask

sealed interface TogetherState {
    data object Loading : TogetherState

    data object ConnectionRequired : TogetherState

    data class RecoverableError(
        val message: String?,
    ) : TogetherState

    data object Unpaired : TogetherState

    data class InviteReady(
        val invite: PairInvite,
    ) : TogetherState

    data class PartnerIdentityReview(
        val pairIdentifier: PairIdentifier,
        val partnerIdentity: PartnerIdentity,
        val partnerAlreadyConfirmed: Boolean,
    ) : TogetherState

    data class WaitingForPartner(
        val pairIdentifier: PairIdentifier,
        val partnerIdentity: PartnerIdentity,
    ) : TogetherState

    data class ActivePair(
        val pairIdentifier: PairIdentifier,
        val partnerIdentity: PartnerIdentity,
        /** The Partner's Shared Tasks, read-only. Everything here is already redacted. */
        val sharedTasks: List<PartnerSharedDailyTask> = emptyList(),
    ) : TogetherState
}
