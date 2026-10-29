package de.velwebsolutions.oasis.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real activity with the real Hilt graph: a link opens its screen once, and rotating keeps where the member went. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MainActivityTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var navigator: Navigator

    @Before
    fun inject() = hilt.inject()

    @Test
    fun `an activity created again does not open its link again`() {
        val link = Intent(ApplicationProvider.getApplicationContext<Application>(), MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("https://get-oasis.com/tropfen/4"))
        ActivityScenario.launch<MainActivity>(link).use { scenario ->
            assertEquals(listOf(NetworkRoute.Post(4)), navigator.stack(MainTab.Source).toList())
            navigator.open(NetworkRoute.Profile("jonas"))
            scenario.recreate()
            assertEquals(listOf(NetworkRoute.Post(4), NetworkRoute.Profile("jonas")), navigator.stack(MainTab.Source).toList())
        }
    }
}
