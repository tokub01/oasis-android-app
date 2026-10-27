package de.velwebsolutions.oasis.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import de.velwebsolutions.oasis.app.shell.AppHost
import kotlinx.coroutines.launch

/**
 * Oasis as a share target (ACTION_SEND / ACTION_SEND_MULTIPLE, docs/oasis-chat.md "Sharing into Oasis"): copies what was
 * shared into the cache while the sending app's read permission lasts, hands it to the app's "Teilen mit …" and closes.
 * No window of its own; signing in and unlocking happen in the app as usual.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            AppHost.receiveShare(this@ShareReceiverActivity, intent)
            finish()
        }
    }
}
