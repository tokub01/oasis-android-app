package de.velwebsolutions.oasis.account.ui

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import de.velwebsolutions.oasis.account.ui.components.ScopedViewModels
import de.velwebsolutions.oasis.account.ui.components.writeFile
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Saving the data export and the view models that live only while their screen is shown. */
@RunWith(RobolectricTestRunner::class)
class AccountComponentsTest {
    @get:Rule val compose = createComposeRule()

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    @Test
    fun savesTheExportWhereThePickerPointsAndReportsAStorageThatRefuses() {
        val file = File(app.cacheDir, "export.zip")
        assertTrue(writeFile(app.contentResolver, Uri.fromFile(file), byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(1, 2), file.readBytes())
        assertFalse("an unknown provider gives no stream", writeFile(app.contentResolver, Uri.parse("content://nowhere/x"), byteArrayOf(1)))
        val broken = Uri.parse("content://broken/x")
        shadowOf(app.contentResolver).registerOutputStream(
            broken,
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("full")
            }
        )
        assertFalse("a full disk", writeFile(app.contentResolver, broken, byteArrayOf(1)))
    }

    class Plain : ViewModel()

    @Test
    fun scopedViewModelsWorkUnderAnOwnerWithoutHiltFactory() {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                ScopedViewModels {
                    val model: Plain = viewModel()
                    Text(model::class.simpleName.orEmpty())
                }
            }
        }
        compose.onNodeWithText("Plain").assertExists()
    }
}
