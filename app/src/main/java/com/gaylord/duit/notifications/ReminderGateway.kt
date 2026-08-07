package com.gaylord.duit.notifications

import com.gaylord.duit.domain.model.TaskIdentifier
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ScheduledTaskReminder(
    val taskIdentifier: TaskIdentifier,
    val date: LocalDate,
    val time: LocalTime,
    val zoneId: ZoneId,
    val triggerAt: Instant,
)

fun interface ReminderGateway {
    /** Upserts the one reminder effect identified by its stable Task identity. */
    suspend fun reconcile(reminder: ScheduledTaskReminder)
}
