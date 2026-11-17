package com.gaylord.duit.domain.pairing

import com.gaylord.duit.domain.model.MemberIdentifier

@JvmInline
value class PairIdentifier(val value: String)

data class PartnerIdentity(
    val displayName: String,
    val photoUrl: String?,
)

data class PairInvite(
    val code: String,
    val expiresAtEpochMillis: Long,
)

sealed interface PairingStatus {
    data object Unpaired : PairingStatus

    data class PendingConfirmation(
        val pairIdentifier: PairIdentifier,
        val partnerIdentity: PartnerIdentity,
        val selfConfirmed: Boolean,
        val partnerConfirmed: Boolean,
    ) : PairingStatus

    data class Active(
        val pairIdentifier: PairIdentifier,
        val partnerIdentity: PartnerIdentity,
        /** The Partner's Member identifier; null until the Pairing backend reports it. */
        val partnerIdentifier: MemberIdentifier? = null,
    ) : PairingStatus
}
