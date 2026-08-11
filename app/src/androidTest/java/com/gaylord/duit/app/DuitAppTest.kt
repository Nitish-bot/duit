package com.gaylord.duit.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskRevisionIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.feature.today.TodayTaskTitleInputTag
import com.gaylord.duit.feature.today.TodayTaskRepeatDailyTag
import com.gaylord.duit.feature.today.TodayTaskSubtaskInputTag
import com.gaylord.duit.workspace.AgendaTask
import com.gaylord.duit.workspace.AgendaOccurrence
import com.gaylord.duit.workspace.AgendaSubtask
import com.gaylord.duit.workspace.AuthenticationSignOutFailure
import com.gaylord.duit.workspace.CommandIdentifier
import com.gaylord.duit.workspace.MemberApprovalFailure
import com.gaylord.duit.workspace.OwnerWorkspaceHydrationFailure
import com.gaylord.duit.workspace.PersonalAgenda
import com.gaylord.duit.workspace.WorkspaceAccessAction
import com.gaylord.duit.workspace.WorkspaceAccessState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DuitAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun opensTodayAndNavigatesAmongTheFourPrimaryDestinations() {
        composeRule.setContent {
            DuitApp(darkTheme = false)
        }

        assertDestinationIsVisible("Today")

        navigateTo("Together")
        assertDestinationIsVisible("Together")

        navigateTo("Plan")
        assertDestinationIsVisible("Plan")

        navigateTo("Tasks")
        assertDestinationIsVisible("Tasks")

        navigateTo("Today")
        assertDestinationIsVisible("Today")
    }

    @Test
    fun primaryNavigationRemainsVisibleAndOperableAtTwoHundredPercentText() {
        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = deviceDensity,
                    fontScale = 2f,
                ),
            ) {
                DuitApp(darkTheme = true)
            }
        }

        listOf("Today", "Together", "Plan", "Tasks").forEach { label ->
            navigationItem(label).assertIsDisplayed()
        }

        navigateTo("Tasks")
        assertDestinationIsVisible("Tasks")
    }

    @Test
    fun readyWorkspaceShowsHydratedTaskOnToday() {
        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                personalAgenda = agendaWithTask(title = "Water the balcony herbs"),
            )
        }

        composeRule.onNodeWithText("Water the balcony herbs").assertIsDisplayed()
        composeRule.onNodeWithText("Your day is clear").assertDoesNotExist()
    }

    @Test
    fun readyWorkspaceShowsTheDailyOccurrenceAndItsUncheckedSubtaskOnToday() {
        val date = LocalDate.of(2026, 8, 9)
        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                personalAgenda =
                    PersonalAgenda(
                        date = date,
                        tasks =
                            listOf(
                                AgendaTask(
                                    identifier = TaskIdentifier("task-daily"),
                                    revisionIdentifier = null,
                                    title = "Water the herbs",
                                    plannedDate = null,
                                    visibility = TaskVisibility.Private,
                                    reminderAt = null,
                                    seriesRevisionIdentifier =
                                        SeriesRevisionIdentifier("revision-daily"),
                                    occurrence =
                                        AgendaOccurrence(
                                            key =
                                                OccurrenceKey(
                                                    TaskIdentifier("task-daily"),
                                                    date,
                                                ),
                                            nominalDate = date,
                                            effectiveDate = date,
                                            subtasks =
                                                listOf(
                                                    AgendaSubtask(
                                                        identifier =
                                                            SubtaskIdentifier("subtask-soil"),
                                                        label = "Check the soil",
                                                        isChecked = false,
                                                    ),
                                                ),
                                        ),
                                ),
                            ),
                    ),
            )
        }

        composeRule.onNodeWithText("Water the herbs").assertIsDisplayed()
        composeRule.onNodeWithText("Daily · Today").assertIsDisplayed()
        composeRule.onNodeWithText("Unchecked · Check the soil").assertIsDisplayed()
    }

    @Test
    fun blankPrivateTaskCannotBeAdded() {
        val addedTitles = mutableListOf<String>()

        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                onAddPrivateTaskToToday = { _, title, result ->
                    addedTitles += title
                    result(true)
                },
            )
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("   ")
        composeRule.onNodeWithText("Add Task").assertIsNotEnabled()
        assertEquals(emptyList<String>(), addedTitles)
    }

    @Test
    fun dailyTaskAddLetsTheOwnerChooseDailyAndOneNormalizedSubtask() {
        val additions = mutableListOf<DailyTaskAddition>()

        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                onAddPrivateDailyTaskToToday = { _, title, subtask, result ->
                    additions += DailyTaskAddition(title, subtask)
                    result(true)
                },
            )
        }

        composeRule.onNodeWithTag(TodayTaskRepeatDailyTag).performScrollTo().performClick()
        composeRule.onNodeWithTag(TodayTaskTitleInputTag)
            .performTextInput("  Water the herbs  ")
        composeRule.onNodeWithTag(TodayTaskSubtaskInputTag)
            .performTextInput("  Check the soil  ")
        composeRule.onNodeWithText("Add Task").performScrollTo().performClick()

        assertEquals(
            listOf(DailyTaskAddition("Water the herbs", "Check the soil")),
            additions,
        )
        composeRule.onNodeWithTag(TodayTaskTitleInputTag).assertEditableTextEquals("")
        composeRule.onNodeWithTag(TodayTaskSubtaskInputTag).assertDoesNotExist()
    }

    @Test
    fun privateTaskAddActionEmitsTrimmedTitleAtTwoHundredPercentText() {
        val addedTitles = mutableListOf<String>()

        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = deviceDensity,
                    fontScale = 2f,
                ),
            ) {
                DuitApp(
                    darkTheme = false,
                    onAddPrivateTaskToToday = { _, title, result ->
                        addedTitles += title
                        result(true)
                    },
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("  Water the herbs  ")
        composeRule.onNodeWithText("Add Task")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf("Water the herbs"), addedTitles)
        composeRule.onNodeWithTag(TodayTaskTitleInputTag).assertEditableTextEquals("")
    }

    @Test
    fun failedPrivateTaskAddKeepsDraftForRetry() {
        val commandIdentifiers = mutableListOf<CommandIdentifier>()

        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                onAddPrivateTaskToToday = { commandIdentifier, _, result ->
                    commandIdentifiers += commandIdentifier
                    result(false)
                },
            )
        }

        composeRule.onNodeWithTag(TodayTaskTitleInputTag).performTextInput("Water the herbs")
        composeRule.onNodeWithText("Add Task").performClick()

        composeRule.onNodeWithTag(TodayTaskTitleInputTag)
            .assertIsNotEnabled()
            .assertEditableTextEquals("Water the herbs")
        composeRule.onNodeWithText("Add Task").assertIsEnabled()
            .performClick()
        assertEquals(2, commandIdentifiers.size)
        assertEquals(commandIdentifiers.first(), commandIdentifiers.last())
    }

    @Test
    fun pendingPrivateTaskAddDisablesDraftUntilPersistenceResponds() {
        var completeAdd: ((Boolean) -> Unit)? = null

        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                onAddPrivateTaskToToday = { _, _, result -> completeAdd = result },
            )
        }

        composeRule.onNodeWithTag(TodayTaskTitleInputTag).performTextInput("Water the herbs")
        composeRule.onNodeWithText("Add Task").performClick()

        composeRule.onNodeWithTag(TodayTaskTitleInputTag).assertIsNotEnabled()
        composeRule.onNodeWithText("Add Task").assertIsNotEnabled()

        composeRule.runOnIdle { requireNotNull(completeAdd)(false) }
        composeRule.onNodeWithTag(TodayTaskTitleInputTag)
            .assertIsNotEnabled()
            .assertEditableTextEquals("Water the herbs")
        composeRule.onNodeWithText("Add Task").assertIsEnabled()
    }

    @Test
    fun recreationKeepsAPendingPrivateTaskDraftAvailableForRetry() {
        val restorationTester = StateRestorationTester(composeRule)
        val commandIdentifiers = mutableListOf<CommandIdentifier>()
        restorationTester.setContent {
            DuitApp(
                darkTheme = false,
                onAddPrivateTaskToToday = { commandIdentifier, _, _ ->
                    commandIdentifiers += commandIdentifier
                },
            )
        }

        composeRule.onNodeWithTag(TodayTaskTitleInputTag).performTextInput("Water the herbs")
        composeRule.onNodeWithText("Add Task").performClick()
        composeRule.onNodeWithTag(TodayTaskTitleInputTag).assertIsNotEnabled()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(TodayTaskTitleInputTag)
            .assertIsNotEnabled()
            .assertEditableTextEquals("Water the herbs")
        composeRule.onNodeWithText("Add Task").assertIsEnabled().performClick()
        assertEquals(2, commandIdentifiers.size)
        assertEquals(commandIdentifiers.first(), commandIdentifiers.last())
    }

    @Test
    fun signedOutIdentitySeesOnlyGoogleSignInAction() {
        val actions = mutableListOf<WorkspaceAccessAction>()

        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                accessState = WorkspaceAccessState.SignInRequired,
                onAccessAction = { actions += it },
            )
        }

        composeRule.onNode(hasText("Sign in to Duit") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Sign in with Google").performClick()
        composeRule.onNode(hasText("Today") and isHeading()).assertDoesNotExist()
        navigationItem("Tasks").assertDoesNotExist()
        assertEquals(listOf(WorkspaceAccessAction.SignIn), actions)
    }

    @Test
    fun unapprovedIdentityGetsExactDevelopmentApprovalInstructions() {
        val uid = "firebase-user-123"
        val actions = mutableListOf<WorkspaceAccessAction>()

        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                accessState = WorkspaceAccessState.ApprovalRequired(authenticatedUid = uid),
                onAccessAction = { actions += it },
            )
        }

        composeRule.onNode(hasText("Approval needed") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Firebase UID").assertIsDisplayed()
        composeRule.onNodeWithText(uid).assertIsDisplayed()
        composeRule.onNodeWithText(
            "1. Open Firestore Database in the development Firebase console.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "2. Create the document /memberApprovals/$uid.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "3. Add a Boolean field named approved and set it to true.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("4. Return here and tap Retry.").assertIsDisplayed()

        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Sign out").performClick()

        navigationItem("Tasks").assertDoesNotExist()
        assertEquals(
            listOf(WorkspaceAccessAction.Retry, WorkspaceAccessAction.SignOut),
            actions,
        )
    }

    @Test
    fun approvalCheckFailureDoesNotMasqueradeAsManualApproval() {
        val uid = "firebase-user-123"
        val actions = mutableListOf<WorkspaceAccessAction>()

        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                accessState = WorkspaceAccessState.ApprovalCheckFailed(
                    authenticatedUid = uid,
                    failure = MemberApprovalFailure.ServerUnavailable,
                ),
                onAccessAction = { actions += it },
            )
        }

        composeRule.onNode(
            hasText("Couldn’t verify access") and isHeading(),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Nothing was changed. Duit couldn’t verify whether this account is approved.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(uid).assertIsDisplayed()
        composeRule.onNodeWithText("Approval needed").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Sign out").performClick()

        navigationItem("Tasks").assertDoesNotExist()
        assertEquals(
            listOf(WorkspaceAccessAction.Retry, WorkspaceAccessAction.SignOut),
            actions,
        )
    }

    @Test
    fun hydrationKeepsWorkspaceLockedAndExposesSignOut() {
        val actions = mutableListOf<WorkspaceAccessAction>()

        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                accessState = WorkspaceAccessState.Hydrating(
                    memberIdentifier = MemberIdentifier("member-a"),
                ),
                onAccessAction = { actions += it },
            )
        }

        composeRule.onNode(
            hasText("Setting up your workspace") and isHeading(),
        ).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Loading workspace")).assertIsDisplayed()
        composeRule.onNodeWithText("Your Tasks stay locked while Duit loads the server workspace.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Sign out").performClick()

        navigationItem("Tasks").assertDoesNotExist()
        assertEquals(listOf(WorkspaceAccessAction.SignOut), actions)
    }

    @Test
    fun failedHydrationReassuresMemberAndOffersRecoveryActions() {
        val actions = mutableListOf<WorkspaceAccessAction>()

        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                accessState = WorkspaceAccessState.Failed(
                    memberIdentifier = MemberIdentifier("member-a"),
                    failure = OwnerWorkspaceHydrationFailure.ServerUnavailable,
                ),
                onAccessAction = { actions += it },
            )
        }

        composeRule.onNode(
            hasText("Couldn’t load your workspace") and isHeading(),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Nothing was changed. Your Tasks are still safe.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Sign out").performClick()

        navigationItem("Tasks").assertDoesNotExist()
        assertEquals(
            listOf(WorkspaceAccessAction.Retry, WorkspaceAccessAction.SignOut),
            actions,
        )
    }

    @Test
    fun failedSafeSignOutKeepsPrivateWorkspaceHiddenAndOffersRecovery() {
        val actions = mutableListOf<WorkspaceAccessAction>()

        composeRule.setContent {
            DuitApp(
                darkTheme = false,
                accessState = WorkspaceAccessState.SignOutFailed(
                    authenticatedUid = "member-a",
                    failure = AuthenticationSignOutFailure.ServerUnavailable,
                ),
                onAccessAction = { actions += it },
                personalAgenda = agendaWithTask("Water the balcony herbs"),
            )
        }

        composeRule.onNode(
            hasText("Couldn’t sign out safely") and isHeading(),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Water the balcony herbs").assertDoesNotExist()
        navigationItem("Tasks").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Sign out").performClick()

        assertEquals(
            listOf(WorkspaceAccessAction.Retry, WorkspaceAccessAction.SignOut),
            actions,
        )
    }

    @Test
    fun approvalActionsRemainReachableAtTwoHundredPercentText() {
        val actions = mutableListOf<WorkspaceAccessAction>()

        composeRule.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = deviceDensity,
                    fontScale = 2f,
                ),
            ) {
                DuitApp(
                    darkTheme = true,
                    accessState = WorkspaceAccessState.ApprovalRequired(
                        authenticatedUid = "long-development-firebase-uid-1234567890",
                    ),
                    onAccessAction = { actions += it },
                )
            }
        }

        composeRule.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed().performClick()

        assertEquals(
            listOf(WorkspaceAccessAction.Retry, WorkspaceAccessAction.SignOut),
            actions,
        )
    }

    private fun navigateTo(label: String) {
        navigationItem(label).performClick()
    }

    private fun navigationItem(label: String) = composeRule.onNode(
        hasText(label) and hasClickAction(),
    )

    private fun assertDestinationIsVisible(label: String) {
        composeRule.onNode(
            hasText(label) and isHeading(),
        ).assertIsDisplayed()
        navigationItem(label).assertIsSelected()
    }

    private fun agendaWithTask(title: String) =
        PersonalAgenda(
            date = LocalDate.of(2026, 8, 9),
            tasks =
                listOf(
                    AgendaTask(
                        identifier = TaskIdentifier("task-1"),
                        revisionIdentifier = TaskRevisionIdentifier("revision-1"),
                        title = title,
                        plannedDate = LocalDate.of(2026, 8, 9),
                        visibility = TaskVisibility.Private,
                        reminderAt = null,
                    ),
                ),
        )
}

private data class DailyTaskAddition(
    val title: String,
    val subtask: String?,
)

private fun SemanticsNodeInteraction.assertEditableTextEquals(value: String) =
    assert(
        SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString(value),
        ),
    )
