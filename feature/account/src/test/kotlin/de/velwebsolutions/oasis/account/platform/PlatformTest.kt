package de.velwebsolutions.oasis.account.platform

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.result.ActivityResult
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreateCustomCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PrepareGetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import de.velwebsolutions.oasis.account.PasswordlessFixtures
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The thin adapters to Credential Manager and SMS User Consent, with the system parts faked where Robolectric has none. */
@RunWith(RobolectricTestRunner::class)
class PlatformTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Answers like Credential Manager would: [get] and [create] give the answer or throw. */
    private class FakeCredentialManager(
        val get: () -> GetCredentialResponse = { GetCredentialResponse(PublicKeyCredential(PasswordlessFixtures.ASSERTION)) },
        val create: () -> CreateCredentialResponse = { CreatePublicKeyCredentialResponse(PasswordlessFixtures.ATTESTATION) }
    ) : CredentialManager {
        val requests = mutableListOf<Any>()

        override suspend fun getCredential(context: Context, request: GetCredentialRequest): GetCredentialResponse {
            requests += request
            return get()
        }

        override suspend fun createCredential(context: Context, request: CreateCredentialRequest): CreateCredentialResponse {
            requests += request
            return create()
        }

        override fun getCredentialAsync(
            context: Context,
            request: GetCredentialRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>
        ) = error("not used")

        override fun getCredentialAsync(
            context: Context,
            pendingGetCredentialHandle: PrepareGetCredentialResponse.PendingGetCredentialHandle,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>
        ) = error("not used")

        override fun prepareGetCredentialAsync(
            request: GetCredentialRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<PrepareGetCredentialResponse, GetCredentialException>
        ) = error("not used")

        override fun createCredentialAsync(
            context: Context,
            request: CreateCredentialRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException>
        ) = error("not used")

        override fun clearCredentialStateAsync(
            request: ClearCredentialStateRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<Void?, ClearCredentialException>
        ) = error("not used")

        override fun createSettingsPendingIntent() = error("not used")
    }

    private suspend fun failure(block: suspend () -> Unit): PasskeyFailure = try {
        block()
        fail("expected a failure")
        error("unreachable")
    } catch (failure: PasskeyFailure) {
        failure
    }

    @Test
    fun signsInAndCreatesWithTheServersJson() = runTest {
        val manager = FakeCredentialManager()
        val passkeys = CredentialManagerPasskeys(context, manager)
        val request = """{"challenge":"Y2g","rpId":"localhost","allowCredentials":[],"userVerification":"required"}"""
        assertEquals(PasswordlessFixtures.ASSERTION, passkeys.authenticate(request))
        val option = (manager.requests[0] as GetCredentialRequest).credentialOptions.single() as GetPublicKeyCredentialOption
        assertEquals(request, option.requestJson)
        val creation =
            """{"rp":{"id":"localhost","name":"Oasis"},"user":{"id":"dQ","name":"mira","displayName":"Mira"},"challenge":"Y2g"}"""
        assertEquals(PasswordlessFixtures.ATTESTATION, passkeys.register(creation))
        assertEquals(creation, (manager.requests[1] as CreatePublicKeyCredentialRequest).requestJson)
    }

    @Test
    fun answersOfAnotherKindAndSystemErrorsBecomeFailures() = runTest {
        val request = """{"challenge":"Y2g"}"""
        val creation = """{"user":{"id":"dQ","name":"mira","displayName":"Mira"},"challenge":"Y2g"}"""
        val other = CredentialManagerPasskeys(
            context,
            FakeCredentialManager(
                get = { GetCredentialResponse(CustomCredential("other", Bundle())) },
                create = { CreateCustomCredentialResponse("other", Bundle()) }
            )
        )
        assertTrue(failure { other.authenticate(request) } is PasskeyFailure.Failed)
        assertTrue(failure { other.register(creation) } is PasskeyFailure.Failed)
        val refusing = CredentialManagerPasskeys(
            context,
            FakeCredentialManager(get = { throw NoCredentialException() }, create = { throw CreateCredentialCancellationException() })
        )
        assertTrue(failure { refusing.authenticate(request) } is PasskeyFailure.NoPasskey)
        assertTrue(failure { refusing.register(creation) } is PasskeyFailure.Cancelled)
    }

    @Test
    fun everyCredentialManagerErrorHasItsText() {
        val cases = mapOf(
            GetCredentialCancellationException() to PasskeyFailure.Cancelled::class,
            CreateCredentialCancellationException() to PasskeyFailure.Cancelled::class,
            NoCredentialException() to PasskeyFailure.NoPasskey::class,
            GetCredentialProviderConfigurationException() to PasskeyFailure.Unavailable::class,
            CreateCredentialProviderConfigurationException() to PasskeyFailure.Unavailable::class,
            GetCredentialUnsupportedException() to PasskeyFailure.Unavailable::class,
            CreateCredentialUnsupportedException() to PasskeyFailure.Unavailable::class,
            CreatePublicKeyCredentialDomException(InvalidStateError()) to PasskeyFailure.AlreadyRegistered::class,
            CreatePublicKeyCredentialDomException(NotAllowedError()) to PasskeyFailure.Failed::class,
            GetCredentialUnknownException() to PasskeyFailure.Failed::class,
            CreateCredentialUnknownException() to PasskeyFailure.Failed::class
        )
        cases.forEach { (error, expected) -> assertEquals(error.toString(), expected, passkeyFailure(error)::class) }
        assertNull("a cancelled sheet stays silent", PasskeyFailure.Cancelled().userMessage)
        assertEquals("cancelled", PasskeyFailure.Cancelled().message)
        listOf(PasskeyFailure.NoPasskey(), PasskeyFailure.AlreadyRegistered(), PasskeyFailure.Unavailable(), PasskeyFailure.Failed())
            .forEach { assertEquals(it.userMessage, it.message) }
    }

    @Test
    fun theAnswerGoesToTheServerAsAnObject() {
        assertEquals("cred", credentialObject(PasswordlessFixtures.ASSERTION)["id"].toString().trim('"'))
        assertTrue(runCatching { credentialObject("kein json") }.exceptionOrNull() is PasskeyFailure.Failed)
        assertTrue(runCatching { credentialObject("[1]") }.exceptionOrNull() is PasskeyFailure.Failed)
    }

    @Test
    fun passkeysNeedAndroid14OrGooglePlayServices() {
        val manager = FakeCredentialManager()
        assertTrue(CredentialManagerPasskeys(context, manager, Build.VERSION_CODES.UPSIDE_DOWN_CAKE).isAvailable)
        assertFalse(CredentialManagerPasskeys(context, manager, Build.VERSION_CODES.TIRAMISU).isAvailable)
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.google.android.gms" })
        assertTrue(CredentialManagerPasskeys(context, manager, Build.VERSION_CODES.TIRAMISU).isAvailable)
        assertTrue("the real manager on this runtime", CredentialManagerPasskeys(context).isAvailable)
    }

    @Test
    fun theCodeIsReadFromTheSms() {
        assertEquals("123456", oneTimeCode("Dein Oasis-Anmeldecode: 123 456. Gültig 10 Minuten. Gib ihn niemandem weiter."))
        assertEquals("654321", oneTimeCode("Code 654321"))
        assertNull(oneTimeCode("Ruf 0176 1234567 an"))
        assertNull(oneTimeCode("Kein Code"))
    }

    @Test
    fun onlyAnAllowedSmsFillsTheField() {
        val sms = Intent().putExtra(SmsRetriever.EXTRA_SMS_MESSAGE, "Dein Oasis-Anmeldecode: 123 456.")
        assertEquals("123456", sharedCode(ActivityResult(Activity.RESULT_OK, sms)))
        assertNull(sharedCode(ActivityResult(Activity.RESULT_CANCELED, sms)))
        assertNull(sharedCode(ActivityResult(Activity.RESULT_OK, null)))
        assertNull(sharedCode(ActivityResult(Activity.RESULT_OK, Intent())))
    }

    @Test
    fun theConsentSheetOfGooglePlayServicesIsHandedOn() {
        val sheet = ComponentName("com.google.android.gms", "com.google.android.gms.auth.api.phone.ui.UserConsentPromptActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(sheet)
        val consents = mutableListOf<Intent>()
        val receiver = SmsConsentReceiver { consents += it }
        fun broadcast(status: Int?, consent: Intent?) = Intent(SmsRetriever.SMS_RETRIEVED_ACTION).apply {
            if (status != null) putExtra(SmsRetriever.EXTRA_STATUS, Status(status))
            if (consent != null) putExtra(SmsRetriever.EXTRA_CONSENT_INTENT, consent)
        }
        val consent = Intent().setComponent(sheet)
        receiver.onReceive(context, Intent(SmsRetriever.SMS_RETRIEVED_ACTION))
        receiver.onReceive(context, broadcast(null, consent))
        receiver.onReceive(context, broadcast(CommonStatusCodes.TIMEOUT, consent))
        receiver.onReceive(context, broadcast(CommonStatusCodes.SUCCESS, null))
        val other = ComponentName("evil.app", "evil.app.Steal")
        shadowOf(context.packageManager).addActivityIfNotPresent(other)
        receiver.onReceive(context, broadcast(CommonStatusCodes.SUCCESS, Intent().setComponent(other)))
        receiver.onReceive(context, broadcast(CommonStatusCodes.SUCCESS, Intent("nothing.handles.this")))
        receiver.onReceive(context, broadcast(CommonStatusCodes.SUCCESS, Intent(consent).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
        assertTrue("nothing to hand on yet", consents.isEmpty())
        receiver.onReceive(context, broadcast(CommonStatusCodes.SUCCESS, consent))
        assertEquals(sheet, consents.single().component)
    }

    @Test
    fun googlePlayServicesIsAskedForTheNextSms() {
        PlayServicesSmsConsent.start(context)
    }
}
