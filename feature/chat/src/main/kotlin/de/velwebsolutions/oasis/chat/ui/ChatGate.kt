package de.velwebsolutions.oasis.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.chat.core.ChatVault
import de.velwebsolutions.oasis.chat.core.MIN_PASSPHRASE_LENGTH
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.ErrorStateView
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.StatusToast
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import kotlinx.coroutines.launch

/**
 * Shows the chat screen only while the keys are open in memory; otherwise „Deine Chats sind privat“ (setup) or „Deine Chats
 * sind gesperrt“ (resources/views/app/chat/partials/setup.blade.php and unlock.blade.php; iOS `ChatGate`).
 */
@Composable
internal fun ChatGate(content: @Composable () -> Unit) {
    val model: ChatAccessViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val vault = model.context.vault
    val announcement by vault.announcement.collectAsStateWithLifecycle()
    // The vault's announcement, taken over by the gate on screen so it shows once, on the visible screen.
    var shown by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(announcement) {
        announcement?.let {
            shown = it
            vault.announcement.value = null
        }
    }
    DisposableEffect(Unit) { onDispose { model.stop() } }

    Box(Modifier.fillMaxSize()) {
        when (val phase = state.phase) {
            ChatPhase.Loading -> OasisScreen("Nachrichten") { LoadingView(label = "Einen Moment …") }

            is ChatPhase.Failed -> OasisScreen("Nachrichten") {
                ErrorStateView(ApiError.Server(0, phase.message), onRetry = { model.load() })
            }

            ChatPhase.Setup -> OasisScreen("Nachrichten") {
                ChatSetupContent(
                    state = state,
                    onPassphraseChange = model::setPassphrase,
                    onConfirmationChange = model::setConfirmation,
                    onSetUp = model::setUp,
                    onSetUpWithDevice = model::setUpWithDevice,
                    onCancelReset = model::cancelReset
                )
            }

            ChatPhase.Unlock -> OasisScreen("Nachrichten") {
                // The biometric prompt starts by itself, like the system's own lock screens; only while the app is in front.
                LaunchedEffect(state.canUnlockWithDevice) { model.autoUnlockWithDevice() }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { model.autoUnlockWithDevice() } }
                ChatUnlockContent(
                    state = state,
                    onPassphraseChange = model::setPassphrase,
                    onUnlock = model::unlock,
                    onUnlockWithDevice = { model.unlockWithDevice() },
                    onStartReset = model::startReset,
                    onRequestLink = model::requestLink,
                    onCancelLink = model::cancelLink
                )
            }

            // Open. Every touch on an open chat screen counts as interaction for the idle lock (typing: ConversationViewModel).
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { awaitPointerEventScope { touchOnEveryEvent(vault) } }
            ) { content() }
        }
        StatusToast(shown, null, onDismissMessage = { shown = null }, onDismissError = {})
    }

    if (state.offersDeviceUnlock) {
        AlertDialog(
            onDismissRequest = { scope.launch { model.declineDeviceOffer() } },
            title = { Text("Künftig mit Fingerabdruck entsperren?", style = OasisType.heading(19), color = OasisColors.Night) },
            text = {
                Text(
                    "Dann öffnest du deine Nachrichten auf diesem Gerät mit deinem Finger oder einem Blick – ohne Passphrase. Die Passphrase bleibt als Reserve.",
                    style = OasisType.Body,
                    color = OasisColors.Ink2
                )
            },
            confirmButton = { OasisTextButton("Fingerabdruck verwenden", { scope.launch { model.acceptDeviceOffer() } }) },
            dismissButton = { OasisTextButton("Nicht jetzt", { scope.launch { model.declineDeviceOffer() } }) },
            containerColor = OasisColors.Paper
        )
    }
}

/**
 * „Deine Chats sind privat“ (docs/oasis-chat-client.md, „C5 Setup with one button“): one button, „Mit Fingerabdruck
 * einrichten“, where the device can; „Lieber mit Passphrase“ opens the passphrase form, which is the only way on devices
 * without biometrics. No backup passphrase is asked here: the chat list offers it afterwards. After „Chats neu
 * einrichten“ the same screen says what that means.
 */
@Composable
internal fun ChatSetupContent(
    state: ChatAccessState,
    onPassphraseChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSetUp: suspend () -> Unit,
    onSetUpWithDevice: suspend () -> Unit,
    onCancelReset: suspend () -> Unit
) {
    var showsPassphrase by rememberSaveable { mutableStateOf(false) }
    val reset = state.canCancelReset
    ChatPage(spacing = 24) {
        PageHeader(
            eyebrow = "Ende-zu-Ende verschlüsselt",
            title = if (reset) "Chats neu einrichten" else "Deine Chats sind privat",
            lead = if (reset) {
                "Ältere Nachrichten auf diesem Gerät kannst du danach nicht mehr lesen."
            } else {
                "Nur ihr könnt eure Nachrichten lesen – nicht einmal Oasis. Das Einrichten dauert ein paar Sekunden."
            }
        )
        if (reset) {
            Text(
                "Deine Chatpartner sehen einen Hinweis, dass du die Chats neu eingerichtet hast.",
                style = OasisType.Body,
                color = OasisColors.Ink2
            )
        }
        if (state.deviceAvailable) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncOasisButton(
                    "Mit Fingerabdruck einrichten",
                    onSetUpWithDevice,
                    kind = OasisButtonKind.Accent,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("chat.setup.device")
                )
                state.deviceSetupStatus?.let { Text(it, style = OasisType.Small, color = OasisColors.Ink2) }
                if (!showsPassphrase) {
                    SmallAction("Lieber mit Passphrase", {
                        showsPassphrase = true
                    }, Modifier.testTag("chat.setup.passphraseToggle"), underline = true)
                }
            }
            AnimatedVisibility(showsPassphrase) { PassphraseForm(state, onPassphraseChange, onConfirmationChange, onSetUp) }
        } else {
            PassphraseForm(state, onPassphraseChange, onConfirmationChange, onSetUp)
        }
        if (reset) {
            AsyncOasisButton("Zurück zum Entsperren", onCancelReset, kind = OasisButtonKind.Secondary, fullWidth = false)
        }
    }
}

@Composable
private fun PassphraseForm(
    state: ChatAccessState,
    onPassphraseChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSetUp: suspend () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        RichBullets(
            listOf(
                AnnotatedString("Die Passphrase ist getrennt von deinem Passwort und verlässt nie dein Gerät."),
                AnnotatedString("Du gibst sie ein, wenn du die Nachrichten öffnest. Gespeichert wird auf deinem Gerät nichts."),
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = OasisColors.Night)) {
                        append("Vergisst du sie, kann niemand deine Nachrichten wiederherstellen")
                    }
                    append(" – auch wir nicht.")
                }
            )
        )
        Panel {
            OasisTextField(
                "Passphrase",
                state.passphrase,
                onPassphraseChange,
                hint = "Mindestens $MIN_PASSPHRASE_LENGTH Zeichen. Gut merkbar sind mehrere Wörter, z. B. „Palme Regen Kompass Tee“.",
                isSecure = true,
                // "Weiter" moves on to the repetition, like iOS' submit label.
                keyboardOptions = OasisKeyboard.Password.copy(imeAction = ImeAction.Next),
                modifier = Modifier.testTag("chat.setup.passphrase")
            )
            OasisTextField(
                "Passphrase wiederholen",
                state.confirmation,
                onConfirmationChange,
                isSecure = true,
                keyboardOptions = OasisKeyboard.Password.copy(imeAction = ImeAction.Done),
                modifier = Modifier.testTag("chat.setup.confirmation")
            )
            AnimatedVisibility(state.formError != null) { FieldError(state.formError.orEmpty()) }
            AsyncOasisButton(
                "Chats einrichten",
                onSetUp,
                fullWidth = false,
                enabled = !state.busy,
                modifier = Modifier.testTag("chat.setup.submit")
            )
            if (state.busy) {
                Text("Wird eingerichtet. Das dauert ein paar Sekunden …", style = OasisType.Small, color = OasisColors.Ink2)
            }
        }
    }
}

/** „Deine Chats sind gesperrt“: biometrics (where this device has a copy), another device, or the passphrase. */
@Composable
internal fun ChatUnlockContent(
    state: ChatAccessState,
    onPassphraseChange: (String) -> Unit,
    onUnlock: suspend () -> Unit,
    onUnlockWithDevice: suspend () -> Unit,
    onStartReset: () -> Unit,
    onRequestLink: suspend () -> Unit,
    onCancelLink: suspend () -> Unit
) {
    var showForgotten by rememberSaveable { mutableStateOf(false) }
    // With a biometric copy on this device the passphrase is the fallback behind "Passphrase verwenden".
    var showsPassphrase by rememberSaveable { mutableStateOf(false) }
    val passphraseVisible = state.hasPassphrase && (!state.canUnlockWithDevice || showsPassphrase)
    val scope = rememberCoroutineScope()
    ChatPage(spacing = 24) {
        PageHeader(
            eyebrow = "Ende-zu-Ende verschlüsselt",
            title = "Deine Chats sind gesperrt",
            lead = when {
                state.canUnlockWithDevice -> "Öffne sie mit deinem Fingerabdruck."
                state.hasPassphrase -> "Gib deine Chat-Passphrase ein. Sie bleibt auf diesem Gerät."
                else -> null
            }
        )
        if (state.canUnlockWithDevice) {
            Panel {
                AsyncOasisButton(
                    "Chats entsperren",
                    onUnlockWithDevice,
                    fullWidth = false,
                    modifier = Modifier.testTag("chat.unlock.device")
                )
                AnimatedVisibility(state.deviceUnlockError != null) { FieldError(state.deviceUnlockError.orEmpty()) }
                if (state.hasPassphrase && !showsPassphrase) {
                    SmallAction("Passphrase verwenden", { showsPassphrase = true }, Modifier.testTag("chat.unlock.usePassphrase"))
                }
            }
        }
        if (!state.canUnlockWithDevice || showsPassphrase) LinkBox(state, onRequestLink, onCancelLink)
        if (!state.hasPassphrase && !state.canUnlockWithDevice) {
            Panel {
                Text(
                    "Deine Chats öffnest du mit deinem Fingerabdruck auf dem Gerät, auf dem du sie eingerichtet hast. Auf anderen Geräten hilft „Neues Gerät verbinden“ oder eine Reserve-Passphrase.",
                    style = OasisType.Body,
                    color = OasisColors.Ink2
                )
            }
        }
        if (passphraseVisible) {
            Panel {
                OasisTextField(
                    "Chat-Passphrase",
                    state.passphrase,
                    onPassphraseChange,
                    isSecure = true,
                    keyboardOptions = OasisKeyboard.Password.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { scope.launch { onUnlock() } }),
                    modifier = Modifier.testTag("chat.unlock.passphrase")
                )
                AnimatedVisibility(state.formError != null) { FieldError(state.formError.orEmpty()) }
                AsyncOasisButton(
                    // With the fingerprint button above, this one says which way it opens.
                    if (state.canUnlockWithDevice) "Mit Passphrase entsperren" else "Chats entsperren",
                    onUnlock,
                    fullWidth = false,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("chat.unlock.submit")
                )
                if (state.busy) Text("Wird entsperrt …", style = OasisType.Small, color = OasisColors.Ink2)
            }
        }
        if (passphraseVisible || !state.canUnlockWithDevice) {
            Panel {
                Disclosure("Passphrase vergessen?", showForgotten, { showForgotten = !showForgotten }) {
                    Text(
                        "Ohne Passphrase lässt sich dein bisheriger Zugang nicht öffnen – von niemandem. Du kannst die Chats neu einrichten. Dann gilt:",
                        style = OasisType.Body,
                        color = OasisColors.Ink2
                    )
                    Bullets(
                        listOf(
                            "Ältere Nachrichten auf diesem Gerät kannst du danach nicht mehr lesen.",
                            "Deine Chatpartner sehen einen Hinweis, dass du die Chats neu eingerichtet hast."
                        )
                    )
                    OasisButton(
                        "Chats neu einrichten",
                        onStartReset,
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false,
                        modifier = Modifier.testTag("chat.unlock.reset")
                    )
                }
            }
        }
    }
}

/** „Neues Gerät verbinden“: an open chat on another device lets this one in. */
@Composable
private fun LinkBox(state: ChatAccessState, onRequestLink: suspend () -> Unit, onCancelLink: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    Panel {
        Text("Neues Gerät verbinden", style = OasisType.heading(19), color = OasisColors.Night)
        Text(
            "Ist dein Chat auf einem anderen Gerät gerade offen (z. B. im Browser)? Dann kann es dieses Gerät freigeben – ohne Passphrase.",
            style = OasisType.Small,
            color = OasisColors.Ink2
        )
        val check = state.linkCheck
        if (check != null) {
            Text(
                "Öffne den Chat auf deinem anderen Gerät und bestätige dort diese Prüfzahl:",
                style = OasisType.Body,
                color = OasisColors.Ink
            )
            Text(
                check,
                style = OasisType.Digits.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
                color = OasisColors.Night,
                modifier = Modifier.testTag("chat.link.check")
            )
            Text(
                "Wir warten bis zu zehn Minuten. Steht dort eine andere Zahl, lehne ab.",
                style = OasisType.Small,
                color = OasisColors.Muted
            )
            SmallAction("Abbrechen", { scope.launch { onCancelLink() } })
        } else {
            AsyncOasisButton(
                "Verbindung anfragen",
                onRequestLink,
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                modifier = Modifier.testTag("chat.link.request")
            )
        }
        state.linkStatus?.let { Text(it, style = OasisType.Small, color = OasisColors.SunText) }
    }
}

/** [Bullets] with emphasis inside an item (the setup's "Vergisst du sie …" in bold, as on iOS and the web). */
@Composable
private fun RichBullets(items: List<AnnotatedString>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = OasisType.Body, color = OasisColors.Ink2)
                Text(item, style = OasisType.Body, color = OasisColors.Ink2)
            }
        }
    }
}

/** Every pointer event on an open chat screen restarts the idle lock (before the screen itself handles it). */
private suspend fun AwaitPointerEventScope.touchOnEveryEvent(vault: ChatVault): Nothing {
    while (true) {
        awaitPointerEvent(PointerEventPass.Initial)
        vault.touch()
    }
}
