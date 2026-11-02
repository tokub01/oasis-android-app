package de.velwebsolutions.oasis.chat

import android.app.Application
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.core.ChatBackground
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.ChatTimeline
import de.velwebsolutions.oasis.chat.core.LookChoice
import de.velwebsolutions.oasis.chat.core.LookPhoto
import de.velwebsolutions.oasis.chat.ui.ConversationContent
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsContent
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsState
import de.velwebsolutions.oasis.chat.ui.ConversationState
import de.velwebsolutions.oasis.chat.ui.EffectActions
import de.velwebsolutions.oasis.chat.ui.EffectOverlay
import de.velwebsolutions.oasis.chat.ui.EffectPlay
import de.velwebsolutions.oasis.chat.ui.LookActions
import de.velwebsolutions.oasis.chat.ui.LookStyle
import de.velwebsolutions.oasis.chat.ui.drawParticle
import de.velwebsolutions.oasis.chat.ui.motionAllowed
import de.velwebsolutions.oasis.chat.ui.particles
import de.velwebsolutions.oasis.chatcrypto.MessageContent
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Backgrounds, the effect menu, chip and overlay, and the „Hintergrund“ section, at 360 dp with a screenshot each. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class ChatLookUiTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private var state by mutableStateOf(ConversationState())
    private var details by mutableStateOf(ConversationDetailsState())

    private val members = listOf(ConversationMember("1", "mira", "Mira"), ConversationMember("2", "jonas", "Jonas"))
    private val conversation = ChatConversation("7", "direct", null, "1", 7, 1, false, members, "")
    private val envelope = MessageEnvelope(1, "7", 1, "2", "n", "c", "s1", "2100-01-01T00:00:00Z")

    private fun item(signature: String, sender: String, text: String) = ChatMessageItem(
        signature,
        envelope.copy(signature = signature),
        sender,
        null,
        ChatMessageItem.State.Readable,
        text,
        MessageContent(text)
    )

    private val effects = EffectActions(onChoose = { calls += "effect $it" }, onClear = { calls += "clear" }, onDone = { calls += "done" })

    @Composable
    private fun Chat() = ConversationContent(
        state = state,
        myId = "1",
        senderName = { "Jonas" },
        onLoadOlder = {},
        onDraftChange = {},
        onSend = { calls += "send" },
        onReport = {},
        onAccept = {},
        onDecline = {},
        onBlockFlagged = {},
        onDismissFlagged = {},
        onNoticeSpoken = {},
        attachments = attachmentActions(calls),
        effects = effects
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides Navigator()) { content() } } }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/chat-look-$name.png")
    }

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2E9E95.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    @Test
    fun everyBackgroundAtThreeHundredSixty() {
        val timeline = ChatTimeline(listOf(item("a", "2", "Hallo Mira, wie geht's?"), item("b", "1", "Gut, und dir?")))
        state = ConversationState(conversation = conversation, timeline = timeline, canSend = true, nextBeforeId = "3")
        show { Chat() }
        for (background in ChatBackground.entries) {
            val photo = if (background == ChatBackground.Photo) LookPhoto(jpeg()) else null
            state = state.copy(look = LookChoice(background, "7", photo))
            capture("background-${background.key}")
            val outlined = compose.onAllNodes(
                androidx.compose.ui.test.hasTestTag("chat.message.outlined")
            ).fetchSemanticsNodes().isNotEmpty()
            assertEquals("only on Nachtwasser the own bubble is outlined", background == ChatBackground.Nachtwasser, outlined)
        }
        // A photo that cannot be read shows sand; the empty note gets its pill.
        state = ConversationState(conversation = conversation, look = LookChoice(ChatBackground.Photo, "7", LookPhoto(byteArrayOf(1))))
        compose.onNodeWithTag("chat.messages.empty").assertExists()
        assertEquals(LookStyle(chosen = true, dark = true), LookStyle.of(LookChoice(ChatBackground.Nachtwasser, "7")))
        assertEquals(LookStyle(), LookStyle.of(LookChoice(ChatBackground.Standard, "7")))
    }

    @Test
    fun theEffectMenuFromSendAndFromPlusAndTheChip() {
        state = ConversationState(conversation = conversation, canSend = true, draft = "Alles Gute!")
        show { Chat() }
        compose.onNodeWithTag("chat.composer.send").performTouchInput { longClick() }
        compose.onNodeWithText("Mit Effekt senden").assertExists()
        capture("effect-menu")
        compose.onNodeWithTag("chat.effect.konfetti").performClick()
        compose.waitForIdle()
        assertTrue(calls.contains("effect konfetti"))

        state = ConversationState(conversation = conversation, canSend = true, pendingEffect = "herzen")
        compose.onNodeWithTag("chat.composer.effectChip").assertExists()
        compose.onNodeWithText("Effekt: Herzen").assertExists()
        capture("effect-chip")
        compose.onNodeWithTag("chat.composer.effectClear").performClick()
        assertTrue(calls.contains("clear"))
        compose.onNodeWithTag("chat.composer.add").performClick()
        compose.onNodeWithTag("chat.composer.effect").performClick()
        compose.onNodeWithTag("chat.effect.feuerwerk").assertExists()
    }

    @Test
    fun anEffectFrameAndEveryParticleAtTwoMoments() {
        show { EffectOverlay(EffectPlay("konfetti", 1), onDone = { calls += "done" }, random = Random(7), progress = 0.45f) }
        capture("effect-konfetti")
        compose.waitForIdle()
        assertTrue("a fixed frame ends at once", calls.contains("done"))

        val image = ImageBitmap(360, 740)
        val scope = CanvasDrawScope()
        for (key in listOf("konfetti", "herzen", "ballons", "feuerwerk")) {
            val parts = particles(key, Random(1))
            assertTrue(parts.isNotEmpty())
            scope.draw(Density(2f), LayoutDirection.Ltr, Canvas(image), Size(360f, 740f)) {
                parts.forEach {
                    drawParticle(it, 0.4f)
                    drawParticle(it, 0.97f)
                }
            }
        }
        assertEquals(60, particles("konfetti").size)
        assertEquals(54, particles("feuerwerk").size)
    }

    @Test
    fun anEffectPlaysAboutTwoPointSixSecondsAndTakesNoTaps() {
        compose.mainClock.autoAdvance = false
        show { EffectOverlay(EffectPlay("ballons", 1), onDone = { calls += "done" }) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("chat.effect").assertExists().assertHasNoClickAction()
        assertFalse(calls.contains("done"))
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue(calls.contains("done"))
    }

    @Test
    fun withTheSystemsAnimationsOffNothingPlays() {
        assertTrue(motionAllowed(application))
        Settings.Global.putFloat(application.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertFalse(motionAllowed(application))
        show { EffectOverlay(EffectPlay("herzen", 1), onDone = { calls += "done" }) }
        compose.waitForIdle()
        assertTrue(calls.contains("done"))
        compose.onNodeWithTag("chat.effect").assertDoesNotExist()
        Settings.Global.putFloat(application.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @Test
    fun theBackgroundSectionInTheDetails() {
        details = ConversationDetailsState(conversation = conversation.copy(type = "group"), look = LookChoice(ChatBackground.Photo, "7"))
        val look = LookActions(
            onBackground = { calls += "bg ${it.key}" },
            onForAll = { calls += "forAll $it" },
            onPickPhoto = { calls += "pick" },
            onRemovePhoto = { calls += "remove" },
            onEffects = { calls += "effects $it" }
        )
        show { ConversationDetailsContent(details, true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, look) }
        compose.onNodeWithTag("chat.look").performScrollTo()
        capture("details")
        compose.onNodeWithTag("chat.look.duene").performScrollTo().performClick()
        compose.onNodeWithTag("chat.look.photo").performScrollTo().performClick()
        compose.onNodeWithTag("chat.look.pick").performScrollTo().performClick()
        compose.onNodeWithTag("chat.look.remove").performScrollTo().performClick()
        compose.onNodeWithTag("chat.look.forAll").performScrollTo().performClick()
        compose.onNodeWithTag("chat.look.effects").performScrollTo().performClick()
        details = details.copy(lookError = "Dieses Foto ließ sich nicht öffnen.", look = LookChoice(ChatBackground.Oase, "7"))
        compose.onNodeWithText("Dieses Foto ließ sich nicht öffnen.").assertExists()
        compose.onNodeWithTag("chat.look.remove").assertDoesNotExist()
        compose.waitForIdle()
        assertEquals(listOf("bg duene", "pick", "pick", "remove", "forAll true", "effects false"), calls)
    }
}
