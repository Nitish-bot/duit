package com.gaylord.duit.domain.recurrence

import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.RecurrenceRule
import com.gaylord.duit.domain.model.SeriesRevision
import com.gaylord.duit.domain.model.TaskIdentifier
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object RecurrenceEngine {
    fun occurrenceKeyOn(
        taskIdentifier: TaskIdentifier,
        revision: SeriesRevision,
        nominalDate: LocalDate,
    ): OccurrenceKey? {
        if (nominalDate.isBefore(revision.startsOn)) return null
        if (revision.endsOnInclusive?.let(nominalDate::isAfter) == true) return null

        val occurs =
            when (val rule = revision.recurrenceRule) {
                is RecurrenceRule.Daily ->
                    ChronoUnit.DAYS.between(revision.startsOn, nominalDate) %
                        rule.everyDays == 0L
            }

        return if (occurs) OccurrenceKey(taskIdentifier, nominalDate) else null
    }
}
