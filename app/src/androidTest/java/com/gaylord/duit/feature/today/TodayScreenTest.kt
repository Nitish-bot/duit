package com.gaylord.duit.feature.today

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.gaylord.duit.designsystem.DuitTheme
import com.gaylord.duit.domain.model.CompletionEpoch
import com.gaylord.duit.domain.model.CompletionRecord
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.OccurrenceOutcome
import com.gaylord.duit.domain.model.OccurrenceSnapshot
import com.gaylord.duit.domain.model.OccurrenceSubtaskState
import com.gaylord.duit.domain.model.RecurrenceRule
import com.gaylord.duit.domain.model.SeriesRevision
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.domain.model.SubtaskTemplate
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.workspace.AgendaOccurrence
import com.gaylord.duit.workspace.AgendaSubtask
import com.gaylord.duit.workspace.AgendaTask
import com.gaylord.duit.workspace.PersonalAgenda
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TodayScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun doneFeedbackUsesTheAuthoritativeEpochAndUndoTargetsOnlyThatEpoch() {
        val events = mutableListOf<TodayEvent>()
        val state = mutableStateOf(TodayUiState(personalAgenda = activeAgenda()))
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TodayScreen(
                    state = state.value,
                    use24HourTime = true,
                    onEvent = { event ->
                        events += event
                        when ((event as TodayEvent.Occurrence).action) {
                            is TodayOccurrenceAction.SetSubtaskChecked ->
                                state.value =
                                    state.value.copy(personalAgenda = activeAgenda(isChecked = true))
                            is TodayOccurrenceAction.Complete ->
                                state.value =
                                    TodayUiState(
                                        personalAgenda = completedAgenda(),
                                        completionFeedback = completionFeedback(),
                                    )
                            is TodayOccurrenceAction.Undo ->
                                state.value = TodayUiState(personalAgenda = activeAgenda())
                            else -> Unit
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag("$TodayActiveSubtaskTagPrefix${SoilSubtaskIdentifier.value}")
            .performClick()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("Done — Water the herbs").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performClick()

        val complete = (events[1] as TodayEvent.Occurrence).action as TodayOccurrenceAction.Complete
        assertEquals(setOf(SoilSubtaskIdentifier), complete.checkedSubtaskIdentifiers)
        assertEquals(
            TodayOccurrenceAction.Undo(
                occurrenceKey = DailyOccurrenceKey,
                completionEpoch = CompletionEpoch("completion-current"),
            ),
            (events.last() as TodayEvent.Occurrence).action,
        )
    }

    @Test
    fun completionFeedbackIsAPoliteLiveRegionAndUndoIsDisabledWhileACommandIsPending() {
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TodayScreen(
                    state =
                        TodayUiState(
                            personalAgenda = completedAgenda(),
                            isCommandPending = true,
                            completionFeedback = completionFeedback(),
                        ),
                    use24HourTime = true,
                )
            }
        }

        composeRule.onNodeWithTag(TodayCompletionFeedbackTag)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.onNodeWithText("Undo").assertIsNotEnabled()
    }

    @Test
    fun doneTodayIsCollapsedAndCorrectionClosesOnlyAfterTheStateSuccessKeyAdvances() {
        val events = mutableListOf<TodayEvent>()
        val state = mutableStateOf(TodayUiState(personalAgenda = completedAgenda()))
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TodayScreen(
                    state = state.value,
                    use24HourTime = true,
                    onEvent = { event ->
                        events += event
                        state.value =
                            state.value.copy(
                                isCommandPending = false,
                                correctionSuccessKey = state.value.correctionSuccessKey + 1,
                            )
                    },
                )
            }
        }

        composeRule.onNodeWithText("Water the herbs").assertDoesNotExist()
        composeRule.onNodeWithText("Done today (1)").performClick()
        composeRule.onNodeWithText("Water the herbs").assertIsDisplayed()
        composeRule.onNodeWithText("Correct").performClick()
        composeRule.onNodeWithText("Correct completion").assertIsDisplayed()
        composeRule.onNodeWithText("Mark not done").performClick()
        composeRule.onNodeWithText("Correct completion").assertDoesNotExist()

        assertEquals(
            TodayOccurrenceAction.CorrectToActionable(
                occurrenceKey = DailyOccurrenceKey,
                completionEpoch = CompletionEpoch("completion-current"),
                checkedSubtaskIdentifiers = setOf(SoilSubtaskIdentifier),
            ),
            (events.single() as TodayEvent.Occurrence).action,
        )
    }

    @Test
    fun correctionEditsTimeAndSubtaskWhileDefinitionAndDatesRemainReadOnly() {
        val events = mutableListOf<TodayEvent>()
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TodayScreen(
                    state = TodayUiState(personalAgenda = completedAgenda()),
                    use24HourTime = true,
                    onEvent = { events += it },
                )
            }
        }

        composeRule.onNodeWithText("Done today (1)").performClick()
        composeRule.onNodeWithText("Correct").performClick()
        composeRule.onNodeWithText("Recorded Task: Water the herbs").assertIsDisplayed()
        composeRule.onNodeWithText("Nominal 2026-08-09 · Effective 2026-08-09")
            .assertIsDisplayed()
        composeRule.onNodeWithText("The Task definition and dates stay as recorded.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(TodayCompletionTimeInputTag)
            .assertEditableTextEquals("07:15")
            .performTextReplacement("08:05")
        composeRule.onNodeWithTag("$TodayCorrectionSubtaskTagPrefix${SoilSubtaskIdentifier.value}")
            .performClick()
        composeRule.onNodeWithText("Save changes").performClick()

        assertEquals(
            TodayOccurrenceAction.CorrectCompletion(
                occurrenceKey = DailyOccurrenceKey,
                completionEpoch = CompletionEpoch("completion-current"),
                correctedCompletedAt =
                    MemberActionMoment(
                        instant = Instant.parse("2026-08-09T08:05:00Z"),
                        localDate = TodayDate,
                        zoneId = ZoneId.of("UTC"),
                    ),
                checkedSubtaskIdentifiers = emptySet(),
            ),
            (events.single() as TodayEvent.Occurrence).action,
        )
    }

    @Test
    fun correctionDraftSurvivesStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            DuitTheme(darkTheme = false) {
                TodayScreen(
                    state = TodayUiState(personalAgenda = completedAgenda()),
                    use24HourTime = true,
                )
            }
        }

        composeRule.onNodeWithText("Done today (1)").performClick()
        composeRule.onNodeWithText("Correct").performClick()
        composeRule.onNodeWithTag(TodayCompletionTimeInputTag).performTextReplacement("08:05")
        composeRule.onNodeWithTag(
            "$TodayCorrectionSubtaskTagPrefix${SoilSubtaskIdentifier.value}",
        ).performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Correct completion").assertIsDisplayed()
        composeRule.onNodeWithTag(TodayCompletionTimeInputTag)
            .assertEditableTextEquals("08:05")
        composeRule.onNodeWithTag(
            "$TodayCorrectionSubtaskTagPrefix${SoilSubtaskIdentifier.value}",
        ).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Off),
        )
    }

}

private val TodayDate = LocalDate.of(2026, 8, 9)
private val DailyTaskIdentifier = TaskIdentifier("task-daily")
private val DailyOccurrenceKey = OccurrenceKey(DailyTaskIdentifier, TodayDate)
private val SoilSubtaskIdentifier = SubtaskIdentifier("subtask-soil")

private fun activeAgenda(isChecked: Boolean = false): PersonalAgenda =
    PersonalAgenda(
        date = TodayDate,
        tasks = listOf(agendaTask(outcome = null, isChecked = isChecked)),
    )

private fun completedAgenda(): PersonalAgenda =
    PersonalAgenda(
        date = TodayDate,
        tasks = emptyList(),
        doneToday = listOf(agendaTask(outcome = OccurrenceOutcome.Complete(completionRecord()))),
    )

private fun completionFeedback(): TodayCompletionFeedback =
    TodayCompletionFeedback(
        title = "Water the herbs",
        occurrenceKey = DailyOccurrenceKey,
        completionEpoch = CompletionEpoch("completion-current"),
    )

private fun agendaTask(
    outcome: OccurrenceOutcome?,
    isChecked: Boolean = outcome is OccurrenceOutcome.Complete,
): AgendaTask =
    AgendaTask(
        identifier = DailyTaskIdentifier,
        revisionIdentifier = null,
        seriesRevisionIdentifier = SeriesRevisionIdentifier("revision-daily"),
        title = "Water the herbs",
        plannedDate = null,
        visibility = TaskVisibility.Private,
        reminderAt = null,
        occurrence =
            AgendaOccurrence(
                key = DailyOccurrenceKey,
                nominalDate = TodayDate,
                effectiveDate = TodayDate,
                subtasks =
                    listOf(
                        AgendaSubtask(
                            identifier = SoilSubtaskIdentifier,
                            label = "Check the soil",
                            isChecked = isChecked,
                        ),
                    ),
                outcome = outcome,
            ),
    )

private fun completionRecord(): CompletionRecord =
    CompletionRecord(
        epoch = CompletionEpoch("completion-current"),
        snapshot =
            OccurrenceSnapshot(
                seriesRevision =
                    SeriesRevision(
                        identifier = SeriesRevisionIdentifier("revision-daily"),
                        taskIdentifier = DailyTaskIdentifier,
                        title = "Water the herbs",
                        subtaskTemplate =
                            listOf(SubtaskTemplate(SoilSubtaskIdentifier, "Check the soil")),
                        recurrenceRule = RecurrenceRule.Daily(),
                        startsOn = TodayDate,
                        endsOnInclusive = null,
                        recordedAt =
                            MemberActionMoment(
                                instant = Instant.parse("2026-08-09T06:30:00Z"),
                                localDate = TodayDate,
                                zoneId = ZoneId.of("UTC"),
                            ),
                    ),
                nominalDate = TodayDate,
                effectiveDate = TodayDate,
            ),
        completedAt =
            MemberActionMoment(
                instant = Instant.parse("2026-08-09T07:15:00Z"),
                localDate = TodayDate,
                zoneId = ZoneId.of("UTC"),
            ),
        subtasks = listOf(OccurrenceSubtaskState(SoilSubtaskIdentifier, isChecked = true)),
    )

private fun SemanticsNodeInteraction.assertEditableTextEquals(value: String) =
    assert(
        SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString(value),
        ),
    )
