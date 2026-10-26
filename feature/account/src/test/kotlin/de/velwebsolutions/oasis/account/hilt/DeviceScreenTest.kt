package de.velwebsolutions.oasis.account.hilt

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.account.AccountScreen
import de.velwebsolutions.oasis.account.FakePasskeys
import de.velwebsolutions.oasis.account.Fixtures
import de.velwebsolutions.oasis.account.PasswordlessFixtures
import de.velwebsolutions.oasis.account.SignedOutScreen
import de.velwebsolutions.oasis.account.platform.LocalPasskeyAuthenticator
import de.velwebsolutions.oasis.account.platform.LocalSmsConsentStarter
import de.velwebsolutions.oasis.account.platform.PasskeyFailure
import de.velwebsolutions.oasis.account.platform.SmsConsentStarter
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screens with the device's passkeys and SMS User Consent faked: [passkeys] answers the passkey sheet, [smsRequests]
 * records the start of SMS User Consent, [shareSms] plays the SMS and the consent sheet of Google Play services.
 */
abstract class DeviceScreenTest : HiltScreenTest() {
    val passkeys = FakePasskeys()
    val smsRequests = mutableListOf<Context>()
    private val sms = SmsConsentStarter { smsRequests += it }
    private val consentSheet = ComponentName("com.google.android.gms", "com.google.android.gms.auth.api.phone.ui.UserConsentPromptActivity")

    fun showWithDevice(content: @Composable () -> Unit) = show {
        CompositionLocalProvider(LocalPasskeyAuthenticator provides { passkeys }, LocalSmsConsentStarter provides sms) { content() }
    }

    fun back() = activity.onBackPressedDispatcher.onBackPressed()

    /** The SMS arrives, the member allows (or declines) it in Google Play services' sheet, and the sheet hands back the SMS. */
    fun shareSms(text: String, allowed: Boolean = true) {
        shadowOf(activity.packageManager).addActivityIfNotPresent(consentSheet)
        activity.sendBroadcast(
            Intent(SmsRetriever.SMS_RETRIEVED_ACTION)
                .putExtra(SmsRetriever.EXTRA_STATUS, Status(CommonStatusCodes.SUCCESS))
                .putExtra(SmsRetriever.EXTRA_CONSENT_INTENT, Intent().setComponent(consentSheet)),
            // Robolectric delivers to a receiver that requires a sender permission only with that permission.
            SmsRetriever.SEND_PERMISSION
        )
        shadowOf(Looper.getMainLooper()).idle()
        val sheet = shadowOf(activity).nextStartedActivityForResult
        assertEquals(consentSheet, sheet.intent.component)
        val result = if (allowed) Activity.RESULT_OK else Activity.RESULT_CANCELED
        shadowOf(activity).receiveResult(sheet.intent, result, Intent().putExtra(SmsRetriever.EXTRA_SMS_MESSAGE, text))
    }
}
