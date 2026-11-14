package com.gaylord.duit.pairing

import com.gaylord.duit.domain.pairing.PairInvite
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.pairing.PairingStatus

interface PairingGateway {
    suspend fun getPairingStatus(): PairingCallResult<PairingStatus>

    suspend fun createPairInvite(): PairingCallResult<PairInvite>

    suspend fun acceptPairInvite(
        code: String,
    ): PairingCallResult<PairingStatus.PendingConfirmation>

    suspend fun confirmPair(
        pairIdentifier: PairIdentifier,
    ): PairingCallResult<PairingStatus>
}

sealed interface PairingCallResult<out Value> {
    data class Success<Value>(val value: Value) : PairingCallResult<Value>

    data class Failure(val failure: PairingCallFailure) : PairingCallResult<Nothing>
}

sealed interface PairingCallFailure {
    data object ConnectionRequired : PairingCallFailure

    data class Recoverable(val message: String?) : PairingCallFailure
}
