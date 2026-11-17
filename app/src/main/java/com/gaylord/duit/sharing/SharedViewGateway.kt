package com.gaylord.duit.sharing

import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.sharing.PartnerSharedDailyTask
import com.gaylord.duit.domain.sharing.SharedDailyView
import com.gaylord.duit.domain.sharing.SharedViewReference
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * The server-side Shared View store. Writes are accepted locally and queued by the offline
 * store, so they return without waiting for the server; failures surface only on
 * synchronization.
 */
interface SharedViewGateway {
    suspend fun publish(view: SharedDailyView)

    /** Removes the Shared View and its completions for this grant. */
    suspend fun revoke(reference: SharedViewReference)

    suspend fun setCompletedDate(
        reference: SharedViewReference,
        date: LocalDate,
    )

    suspend fun clearCompletedDate(
        reference: SharedViewReference,
        date: LocalDate,
    )

    /**
     * The Shared Tasks the Partner (identified by [partnerIdentifier], the Owner of those
     * tasks) currently publishes into the Pair, read-only.
     */
    fun observePartnerSharedViews(
        pairIdentifier: PairIdentifier,
        partnerIdentifier: MemberIdentifier,
    ): Flow<List<PartnerSharedDailyTask>>
}
