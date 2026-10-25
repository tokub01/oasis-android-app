package de.velwebsolutions.oasis.account.data

import de.velwebsolutions.oasis.account.api.AccountService
import de.velwebsolutions.oasis.account.api.AccountSettings
import de.velwebsolutions.oasis.account.api.BallotBody
import de.velwebsolutions.oasis.account.api.CodeBody
import de.velwebsolutions.oasis.account.api.ComplaintDecision
import de.velwebsolutions.oasis.account.api.ConfirmPasswordBody
import de.velwebsolutions.oasis.account.api.DeleteBody
import de.velwebsolutions.oasis.account.api.DeviceOverview
import de.velwebsolutions.oasis.account.api.GlossaryTerm
import de.velwebsolutions.oasis.account.api.HiddenWords
import de.velwebsolutions.oasis.account.api.PasswordBody
import de.velwebsolutions.oasis.account.api.PrivacyCentre
import de.velwebsolutions.oasis.account.api.PrivacySettingItem
import de.velwebsolutions.oasis.account.api.PrivacyValue
import de.velwebsolutions.oasis.account.api.ProfileBody
import de.velwebsolutions.oasis.account.api.ProfileChange
import de.velwebsolutions.oasis.account.api.ReasonBody
import de.velwebsolutions.oasis.account.api.ReportBody
import de.velwebsolutions.oasis.account.api.ReportForm
import de.velwebsolutions.oasis.account.api.SafetyList
import de.velwebsolutions.oasis.account.api.SafetyLists
import de.velwebsolutions.oasis.account.api.SettingsChange
import de.velwebsolutions.oasis.account.api.StatementBody
import de.velwebsolutions.oasis.account.api.StatementSaved
import de.velwebsolutions.oasis.account.api.Suspension
import de.velwebsolutions.oasis.account.api.VotesOverview
import de.velwebsolutions.oasis.account.api.WelcomeProfileBody
import de.velwebsolutions.oasis.account.api.WelcomeTour
import de.velwebsolutions.oasis.account.api.WelcomeVisibilityBody
import de.velwebsolutions.oasis.account.api.WordBody
import de.velwebsolutions.oasis.core.auth.RegistrationForm
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.model.ToneHint
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.core.network.CommonApi
import de.velwebsolutions.oasis.core.network.Confirmed
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 423 from the account endpoints: the password has to be confirmed on this device first. */
val ApiError.needsPasswordConfirmation: Boolean
    get() = this is ApiError.Server && status == 423

/** The account area: settings, lock and complaints, privacy, devices, safety, the welcome tour, votes and glossary. */
interface AccountRepository {
    suspend fun settings(): AccountSettings

    suspend fun updateProfile(displayName: String, email: String): ProfileChange

    suspend fun updatePassword(current: String, new: String, confirmation: String): String

    /** „Kurz bestätigen“: valid for this device for `auth.password_timeout` (three hours). */
    suspend fun confirmPassword(password: String): AccountSettings

    suspend fun enableTwoFactor(): SettingsChange

    suspend fun confirmTwoFactor(code: String): SettingsChange

    suspend fun regenerateRecoveryCodes(): SettingsChange

    suspend fun disableTwoFactor(): SettingsChange

    /** The data export ZIP (`oasis-daten.zip`). */
    suspend fun export(): ByteArray

    suspend fun deleteAccount(confirmed: Boolean)

    /** The six-digit code of the confirmation mail; answers with the member, now confirmed. */
    suspend fun verifyEmailCode(code: String): Me

    /** „Zwei-Faktor verloren“, step 1 (guest): the neutral answer of the web form. */
    suspend fun requestTwoFactorRecovery(email: String, password: String, form: RegistrationForm): String

    suspend fun suspension(): Suspension

    suspend fun saveStatement(statement: String): StatementSaved

    suspend fun complaintDecision(id: Int): ComplaintDecision

    suspend fun submitComplaint(decisionId: Int, reason: String)

    suspend fun privacy(): PrivacyCentre

    suspend fun updatePrivacy(key: String, value: PrivacyValue): Confirmed<PrivacySettingItem>

    /** „Tropfen begrenzen“; returns the confirmation with the number of Tropfen. */
    suspend fun limitPastPosts(): String

    suspend fun hiddenWords(): HiddenWords

    suspend fun addHiddenWord(word: String): Confirmed<HiddenWords>

    suspend fun removeHiddenWord(word: String): Confirmed<HiddenWords>

    suspend fun devices(): DeviceOverview

    suspend fun signOutApp(id: Int): String

    suspend fun endSession(key: String): String

    suspend fun signOutEverywhereElse(): String

    suspend fun forgetKnownDevice(id: Int): String

    suspend fun forgetChatDevice(id: Int): String

    suspend fun safety(): SafetyLists

    suspend fun lift(list: SafetyList, userId: Int): String

    suspend fun reportForm(url: String): ReportForm

    suspend fun report(url: String, category: String?, comment: String)

    suspend fun welcome(): WelcomeTour

    suspend fun saveWelcomeProfile(bio: String, location: String, avatarColor: String, toneConfirmed: Boolean)

    suspend fun saveWelcomeVisibility(profileAudience: String, defaultPostVisibility: String)

    /** Follows or leaves a starter oasis; returns whether the member follows it now. */
    suspend fun toggleStarterOasis(id: Int): Boolean

    suspend fun finishWelcome()

    /** The live hint for the profile text (shared with the composer: `network/tone`). */
    suspend fun profileTone(text: String): ToneHint

    suspend fun votes(): VotesOverview

    suspend fun cast(voteId: Int, optionId: Int?): String

    suspend fun glossary(): List<GlossaryTerm>
}

/** [AccountRepository] over the Oasis API. */
class DefaultAccountRepository @Inject constructor(private val client: ApiClient, private val common: CommonApi) : AccountRepository {
    private val service = client.create<AccountService>()

    override suspend fun settings() = service.settings().data

    override suspend fun updateProfile(displayName: String, email: String) = service.updateProfile(ProfileBody(displayName, email))

    override suspend fun updatePassword(current: String, new: String, confirmation: String) =
        service.updatePassword(PasswordBody(current, new, confirmation)).message

    override suspend fun confirmPassword(password: String) = service.confirmPassword(ConfirmPasswordBody(password)).data

    override suspend fun enableTwoFactor() = service.enableTwoFactor()

    override suspend fun confirmTwoFactor(code: String) = service.confirmTwoFactor(CodeBody(code))

    override suspend fun regenerateRecoveryCodes() = service.regenerateRecoveryCodes()

    override suspend fun disableTwoFactor() = service.disableTwoFactor()

    override suspend fun export() = client.download(client.url("account/export").toString())

    override suspend fun deleteAccount(confirmed: Boolean) = service.deleteAccount(DeleteBody(confirmed))

    override suspend fun verifyEmailCode(code: String) = service.verifyEmailCode(CodeBody(code)).data

    override suspend fun requestTwoFactorRecovery(email: String, password: String, form: RegistrationForm): String {
        val fields = mapOf("email" to email, "password" to password, form.honeypotField to "", form.tokenField to form.formToken)
        return service.requestTwoFactorRecovery(JsonObject(fields.mapValues { JsonPrimitive(it.value) })).message
    }

    override suspend fun suspension() = service.suspension().data

    override suspend fun saveStatement(statement: String) = service.saveStatement(StatementBody(statement))

    override suspend fun complaintDecision(id: Int) = service.complaintDecision(id).data

    override suspend fun submitComplaint(decisionId: Int, reason: String) = service.submitComplaint(decisionId, ReasonBody(reason))

    override suspend fun privacy() = service.privacy().data

    override suspend fun updatePrivacy(key: String, value: PrivacyValue): Confirmed<PrivacySettingItem> {
        val wire = when (value) {
            is PrivacyValue.Flag -> JsonPrimitive(value.value)
            is PrivacyValue.Choice -> JsonPrimitive(value.value)
        }
        return service.updatePrivacy(key, JsonObject(mapOf("value" to wire)))
    }

    override suspend fun limitPastPosts() = service.limitPastPosts().message

    override suspend fun hiddenWords() = service.hiddenWords().data

    override suspend fun addHiddenWord(word: String) = service.addHiddenWord(WordBody(word))

    override suspend fun removeHiddenWord(word: String) = service.removeHiddenWord(WordBody(word))

    override suspend fun devices() = service.devices().data

    override suspend fun signOutApp(id: Int) = service.signOutApp(id).message

    override suspend fun endSession(key: String) = service.endSession(key).message

    override suspend fun signOutEverywhereElse() = service.signOutEverywhereElse().message

    override suspend fun forgetKnownDevice(id: Int) = service.forgetKnownDevice(id).message

    override suspend fun forgetChatDevice(id: Int) = service.forgetChatDevice(id).message

    override suspend fun safety() = service.safety().data

    override suspend fun lift(list: SafetyList, userId: Int) = service.lift(list.path, userId).message

    override suspend fun reportForm(url: String) = service.reportForm(url).data

    override suspend fun report(url: String, category: String?, comment: String) = service.report(ReportBody(url, category, comment))

    override suspend fun welcome() = service.welcome().data

    override suspend fun saveWelcomeProfile(bio: String, location: String, avatarColor: String, toneConfirmed: Boolean) =
        service.saveWelcomeProfile(WelcomeProfileBody(bio, location, avatarColor, toneConfirmed))

    override suspend fun saveWelcomeVisibility(profileAudience: String, defaultPostVisibility: String) =
        service.saveWelcomeVisibility(WelcomeVisibilityBody(profileAudience, defaultPostVisibility))

    override suspend fun toggleStarterOasis(id: Int) = service.toggleStarterOasis(id).joined

    override suspend fun finishWelcome() = service.finishWelcome()

    override suspend fun profileTone(text: String) = common.toneHint(text, "profile")

    override suspend fun votes() = service.votes().data

    override suspend fun cast(voteId: Int, optionId: Int?) = service.cast(voteId, BallotBody(optionId)).message

    override suspend fun glossary() = service.glossary().data
}
