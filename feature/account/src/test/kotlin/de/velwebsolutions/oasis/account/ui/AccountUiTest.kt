package de.velwebsolutions.oasis.account.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.account.api.PrivacyCentre
import de.velwebsolutions.oasis.account.api.PrivacySettingItem
import de.velwebsolutions.oasis.account.api.PrivacyValue
import de.velwebsolutions.oasis.account.ui.auth.RegisterContent
import de.velwebsolutions.oasis.account.ui.auth.RegisterUiState
import de.velwebsolutions.oasis.account.ui.auth.SignInActions
import de.velwebsolutions.oasis.account.ui.auth.SignInContent
import de.velwebsolutions.oasis.account.ui.auth.SignInUiState
import de.velwebsolutions.oasis.account.ui.auth.TwoFactorContent
import de.velwebsolutions.oasis.account.ui.auth.TwoFactorUiState
import de.velwebsolutions.oasis.account.ui.auth.VerifyEmailActions
import de.velwebsolutions.oasis.account.ui.auth.VerifyEmailContent
import de.velwebsolutions.oasis.account.ui.auth.VerifyEmailUiState
import de.velwebsolutions.oasis.account.ui.components.AccountPage
import de.velwebsolutions.oasis.account.ui.components.QrCodeView
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyCenterContent
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyCheckupContent
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyUiState
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The core flows of the account area on the JVM (iOS AccountUITests): sign-in with 2FA, registration, privacy. */
@RunWith(RobolectricTestRunner::class)
class AccountUiTest {
    @get:Rule val compose = createComposeRule()

    private val centre: PrivacyCentre = OasisJson.snake.decodeFromString<DataEnvelope<PrivacyCentre>>(Fixtures.privacy).data

    @Test
    fun signInTypesTheCredentialsAndOffersRegistrationAndReset() {
        val typed = mutableListOf<String>()
        var signIns = 0
        var registers = 0
        compose.setContent {
            SignInContent(
                ui = SignInUiState(login = "mira", password = "geheim"),
                actions = SignInActions(
                    onLogin = { typed += "login:$it" },
                    onPassword = { typed += "password:$it" },
                    onSignIn = { signIns += 1 },
                    onForgotPassword = {},
                    onRegister = { registers += 1 },
                    onLoginCode = {},
                    onPasskey = null
                )
            )
        }
        compose.onNodeWithText("Willkommen zurück.").assertIsDisplayed()
        compose.onNodeWithContentDescription("E-Mail oder Benutzername").performTextInput("x")
        assertEquals("the field reports the edited text", listOf("login:xmira"), typed)
        compose.onNodeWithTag("signin.button").performScrollTo().performClick()
        compose.waitUntil { signIns == 1 }
        compose.onNodeWithText("Passwort vergessen?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mitglied werden").performScrollTo().performClick()
        assertEquals(1, registers)
    }

    @Test
    fun signInShowsAFieldRefusalAtTheFieldAndOtherErrorsAsBanner() {
        compose.setContent {
            SignInContent(
                SignInUiState(
                    login = "mira",
                    error = ApiError.Validation("x", mapOf("login" to listOf("Diese Zugangsdaten stimmen nicht.")))
                ),
                SignInActions({}, {}, {}, {}, {}, {}, null)
            )
        }
        compose.onNodeWithText("Diese Zugangsdaten stimmen nicht.").assertIsDisplayed()
        compose.onNodeWithTag("signin.button").assertIsNotEnabled()
    }

    @Test
    fun theSecondFactorTakesTheCodeAndLeadsToTheRecovery() {
        var submitted = 0
        var lost = 0
        compose.setContent {
            TwoFactorContent(
                TwoFactorUiState(
                    code = "123456",
                    error = ApiError.Validation("x", mapOf("challenge" to listOf("Bitte melde dich erneut an.")))
                ),
                {},
                {},
                { submitted += 1 },
                { lost += 1 }
            )
        }
        compose.onNodeWithText("Noch ein Schritt").assertIsDisplayed()
        compose.onNodeWithText("Bitte melde dich erneut an.").assertIsDisplayed()
        compose.onNodeWithTag("twofactor.submit").performScrollTo().performClick()
        compose.waitUntil { submitted == 1 }
        compose.onNodeWithTag("twofactor.lost").performScrollTo().performClick()
        assertEquals(1, lost)
    }

    @Test
    fun registrationAsksForTheBirthDateAndOpensTheLegalPages() {
        val legal = mutableListOf<String>()
        compose.setContent {
            RegisterContent(
                RegisterUiState(form = RegistrationForm("website_url", "form_started", "abc"), birthDateMissing = true),
                {},
                {},
                {},
                {},
                { legal += it }
            )
        }
        compose.onNodeWithText("Werde Teil der Oase.").assertIsDisplayed()
        compose.onNodeWithText("Bitte prüfe die markierten Felder.").assertIsDisplayed()
        compose.onNodeWithText("So geht es los").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Beitrag wählen – bezahlt wird über Google Play").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Das Geburtsdatum ist erforderlich.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("AGB").performScrollTo().performClick()
        assertEquals(listOf("agb"), legal)
        compose.onNodeWithTag("register.birthDate").performScrollTo().performClick()
        compose.onNodeWithTag("register.birthDate.take").assertIsDisplayed()
    }

    @Test
    fun drawsEveryPrivacySettingFromItsKindAndChoices() {
        val changes = mutableListOf<Pair<String, PrivacyValue>>()
        val opened = mutableListOf<AppRoute>()
        compose.setContent {
            PrivacyCenterContent(
                PrivacyUiState(state = Loadable.Loaded(centre)),
                onRetry = {},
                onChange = { setting: PrivacySettingItem, value: PrivacyValue -> changes += setting.key to value },
                onLimit = {},
                open = { opened += it }
            )
        }
        compose.onNodeWithText("Wer darf dein Profil sehen?").assertIsDisplayed()
        compose.onNodeWithTag("privacy.profile_audience.everyone").assertIsSelected()
        compose.onNodeWithTag("privacy.profile_audience.members").performScrollTo().performClick()
        assertEquals("profile_audience" to PrivacyValue.Choice("members"), changes.last())

        val searchable = centre.sections.flatMap { it.settings }.first { it.key == "searchable" }
        compose.onNodeWithContentDescription(searchable.title).performScrollTo().performClick()
        assertEquals("searchable" to PrivacyValue.Flag(!searchable.value.flag), changes.last())

        compose.onNodeWithTag("privacy.checkup").performScrollTo().performClick()
        assertEquals(AccountRoute.PrivacyCheckup, opened.last())
    }

    @Test
    fun theCheckupShowsOneSectionPerStepAndTheSummaryAtTheEnd() {
        var next = 0
        compose.setContent {
            AccountPage {
                PrivacyCheckupContent(PrivacyUiState(state = Loadable.Loaded(centre), step = 5), { _, _ -> }, { next += 1 }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("checkup.title").assertIsDisplayed()
        compose.onNodeWithText("Alles im Blick").assertIsDisplayed()
        compose.onNodeWithText("Zusammenfassung").assertIsDisplayed()
        compose.onNodeWithTag("checkup.done").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun systemBackGoesOneCheckupStepBackBeforeLeaving() {
        var backs = 0
        var dispatcher: OnBackPressedDispatcher? = null
        var step by mutableIntStateOf(2)
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            AccountPage {
                PrivacyCheckupContent(PrivacyUiState(state = Loadable.Loaded(centre), step = step), { _, _ -> }, {}, {
                    backs += 1
                    step -= 1
                }, {}, {})
            }
        }
        compose.runOnUiThread { dispatcher!!.onBackPressed() }
        compose.waitForIdle()
        assertEquals("back on step 2 goes to step 1", 1, backs)
        compose.runOnUiThread { dispatcher!!.onBackPressed() }
        compose.waitForIdle()
        assertEquals("on the first step back leaves the check (the screen's own back)", 1, backs)
    }

    @Test
    fun aRefusedPrivacyValueShowsItsMessageAtTheSetting() {
        compose.setContent {
            AccountPage {
                PrivacyCheckupContent(
                    PrivacyUiState(
                        state = Loadable.Loaded(centre),
                        step = 1,
                        errors = mapOf("profile_audience" to "Diese Auswahl gibt es nicht.")
                    ),
                    { _, _ -> },
                    {},
                    {},
                    {},
                    {}
                )
            }
        }
        compose.onNodeWithText("Schritt 1 von 4 – jede Auswahl gilt sofort.").assertIsDisplayed()
        compose.onNodeWithText("Diese Auswahl gibt es nicht.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun verifyEmailNamesTheAddressAndExplainsAMissingConfirmation() {
        compose.setContent {
            VerifyEmailContent("mira@demo.oasis.tld", VerifyEmailUiState(checked = true), VerifyEmailActions({}, {}, {}, {}, {}, {}))
        }
        compose.onNodeWithText("Fast geschafft.").assertIsDisplayed()
        compose.onNodeWithText("mira@demo.oasis.tld", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Deine Adresse ist noch nicht bestätigt.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("verify.resend").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun drawsTheQrCodeOfTheTwoFactorSetup() {
        compose.setContent { QrCodeView("otpauth://totp/Oasis:mira%40demo.oasis.tld?secret=ABCDEFGHIJKLMNOP&issuer=Oasis") }
        compose.onNodeWithContentDescription("QR-Code für die Authenticator-App").assertIsDisplayed()
    }
}
