package de.velwebsolutions.oasis.account

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.data.AccountRepository
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.navigation.IncomingLink
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Account links from mails while somebody is signed in: the confirmation link confirms the address (the app then
 * moves on), a reset link goes to the website (the app sets a new password only when signed out), a code link has
 * nothing left to do.
 */
@HiltViewModel
class AccountLinksViewModel @Inject constructor(private val repository: AccountRepository, private val session: Session) :
    ViewModel() {
    private val mutableNotice = MutableStateFlow<String?>(null)

    /** The answer to the confirmation link, shown in a dialog. */
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    /** Sends the parts of the confirmation link; a refused link says why (`link`). */
    suspend fun verify(link: IncomingLink.VerifyEmail) {
        mutableNotice.value = catching { repository.verifyEmailLink(link.id, link.hash, link.expires, link.signature) }
            .onSuccess(session::update)
            .fold({ CONFIRMED }, { error -> error.asApiError().let { it.message("link") ?: it.userMessage } })
    }

    /** Starts [verify] in the view model's scope, so taking the link from the navigator does not cancel it. */
    fun start(link: IncomingLink.VerifyEmail) {
        viewModelScope.launch { verify(link) }
    }

    fun dismiss() {
        mutableNotice.value = null
    }

    companion object {
        /** The web's answer (AuthController::verifyEmailLink). */
        const val CONFIRMED = "Danke! Deine E-Mail-Adresse ist bestätigt."
    }
}

/** Takes the account links waiting in the navigator while somebody is signed in (placed by the app's root screen). */
@Composable
fun AccountLinks(viewModel: AccountLinksViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val link = navigator.pendingLink
    LaunchedEffect(link) {
        when (link) {
            is IncomingLink.VerifyEmail -> {
                navigator.takeLink()
                viewModel.start(link)
            }

            is IncomingLink.ResetPassword -> {
                navigator.takeLink()
                navigator.openWeb(link.url)
            }

            IncomingLink.LoginCode -> navigator.takeLink()

            else -> Unit
        }
    }
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    notice?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            text = { Text(text, style = OasisType.Body, color = OasisColors.Ink, modifier = Modifier.testTag("accountlink.notice")) },
            confirmButton = { OasisTextButton("OK", viewModel::dismiss) },
            containerColor = OasisColors.Paper
        )
    }
}
