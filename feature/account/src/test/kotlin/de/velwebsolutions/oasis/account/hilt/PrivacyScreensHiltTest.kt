package de.velwebsolutions.oasis.account.hilt

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Privatsphäre-Center, Privatsphäre-Check and Verborgene Wörter. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivacyScreensHiltTest : HiltScreenTest() {
    private fun stack() = navigator.stack(navigator.selectedTab).toList()

    @Test
    fun theCentreSavesSettingsLimitsPostsAndLinksOnwards() {
        signIn()
        stub("GET", "privacy", Fixtures.privacy)
        stub(
            "PUT",
            "privacy/settings/searchable",
            """{"message":"Gespeichert.","data":{"key":"searchable","title":"Auffindbar","kind":"switch","value":false}}"""
        )
        stub("PUT", "privacy/settings/profile_audience", """{"message":"x","errors":{"value":["Nicht erlaubt."]}}""", status = 422)
        stub("POST", "privacy/limit-past-posts", """{"message":"3 Tropfen sind jetzt nur für Mitglieder sichtbar."}""")
        show { AccountScreen(AccountRoute.Privacy) }
        waitForTag("privacy.checkup")
        shot("privacy")
        compose.onNodeWithTag("privacy.profile_audience.members").tap()
        waitForText("Nicht erlaubt.")
        compose.onNodeWithContentDescription("In der Oasis-Suche auffindbar").tap()
        waitForRequest("PUT", "privacy/settings/searchable")
        compose.onNodeWithText("Tropfen begrenzen").tap()
        screenShot("privacy-limit")
        compose.onAllNodesText("Abbrechen").last().press()
        compose.onNodeWithText("Tropfen begrenzen").tap()
        compose.onAllNodesText("Tropfen begrenzen").last().press()
        waitForRequest("POST", "privacy/limit-past-posts")
        listOf("privacy.checkup", "privacy.words", "privacy.safety", "privacy.devices", "privacy.hints", "privacy.settings")
            .forEach { compose.onNodeWithTag(it).tap() }
        assertEquals(
            listOf(
                AccountRoute.PrivacyCheckup,
                AccountRoute.HiddenWords,
                AccountRoute.Safety,
                AccountRoute.Devices,
                LiveRoute.Settings,
                AccountRoute.Settings
            ),
            stack()
        )
        compose.onNodeWithTag("privacy.checkup").performTouchInput { swipeDown() }
    }

    @Test
    fun theCentreWithoutPublicPostsAndWithHiddenWordsAndALimitedSwitch() {
        signIn()
        stub(
            "GET",
            "privacy",
            """{"data":{"sections":[{"key":"s","title":"Sicherheit","in_checkup":true,"settings":[""" +
                """{"key":"login_alerts","title":"Anmelde-Warnungen","kind":"switch","value":true,"limited":true}]}],""" +
                """"counts":{"public_posts":0,"hidden_words":1}}}"""
        )
        stub("POST", "privacy/limit-past-posts", """{"message":"Server Error"}""", status = 500)
        show { AccountScreen(AccountRoute.Privacy) }
        waitForText("Du hast gerade keine öffentlichen Tropfen.", substring = true)
        compose.onNodeWithText("1 Wort verborgen.", substring = true).assertExists()
        compose.onNodeWithText("Unter 18 gilt hier eine strengere Grenze", substring = true).assertExists()
        shot("privacy-limited")
    }

    @Test
    fun manyPublicPostsAndHiddenWords() {
        signIn()
        stub("GET", "privacy", """{"data":{"sections":[],"counts":{"public_posts":1,"hidden_words":4}}}""")
        stub("POST", "privacy/limit-past-posts", """{"message":"Server Error"}""", status = 500)
        show { AccountScreen(AccountRoute.Privacy) }
        waitForText("Betrifft 1 Tropfen.", substring = true)
        compose.onNodeWithText("4 Wörter verborgen.", substring = true).assertExists()
        compose.onNodeWithText("Tropfen begrenzen").tap()
        compose.onAllNodesText("Tropfen begrenzen").last().press()
        waitForRequest("POST", "privacy/limit-past-posts")
    }

    @Test
    fun theCheckupWalksTheSectionsToTheSummary() {
        signIn()
        stub("GET", "privacy", Fixtures.privacy)
        navigator.stack(navigator.selectedTab).add(AccountRoute.PrivacyCheckup)
        show { AccountScreen(AccountRoute.PrivacyCheckup) }
        waitForTag("checkup.next")
        shot("checkup")
        repeat(4) { compose.onNodeWithTag("checkup.next").tap() }
        waitForTag("checkup.done")
        shot("checkup-summary")
        compose.onNodeWithText("Zurück").tap()
        compose.onNodeWithTag("checkup.next").tap()
        listOf(
            "Blockiert, stumm & eingeschränkt",
            "Verborgene Wörter",
            "Geräte und Anmeldungen",
            "Deine Daten herunterladen und Konto löschen"
        )
            .forEach { compose.onNodeWithText(it).tap() }
        assertEquals(5, stack().size)
        compose.onNodeWithTag("checkup.done").tap()
        compose.onNodeWithTag("checkup.done").tap()
    }

    @Test
    fun theCheckupInASheetClosesIt() {
        signIn()
        stub("GET", "privacy", """{"data":{"sections":[],"counts":{}}}""")
        navigator.present(AccountRoute.PrivacyCheckup)
        show { AccountScreen(AccountRoute.PrivacyCheckup) }
        waitForTag("checkup.done")
        compose.onNodeWithTag("checkup.done").tap()
        assertEquals(null, navigator.sheet)
    }

    @Test
    fun wordsAreAddedRefusedAndRemoved() {
        signIn()
        stub("GET", "privacy/words", Fixtures.hiddenWords)
        stub("POST", "privacy/words", """{"message":"x","errors":{"word":["Ein Wort braucht mindestens 2 Zeichen."]}}""", status = 422)
        show { AccountScreen(AccountRoute.HiddenWords) }
        waitForText("Du verbirgst noch keine Wörter.")
        shot("words-empty")
        type("Wort oder Ausdruck hinzufügen", "x")
        compose.onNodeWithTag("words.add").tap()
        waitForText("Ein Wort braucht mindestens 2 Zeichen.")
        stub("POST", "privacy/words", """{"message":"Server Error"}""", status = 500)
        compose.onNodeWithTag("words.add").tap()
        stub("POST", "privacy/words", """{"message":"„spoiler“ wird verborgen.","data":{"words":["spoiler","krimi"],"max":50}}""")
        compose.onNodeWithTag("words.add").tap()
        waitForText("spoiler")
        shot("words")
        stub("DELETE", "privacy/words", """{"message":"Server Error"}""", status = 500)
        compose.onNodeWithContentDescription("„krimi“ nicht mehr verbergen").tap()
        waitForRequest("DELETE", "privacy/words")
        stub("DELETE", "privacy/words", """{"message":"Wird wieder gezeigt.","data":{"words":["spoiler"],"max":50}}""")
        compose.onNodeWithContentDescription("„krimi“ nicht mehr verbergen").tap()
        compose.waitUntil(5_000) { compose.onAllNodesText("krimi").isEmpty() }
        assertTrue(transport.requests.count { it.path == "privacy/words" && it.request.method == "DELETE" } == 2)
    }
}
