package com.syncdeck.app.platform

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

object LinuxFolderPicker {
    fun chooseExisting(title: String): Path? = chooseDirectory(title)

    fun chooseOfflineUpdateBundle(): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Import offline update bundle"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter("SyncDroid-Mesh offline update (*.sdu)", "sdu")
            currentDirectory = Path.of(System.getProperty("user.home")).toFile()
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath().toAbsolutePath().normalize()
        } else {
            null
        }
    }

    fun chooseChatAttachment(): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Attach a file to mesh chat"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = true
            currentDirectory = Path.of(System.getProperty("user.home")).toFile()
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath().toAbsolutePath().normalize()
        } else null
    }

    fun openChatAttachment(path: Path) {
        val file = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(file)) { "This attachment is no longer available" }
        require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            "File manager integration is unavailable"
        }
        Desktop.getDesktop().open(file.toFile())
    }

    fun openInFileManager(path: Path) {
        val folder = path.toAbsolutePath().normalize()
        require(Files.isDirectory(folder)) { "This folder is no longer available on this device" }
        require(Desktop.isDesktopSupported()) { "File manager integration is unavailable" }
        val desktop = Desktop.getDesktop()
        require(desktop.isSupported(Desktop.Action.OPEN)) { "File manager integration is unavailable" }
        desktop.open(folder.toFile())
    }

    fun chooseParentAndCreate(title: String, displayName: String): Path? {
        val parent = chooseDirectory(title) ?: return null
        val folderName = displayName
            .trim()
            .replace(Regex("[/\\u0000:]"), "-")
            .ifBlank { "SyncDeck Folder" }
        val destination = parent.resolve(folderName)
        require(!Files.exists(destination)) {
            "A file or folder named '$folderName' already exists in that location"
        }
        return Files.createDirectory(destination)
    }

    private fun chooseDirectory(title: String): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            currentDirectory = Path.of(System.getProperty("user.home")).toFile()
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath().toAbsolutePath().normalize()
        } else {
            null
        }
    }
}
