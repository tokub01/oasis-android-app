package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.api.PrivacyValue
import de.velwebsolutions.oasis.account.api.TwoFactorState
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account area's models decode the real answers of the Laravel resources and send the web's fields. */
class AccountRepositoryTest {
    @Test
    fun decodesSettingsPrivacyAndDevices() = runTest {
        val transport = StubTransport()
            .stub("GET", "account", Fixtures.account)
            .stub("GET", "privacy", Fixtures.privacy)
            .stub("GET", "privacy/devices", Fixtures.devices)
            .stub("GET", "privacy/words", Fixtures.hiddenWords)
        val api = repository(transport)
        val settings = api.settings()
        assertEquals("mira", settings.username)
        assertEquals(TwoFactorState.Phase.Off, settings.twoFactor.state)
        assertFalse(settings.passwordConfirmed)

        val privacy = api.privacy()
        assertEquals(listOf("sichtbarkeit", "interaktionen", "auffindbarkeit", "sicherheit"), privacy.sections.map { it.key })
        assertEquals(4, privacy.sections.count { it.inCheckup })
        val profile = privacy.sections.first().settings.first()
        assertEquals("profile_audience", profile.key)
        assertFalse(profile.isSwitch)
        assertEquals(3, profile.choices.size)
        assertEquals(PrivacyValue.Choice("everyone"), profile.value)
        assertEquals("Alle – auch ohne Konto", profile.valueLabel)
        val searchable = privacy.sections.flatMap { it.settings }.first { it.key == "searchable" }
        assertTrue(searchable.isSwitch)
        assertTrue(searchable.choices.isEmpty())
        assertTrue(searchable.value is PrivacyValue.Flag)
        assertTrue(searchable.valueLabel == "Ja" || searchable.valueLabel == "Nein")

        val devices = api.devices()
        assertEquals("iPhone von Mira", devices.current.name)
        assertEquals("iPad von Mira", devices.apps.first().name)
        assertTrue(devices.sessionsListable)
        assertEquals(1, devices.sessions.size)
        assertEquals(1, devices.known.size)
        assertTrue(devices.chatDevices.isEmpty())

        assertEquals(50, api.hiddenWords().max)
    }

    @Test
    fun decodesSafetyTourVotesGlossaryAndReport() = runTest {
        val transport = StubTransport()
            .stub("GET", "safety", Fixtures.safety)
            .stub("GET", "onboarding", Fixtures.onboarding)
            .stub("GET", "votes", Fixtures.votes)
            .stub("GET", "glossary", Fixtures.glossary)
            .stub("GET", "report", Fixtures.report)
        val api = repository(transport)
        assertTrue(api.safety().blocked.isEmpty())
        val tour = api.welcome()
        assertEquals(listOf("profil", "sichtbarkeit", "oasen", "menschen"), tour.steps.map { it.key })
        assertEquals("Sonne", tour.colors.first().label)
        assertTrue(tour.oases.isNotEmpty())
        assertEquals(null, api.votes().open.first().options.first().share)
        assertEquals("Tropfen", api.glossary().first().term)
        val report = api.reportForm("http://localhost/profil/jonas")
        assertEquals(8, report.categories.size)
        assertEquals("http://localhost/profil/jonas", transport.requests.last().request.url.queryParameter("url"))
    }

    @Test
    fun decodesSuspensionAndComplaint() = runTest {
        val transport = StubTransport()
            .stub(
                "GET",
                "suspension",
                """{"data":{"type":"provisional","label":"Vorläufige Sperre bis zur Prüfung","started_at":"2026-10-22T10:00:00.000000Z","accusation":"Verdacht auf eine Beleidigung","excerpt":"Du bist ein Vollidiot","statement":null,"statement_at":null,"strikes":0,"strike_limit":3,"review_hours":24,"statement_max":3000}}"""
            )
            .stub(
                "GET",
                "complaints/7",
                """{"data":{"id":7,"outcome":"Inhalt entfernt","ground":"Community-Regeln","rule_reference":"Regel 1","explanation":"Begründung","deadline":"2027-04-22T10:00:00.000000Z"}}"""
            )
        val api = repository(transport)
        val suspension = api.suspension()
        assertTrue(suspension.isProvisional)
        assertFalse(suspension.isBan)
        assertEquals(3, suspension.strikeLimit)
        assertEquals("Regel 1", api.complaintDecision(7).ruleReference)
    }

    @Test
    fun sendsPrivacyValuesAsSwitchOrAudience() = runTest {
        val saved =
            """{"message":"Gespeichert.","data":{"key":"searchable","title":"Suche","help":"","kind":"switch","value":false,"choices":[],"limited":false}}"""
        val transport = StubTransport()
            .stub("PUT", "privacy/settings/searchable", saved)
            .stub(
                "PUT",
                "privacy/settings/profile_audience",
                """{"message":"Gespeichert.","data":{"key":"profile_audience","title":"Profil","help":"","kind":"choice","value":"members","choices":[],"limited":false}}"""
            )
        val api = repository(transport)
        assertEquals(PrivacyValue.Flag(false), api.updatePrivacy("searchable", PrivacyValue.Flag(false)).data.value)
        assertEquals(JsonObject(mapOf("value" to JsonPrimitive(false))), transport.lastBody("privacy/settings/searchable"))
        api.updatePrivacy("profile_audience", PrivacyValue.Choice("members"))
        assertEquals(JsonObject(mapOf("value" to JsonPrimitive("members"))), transport.lastBody("privacy/settings/profile_audience"))
    }

    @Test
    fun sendsTheBotTrapFieldsOfTheTwoFactorRecoveryAsTheyAre() = runTest {
        val transport = StubTransport().stub(
            "POST",
            "auth/two-factor-recovery",
            """{"message":"Wenn die Angaben stimmen, bekommst du eine E-Mail."}"""
        )
        val form = RegistrationForm(honeypotField = "website_url", tokenField = "form_started", formToken = "abc", trialDays = 30)
        val message = repository(transport).requestTwoFactorRecovery("mira@demo.oasis.tld", "geheim-geheim", form)
        assertEquals("Wenn die Angaben stimmen, bekommst du eine E-Mail.", message)
        assertEquals(
            JsonObject(
                mapOf(
                    "email" to JsonPrimitive("mira@demo.oasis.tld"),
                    "password" to JsonPrimitive("geheim-geheim"),
                    "website_url" to JsonPrimitive(""),
                    "form_started" to JsonPrimitive("abc")
                )
            ),
            transport.lastBody("auth/two-factor-recovery")
        )
    }

    @Test
    fun deletesTheAccountWithTheConsentAndLiftsSafetyEntries() = runTest {
        val transport = StubTransport()
            .stub("DELETE", "account", "{}")
            .stub("DELETE", "safety/mutes/9", """{"message":"Nicht mehr stummgeschaltet."}""")
        val api = repository(transport)
        api.deleteAccount(true)
        assertEquals(JsonObject(mapOf("confirm" to JsonPrimitive(true))), transport.lastBody("account"))
        assertEquals("Nicht mehr stummgeschaltet.", api.lift(de.velwebsolutions.oasis.account.api.SafetyList.Mutes, 9))
    }
}
