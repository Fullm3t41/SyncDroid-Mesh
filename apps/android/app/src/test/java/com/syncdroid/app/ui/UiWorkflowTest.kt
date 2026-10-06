package com.syncdroid.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.syncdroid.app.storage.ManagedStorageRoot
import com.syncdroid.app.ui.theme.SyncDroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val files = TemporaryFolder()

    @Test fun clearingTheNativeCodeInputDisablesJoin() {
        compose.setContent {
            SyncDroidTheme { Surface {
                PairingScreen(null, false, null, "My mesh", false, 5, 0L, {}, {}, {}, {})
            } }
        }
        compose.onNodeWithText("Find existing device").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { codeInput().setText("123456") }
        compose.onNodeWithText("Find existing device").assertIsEnabled()
        saveScreenshot("pairing-code-light")
        compose.runOnIdle { codeInput().setText("") }
        compose.onNodeWithText("Find existing device").assertIsNotEnabled()
    }

    @Test fun joiningDisablesInputAndDuplicateAttempts() {
        var busy by mutableStateOf(false)
        val submitted = mutableListOf<String>()
        compose.setContent {
            SyncDroidTheme(darkTheme = true) { Surface {
                PairingScreen(null, busy, null, "My mesh", false, 5, 0L,
                    { submitted += it; busy = true }, {}, {}, {})
            } }
        }
        compose.onNodeWithText("Find existing device").performScrollTo()
        compose.runOnIdle { codeInput().setText("123456") }
        compose.onNodeWithText("Find existing device").performClick()
        compose.onNodeWithText("Finding device…").assertIsNotEnabled()
        saveScreenshot("pairing-busy-dark")
        compose.runOnIdle {
            assertEquals(listOf("123456"), submitted)
            assertFalse(codeInput().isEnabled)
        }
    }

    @Test fun systemBackTraversesFolderBeforeLeavingPicker() {
        val root = files.newFolder("Storage")
        root.resolve("Child/Nested").mkdirs()
        var exits = 0
        compose.setContent {
            SyncDroidTheme { Surface {
                FileManagerScreen(listOf(ManagedStorageRoot("test", "Storage", root, false)),
                    onBack = { exits++ }, onFolderSelected = { _, _, _ -> })
            } }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Child").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Child").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Nested").fetchSemanticsNodes().isNotEmpty() }
        saveScreenshot("folder-picker-child")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Child").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(0, exits) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, exits) }
    }

    private fun codeInput(): EditText = requireNotNull(findEditText(compose.activity.window.decorView))

    private fun saveScreenshot(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/ui-review/$name.png").apply { parentFile?.mkdirs() }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findEditText(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
