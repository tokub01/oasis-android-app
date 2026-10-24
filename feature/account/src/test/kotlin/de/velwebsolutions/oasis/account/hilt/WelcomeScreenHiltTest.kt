package de.velwebsolutions.oasis.account.hilt

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The welcome tour: about you with the tone hint, who sees you, oases, people – and leaving it. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeScreenHiltTest : HiltScreenTest() {
    private fun stack() = navigator.stack(navigator.selectedTab).toList()

    @Test
    fun theTourWalksAllStepsAndEndsWhereItWasOpened() {
        signIn()
        stub("GET", "onboarding", Fixtures.onboarding.replace("\"banner_url\": null", "\"banner_url\": \"http://localhost/banner.jpg\""))
        stub("POST", "network/tone", """{"data":{"hint":"Alter","nudge":true}}""")
        stub("PUT", "onboarding/profile", """{"message":"x","errors":{"bio":["Bitte freundlich."]}}""", status = 422)
        stub("PUT", "onboarding/visibility", "{}")
        stub("POST", "onboarding/oases/3", """{"joined":true}""")
        stub("POST", "onboarding/finish", "{}")
        stub("GET", "auth/me", """{"data":${Fixtures.ME}}""")
        navigator.stack(navigator.selectedTab).add(AccountRoute.Welcome)
        show { AccountScreen(AccountRoute.Welcome) }
        waitForTag("welcome.title")
        type("Über dich (optional)", " Alter!")
        type("Ort (optional)", " Mitte")
        compose.mainClock.advanceTimeBy(1_000)
        waitForText("Kurzer Hinweis", substring = true)
        shot("welcome-profile")
        compose.onNodeWithContentDescription("Quelle").tap()
        compose.onNodeWithTag("welcome.next").tap()
        waitForText("Bitte freundlich.")
        compose.onNodeWithTag("welcome.toneConfirmed").tap()
        stub("PUT", "onboarding/profile", "{}")
        compose.onNodeWithTag("welcome.next").tap()
        waitForTag("welcome.audience.members")
        shot("welcome-visibility")
        compose.onNodeWithTag("welcome.audience.members").tap()
        compose.onNodeWithTag("welcome.visibility.members").tap()
        compose.onNodeWithText("Alle Einstellungen im Privatsphäre-Check").tap()
        compose.onNodeWithTag("welcome.next").tap()
        waitForText("Alle Oasen ansehen")
        shot("welcome-oases")
        compose.onAllNodesText("Folgen").first().tap()
        waitForRequest("POST", "onboarding/oases/3")
        compose.onNodeWithText("Alle Oasen ansehen").tap()
        compose.onNodeWithTag("welcome.skip").tap()
        waitForText("Freund:innen einladen")
        shot("welcome-people")
        type("Menschen suchen", "lea")
        compose.onNodeWithText("Suchen").tap()
        compose.onNodeWithText("Freund:innen einladen").tap()
        compose.onNodeWithText("Zurück").tap()
        compose.onNodeWithTag("welcome.next").tap()
        compose.onNodeWithTag("welcome.finish").tap()
        waitForRequest("POST", "onboarding/finish")
        // Opened again from the settings, the tour goes back one screen (here the last page opened from it).
        compose.waitUntil(5_000) { stack().last() == NetworkRoute.Search("lea") }
        assertEquals(listOf(AccountRoute.Welcome, AccountRoute.PrivacyCheckup, NetworkRoute.Oases), stack().take(3))
    }

    @Test
    fun aNewMemberLeavesTheTourForTheQuelleAndFailuresAreShown() {
        signIn()
        stub(
            "GET",
            "onboarding",
            """{"data":{"steps":[{"key":"profil","title":"Über dich"},{"key":"menschen","title":"Menschen finden"}],"onboarded":false}}"""
        )
        stub("PUT", "onboarding/profile", """{"message":"Server Error"}""", status = 500)
        stub("POST", "onboarding/finish", """{"message":"Server Error"}""", status = 500)
        stub("POST", "network/tone", """{"message":"Server Error"}""", status = 500)
        show { AccountScreen(AccountRoute.Welcome) }
        waitForTag("welcome.title")
        type("Über dich (optional)", "Hallo")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("welcome.next").tap()
        waitForRequest("PUT", "onboarding/profile")
        compose.onNodeWithTag("welcome.later").tap()
        waitForRequest("POST", "onboarding/finish")
        stub("POST", "onboarding/finish", "{}")
        stub("GET", "auth/me", """{"data":${Fixtures.ME}}""")
        compose.onNodeWithTag("welcome.later").tap()
        compose.waitUntil(5_000) { transport.requests.count { it.path == "onboarding/finish" } == 2 }
        assertEquals(emptyList<Any>(), stack())
    }

    @Test
    fun theTourWithoutOasesAndALongText() {
        signIn()
        stub(
            "GET",
            "onboarding",
            """{"data":{"steps":[{"key":"oasen","title":"Oasen finden"}],"onboarded":true}}"""
        )
        stub("POST", "onboarding/oases/1", """{"message":"Server Error"}""", status = 500)
        show { AccountScreen(AccountRoute.Welcome) }
        waitForText("Noch gibt es keine öffentlichen Oasen.", substring = true)
        shot("welcome-no-oases")
    }
}
