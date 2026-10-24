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
 * Shows the chat screen only while the keys are open in memory; otherwise "Chat einrichten" or "Nachrichten entsperren"
 * (resources/views/app/chat/partials/setup.blade.php and unlock.blade.php; iOS `ChatGate`).
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

            // Every touch on an open chat screen counts as interaction for the idle lock (typing: ConversationViewModel).
            ChatPhase.Open -> Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                vault.touch()
                            }
                        }
                    }
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

/** "Chat einrichten": biometrics without a passphrase (where the device can), or a separate chat passphrase. */
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
    ChatPage(spacing = 24) {
        PageHeader(
            eyebrow = "Nachrichten",
            title = "Chat einrichten",
            lead =
                "Deine Nachrichten werden auf deinem Gerät verschlüsselt. Lesen können sie nur die Menschen im Chat – nicht Oasis und nicht unser Hosting."
        )
        if (state.canCancelReset) {
            Banner(
                "Neue Schlüssel: Bisherige Nachrichten kannst du danach nicht mehr lesen, und deine Chatpartner sehen einen Hinweis, dass sich deine Schlüssel geändert haben.",
                kind = BannerKind.Info
            )
        }
        if (state.deviceAvailable) {
            Panel(tone = PanelTone.Night) {
                Text(
                    "Mit Fingerabdruck oder Gesichtserkennung – ohne extra Passwort",
                    style = OasisType.heading(21),
                    color = OasisColors.OnNight
                )
                Text(
                    "Deine Chat-Schlüssel lassen sich dann nur mit dem Sicherheitschip dieses Geräts öffnen – nicht von uns. Zum Öffnen reicht dein Finger oder ein Blick.",
                    style = OasisType.Body,
                    color = OasisColors.OnNightMuted
                )
                AsyncOasisButton(
                    "Mit Fingerabdruck / Gesichtserkennung einrichten",
                    onSetUpWithDevice,
                    kind = OasisButtonKind.Accent,
                    fullWidth = false,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("chat.setup.device")
                )
                state.deviceSetupStatus?.let { Text(it, style = OasisType.Small, color = OasisColors.OnNight) }
            }
            Disclosure(
                "Lieber mit eigener Chat-Passphrase",
                showsPassphrase,
                { showsPassphrase = !showsPassphrase },
                Modifier.testTag("chat.setup.passphraseToggle")
            ) {
                PassphraseForm(state, onPassphraseChange, onConfirmationChange, onSetUp)
            }
        } else {
            PassphraseForm(state, onPassphraseChange, onConfirmationChange, onSetUp)
        }
        if (state.canCancelReset) {
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
                "Schlüssel erstellen",
                onSetUp,
                fullWidth = false,
                enabled = !state.busy,
                modifier = Modifier.testTag("chat.setup.submit")
            )
            if (state.busy) {
                Text("Schlüssel werden erstellt. Das dauert ein paar Sekunden …", style = OasisType.Small, color = OasisColors.Ink2)
            }
        }
    }
}

/** "Nachrichten entsperren": biometrics (where this device has a copy), another device, or the passphrase. */
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
            eyebrow = "Nachrichten",
            title = "Nachrichten entsperren",
            lead = when {
                state.canUnlockWithDevice -> "Öffne deine Nachrichten mit Fingerabdruck oder Gesichtserkennung."
                state.hasPassphrase -> "Gib deine Chat-Passphrase ein. Sie bleibt auf diesem Gerät."
                else -> null
            }
        )
        if (state.canUnlockWithDevice) {
            Panel {
                AsyncOasisButton(
                    "Mit Fingerabdruck oder Gesichtserkennung entsperren",
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
                    "Deinen Chat öffnest du mit Fingerabdruck oder Gesichtserkennung auf dem Gerät, auf dem du ihn eingerichtet hast. Auf anderen Geräten hilft „Mit anderem Gerät freigeben“ oder eine Reserve-Passphrase.",
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
                    "Entsperren",
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
                        "Ohne Passphrase lassen sich deine bisherigen Schlüssel nicht öffnen – von niemandem. Du kannst neue Schlüssel erstellen. Dann gilt:",
                        style = OasisType.Body,
                        color = OasisColors.Ink2
                    )
                    Bullets(
                        listOf(
                            "Bisherige Nachrichten kannst du nicht mehr lesen.",
                            "Deine Chatpartner sehen einen Hinweis, dass sich deine Schlüssel geändert haben."
                        )
                    )
                    OasisButton(
                        "Neue Schlüssel erstellen",
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

/** "Mit anderem Gerät freigeben". */
@Composable
private fun LinkBox(state: ChatAccessState, onRequestLink: suspend () -> Unit, onCancelLink: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    Panel {
        Text("Mit anderem Gerät freigeben", style = OasisType.heading(19), color = OasisColors.Night)
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
                "Freigabe anfragen",
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
