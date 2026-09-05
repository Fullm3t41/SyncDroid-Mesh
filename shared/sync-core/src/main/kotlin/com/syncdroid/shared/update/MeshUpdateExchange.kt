package com.syncdroid.shared.update

import com.syncdroid.shared.protocol.MeshSessionMessage

class MeshUpdateExchange(private val cache: MeshUpdateCache) {
    suspend fun run(
        localDeviceId: String,
        remoteDeviceId: String,
        send: suspend (MeshSessionMessage) -> Unit,
        receive: suspend () -> MeshSessionMessage,
    ) {
        val exchange = cache.openExchange()
        try {
            MeshUpdateExchange(exchange).runPinned(localDeviceId, remoteDeviceId, send, receive)
        } finally {
            exchange.closeExchange()
        }
    }

    private suspend fun runPinned(
        localDeviceId: String,
        remoteDeviceId: String,
        send: suspend (MeshSessionMessage) -> Unit,
        receive: suspend () -> MeshSessionMessage,
    ) {
        send(MeshSessionMessage.UpdateInventory(cache.availableAssets()))
        val remoteAssets = (receive() as? MeshSessionMessage.UpdateInventory)?.assets
            ?: error("Peer did not send an update inventory")

        if (localDeviceId < remoteDeviceId) {
            requestAvailableUpdate(remoteAssets, send, receive)
            serveAvailableUpdates(send, receive)
        } else {
            serveAvailableUpdates(send, receive)
            requestAvailableUpdate(remoteAssets, send, receive)
        }
    }

    private suspend fun requestAvailableUpdate(
        remoteAssets: List<com.syncdroid.shared.protocol.UpdateAssetDescriptor>,
        send: suspend (MeshSessionMessage) -> Unit,
        receive: suspend () -> MeshSessionMessage,
    ) {
        val completed = mutableSetOf<String>()
        while (true) {
            val desired = cache.desiredAsset(remoteAssets)?.takeUnless { it.sha256 in completed } ?: break
            var offset = cache.partialSize(desired.sha256)
            while (offset < desired.sizeBytes) {
                send(MeshSessionMessage.UpdateRequest(desired.sha256, offset, UPDATE_CHUNK_BYTES))
                val chunk = receive() as? MeshSessionMessage.UpdateChunk
                    ?: error("Peer did not return the requested update chunk")
                require(chunk.asset == desired && chunk.offset == offset && chunk.bytes.isNotEmpty()) {
                    "Peer returned an invalid update chunk"
                }
                cache.writeChunk(desired, offset, chunk.bytes)
                offset += chunk.bytes.size
            }
            completed += desired.sha256
        }
        send(MeshSessionMessage.UpdatePhaseDone)
    }

    private suspend fun serveAvailableUpdates(
        send: suspend (MeshSessionMessage) -> Unit,
        receive: suspend () -> MeshSessionMessage,
    ) {
        while (true) {
            when (val message = receive()) {
                MeshSessionMessage.UpdatePhaseDone -> return
                is MeshSessionMessage.UpdateRequest -> {
                    val asset = cache.availableAssets().singleOrNull { it.sha256 == message.sha256 }
                        ?: error("Requested update is not cached")
                    val bytes = cache.readChunk(message.sha256, message.offset, message.maxBytes)
                        ?: error("Requested update chunk is unavailable")
                    send(MeshSessionMessage.UpdateChunk(asset, message.offset, bytes))
                }
                else -> error("Unexpected update exchange message: ${message::class.simpleName}")
            }
        }
    }

    companion object {
        const val UPDATE_CHUNK_BYTES = 1024 * 1024
    }
}
