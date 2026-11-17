package com.gaylord.duit.platform.firebase

import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.RecurrenceRule
import com.gaylord.duit.domain.model.RecurringSeries
import com.gaylord.duit.domain.model.RecurringSeriesCreation
import com.gaylord.duit.domain.model.SeriesRevision
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.ShareGeneration
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.domain.pairing.PairIdentifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FirestoreRecurringSeriesVisibilityCodecTest {
    @Test
    fun `a new Series is stored Private with no grant fields`() {
        val documents = FirestoreRecurringSeriesCodec.encode(series(TaskVisibility.Private))

        assertEquals("PRIVATE", documents.task["visibility"])
        assertEquals(
            emptyList<String>(),
            documents.task.keys.filter { it.startsWith("share") },
        )
    }

    @Test
    fun `a Shared Series round-trips its Pair, generation and Partner`() {
        val shared =
            TaskVisibility.Shared(
                PairIdentifier("pair-1"),
                ShareGeneration("generation-1"),
                MemberIdentifier("member-bob"),
            )
        val creation = series(shared)
        val documents = FirestoreRecurringSeriesCodec.encode(creation)

        assertEquals(
            creation,
            FirestoreRecurringSeriesCodec.decode(documents.task, documents.revision),
        )
    }

    private fun series(visibility: TaskVisibility): RecurringSeriesCreation {
        val moment =
            MemberActionMoment(
                Instant.parse("2026-08-09T09:00:00Z"),
                LocalDate.of(2026, 8, 9),
                ZoneId.of("UTC"),
            )
        val revision =
            SeriesRevision(
                identifier = SeriesRevisionIdentifier("revision-tea"),
                taskIdentifier = TaskIdentifier("task-tea"),
                title = "Tea",
                subtaskTemplate = emptyList(),
                recurrenceRule = RecurrenceRule.Daily(),
                startsOn = moment.localDate,
                endsOnInclusive = null,
                recordedAt = moment,
            )
        return RecurringSeriesCreation(
            series =
                RecurringSeries(
                    identifier = TaskIdentifier("task-tea"),
                    ownerIdentifier = MemberIdentifier("member-alice"),
                    activeRevisionIdentifier = revision.identifier,
                    visibility = visibility,
                    createdAt = moment,
                ),
            initialRevision = revision,
        )
    }
}
