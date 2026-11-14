package com.gaylord.duit.domain.pairing

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
    ) : PairingStatus
}
