package com.gaylord.duit.platform.firebase

import com.gaylord.duit.domain.model.CompletionEpoch
import com.gaylord.duit.domain.model.CompletionRecord
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.OccurrenceOutcome
import com.gaylord.duit.domain.model.OccurrenceSnapshot
import com.gaylord.duit.domain.model.OccurrenceState
import com.gaylord.duit.domain.model.OccurrenceSubtaskState
import com.gaylord.duit.domain.model.RecurrenceRule
import com.gaylord.duit.domain.model.SeriesRevision
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.domain.model.SubtaskTemplate
import com.gaylord.duit.domain.model.TaskIdentifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FirestoreOccurrenceStateCodecTest {
    @Test
    fun `Complete to Actionable correction retains its immutable Occurrence snapshot`() {
        val ownerIdentifier = MemberIdentifier("member-owner")
        val completed = completedState()
        val decodedComplete =
            FirestoreOccurrenceStateCodec.decode(
                ownerIdentifier,
                FirestoreOccurrenceStateCodec.encode(ownerIdentifier, completed),
            )
        val completion =
            (decodedComplete.outcome as OccurrenceOutcome.Complete).record
        val actionable =
            decodedComplete.copy(
                outcome = null,
                actionableSubtasksBeforeCompletion = null,
                invalidatedCompletionEpochs =
                    decodedComplete.invalidatedCompletionEpochs + completion.epoch,
            )

        val encodedActionable = FirestoreOccurrenceStateCodec.encode(ownerIdentifier, actionable)
        val reconstructed =
            FirestoreOccurrenceStateCodec.decode(ownerIdentifier, encodedActionable)

        assertFalse(encodedActionable.containsKey("completionEpoch"))
        assertEquals(actionable, reconstructed)
        assertEquals(completed.snapshot, reconstructed.snapshot)
    }
}

private fun completedState(): OccurrenceState {
    val date = LocalDate.of(2026, 8, 9)
    val taskIdentifier = TaskIdentifier("task-herbs")
    val subtaskIdentifier = SubtaskIdentifier("subtask-soil")
    val moment =
        MemberActionMoment(
            instant = Instant.parse("2026-08-09T07:15:00Z"),
            localDate = date,
            zoneId = ZoneId.of("UTC"),
        )
    val snapshot =
        OccurrenceSnapshot(
            seriesRevision =
                SeriesRevision(
                    identifier = SeriesRevisionIdentifier("revision-herbs"),
                    taskIdentifier = taskIdentifier,
                    title = "Water the herbs",
                    subtaskTemplate =
                        listOf(SubtaskTemplate(subtaskIdentifier, "Check the soil")),
                    recurrenceRule = RecurrenceRule.Daily(),
                    startsOn = date,
                    endsOnInclusive = null,
                    recordedAt = moment,
                ),
            nominalDate = date,
            effectiveDate = date,
        )
    val completedSubtasks =
        listOf(OccurrenceSubtaskState(subtaskIdentifier, isChecked = true))
    val completion =
        CompletionRecord(
            epoch = CompletionEpoch("completion-first"),
            snapshot = snapshot,
            completedAt = moment,
            subtasks = completedSubtasks,
        )

    return OccurrenceState(
        key = OccurrenceKey(taskIdentifier, date),
        snapshot = snapshot,
        subtasks = completedSubtasks,
        outcome = OccurrenceOutcome.Complete(completion),
        actionableSubtasksBeforeCompletion =
            listOf(OccurrenceSubtaskState(subtaskIdentifier, isChecked = false)),
    )
}
