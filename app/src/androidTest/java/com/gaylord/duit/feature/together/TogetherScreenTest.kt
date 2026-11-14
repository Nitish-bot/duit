package com.gaylord.duit.feature.together

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gaylord.duit.designsystem.DuitTheme
import com.gaylord.duit.domain.pairing.PairInvite
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.pairing.PartnerIdentity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TogetherScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unpairedTogetherExplainsPrivacyAndOffersCreateOrEnterActions() {
        var createRequests = 0
        val acceptedCodes = mutableListOf<String>()
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TogetherScreen(
                    state = TogetherState.Unpaired,
                    onEvent = { event ->
                        when (event) {
                            TogetherEvent.CreateInvite -> createRequests += 1
                            is TogetherEvent.AcceptInvite -> acceptedCodes += event.code
                            else -> Unit
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("Pairing is optional").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Pairing never shares Tasks automatically. Your Tasks stay private until you choose to share them.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Create Pair Invite").performClick()
        composeRule.onNodeWithText("Enter code").assertIsNotEnabled()
        composeRule.onNodeWithTag(PairInviteCodeInputTag)
            .performTextInput("  7K3M9P2Q  ")
        composeRule.onNodeWithText("Enter code").performClick()

        assertEquals(1, createRequests)
        assertEquals(listOf("7K3M9P2Q"), acceptedCodes)
    }

    @Test
    fun pendingPairShowsGoogleIdentityAndRequiresExplicitConfirmation() {
        var confirmations = 0
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TogetherScreen(
                    state =
                        TogetherState.PartnerIdentityReview(
                            pairIdentifier = PairIdentifier("pair-alex-sam"),
                            partnerIdentity =
                                PartnerIdentity(
                                    "Sam Rivera",
                                    "https://example.test/sam.jpg",
                                ),
                            partnerAlreadyConfirmed = true,
                        ),
                    onEvent = { event ->
                        if (event == TogetherEvent.ConfirmPartnerIdentity) confirmations += 1
                    },
                )
            }
        }

        composeRule.onNodeWithText("Is this your Partner?").assertIsDisplayed()
        composeRule.onNodeWithText("Sam Rivera").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sam Rivera’s Google profile photo")
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "They already confirmed. You’re still in control of your confirmation.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Pairing never shares Tasks automatically. Your Tasks stay private until you choose to share them.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Confirm Partner").performClick()

        assertEquals(1, confirmations)
    }

    @Test
    fun createdInviteShowsOnlyTheShortLivedOneUseCode() {
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TogetherScreen(
                    state =
                        TogetherState.InviteReady(
                            PairInvite(
                                code = "7K3M9P2Q",
                                expiresAtEpochMillis = Long.MAX_VALUE,
                            ),
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Your Pair Invite").assertIsDisplayed()
        composeRule.onNodeWithText("7K3M9P2Q").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Share this one-use code with your Partner. It expires shortly.",
        ).assertIsDisplayed()
    }

    @Test
    fun confirmationMovesFromCalmWaitingCopyToPrivateByDefaultActivePair() {
        val pairIdentifier = PairIdentifier("pair-alex-sam")
        val partnerIdentity = PartnerIdentity("Sam Rivera", null)
        var state: TogetherState by
            mutableStateOf(
                TogetherState.WaitingForPartner(pairIdentifier, partnerIdentity),
            )
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TogetherScreen(state = state)
            }
        }

        composeRule.onNodeWithText("Waiting for Sam Rivera").assertIsDisplayed()
        composeRule.onNodeWithText(
            "You confirmed this identity. Duit will keep checking while Together is open.",
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            state = TogetherState.ActivePair(pairIdentifier, partnerIdentity)
        }

        composeRule.onNodeWithText("Together with Sam Rivera").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Pairing is active. Your existing Tasks are still private until you choose to share them.",
        ).assertIsDisplayed()
    }

    @Test
    fun PairingFailuresStayRecoverableAndReassureThatPersonalTasksRemainAvailable() {
        var retries = 0
        var state: TogetherState by mutableStateOf(TogetherState.ConnectionRequired)
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                TogetherScreen(
                    state = state,
                    onEvent = { event ->
                        if (event == TogetherEvent.Retry) retries += 1
                    },
                )
            }
        }

        composeRule.onNodeWithText("Pairing needs a connection").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Pairing requires a connection. Your personal Tasks are still available.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        composeRule.runOnIdle {
            state = TogetherState.RecoverableError("The Pair Invite has expired.")
        }
        composeRule.onNodeWithText("Couldn’t update Together").assertIsDisplayed()
        composeRule.onNodeWithText("The Pair Invite has expired.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(2, retries)
    }

    @Test
    fun TogetherBindsPairingRefreshToItsStartedLifecycle() {
        var opened = 0
        var closed = 0
        var showTogether by mutableStateOf(true)
        composeRule.setContent {
            DuitTheme(darkTheme = false) {
                if (showTogether) {
                    TogetherScreen(
                        state = TogetherState.Loading,
                        onEvent = { event ->
                            when (event) {
                                TogetherEvent.Started -> opened += 1
                                TogetherEvent.Stopped -> closed += 1
                                else -> Unit
                            }
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Checking Pairing status")
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, opened)
            showTogether = false
        }
        composeRule.waitForIdle()

        assertEquals(1, closed)
    }
}
