package com.syncdroid.shared.cloud

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CloudRemoteStoreTest {
    @Test fun oneDriveUploadsChunksDownloadsAndTrashesUsingRealHttpClient() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val root = Files.createTempDirectory("onedrive-http-")
        val received = ByteArrayOutputStream()
        val ranges = mutableListOf<String>()
        var trashed = false
        val url = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { request ->
            val path = request.requestURI.path
            val body: ByteArray
            val status: Int
            when {
                path.endsWith("createUploadSession") -> {
                    body = "{\"uploadUrl\":\"$url/upload\"}".toByteArray(); status = 200
                }
                path == "/upload" -> {
                    val chunk = request.requestBody.readBytes()
                    assertEquals(chunk.size.toString(), request.requestHeaders.getFirst("Content-Length"))
                    assertNull(request.requestHeaders.getFirst("Authorization"))
                    received.write(chunk)
                    ranges += request.requestHeaders.getFirst("Content-Range")
                    status = if (ranges.size == 2) 201 else 202
                    body = if (status == 201) "{\"id\":\"done\",\"name\":\"file.sdenc\",\"size\":${received.size()}}".toByteArray()
                        else "{\"nextExpectedRanges\":[\"3276800-\"]}".toByteArray()
                }
                request.requestMethod == "DELETE" -> { trashed = true; status = 204; body = byteArrayOf() }
                else -> { status = 200; body = received.toByteArray() }
            }
            request.sendResponseHeaders(status, if (status == 204) -1 else body.size.toLong())
            request.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val store = OneDriveRemoteStore({ "test-token" }, graphEndpoint = url)
            val bytes = ByteArray(10 * 320 * 1024 + 29) { (it % 251).toByte() }
            val source = root.resolve("source")
            Files.write(source, bytes)
            val item = store.upload("folder", "file.sdenc", source)
            assertContentEquals(bytes, received.toByteArray())
            assertEquals(2, ranges.size)
            val destination = root.resolve("download")
            store.download(item.id, destination)
            assertContentEquals(bytes, Files.readAllBytes(destination))
            store.trash(item.id)
            assertTrue(trashed)
        } finally { server.stop(0); root.toFile().deleteRecursively() }
    }

    @Test fun googleDriveListsModificationDatesAndMovesOldObjectsToTrash() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var trashBody = ""
        server.createContext("/") { request ->
            assertEquals("Bearer test-token", request.requestHeaders.getFirst("Authorization"))
            val response = if (request.requestMethod == "PATCH") {
                trashBody = request.requestBody.bufferedReader().readText(); "{\"id\":\"old\"}"
            } else "{\"files\":[{\"id\":\"old\",\"name\":\"file.sdenc\",\"size\":\"12\",\"modifiedTime\":\"2026-01-01T00:00:00Z\"}]}"
            val bytes = response.toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val store = GoogleDriveRemoteStore({ "test-token" }, filesEndpoint = "http://127.0.0.1:${server.address.port}/files")
            val item = store.list("folder").single()
            assertEquals(1767225600000, item.modifiedAtMillis)
            store.trash(item.id)
            assertEquals("{\"trashed\":true}", trashBody)
        } finally { server.stop(0) }
    }
}
