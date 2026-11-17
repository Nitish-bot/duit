package com.gaylord.duit.platform.firebase

import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.ShareGeneration
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.sharing.SharedDailyView
import com.gaylord.duit.domain.sharing.SharedViewReference
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedViewCodecTest {
    private val reference =
        SharedViewReference(
            ownerIdentifier = MemberIdentifier("member-alice"),
            pairIdentifier = PairIdentifier("pair-1"),
            partnerIdentifier = MemberIdentifier("member-bob"),
            taskIdentifier = TaskIdentifier("task-tea"),
            shareGeneration = ShareGeneration("generation-1"),
        )
    private val view =
        SharedViewCodecTestFixtures.view(reference)

    @Test
    fun `a published Shared View contains only the redacted fields`() {
        assertEquals(
            setOf(
                "taskId", "ownerUid", "pairId", "partnerUid", "shareGeneration", "title",
                "subtaskLabels", "everyDays", "startsOn", "sharedOn",
            ),
            SharedViewCodec.encode(view).keys,
        )
    }

    @Test
    fun `a Shared View round-trips through its document`() {
        assertEquals(
            view,
            SharedViewCodec.decode("task-tea", SharedViewCodec.encode(view)),
        )
    }

    @Test
    fun `a completion document carries only its date, generation and Partner`() {
        assertEquals(
            mapOf(
                "partnerUid" to "member-bob",
                "shareGeneration" to "generation-1",
                "date" to "2026-08-09",
            ),
            SharedViewCodec.encodeCompletion(reference, LocalDate.of(2026, 8, 9)),
        )
    }
}

private object SharedViewCodecTestFixtures {
    fun view(reference: SharedViewReference) =
        SharedDailyView(
            reference = reference,
            title = "Tea",
            subtaskLabels = listOf("Boil water"),
            everyDays = 1,
            startsOn = LocalDate.of(2026, 8, 9),
            endsOnInclusive = null,
            sharedOn = LocalDate.of(2026, 8, 9),
        )
}
