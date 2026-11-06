package de.velwebsolutions.oasis.account.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.account.api.PrivacyCentre
import de.velwebsolutions.oasis.account.api.PrivacySettingItem
import de.velwebsolutions.oasis.account.api.PrivacyValue
import de.velwebsolutions.oasis.account.ui.auth.RegisterActions
import de.velwebsolutions.oasis.account.ui.auth.RegisterContent
import de.velwebsolutions.oasis.account.ui.auth.RegisterPage
import de.velwebsolutions.oasis.account.ui.auth.RegisterUiState
import de.velwebsolutions.oasis.account.ui.auth.RegisterViewModel
import de.velwebsolutions.oasis.account.ui.auth.SignInActions
import de.velwebsolutions.oasis.account.ui.auth.SignInContent
import de.velwebsolutions.oasis.account.ui.auth.SignInUiState
import de.velwebsolutions.oasis.account.ui.auth.TwoFactorContent
import de.velwebsolutions.oasis.account.ui.auth.TwoFactorUiState
import de.velwebsolutions.oasis.account.ui.auth.UsernameCheck
import de.velwebsolutions.oasis.account.ui.auth.VerifyEmailActions
import de.velwebsolutions.oasis.account.ui.auth.VerifyEmailContent
import de.velwebsolutions.oasis.account.ui.auth.VerifyEmailUiState
import de.velwebsolutions.oasis.account.ui.components.AccountPage
import de.velwebsolutions.oasis.account.ui.components.QrCodeView
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyCenterContent
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyCheckupContent
import de.velwebsolutions.oasis.account.ui.privacy.PrivacyUiState
import de.velwebsolutions.oasis.core.auth.Registration
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val legal = mutableListOf<String>()
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
                    onPasskey = null,
                    onLegal = { legal += it }
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
        // § 5 DDG: Impressum and Datenschutz before anyone signs in.
        compose.onNodeWithTag("signin.impressum").performScrollTo().performClick()
        compose.onNodeWithTag("signin.datenschutz").performScrollTo().performClick()
        assertEquals(listOf("impressum", "datenschutz"), legal)
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
                RegisterUiState(
                    form = RegistrationForm("website_url", "form_started", "abc"),
                    page = RegisterPage.Finish,
                    fieldErrors = mapOf("birth_date" to RegisterViewModel.BIRTH_DATE_REQUIRED, "terms" to RegisterViewModel.TERMS_REQUIRED)
                ),
                RegisterActions.None,
                {},
                { legal += it }
            )
        }
        compose.onNodeWithText("Fast geschafft").assertIsDisplayed()
        compose.onNodeWithText("Schritt 1 von 4 · Konto").assertIsDisplayed()
        compose.onNodeWithContentDescription("Seite 3 von 3").assertExists()
        compose.onNodeWithText("So geht es los").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Beitrag wählen – bezahlt wird über Google Play").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Das Geburtsdatum ist erforderlich.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bitte akzeptiere die Nutzungsbedingungen.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("AGB").performScrollTo().performClick()
        assertEquals(listOf("agb"), legal)
        compose.onNodeWithTag("register.birthDate").performScrollTo().performClick()
        compose.onNodeWithTag("register.birthDate.take").assertIsDisplayed()
    }

    @Test
    fun aBirthDateClearedInTheKeyboardInputIsNotTaken() {
        val taken = mutableListOf<java.time.LocalDate>()
        val actions = RegisterActions({}, {}, {}, {}, { taken += it }, {}, {}, {})
        compose.setContent {
            RegisterContent(
                RegisterUiState(
                    form = RegistrationForm("website_url", "form_started", "abc"),
                    page = RegisterPage.Finish
                ),
                actions,
                {
                },
                {}
            )
        }
        compose.onNodeWithTag("register.birthDate").performScrollTo().performClick()
        // The pencil of Material's date picker switches to typing the date; an empty or incomplete one selects nothing.
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextClearance()
        compose.onNodeWithTag("register.birthDate.take").assertIsNotEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertTrue("nothing taken, and no crash", taken.isEmpty())
        compose.onNodeWithTag("register.birthDate.take").assertExists()
    }

    @Test
    fun theUsernameLineOffersTheSuggestionAndThePasswordShowsItsRule() {
        val taken = mutableListOf<String>()
        var state by mutableStateOf(
            RegisterUiState(
                registration = Registration(displayName = "Mira", username = "mira"),
                usernameCheck = UsernameCheck.Taken("mira", "mira2")
            )
        )
        val actions = RegisterActions({}, {}, { taken += it }, {}, {}, {}, {}, {})
        compose.setContent { RegisterContent(state, actions, {}, {}) }
        compose.onNodeWithText("Wie sollen andere dich nennen?").assertExists()
        compose.onNodeWithText("@mira ist schon vergeben – wie wäre es mit ", substring = true).assertExists()
        compose.onNodeWithTag("register.username.suggestion").performClick()
        assertEquals(listOf("mira2"), taken)
        state = state.copy(usernameCheck = UsernameCheck.Taken("mira", null))
        compose.onNodeWithText("@mira ist schon vergeben.").assertExists()
        state = state.copy(usernameCheck = UsernameCheck.Free("mira"))
        compose.onNodeWithText("@mira ist frei.").assertExists()
        state = state.copy(usernameCheck = UsernameCheck.Invalid("Mi", "Der Benutzername muss mindestens 3 Zeichen lang sein."))
        compose.onNodeWithText("Der Benutzername muss mindestens 3 Zeichen lang sein.").assertExists()
        state = state.copy(error = ApiError.Validation("x", mapOf("username" to listOf("Dieser Benutzername ist nicht erlaubt."))))
        compose.onNodeWithText("Dieser Benutzername ist nicht erlaubt.").assertExists()
        compose.onNodeWithText("Bitte prüfe die markierten Felder.").assertExists()
        compose.onNodeWithText("@mira ist frei.").assertDoesNotExist()
        state = state.copy(error = ApiError.Server(500, "Server Error"))
        compose.onNodeWithText("Server Error").assertExists()

        state = RegisterUiState(page = RegisterPage.SignIn, registration = Registration(password = "kurz"))
        compose.onNodeWithText("Wie meldest du dich an?").assertExists()
        compose.onNodeWithTag("register.password.rule").assertExists()
        compose.onNodeWithText("Mindestens 10 Zeichen", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Später kannst du einen Passkey einrichten und dich ganz ohne Passwort anmelden.").assertExists()
        compose.onNodeWithContentDescription("Passwort anzeigen").performClick()
        compose.onNodeWithContentDescription("Passwort verbergen").performClick()
        compose.onNodeWithContentDescription("Passwort anzeigen").assertExists()
        state = state.copy(registration = Registration(password = "ein-langes-passwort"))
        compose.onNodeWithTag("register.password.rule").assertExists()
        compose.onNodeWithText("Werbefrei und ohne Algorithmus, getragen von den Mitgliedern.").assertDoesNotExist()
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
