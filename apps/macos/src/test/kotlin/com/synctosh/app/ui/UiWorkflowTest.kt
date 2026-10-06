package com.synctosh.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.synctosh.app.mesh.CloudAccountStatus
import com.synctosh.app.mesh.VisiblePairingOffer
import com.synctosh.app.model.MeshPeer
import com.synctosh.app.model.ThemeMode
import com.syncdroid.shared.cloud.CloudProvider
import com.syncdroid.shared.cloud.CloudSyncPolicy
import com.syncdroid.shared.cloud.CloudSyncScope
import com.syncdroid.shared.update.UpdateState
import java.io.File
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UI interaction regressions; OS integrations are tested on their native platforms. */
@OptIn(ExperimentalTestApi::class)
class UiWorkflowTest {
    @Test fun pastedPairingCodeCanBeSubmittedWithEnter() = runComposeUiTest {
        var joined: String? = null
        setContent { SyncToshTheme(ThemeMode.Light) {
            JoinMeshDialog(5, false, null, {}, { joined = it })
        } }
        onNode(hasSetTextAction()).performTextInput("123 456")
        onNodeWithText("Join", substring = false).assertIsEnabled()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals("123456", joined) }
    }

    @Test fun clearingCodeAndLockoutPreventPairing() = runComposeUiTest {
        var attempts by mutableStateOf(5)
        var joined = false
        setContent { SyncToshTheme(ThemeMode.Dark) {
            JoinMeshDialog(attempts, false, null, {}, { joined = true })
        } }
        onNode(hasSetTextAction()).performTextInput("123456")
        onNode(hasSetTextAction()).performTextClearance()
        onNodeWithText("Join", substring = false).assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("12345")
        onNodeWithText("Join", substring = false).assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("6")
        runOnIdle { attempts = 0 }
        onNodeWithText("Join", substring = false).assertIsNotEnabled()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(false, joined) }
    }

    @Test fun pairingInProgressDisablesDuplicateSubmission() = runComposeUiTest {
        var busy by mutableStateOf(false)
        var submissions = 0
        setContent { SyncToshTheme(ThemeMode.Light) {
            JoinMeshDialog(5, busy, null, {}, { submissions++; busy = true })
        } }
        onNode(hasSetTextAction()).performTextInput("123456")
        onNodeWithText("Join", substring = false).performClick()
        onNodeWithText("Pairing…").assertIsNotEnabled()
        onNodeWithText("Cancel").assertIsNotEnabled()
        onNode(hasSetTextAction()).assertDoesNotExist()
        runOnIdle { assertEquals(1, submissions) }
    }

    @Test fun expiredOfferDoesNotDisplayAnUnusableCode() = runComposeUiTest {
        setContent { SyncToshTheme(ThemeMode.Light) {
            PairingOfferDialog(VisiblePairingOffer("123456", 0L), {})
        } }
        onNodeWithText("This code has expired.", substring = true).assertIsDisplayed()
        onNodeWithText("123  456").assertDoesNotExist()
    }

    @Test fun firstRunOffersBothSetupPaths() = runComposeUiTest {
        var start = false
        var join = false
        setContent { ReviewSurface {
            SyncScreen("My computer", emptyList(), emptyList(), null, "Ready to sync",
                false, true, {}, {}, {}, { start = true }, { join = true })
        } }
        onNodeWithText("Start a mesh").performClick()
        onNodeWithText("Join with code").performClick()
        onNodeWithText("Sync now").assertDoesNotExist()
        runOnIdle { assertTrue(start && join) }
    }

    @Test fun folderCreationRequiresMeshSetup() = runComposeUiTest {
        var hasMesh by mutableStateOf(false)
        var added = false
        setContent { ReviewSurface {
            FoldersScreen(emptyList(), hasMesh, CloudSyncPolicy(), { added = true }, {}, {}, {},
                { _, _ -> }, { emptyList() }, { _, _, _ -> }, { _, _ -> })
        } }
        onNodeWithText("Add", substring = false).assertIsNotEnabled()
        runOnIdle { hasMesh = true }
        onNodeWithText("Add", substring = false).performClick()
        runOnIdle { assertTrue(added) }
    }

    @Test fun removingADeviceIsDiscoverableAndRequiresConfirmation() = runComposeUiTest {
        var removed: String? = null
        setContent { ReviewSurface {
            DevicesScreen("My computer", listOf(MeshPeer("phone", "Phone", false)),
                {}, {}, {}, { removed = it }, true)
        } }
        onNodeWithContentDescription("Options for Phone").performScrollTo().performClick()
        onNodeWithText("Delete", substring = false).performClick()
        onNodeWithText("Delete Phone?").assertIsDisplayed()
        runOnIdle { assertEquals(null, removed) }
        onNodeWithText("Cancel").performClick()
        runOnIdle { assertEquals(null, removed) }
        onNodeWithContentDescription("Options for Phone").performClick()
        onNodeWithText("Delete", substring = false).performClick()
        onNodeWithText("Delete", substring = false).performClick()
        runOnIdle { assertEquals("phone", removed) }
    }

    @Test fun chatEnterSendsAndClearsTheDraft() = runComposeUiTest {
        var message: String? = null
        var mesh by mutableStateOf(false)
        setContent { ReviewSurface {
            ChatScreen(emptyList(), "me", emptyMap(), mesh, { message = it }, {}, {}, {}, { null })
        } }
        onNodeWithText("Send").assertIsNotEnabled()
        onNodeWithContentDescription("Attach a file").assertIsNotEnabled()
        runOnIdle { mesh = true }
        onNode(hasSetTextAction()).performTextInput("Hello mesh")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals("Hello mesh", message) }
        onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test fun settingsActionsAndAppearanceAreReachable() = runComposeUiTest {
        val opened = mutableListOf<String>()
        var mode by mutableStateOf(ThemeMode.Light)
        setContent { ReviewSurface(mode) { ReviewSettings(mode, { mode = it }, { opened += it }) } }
        onNodeWithText("Dark", substring = false).performClick()
        runOnIdle { assertEquals(ThemeMode.Dark, mode) }
        for (label in listOf("Cloud sync", "File conflicts", "File history", "Power & discovery", "Background operation")) {
            onNodeWithText(label, substring = false).performScrollTo().performClick()
        }
        runOnIdle { assertEquals(listOf("cloud", "conflicts", "history", "power", "background"), opened) }
        onNodeWithText("Advanced update options").performScrollTo().performClick()
        onNodeWithText("Import offline update bundle").performScrollTo().assertIsDisplayed()
        onNodeWithText("About SyncTosh").performScrollTo().assertHasNoClickAction()
    }

    @Test fun meshDiagramShowsDeviceNamesAtLargeTextSizes() = runComposeUiTest {
        setContent { ReviewSurface(width = 420, fontScale = 1.5f) {
            LocalMeshView("My personal computer", listOf(MeshPeer("phone", "Work phone", true)), {})
        } }
        onNodeWithText("My personal computer").assertIsDisplayed()
        onNodeWithText("Work phone").assertIsDisplayed()
        saveScreenshot("mesh-large-text")
    }

    @Test fun syncActionsFitANarrowWindowWithLargeText() = runComposeUiTest {
        setContent { ReviewSurface(width = 420, fontScale = 1.5f) {
            SyncScreen("My computer", emptyList(), emptyList(), "Family mesh",
                "Ready to sync", false, true, {}, {}, {}, {}, {})
        } }
        val bounds = onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val sync = onNodeWithText("Sync now").fetchSemanticsNode().boundsInRoot
        saveScreenshot("sync-narrow-large-text")
        assertTrue(sync.left >= bounds.left && sync.right <= bounds.right, "Sync action clipped: $sync in $bounds")
        val title = onNodeWithText("Ready to sync").fetchSemanticsNode().boundsInRoot
        assertTrue(title.width >= 150f, "Status text squeezed to ${title.width}px")
    }

    @Test fun mainAndSettingsScreensRenderInBothThemes() = runComposeUiTest {
        var mode by mutableStateOf(ThemeMode.Light)
        var screen by mutableStateOf("sync")
        setContent { ReviewSurface(mode) {
            when (screen) {
                "sync" -> SyncScreen("My computer", emptyList(), emptyList(), null,
                    "Ready to sync", false, true, {}, {}, {}, {}, {})
                "folders" -> FoldersScreen(emptyList(), false, CloudSyncPolicy(), {}, {}, {}, {},
                    { _, _ -> }, { emptyList() }, { _, _, _ -> }, { _, _ -> })
                "devices" -> DevicesScreen("My computer", listOf(MeshPeer("phone", "Phone", true)), {}, {}, {}, {}, true)
                "chat" -> ChatScreen(emptyList(), "me", emptyMap(), true, {}, {}, {}, {}, { null })
                "settings" -> ReviewSettings(mode)
                "cloud" -> CloudSyncSettingsScreen(CloudSyncPolicy(), emptyList(), {}, { _, _ -> },
                    CloudProvider.entries.map { CloudAccountStatus(it, true, false) }, false, {}, "Ready", {}, {}, {})
                "background" -> BackgroundOperationScreen(false, false, {}, {}, {})
                "power" -> PowerDiscoveryScreen(15, 300L, false, "Home Wi-Fi", setOf("Home Wi-Fi"), {}, {}, {}, {}, {}, {})
                "history" -> FileHistoryScreen(emptyList(), emptyList(), emptyMap(), false, null, {}, {})
            }
        } }
        for (theme in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            for (page in listOf("sync", "folders", "devices", "chat", "settings", "cloud", "background", "power", "history")) {
                runOnIdle { mode = theme; screen = page }
                onNodeWithTag("viewport").assertIsDisplayed()
                saveScreenshot("${theme.name.lowercase()}-$page")
            }
        }
    }

    private fun ComposeUiTest.saveScreenshot(name: String) {
        val bitmap = onNodeWithTag("viewport").captureToImage()
        val output = File("build/ui-review/$name.png").apply { parentFile.mkdirs() }
        Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
            image.encodeToData()!!.use { output.writeBytes(it.bytes) }
        }
    }
}

@Composable
private fun ReviewSurface(mode: ThemeMode = ThemeMode.Light, width: Int = 980, fontScale: Float = 1f, content: @Composable () -> Unit) {
    SyncToshTheme(mode) {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            Surface(Modifier.size(width.dp, 720.dp).clipToBounds().testTag("viewport"), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

@Composable
private fun ReviewSettings(mode: ThemeMode, onTheme: (ThemeMode) -> Unit = {}, onOpen: (String) -> Unit = {}) {
    SettingsScreen(updateState = UpdateState.Idle("1.2.15"), onUpdateAction = {}, onImportUpdateBundle = {},
        onDownloadUpdateBundle = {}, offlineUpdateImportUnlocked = false, onOfflineUpdateImportUnlocked = {},
        themeMode = mode, onThemeModeChanged = onTheme, onOpenPowerSettings = { onOpen("power") },
        onOpenFileHistory = { onOpen("history") }, conflictCount = 0, onOpenConflicts = { onOpen("conflicts") },
        cloudScope = CloudSyncScope.DISABLED,
        onOpenCloudSettings = { onOpen("cloud") }, launchAtLogin = false, noBackgroundService = false,
        onOpenBackgroundSettings = { onOpen("background") })
}
