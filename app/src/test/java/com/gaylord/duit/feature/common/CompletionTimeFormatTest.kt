package com.gaylord.duit.feature.common

import com.gaylord.duit.domain.model.MemberActionMoment
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionTimeFormatTest {
    @Test
    fun `localized 12 hour output parses without changing the locale marker case`() {
        val formatter = completionTimeFormatter(use24HourTime = false, locale = Locale.forLanguageTag("el-GR"))
        val time = LocalTime.of(8, 5)

        assertEquals(time, parseCompletionTime(time.format(formatter), formatter))
        assertEquals(time, parseCompletionTime("  ${time.format(formatter)}  ", formatter))
    }

    @Test
    fun `an unedited completion time keeps the recorded moment exactly`() {
        val recorded =
            MemberActionMoment(
                instant = Instant.parse("2026-08-09T02:35:42.123Z"),
                localDate = LocalDate.of(2026, 8, 9),
                zoneId = ZoneId.of("Asia/Kolkata"),
            )

        assertEquals(LocalTime.of(8, 5), recorded.completionLocalTime())
        assertEquals(recorded, recorded.withCorrectedLocalTime(LocalTime.of(8, 5)))
    }

    @Test
    fun `an unedited completion time in a repeated daylight-saving hour keeps its instant`() {
        val recorded =
            MemberActionMoment(
                instant = Instant.parse("2026-11-01T06:30:00Z"),
                localDate = LocalDate.of(2026, 11, 1),
                zoneId = ZoneId.of("America/New_York"),
            )

        assertEquals(recorded, recorded.withCorrectedLocalTime(recorded.completionLocalTime()))
    }

    @Test
    fun `an edited completion time keeps the recorded local date and zone`() {
        val recorded =
            MemberActionMoment(
                instant = Instant.parse("2026-08-09T02:35:42Z"),
                localDate = LocalDate.of(2026, 8, 9),
                zoneId = ZoneId.of("Asia/Kolkata"),
            )

        assertEquals(
            MemberActionMoment(
                instant = Instant.parse("2026-08-09T01:45:00Z"),
                localDate = LocalDate.of(2026, 8, 9),
                zoneId = ZoneId.of("Asia/Kolkata"),
            ),
            recorded.withCorrectedLocalTime(LocalTime.of(7, 15)),
        )
    }
}
