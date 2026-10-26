package de.velwebsolutions.oasis.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.design.components.OasisLogo
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.design.theme.OasisType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the feature graphic of the Play listing (1024 × 500, src/main/play/listings/de-DE/graphics/feature-graphic)
 * from the app's own logo and fonts: `./gradlew :app:testDebugUnitTest --tests '*StoreGraphicsTest*'` writes it to
 * build/outputs/roborazzi/play-feature-graphic.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1024dp-h500dp-mdpi")
class StoreGraphicsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun featureGraphic() {
        compose.setContent {
            OasisTheme {
                Box(Modifier.fillMaxSize().background(OasisColors.Sand), contentAlignment = Alignment.CenterStart) {
                    Column(Modifier.padding(horizontal = 72.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                        OasisLogo(size = 96.dp)
                        Text("Social Media, das dir Zeit zurückgibt.", style = OasisType.display(44), color = OasisColors.Night)
                        Text("Ohne Werbung. Ohne Algorithmus. Mit einem Ende.", style = OasisType.Lead, color = OasisColors.Ink2)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/play-feature-graphic.png")
    }
}
