package com.gaylord.duit.feature.common

import com.gaylord.duit.domain.model.MemberActionMoment
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

fun completionTimeFormatter(
    use24HourTime: Boolean,
    locale: Locale,
): DateTimeFormatter =
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern(if (use24HourTime) "HH:mm" else "h:mm a")
        .toFormatter(locale)

fun parseCompletionTime(
    value: String,
    formatter: DateTimeFormatter,
): LocalTime? =
    runCatching { LocalTime.parse(value.trim(), formatter) }.getOrNull()

/** The recorded completion time as the Owner sees it: local to its recorded zone, to the minute. */
fun MemberActionMoment.completionLocalTime(): LocalTime =
    instant.atZone(zoneId).toLocalTime().withSecond(0).withNano(0)

/**
 * Applies an edited time of day to a recorded completion without moving its local date or zone.
 * An unedited time returns the recorded moment unchanged, preserving its exact instant.
 */
fun MemberActionMoment.withCorrectedLocalTime(correctedLocalTime: LocalTime): MemberActionMoment =
    if (correctedLocalTime == completionLocalTime()) {
        this
    } else {
        copy(instant = LocalDateTime.of(localDate, correctedLocalTime).atZone(zoneId).toInstant())
    }
