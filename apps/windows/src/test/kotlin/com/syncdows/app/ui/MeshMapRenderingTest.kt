package com.syncdows.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.syncdows.app.model.MeshPeer
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test

class MeshMapRenderingTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun rendersLongNamesAndDetailsAtWideAndNarrowWidths() {
        SwingUtilities.invokeAndWait {
            for ((width, scale) in listOf(900 to 1f, 380 to 1.5f)) {
                val scene = ImageComposeScene(width = width, height = 1800, density = Density(1f, scale)) {
                    MaterialTheme {
                        Column {
                            LocalMeshView("Living room Windows gaming computer", (0 until 8).map { index ->
                                MeshPeer("device-$index", listOf("Bedroom handheld gaming device", "Galaxy Fold 5", "Office desktop computer", "A very long device name that must wrap completely")[index % 4],
                                    online = index % 3 != 0, lastOnlineAtMillis = 1788540000000L,
                                    syncing = index == 1, syncProgress = if (index == 1) 0.63f else null)
                            }, {})
                        }
                    }
                }
                try {
                    scene.render().close()
                    val image = scene.render()
                    File("build/mesh-map-$width.png").writeBytes(requireNotNull(image.encodeToData()).bytes)
                    image.close()
                } finally { scene.close() }
            }
        }
    }
}
