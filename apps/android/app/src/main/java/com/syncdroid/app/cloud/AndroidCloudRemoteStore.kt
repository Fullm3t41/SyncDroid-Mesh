package com.syncdroid.app.cloud

import com.syncdroid.shared.cloud.CloudProvider
import com.syncdroid.shared.cloud.CloudRemoteItem
import com.syncdroid.shared.cloud.CloudRemoteStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Streaming transport for Android; large files never need to fit in a byte array. */
class AndroidCloudRemoteStore(
    private val provider: CloudProvider,
    private val token: suspend () -> String,
) : CloudRemoteStore {
    override val rootId = "root"
    private val google get() = provider == CloudProvider.GOOGLE_DRIVE
    private val drive = "https://www.googleapis.com/drive/v3/files"
    private val graph = "https://graph.microsoft.com/v1.0/me/drive"
    private fun item(id: String) = if (id == "root") "$graph/root" else "$graph/items/${encode(id)}"

    override suspend fun ensureFolder(parentId: String, name: String): String {
        list(parentId).firstOrNull { it.folder && it.name == name }?.let { return it.id }
        val body = JSONObject().put("name", name)
        if (google) body.put("mimeType", "application/vnd.google-apps.folder").put("parents", org.json.JSONArray(listOf(parentId)))
        else body.put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "fail")
        return json("POST", if (google) "$drive?fields=id" else "${item(parentId)}/children", body).getString("id")
    }

    override suspend fun list(parentId: String): List<CloudRemoteItem> {
        val result = mutableListOf<CloudRemoteItem>()
        var next: String? = if (google) "$drive?q=${encode("'$parentId' in parents and trashed = false")}&fields=nextPageToken,files(id,name,size,mimeType)&pageSize=1000"
            else "${item(parentId)}/children?%24select=id,name,size,folder&%24top=200"
        val first = next
        while (next != null) {
            val response = json("GET", next)
            val entries = response.getJSONArray(if (google) "files" else "value")
            repeat(entries.length()) { result += remoteItem(entries.getJSONObject(it)) }
            next = if (google) response.optString("nextPageToken").takeIf { it.isNotBlank() }?.let { "$first&pageToken=${encode(it)}" }
                else response.optString("@odata.nextLink").takeIf { it.isNotBlank() }
        }
        return result
    }

    override suspend fun upload(parentId: String, name: String, source: Path): CloudRemoteItem {
        val size = Files.size(source)
        if (google) {
            val existing = list(parentId).firstOrNull { !it.folder && it.name == name }
            val metadata = JSONObject().put("name", name).apply { if (existing == null) put("parents", org.json.JSONArray(listOf(parentId))) }
            val endpoint = "https://www.googleapis.com/upload/drive/v3/files" +
                (existing?.let { "/${encode(it.id)}" } ?: "") + "?uploadType=resumable&fields=id,name,size"
            val response = request("POST", endpoint, metadata.toString().toByteArray(), headers = buildMap {
                put("Content-Type", "application/json")
                put("X-Upload-Content-Length", size.toString())
                put("X-Upload-Content-Type", "application/octet-stream")
                if (existing != null) put("X-HTTP-Method-Override", "PATCH")
            })
            val location = response.second.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull()
                ?: error("Google Drive did not open an upload session")
            return remoteItem(JSONObject(request("PUT", location, source = source, authorized = false).first))
        }
        val session = json("POST", "${item(parentId)}:/${encode(name)}:/createUploadSession",
            JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace")))
        val uploadUrl = session.getString("uploadUrl")
        var completed: JSONObject? = null
        withContext(Dispatchers.IO) {
            Files.newInputStream(source).use { input ->
                var offset = 0L
                while (offset < size) {
                    val count = minOf(10L * 320 * 1024, size - offset).toInt()
                    val bytes = ByteArray(count)
                    java.io.DataInputStream(input).readFully(bytes)
                    val response = request("PUT", uploadUrl, bytes, authorized = false,
                        headers = mapOf("Content-Range" to "bytes $offset-${offset + count - 1}/$size"))
                    val value = JSONObject(response.first)
                    if (value.has("id")) completed = value
                    offset += count
                }
            }
        }
        return remoteItem(requireNotNull(completed) { "OneDrive upload did not complete" })
    }

    override suspend fun download(itemId: String, destination: Path) {
        request("GET", if (google) "$drive/${encode(itemId)}?alt=media" else "${item(itemId)}/content", destination = destination)
    }

    override suspend fun trash(itemId: String) {
        if (google) request("POST", "$drive/${encode(itemId)}", "{\"trashed\":true}".toByteArray(),
            headers = mapOf("Content-Type" to "application/json", "X-HTTP-Method-Override" to "PATCH"))
        else request("DELETE", item(itemId))
    }

    private suspend fun json(method: String, url: String, body: JSONObject? = null): JSONObject =
        JSONObject(request(method, url, body?.toString()?.toByteArray(), headers = mapOf("Content-Type" to "application/json")).first)

    private suspend fun request(
        method: String, url: String, bytes: ByteArray? = null, source: Path? = null,
        destination: Path? = null, authorized: Boolean = true, headers: Map<String, String> = emptyMap(),
    ): Pair<String, Map<String?, List<String>>> = withContext(Dispatchers.IO) {
        var current = URL(url)
        require(current.protocol == "https")
        val originalHost = current.host
        val access = if (authorized) token() else null
        repeat(6) {
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 20_000; connection.readTimeout = 120_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                headers.forEach(connection::setRequestProperty)
                if (access != null && current.host == originalHost) connection.setRequestProperty("Authorization", "Bearer $access")
                if (bytes != null || source != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(source?.let(Files::size) ?: bytes!!.size.toLong())
                    connection.outputStream.use { output ->
                        if (source != null) Files.newInputStream(source).use { input -> input.copyTo(output) }
                        else output.write(bytes!!)
                    }
                }
                val status = connection.responseCode
                if (method == "GET" && status in listOf(301, 302, 303, 307, 308)) {
                    current = URL(current, connection.getHeaderField("Location"))
                    require(current.protocol == "https")
                } else {
                    check(status in 200..299) { "${provider.displayName} request failed ($status)" }
                    val body = if (destination != null) {
                        connection.inputStream.use { input -> Files.newOutputStream(destination).use { input.copyTo(it) } }; ""
                    } else connection.inputStream.bufferedReader().use { it.readText() }
                    return@withContext body to connection.headerFields
                }
            } finally { connection.disconnect() }
        }
        error("Too many cloud download redirects")
    }

    private fun remoteItem(value: JSONObject) = CloudRemoteItem(value.getString("id"), value.getString("name"),
        value.optLong("size"), value.has("folder") || value.optString("mimeType") == "application/vnd.google-apps.folder")
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
