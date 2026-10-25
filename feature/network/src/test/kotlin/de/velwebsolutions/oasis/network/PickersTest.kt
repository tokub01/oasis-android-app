package de.velwebsolutions.oasis.network

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.DataEnvelope
import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.api.ComposerContext
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.ui.components.CameraFileProvider
import de.velwebsolutions.oasis.network.ui.components.ImagePicker
import de.velwebsolutions.oasis.network.ui.components.rememberImagePicker
import de.velwebsolutions.oasis.network.ui.compose.ComposeUiState
import de.velwebsolutions.oasis.network.ui.compose.ComposerCallbacks
import de.velwebsolutions.oasis.network.ui.oases.OasisFormScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisFormViewModel
import de.velwebsolutions.oasis.network.ui.posts.ComposeContent
import de.velwebsolutions.oasis.network.ui.profiles.EditProfileScreen
import de.velwebsolutions.oasis.network.ui.profiles.EditProfileViewModel
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The photo picker and the camera with a stand-in for the system apps (an activity-result registry that answers at once). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h3000dp")
class PickersTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val models = ViewModelKeeper()

    /** What the system apps answer: a picked image, several, none, or whether the camera saved its photo. */
    private var answer: Any? = null
    private val launches = mutableMapOf<Class<*>, Int>()

    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches[contract.javaClass] = requestCode
            dispatchResult(requestCode, answer)
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    @Before
    fun camera() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        // FileProvider remembers its folders per process; every Robolectric test has its own cache folder.
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let {
            (it.get(null) as MutableMap<*, *>).clear()
        }
    }

    @After
    fun clear() = models.clear()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        OasisTheme {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalNavigator provides Navigator(),
                content = content
            )
        }
    }

    /** Waits until [condition] holds: image work runs on Dispatchers.IO and comes back through the main looper. */
    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not met within 5 s" }
            Thread.sleep(20)
            compose.waitForIdle()
        }
    }

    private fun imageFile(name: String): Uri {
        val bitmap = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    private fun textFile(): Uri = Uri.fromFile(File(context.cacheDir, "notiz.txt").apply { writeText("kein Bild") })

    @Test
    fun `one image, several, none, and photos from the camera`() {
        val single = mutableListOf<List<Uri>>()
        val several = mutableListOf<List<Uri>>()
        var one: ImagePicker? = null
        var many: ImagePicker? = null
        show {
            one = rememberImagePicker { single += it }
            many = rememberImagePicker(maxItems = 2) { several += it }
        }
        compose.waitForIdle()
        val a = imageFile("a.png")
        val b = imageFile("b.png")
        answer = null
        one!!.pick()
        answer = a
        one!!.pick()
        answer = emptyList<Uri>()
        many!!.pick()
        answer = listOf(a, b, a)
        many!!.pick()
        assertEquals(listOf(listOf(a)), single)
        assertEquals(listOf(listOf(a, b)), several)

        answer = false
        one!!.takePhoto!!()
        answer = true
        one!!.takePhoto!!()
        assertEquals(2, single.size)
        assertTrue(single.last().single().toString().startsWith("content://"))
        // A late answer of the camera without a photo on its way (the app was restarted) is ignored.
        registry.dispatchResult(launches.getValue(ActivityResultContracts.TakePicture::class.java), true)
        assertEquals(2, single.size)
        assertNotNull(CameraFileProvider())
    }

    @Test
    fun `without a camera only the photo picker is offered`() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
        var picker: ImagePicker? = null
        show { picker = rememberImagePicker {} }
        compose.waitForIdle()
        assertNull(picker!!.takePhoto)
    }

    @Test
    fun `the composer adds picked and photographed images and skips unreadable ones`() {
        val added = mutableListOf<ImageUpload?>()
        val composer = OasisJson.snake.decodeFromString(DataEnvelope.serializer(ComposerContext.serializer()), Fixtures.composer).data
        val callbacks = ComposerCallbacks(
            onBody = {}, onVisibility = {}, onKind = {}, onLinkRead = {}, onToneConfirmed = {}, onAddImage = { added += it },
            onRemoveImage = {}, onAlt = { _, _ -> }, checkTone = {}, publish = { null }
        )
        show { ComposeContent(ComposeUiState(context = Loadable.Loaded(composer)), callbacks, onPublished = {}) }
        answer = listOf(imageFile("c.png"), textFile())
        compose.onNodeWithTag("compose.addImages").performClick()
        settle { added.size == 2 }
        assertEquals("bild-1.png", added[0]?.filename)
        assertNull(added[1])
        answer = true
        compose.onNodeWithTag("compose.camera").performClick()
        settle { added.size == 3 }
    }

    @Test
    fun `the profile stores a picked photo and banner`() {
        val saved = Fixtures.ownProfile.replaceFirst("{\"data\"", "{\"message\":\"Gespeichert.\",\"data\"")
        val network = StubbedNetwork(
            stub("GET", "network/profile", Fixtures.ownProfile),
            stub("POST", "network/tone", """{"data":{"hint":null,"nudge":false}}"""),
            stub("POST", "network/profile/photo", saved),
            stub("POST", "network/profile/banner", saved)
        )
        val model = models.keep(EditProfileViewModel(network.repository, network.common))
        show { EditProfileScreen(model) }
        settle { compose.onAllNodes(hasText("Farbe deiner Initialen")).fetchSemanticsNodes().isNotEmpty() }
        answer = imageFile("d.png")
        compose.onNodeWithTag("profileEdit.photo").performClick()
        settle { network.transport.requests.any { it.path == "network/profile/photo" } }
        settle { !model.form.value.uploading }
        compose.onNodeWithTag("profileEdit.banner").performClick()
        settle { network.transport.requests.any { it.path == "network/profile/banner" } }
    }

    @Test
    fun `founding an oasis keeps a picked banner and shows it`() {
        val network = StubbedNetwork(stub("GET", "network/oases", Fixtures.oases))
        val model = models.keep(OasisFormViewModel(null, network.repository))
        show { OasisFormScreen(null, model) }
        settle { compose.onAllNodes(hasText("Bild hochladen")).fetchSemanticsNodes().isNotEmpty() }
        answer = imageFile("e.png")
        compose.onNodeWithText("Bild hochladen").performClick()
        settle { model.state.value.banner != null }
        settle { compose.onAllNodes(hasText("Ausgewählt: banner.png")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Entfernen").performClick()
        settle { model.state.value.banner == null }
    }
}
