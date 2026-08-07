package com.gaylord.duit.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
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
}
