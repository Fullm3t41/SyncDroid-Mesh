package com.syncdroid.shared.cloud

import java.io.RandomAccessFile
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CloudRemoteItem(val id: String, val name: String, val sizeBytes: Long, val folder: Boolean, val modifiedAtMillis: Long = 0)

interface CloudRemoteStore {
    val rootId: String
    suspend fun ensureFolder(parentId: String, name: String): String
    suspend fun list(parentId: String): List<CloudRemoteItem>
    suspend fun upload(parentId: String, name: String, source: Path): CloudRemoteItem
    suspend fun download(itemId: String, destination: Path)
    suspend fun trash(itemId: String)
}

class GoogleDriveRemoteStore(
    private val accessToken: suspend () -> String,
    private val http: HttpClient = defaultCloudHttpClient(),
    private val filesEndpoint: String = FILES,
    private val uploadEndpoint: String = UPLOAD,
) : CloudRemoteStore {
    override val rootId = "root"

    override suspend fun ensureFolder(parentId: String, name: String): String {
        list(parentId).firstOrNull { it.folder && it.name == name }?.let { return it.id }
        val json = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", listOf(parentId))
        return jsonRequest(
            HttpRequest.newBuilder(URI("$filesEndpoint?fields=id,name,mimeType,size"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString())),
        ).getString("id")
    }

    override suspend fun list(parentId: String): List<CloudRemoteItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<CloudRemoteItem>()
        var pageToken: String? = null
        do {
            val query = buildString {
                append("q=").append(url("'$parentId' in parents and trashed = false"))
                append("&fields=").append(url("nextPageToken,files(id,name,mimeType,size,modifiedTime)"))
                append("&pageSize=1000")
                pageToken?.let { append("&pageToken=").append(url(it)) }
            }
            val json = jsonRequest(HttpRequest.newBuilder(URI("$filesEndpoint?$query")).GET())
            val files = json.getJSONArray("files")
            repeat(files.length()) { index ->
                val item = files.getJSONObject(index)
                items += CloudRemoteItem(
                    item.getString("id"), item.getString("name"), item.optLong("size", 0),
                    item.optString("mimeType") == FOLDER_MIME, modifiedTime(item.optString("modifiedTime")),
                )
            }
            pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        items
    }

    override suspend fun upload(parentId: String, name: String, source: Path): CloudRemoteItem = withContext(Dispatchers.IO) {
        require(Files.isRegularFile(source))
        val existing = list(parentId).firstOrNull { !it.folder && it.name == name }
        val metadata = JSONObject().put("name", name).apply { if (existing == null) put("parents", listOf(parentId)) }
        val endpoint = if (existing == null) "$uploadEndpoint/files?uploadType=resumable&fields=id,name,mimeType,size"
        else "$uploadEndpoint/files/${urlPath(existing.id)}?uploadType=resumable&fields=id,name,mimeType,size"
        val startBuilder = HttpRequest.newBuilder(URI(endpoint))
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("X-Upload-Content-Type", "application/octet-stream")
            .header("X-Upload-Content-Length", Files.size(source).toString())
        val start = authorized(
            if (existing == null) startBuilder.POST(HttpRequest.BodyPublishers.ofString(metadata.toString()))
            else startBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(metadata.toString())),
        )
        val startResponse = http.send(start, HttpResponse.BodyHandlers.ofString())
        require(startResponse.statusCode() in 200..299) { cloudError("Google Drive", startResponse) }
        val location = startResponse.headers().firstValue("Location").orElseThrow {
            IllegalStateException("Google Drive did not open a resumable upload")
        }
        val upload = HttpRequest.newBuilder(URI(location))
            .timeout(Duration.ofMinutes(30))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofFile(source))
            .build()
        val response = http.send(upload, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { cloudError("Google Drive", response) }
        responseItem(JSONObject(response.body()))
    }

    override suspend fun download(itemId: String, destination: Path) = withContext(Dispatchers.IO) {
        Files.createDirectories(requireNotNull(destination.parent))
        val request = authorized(HttpRequest.newBuilder(URI("$filesEndpoint/${urlPath(itemId)}?alt=media")).GET())
        val response = http.send(request, HttpResponse.BodyHandlers.ofFile(destination))
        require(response.statusCode() in 200..299) { "Google Drive download failed (${response.statusCode()})" }
    }

    override suspend fun trash(itemId: String) {
        jsonRequest(HttpRequest.newBuilder(URI("$filesEndpoint/${urlPath(itemId)}"))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"trashed\":true}")))
    }

    private suspend fun jsonRequest(builder: HttpRequest.Builder): JSONObject = withContext(Dispatchers.IO) {
        val response = http.send(authorized(builder), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { cloudError("Google Drive", response) }
        JSONObject(response.body())
    }

    private suspend fun authorized(builder: HttpRequest.Builder): HttpRequest =
        builder.timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer ${accessToken()}").build()

    private fun responseItem(json: JSONObject) = CloudRemoteItem(
        json.getString("id"), json.getString("name"), json.optLong("size", 0), json.optString("mimeType") == FOLDER_MIME,
    )

    private companion object {
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}

class OneDriveRemoteStore(
    private val accessToken: suspend () -> String,
    private val http: HttpClient = defaultCloudHttpClient(),
    private val graphEndpoint: String = GRAPH,
) : CloudRemoteStore {
    override val rootId = "root"

    override suspend fun ensureFolder(parentId: String, name: String): String {
        list(parentId).firstOrNull { it.folder && it.name == name }?.let { return it.id }
        val body = JSONObject().put("name", name).put("folder", JSONObject())
            .put("@microsoft.graph.conflictBehavior", "fail")
        return jsonRequest(
            HttpRequest.newBuilder(URI("${itemEndpoint(parentId)}/children"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())),
        ).getString("id")
    }

    override suspend fun list(parentId: String): List<CloudRemoteItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<CloudRemoteItem>()
        var next: String? = "${itemEndpoint(parentId)}/children?\$select=id,name,size,folder,lastModifiedDateTime&\$top=1000"
        while (next != null) {
            val json = jsonRequest(HttpRequest.newBuilder(URI(next)).GET())
            val values = json.getJSONArray("value")
            repeat(values.length()) { result += responseItem(values.getJSONObject(it)) }
            next = json.optString("@odata.nextLink").takeIf(String::isNotBlank)
        }
        result
    }

    override suspend fun upload(parentId: String, name: String, source: Path): CloudRemoteItem = withContext(Dispatchers.IO) {
        require(Files.isRegularFile(source))
        if (Files.size(source) == 0L) {
            val request = authorized(
                HttpRequest.newBuilder(URI("${itemEndpoint(parentId)}:/${urlPath(name)}:/content"))
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.noBody()),
            )
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            require(response.statusCode() in 200..299) { cloudError("OneDrive", response) }
            return@withContext responseItem(JSONObject(response.body()))
        }
        val encodedName = urlPath(name)
        val sessionBody = JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace"))
        val session = jsonRequest(
            HttpRequest.newBuilder(URI("${itemEndpoint(parentId)}:/$encodedName:/createUploadSession"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(sessionBody.toString())),
        )
        val uploadUrl = URI(session.getString("uploadUrl"))
        val size = Files.size(source)
        var offset = 0L
        var completed: JSONObject? = null
        RandomAccessFile(source.toFile(), "r").use { file ->
            while (offset < size) {
                val count = minOf(UPLOAD_CHUNK_BYTES.toLong(), size - offset).toInt()
                val bytes = ByteArray(count).also { if (count > 0) file.readFully(it) }
                val end = offset + count - 1
                val request = HttpRequest.newBuilder(uploadUrl)
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Range", "bytes $offset-$end/$size")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                require(response.statusCode() in 200..299) { cloudError("OneDrive", response) }
                if (response.statusCode() == 200 || response.statusCode() == 201) completed = JSONObject(response.body())
                offset += count
            }
        }
        responseItem(requireNotNull(completed) { "OneDrive upload did not complete" })
    }

    override suspend fun download(itemId: String, destination: Path) = withContext(Dispatchers.IO) {
        Files.createDirectories(requireNotNull(destination.parent))
        val response = http.send(
            authorized(HttpRequest.newBuilder(URI("$graphEndpoint/me/drive/items/${urlPath(itemId)}/content")).GET()),
            HttpResponse.BodyHandlers.ofFile(destination),
        )
        require(response.statusCode() in 200..299) { "OneDrive download failed (${response.statusCode()})" }
    }

    override suspend fun trash(itemId: String): Unit = withContext(Dispatchers.IO) {
        val response = http.send(authorized(HttpRequest.newBuilder(
            URI("$graphEndpoint/me/drive/items/${urlPath(itemId)}")).DELETE()), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299 || response.statusCode() == 404) { cloudError("OneDrive", response) }
    }

    private suspend fun jsonRequest(builder: HttpRequest.Builder): JSONObject = withContext(Dispatchers.IO) {
        val response = http.send(authorized(builder), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { cloudError("OneDrive", response) }
        JSONObject(response.body())
    }

    private suspend fun authorized(builder: HttpRequest.Builder): HttpRequest =
        builder.timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer ${accessToken()}").build()

    private fun responseItem(json: JSONObject) = CloudRemoteItem(
        json.getString("id"), json.getString("name"), json.optLong("size", 0), json.has("folder"), modifiedTime(json.optString("lastModifiedDateTime")),
    )

    private fun itemEndpoint(itemId: String): String = if (itemId == rootId) {
        "$graphEndpoint/me/drive/root"
    } else {
        "$graphEndpoint/me/drive/items/${urlPath(itemId)}"
    }

    private companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0"
        // Required to be a multiple of 320 KiB by Microsoft Graph.
        const val UPLOAD_CHUNK_BYTES = 10 * 320 * 1024
    }
}

private fun defaultCloudHttpClient() = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private fun url(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
private fun urlPath(value: String) = url(value).replace("+", "%20")
private fun cloudError(provider: String, response: HttpResponse<String>): String = runCatching {
    val json = JSONObject(response.body())
    json.optJSONObject("error")?.optString("message")
        ?: json.optString("error_description")
}.getOrNull()?.takeIf(String::isNotBlank) ?: "$provider request failed (${response.statusCode()})"

private fun modifiedTime(value: String): Long = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0)
