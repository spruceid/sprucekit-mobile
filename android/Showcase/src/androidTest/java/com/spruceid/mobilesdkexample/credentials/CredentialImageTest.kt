package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Needs a device: `android.util.Base64` and `BitmapFactory` are unavailable to JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class CredentialImageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** A 1x1 transparent PNG. */
    private val pngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    /** Renders inside a tagged container so an absent image can be told from an absent hierarchy. */
    private fun setContentInContainer(image: String, alt: String) {
        composeTestRule.setContent {
            Box(Modifier.testTag("container")) {
                CredentialImage(image, alt)
            }
        }
        composeTestRule.onNodeWithTag("container").assertExists()
    }

    @Test
    fun httpLinkRendersRemotelyInsteadOfCrashing() {
        setContentInContainer("http://example.com/logo.png", "issuer logo")

        // The node exists because we took the AsyncImage branch; the load itself fails silently.
        composeTestRule.onNodeWithContentDescription("issuer logo").assertExists()
    }

    @Test
    fun httpsLinkRendersRemotely() {
        setContentInContainer("https://example.com/logo.png", "issuer logo")

        composeTestRule.onNodeWithContentDescription("issuer logo").assertExists()
    }

    @Test
    fun inlineBase64ImageRenders() {
        setContentInContainer("data:image/png;base64,$pngBase64", "portrait")

        composeTestRule.onNodeWithContentDescription("portrait").assertIsDisplayed()
    }

    @Test
    fun bareBase64ImageRenders() {
        setContentInContainer(pngBase64, "portrait")

        composeTestRule.onNodeWithContentDescription("portrait").assertIsDisplayed()
    }

    @Test
    fun base64ThatCannotBeDecodedIsSkippedInsteadOfCrashing() {
        // One leftover character is the only length `Base64.decode` actually rejects.
        setContentInContainer("data:image/png;base64,A", "portrait")

        composeTestRule.onNodeWithContentDescription("portrait").assertDoesNotExist()
    }

    @Test
    fun validBase64ThatIsNotAnImageIsSkippedInsteadOfCrashing() {
        // Decodes cleanly, but BitmapFactory can't make an image of it.
        setContentInContainer("aGVsbG8gd29ybGQ=", "portrait")

        composeTestRule.onNodeWithContentDescription("portrait").assertDoesNotExist()
    }
}
