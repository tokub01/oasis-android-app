package de.velwebsolutions.oasis.account.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import de.velwebsolutions.oasis.core.network.OasisJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Why a passkey ceremony gave no answer; [userMessage] is what the member reads (nothing when they cancelled). */
sealed class PasskeyFailure(val userMessage: String?) : Exception(userMessage ?: "cancelled") {
    /** The member closed the system sheet: nothing to say. */
    class Cancelled : PasskeyFailure(null)

    /** No passkey for Oasis on this device or in the password manager. */
    class NoPasskey : PasskeyFailure(
        "Auf diesem Gerät ist kein Passkey für Oasis gespeichert. Melde dich mit Passwort oder Code an und richte in den Einstellungen einen Passkey ein."
    )

    /** This device already holds a passkey of the account (the server lists it in `excludeCredentials`). */
    class AlreadyRegistered : PasskeyFailure("Auf diesem Gerät gibt es schon einen Passkey für dein Konto.")

    /** No provider for passkeys: Google Play services missing or too old, or no screen lock. */
    class Unavailable : PasskeyFailure(
        "Passkeys gehen auf diesem Gerät gerade nicht. Prüf, ob eine Displaysperre eingerichtet ist und die Google Play-Dienste aktuell sind."
    )

    /** Anything else the system reported. */
    class Failed : PasskeyFailure("Das hat mit dem Passkey nicht geklappt. Bitte versuch es noch einmal.")
}

/** Passkeys of this device: sign in with one, create one. Answers are WebAuthn JSON for the server. */
interface PasskeyAuthenticator {
    /** Whether this device can use passkeys at all. */
    val isAvailable: Boolean

    /** Asks for a passkey to [requestJson] (PublicKeyCredentialRequestOptionsJSON); returns the assertion JSON. */
    suspend fun authenticate(requestJson: String): String

    /** Creates a passkey for [requestJson] (PublicKeyCredentialCreationOptionsJSON); returns the attestation JSON. */
    suspend fun register(requestJson: String): String
}

/**
 * [PasskeyAuthenticator] over Credential Manager: Android 14 and newer bring their own provider, older versions use
 * Google Play services. [context] is the activity, so the system sheets open in its task.
 */
class CredentialManagerPasskeys(
    private val context: Context,
    private val manager: CredentialManager = CredentialManager.create(context),
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : PasskeyAuthenticator {
    override val isAvailable: Boolean
        get() = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || hasPlayServices()

    override suspend fun authenticate(requestJson: String): String {
        val response = try {
            manager.getCredential(context, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson))))
        } catch (error: GetCredentialException) {
            throw passkeyFailure(error)
        }
        val credential = response.credential as? PublicKeyCredential ?: throw PasskeyFailure.Failed()
        return credential.authenticationResponseJson
    }

    override suspend fun register(requestJson: String): String {
        val response: CreateCredentialResponse = try {
            manager.createCredential(context, CreatePublicKeyCredentialRequest(requestJson))
        } catch (error: CreateCredentialException) {
            throw passkeyFailure(error)
        }
        return (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson ?: throw PasskeyFailure.Failed()
    }

    private fun hasPlayServices(): Boolean = try {
        context.packageManager.getPackageInfo(PLAY_SERVICES, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val PLAY_SERVICES = "com.google.android.gms"
    }
}

/** What a Credential Manager error means for the member. */
internal fun passkeyFailure(error: Exception): PasskeyFailure = when (error) {
    is GetCredentialCancellationException, is CreateCredentialCancellationException -> PasskeyFailure.Cancelled()

    is NoCredentialException -> PasskeyFailure.NoPasskey()

    is GetCredentialProviderConfigurationException,
    is CreateCredentialProviderConfigurationException,
    is GetCredentialUnsupportedException,
    is CreateCredentialUnsupportedException -> PasskeyFailure.Unavailable()

    is CreatePublicKeyCredentialDomException ->
        if (error.domError is InvalidStateError) PasskeyFailure.AlreadyRegistered() else PasskeyFailure.Failed()

    else -> PasskeyFailure.Failed()
}

/** The WebAuthn answer of Credential Manager as the JSON object the server expects (not as a string). */
internal fun credentialObject(json: String): JsonObject =
    runCatching { OasisJson.verbatim.parseToJsonElement(json).jsonObject }.getOrElse { throw PasskeyFailure.Failed() }

/** How the screens get their [PasskeyAuthenticator] for the activity (tests provide a fake). */
val LocalPasskeyAuthenticator = staticCompositionLocalOf<
    (
        Context
    ) -> PasskeyAuthenticator
    > { { context -> CredentialManagerPasskeys(context) } }

/** The [PasskeyAuthenticator] of the current activity. */
@Composable
internal fun rememberPasskeyAuthenticator(): PasskeyAuthenticator {
    val context = LocalContext.current
    val factory = LocalPasskeyAuthenticator.current
    return remember(context, factory) { factory(context) }
}
