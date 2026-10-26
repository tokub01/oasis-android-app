package de.velwebsolutions.oasis.account.platform

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/** The six digits of a code SMS („Dein Oasis-Anmeldecode: 123 456. …“), without the space; null if there are none. */
fun oneTimeCode(message: String): String? = SixDigits.find(message)?.groupValues?.let { it[1] + it[2] }

private val SixDigits = Regex("""(?<!\d)(\d{3}) ?(\d{3})(?!\d)""")

/** Starts SMS User Consent: for five minutes the system offers the next SMS with a code to the app, once the member allows it. */
fun interface SmsConsentStarter {
    fun start(context: Context)
}

/** [SmsConsentStarter] of Google Play services (any sender; the member sees the SMS and decides). */
object PlayServicesSmsConsent : SmsConsentStarter {
    override fun start(context: Context) {
        SmsRetriever.getClient(context).startSmsUserConsent(null)
    }
}

/**
 * Hears the broadcast of SMS User Consent and hands its consent intent on. The intent must open Google Play services'
 * own consent sheet; anything else (another app sending the action) is ignored.
 */
internal class SmsConsentReceiver(private val onConsent: (Intent) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val status = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_STATUS, Status::class.java)
        // A timeout (no SMS within five minutes) needs nothing: the member types the code.
        if (status?.statusCode != CommonStatusCodes.SUCCESS) return
        val consent = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java) ?: return
        val target = consent.resolveActivity(context.packageManager)
        if (target?.packageName == PLAY_SERVICES && consent.flags and GRANTS == 0) onConsent(consent)
    }

    private companion object {
        const val PLAY_SERVICES = "com.google.android.gms"
        const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** The code of the SMS the member shared in the consent sheet; null when they declined. */
internal fun sharedCode(result: ActivityResult): String? =
    if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)?.let(::oneTimeCode) else null

/** How the screens start SMS User Consent (tests provide a fake). */
val LocalSmsConsentStarter = staticCompositionLocalOf<SmsConsentStarter> { PlayServicesSmsConsent }

/**
 * While [active], waits for the SMS with the code: the system asks the member whether Oasis may read that one SMS, and
 * its code goes to [onCode]. Typing the code by hand always works as well.
 */
@Composable
internal fun SmsCodeConsent(active: Boolean, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val starter = LocalSmsConsentStarter.current
    val currentOnCode by rememberUpdatedState(onCode)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        sharedCode(result)?.let { currentOnCode(it) }
    }
    DisposableEffect(active, context) {
        if (!active) return@DisposableEffect onDispose {}
        val receiver = SmsConsentReceiver { launcher.launch(it) }
        val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        ContextCompat.registerReceiver(context, receiver, filter, SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        starter.start(context)
        onDispose { context.unregisterReceiver(receiver) }
    }
}
