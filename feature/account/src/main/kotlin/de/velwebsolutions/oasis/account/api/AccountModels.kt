package de.velwebsolutions.oasis.account.api

import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.OasisInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// Account settings (`App\Http\Resources\Api\V1\Account\AccountSettingsResource`).

/** The blocks of /app/einstellungen: profile, two-factor state and this device's password confirmation. */
@Serializable
data class AccountSettings(
    val displayName: String,
    val username: String,
    val email: String,
    val emailVerified: Boolean = true,
    val twoFactor: TwoFactorState = TwoFactorState(),
    val passwordConfirmed: Boolean = false
)

/** Two-factor sign-in: off, set up but not confirmed (with what the authenticator app needs), or active. */
@Serializable
data class TwoFactorState(
    val state: Phase = Phase.Off,
    /** The secret to type into the authenticator app, only while pending. */
    val setupKey: String? = null,
    /** `otpauth://totp/…`, shown as QR code and as a link to the authenticator app, only while pending. */
    val otpauthUrl: String? = null
) {
    @Serializable
    enum class Phase {
        @SerialName("off")
        Off,

        @SerialName("pending")
        Pending,

        @SerialName("active")
        Active
    }
}

/**
 * Answer of a two-factor change: the German status message, the new state and – only right after confirming two-factor
 * or creating new codes – the recovery codes, shown once like the web.
 */
@Serializable
data class SettingsChange(val message: String, val data: AccountSettings, val recoveryCodes: List<String>? = null)

/** One passkey of the account (`PasskeyResource`): never the key itself, only what the member sees. */
@Serializable
data class Passkey(
    val id: Int,
    val name: String,
    /** The password manager it lives in, when the authenticator says so („Google Passwortmanager“). */
    val authenticator: String? = null,
    val createdAt: OasisInstant? = null,
    val lastUsedAt: OasisInstant? = null
)

/** The optional mobile number for sign-in codes by SMS (`account/phone`); numbers only masked. */
@Serializable
data class PhoneNumberStatus(
    /** False while SMS is not set up on the server: then there is nothing to enter. */
    val smsAvailable: Boolean = false,
    /** The confirmed number, masked („+49 176 ••• 78“). */
    val phoneNumber: String? = null,
    val verifiedAt: OasisInstant? = null,
    /** A new number waiting for the code of its SMS, masked. */
    val pending: String? = null
)

/** `{message, data: Me}` of a profile change. */
@Serializable
data class ProfileChange(val message: String? = null, val data: Me)

// Lock screen and complaints.

/** The active lock (/app/gesperrt). */
@Serializable
data class Suspension(
    val type: String,
    val label: String,
    val startedAt: OasisInstant,
    val accusation: String,
    val excerpt: String? = null,
    val statement: String? = null,
    val statementAt: OasisInstant? = null,
    val strikes: Int = 0,
    val strikeLimit: Int = 3,
    val reviewHours: Int = 24,
    val statementMax: Int = 3000
) {
    val isBan: Boolean get() = type == "ban"
    val isProvisional: Boolean get() = type == "provisional"
}

@Serializable
data class StatementSaved(val message: String, val statementAt: OasisInstant)

/** A moderation decision as the affected member may read it. */
@Serializable
data class ComplaintDecision(
    val id: Int,
    val outcome: String,
    val ground: String,
    val ruleReference: String = "",
    val explanation: String = "",
    val deadline: OasisInstant
)

// Privacy centre.

/** One choice of an audience setting (also report categories, avatar colours and the tour's audiences). */
@Serializable
data class PrivacyChoice(val value: String, val label: String)

/** A value that is either a switch ([Flag]) or an audience ([Choice]), as the API sends it (`true` or `"members"`). */
@Serializable(with = PrivacyValueSerializer::class)
sealed interface PrivacyValue {
    data class Flag(val value: Boolean) : PrivacyValue

    data class Choice(val value: String) : PrivacyValue

    val flag: Boolean get() = (this as? Flag)?.value ?: false
    val choice: String? get() = (this as? Choice)?.value
}

/** Decodes a JSON boolean as [PrivacyValue.Flag] and anything else as [PrivacyValue.Choice]. */
object PrivacyValueSerializer : KSerializer<PrivacyValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): PrivacyValue {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return PrivacyValue.Choice(element.toString())
        if (!primitive.isString) primitive.booleanOrNull?.let { return PrivacyValue.Flag(it) }
        return PrivacyValue.Choice(primitive.content)
    }

    override fun serialize(encoder: Encoder, value: PrivacyValue) {
        val element = when (value) {
            is PrivacyValue.Flag -> JsonPrimitive(value.value)
            is PrivacyValue.Choice -> JsonPrimitive(value.value)
        }
        (encoder as JsonEncoder).encodeJsonElement(element)
    }
}

/**
 * One privacy setting with its German title and help, as PrivacyOptions describes it. The screens render it from
 * [kind] and [choices] alone, so a new setting on the server needs no app update.
 */
@Serializable
data class PrivacySettingItem(
    val key: String,
    val title: String,
    val help: String = "",
    /** "switch" or "choice". */
    val kind: String,
    val value: PrivacyValue,
    val choices: List<PrivacyChoice> = emptyList(),
    /** Under 18: fewer choices (a switch is locked). */
    val limited: Boolean = false
) {
    val isSwitch: Boolean get() = kind == "switch"

    /** The value as the summary of the check shows it ("Ja"/"Nein" or the audience label). */
    val valueLabel: String
        get() = when (value) {
            is PrivacyValue.Flag -> if (value.value) "Ja" else "Nein"
            is PrivacyValue.Choice -> choices.firstOrNull { it.value == value.value }?.label ?: value.value
        }
}

@Serializable
data class PrivacySection(val key: String, val title: String, val inCheckup: Boolean = false, val settings: List<PrivacySettingItem>)

@Serializable
data class PrivacyCounts(
    val publicPosts: Int = 0,
    val hiddenWords: Int = 0,
    val restricted: Int = 0,
    val blocked: Int = 0,
    val muted: Int = 0
)

@Serializable
data class PrivacyCentre(val sections: List<PrivacySection>, val counts: PrivacyCounts = PrivacyCounts())

@Serializable
data class HiddenWords(val words: List<String>, val max: Int)

// Devices.

@Serializable
data class DeviceOverview(
    val current: Current,
    val apps: List<AppSignIn> = emptyList(),
    val sessionsListable: Boolean = false,
    val sessions: List<BrowserSession> = emptyList(),
    val known: List<KnownDevice> = emptyList(),
    val loginAlerts: Boolean = false,
    val chatDevices: List<ChatDevice> = emptyList(),
    val chatPassphrase: Boolean = false
) {
    @Serializable
    data class Current(val name: String? = null, val device: String, val ip: String)

    @Serializable
    data class AppSignIn(val id: Int, val name: String, val signedInAt: OasisInstant? = null, val lastUsedAt: OasisInstant? = null)

    @Serializable
    data class BrowserSession(val key: String, val device: String, val ip: String, val lastActiveAt: OasisInstant)

    @Serializable
    data class KnownDevice(val id: Int, val label: String, val lastSeenAt: OasisInstant)

    @Serializable
    data class ChatDevice(val id: Int, val label: String, val createdAt: OasisInstant)
}

// Safety.

/** A person in the safety lists, with the avatar fields of the network's member resource. */
@Serializable
data class SafetyAccount(
    val id: Int,
    val username: String,
    val displayName: String,
    val initials: String,
    val avatarColor: String = "dune",
    val photoUrl: String? = null
)

@Serializable
data class SafetyLists(
    val blocked: List<SafetyAccount> = emptyList(),
    val muted: List<SafetyAccount> = emptyList(),
    val restricted: List<SafetyAccount> = emptyList()
)

/** The three lists of Blockiert & stumm and their endpoints. */
enum class SafetyList(val path: String) { Blocks("blocks"), Mutes("mutes"), Restrictions("restrictions") }

@Serializable
data class ReportForm(val url: String, val categories: List<PrivacyChoice>, val reviewHours: Int = 24)

// Welcome tour.

@Serializable
data class WelcomeStep(val key: String, val title: String)

@Serializable
data class StarterOasis(
    val id: Int,
    val slug: String,
    val name: String,
    val description: String = "",
    val color: String = "dune",
    val membersCount: Int = 0,
    val bannerUrl: String? = null,
    val joined: Boolean = false
)

@Serializable
data class WelcomeTour(
    val steps: List<WelcomeStep>,
    val onboarded: Boolean = false,
    val bio: String = "",
    val location: String = "",
    val avatarColor: String = "sun",
    val colors: List<PrivacyChoice> = emptyList(),
    val profileAudience: String = "members",
    val defaultPostVisibility: String = "members",
    val audiences: List<PrivacyChoice> = emptyList(),
    val visibilities: List<PrivacyChoice> = emptyList(),
    val oases: List<StarterOasis> = emptyList()
)

@Serializable
data class OasisJoined(val joined: Boolean)

// Votes.

@Serializable
data class VoteOption(val id: Int, val label: String, val share: Int? = null, val winner: Boolean? = null)

@Serializable
data class OpenVote(
    val id: Int,
    val title: String,
    val description: String = "",
    val endsAt: OasisInstant,
    val voted: Boolean = false,
    val options: List<VoteOption> = emptyList()
)

@Serializable
data class EndedVote(
    val id: Int,
    val title: String,
    val endsAt: OasisInstant,
    val total: Int = 0,
    val options: List<VoteOption> = emptyList()
)

@Serializable
data class VotesOverview(val canVote: Boolean = false, val open: List<OpenVote> = emptyList(), val ended: List<EndedVote> = emptyList())

// Glossary.

@Serializable
data class GlossaryTerm(val term: String, val slug: String, val explanation: String)
