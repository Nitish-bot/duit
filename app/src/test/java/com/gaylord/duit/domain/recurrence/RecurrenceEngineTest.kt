package com.gaylord.duit.domain.recurrence

import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.RecurrenceRule
import com.gaylord.duit.domain.model.SeriesRevision
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.TaskIdentifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RecurrenceEngineTest {
    @Test
    fun `Daily recurrence uses its anchor interval inclusive bound and stable nominal-date key`() {
        val taskIdentifier = TaskIdentifier("task-herbs")
        val revision =
            SeriesRevision(
                identifier = SeriesRevisionIdentifier("revision-herbs"),
                taskIdentifier = taskIdentifier,
                title = "Water the herbs",
                subtaskTemplate = emptyList(),
                recurrenceRule = RecurrenceRule.Daily(everyDays = 2),
                startsOn = LocalDate.of(2026, 8, 1),
                endsOnInclusive = LocalDate.of(2026, 8, 5),
                recordedAt =
                    MemberActionMoment(
                        instant = Instant.parse("2026-08-01T08:00:00Z"),
                        localDate = LocalDate.of(2026, 8, 1),
                        zoneId = ZoneId.of("UTC"),
                    ),
            )

        assertEquals(
            listOf(
                null,
                OccurrenceKey(taskIdentifier, LocalDate.of(2026, 8, 1)),
                null,
                OccurrenceKey(taskIdentifier, LocalDate.of(2026, 8, 3)),
                null,
                OccurrenceKey(taskIdentifier, LocalDate.of(2026, 8, 5)),
                null,
            ),
            (0L..6L).map { offset ->
                RecurrenceEngine.occurrenceKeyOn(
                    taskIdentifier = taskIdentifier,
                    revision = revision,
                    nominalDate = LocalDate.of(2026, 7, 31).plusDays(offset),
                )
            },
        )
    }
}
