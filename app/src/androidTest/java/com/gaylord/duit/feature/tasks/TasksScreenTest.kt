package com.gaylord.duit.feature.tasks

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.gaylord.duit.designsystem.DuitTheme
import com.gaylord.duit.domain.model.CompletionEpoch
import com.gaylord.duit.domain.model.CompletionRecord
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.OccurrenceOutcome
import com.gaylord.duit.domain.model.OccurrenceSnapshot
import com.gaylord.duit.domain.model.OccurrenceSubtaskState
import com.gaylord.duit.domain.model.RecurrenceRule
import com.gaylord.duit.domain.model.SeriesRevision
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.ShareGeneration
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.domain.model.SubtaskTemplate
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.workspace.AgendaOccurrence
import com.gaylord.duit.workspace.AgendaSubtask
import com.gaylord.duit.workspace.AgendaTask
import com.gaylord.duit.workspace.PersonalHistory
import com.gaylord.duit.workspace.RecurringSeriesDetail
import com.gaylord.duit.workspace.RecurringSeriesLibraryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TasksScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun repeatingTaskLibraryOpensOneTaskDetailThroughTypedEvents() {
        val events = mutableListOf<TasksEvent>()
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TasksScreen(
                    state = libraryState(),
                    onEvent = events::add,
                )
            }
        }

        composeRule.onNodeWithText("Repeating Tasks").assertIsDisplayed()
        composeRule.onNodeWithText("Water the herbs").performClick()

        assertEquals(
            listOf(TasksEvent.SelectTask(DailyTaskIdentifier)),
            events.filterIsInstance<TasksEvent.SelectTask>(),
        )
    }

    @Test
    fun pairedOwnerSharesAndRevokesThroughTypedEventsWithHonestOfflineCopy() {
        val events = mutableListOf<TasksEvent>()
        var state by mutableStateOf(detailState().copy(partnerName = "Sam"))
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TasksScreen(state = state, onEvent = events::add)
            }
        }

        composeRule.onNodeWithText("Private. Only you can see this Task.").assertIsDisplayed()
        composeRule.onNodeWithTag("tasks-share-with-partner").performClick()
        composeRule.runOnIdle {
            val detail = requireNotNull(state.selectedTask)
            state =
                state.copy(
                    selectedTask =
                        detail.copy(
                            visibility =
                                TaskVisibility.Shared(
                                    PairIdentifier("pair-1"),
                                    ShareGeneration("generation-1"),
                                    MemberIdentifier("member-sam"),
                                ),
                        ),
                )
        }
        composeRule.onNodeWithText("Shared with your Partner, read-only.").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Making this Task Private takes effect for you immediately, even offline. " +
                "Your Partner’s phone may keep showing what it already received until it next syncs.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("tasks-make-private").performClick()

        assertEquals(
            listOf(
                TasksEvent.ShareWithPartner(DailyTaskIdentifier),
                TasksEvent.MakePrivate(DailyTaskIdentifier),
            ),
            events.filter { it is TasksEvent.ShareWithPartner || it is TasksEvent.MakePrivate },
        )
    }

    @Test
    fun unpairedOwnerSeesNoShareAction() {
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TasksScreen(state = detailState())
            }
        }

        composeRule.onNodeWithText("Sharing is available while you are paired.").assertIsDisplayed()
        composeRule.onNodeWithTag("tasks-share-with-partner").assertDoesNotExist()
    }

    @Test
    fun correctionEditsTimeAndSubtasksWhileHistoricalDefinitionAndDatesStayReadOnly() {
        val events = mutableListOf<TasksEvent>()
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TasksScreen(
                    state = detailState(),
                    use24HourTime = true,
                    onEvent = events::add,
                )
            }
        }

        composeRule.onNodeWithText("Completion history").assertIsDisplayed()
        composeRule.onNodeWithText("Correct").performClick()
        composeRule.onNodeWithText("Recorded Task: Water the herbs").assertIsDisplayed()
        composeRule.onNode(
            hasText("Nominal 2026-08-09 · Effective 2026-08-09") and
                hasAnyAncestor(isDialog()),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "The recorded Task definition and occurrence dates cannot be changed.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(TasksCompletionTimeInputTag)
            .assertEditableTextEquals("07:15")
            .performTextReplacement("08:05")
        composeRule.onNodeWithTag(
            "$TasksCorrectionSubtaskTagPrefix${SoilSubtaskIdentifier.value}",
        ).performClick()
        composeRule.onNodeWithText("Save changes").performClick()

        assertEquals(
            listOf(
                TasksEvent.CorrectCompletion(
                    occurrenceKey = DailyOccurrenceKey,
                    completionEpoch = CompletionEpoch("completion-current"),
                    correctedCompletedAt =
                        MemberActionMoment(
                            instant = Instant.parse("2026-08-09T08:05:00Z"),
                            localDate = CompletionDate,
                            zoneId = ZoneId.of("UTC"),
                        ),
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            ),
            events.filterIsInstance<TasksEvent.CorrectCompletion>(),
        )
    }

    @Test
    fun historicalCompletionCanBeCorrectedBackToActionable() {
        val events = mutableListOf<TasksEvent>()
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TasksScreen(
                    state = detailState(),
                    use24HourTime = true,
                    onEvent = events::add,
                )
            }
        }

        composeRule.onNodeWithText("Correct").performClick()
        composeRule.onNodeWithText("Mark not done").performClick()

        assertEquals(
            listOf(
                TasksEvent.CorrectToActionable(
                    occurrenceKey = DailyOccurrenceKey,
                    completionEpoch = CompletionEpoch("completion-current"),
                    checkedSubtaskIdentifiers = setOf(SoilSubtaskIdentifier),
                ),
            ),
            events.filterIsInstance<TasksEvent.CorrectToActionable>(),
        )
    }

    @Test
    fun historicalCorrectionDraftSurvivesStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            DuitTheme(darkTheme = false) {
                TasksScreen(
                    state = detailState(),
                    use24HourTime = true,
                )
            }
        }

        composeRule.onNodeWithText("Correct").performClick()
        composeRule.onNodeWithTag(TasksCompletionTimeInputTag).performTextReplacement("08:05")
        composeRule.onNodeWithTag(
            "$TasksCorrectionSubtaskTagPrefix${SoilSubtaskIdentifier.value}",
        ).performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Correct completion").assertIsDisplayed()
        composeRule.onNodeWithTag(TasksCompletionTimeInputTag)
            .assertEditableTextEquals("08:05")
        composeRule.onNodeWithTag(
            "$TasksCorrectionSubtaskTagPrefix${SoilSubtaskIdentifier.value}",
        ).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Off),
        )
    }
}

private val CompletionDate = LocalDate.of(2026, 8, 9)
private val DailyTaskIdentifier = TaskIdentifier("task-daily")
private val DailyOccurrenceKey = OccurrenceKey(DailyTaskIdentifier, CompletionDate)
private val SoilSubtaskIdentifier = SubtaskIdentifier("subtask-soil")

private fun libraryState(): TasksUiState =
    TasksUiState(
        library = listOf(RecurringSeriesLibraryEntry(detail = recurringDetail())),
        isLibraryLoading = false,
    )

private fun detailState(): TasksUiState =
    TasksUiState(
        library = listOf(RecurringSeriesLibraryEntry(detail = recurringDetail())),
        selectedTaskIdentifier = DailyTaskIdentifier,
        selectedTask = recurringDetail(),
        history = PersonalHistory(completions = listOf(completedAgendaTask())),
        isLibraryLoading = false,
        isHistoryLoading = false,
    )

private fun recurringDetail(): RecurringSeriesDetail =
    RecurringSeriesDetail(
        identifier = DailyTaskIdentifier,
        ownerIdentifier = MemberIdentifier("member-1"),
        visibility = TaskVisibility.Private,
        createdAt = recordedMoment(),
        initialRevision = seriesRevision(),
    )

private fun completedAgendaTask(): AgendaTask =
    AgendaTask(
        identifier = DailyTaskIdentifier,
        revisionIdentifier = null,
        title = "Water the herbs",
        plannedDate = null,
        visibility = TaskVisibility.Private,
        reminderAt = null,
        seriesRevisionIdentifier = SeriesRevisionIdentifier("revision-daily"),
        occurrence =
            AgendaOccurrence(
                key = DailyOccurrenceKey,
                nominalDate = CompletionDate,
                effectiveDate = CompletionDate,
                subtasks =
                    listOf(
                        AgendaSubtask(
                            identifier = SoilSubtaskIdentifier,
                            label = "Check the soil",
                            isChecked = true,
                        ),
                    ),
                outcome = OccurrenceOutcome.Complete(completionRecord()),
            ),
    )

private fun completionRecord(): CompletionRecord =
    CompletionRecord(
        epoch = CompletionEpoch("completion-current"),
        snapshot =
            OccurrenceSnapshot(
                seriesRevision = seriesRevision(),
                nominalDate = CompletionDate,
                effectiveDate = CompletionDate,
            ),
        completedAt =
            MemberActionMoment(
                instant = Instant.parse("2026-08-09T07:15:00Z"),
                localDate = CompletionDate,
                zoneId = ZoneId.of("UTC"),
            ),
        subtasks = listOf(OccurrenceSubtaskState(SoilSubtaskIdentifier, isChecked = true)),
    )

private fun seriesRevision(): SeriesRevision =
    SeriesRevision(
        identifier = SeriesRevisionIdentifier("revision-daily"),
        taskIdentifier = DailyTaskIdentifier,
        title = "Water the herbs",
        subtaskTemplate = listOf(SubtaskTemplate(SoilSubtaskIdentifier, "Check the soil")),
        recurrenceRule = RecurrenceRule.Daily(),
        startsOn = CompletionDate.minusDays(5),
        endsOnInclusive = null,
        recordedAt = recordedMoment(),
    )

private fun recordedMoment(): MemberActionMoment =
    MemberActionMoment(
        instant = Instant.parse("2026-08-04T06:30:00Z"),
        localDate = CompletionDate.minusDays(5),
        zoneId = ZoneId.of("UTC"),
    )

private fun SemanticsNodeInteraction.assertEditableTextEquals(value: String) =
    assert(
        SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString(value),
        ),
    )
