package com.syncdroid.app.cloud

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Let admitted mesh sessions drain, while new sessions retry after cloud work. */
object CloudTransferGate {
    private val lock = Any()
    private val cloudMutex = Mutex()
    private var meshSessions = 0
    private var cloudRequested = false

    suspend fun <T> mesh(block: suspend () -> T): T {
        synchronized(lock) {
            check(!cloudRequested) { "Cloud sync is active; mesh files will retry shortly" }
            meshSessions++
        }
        return try { block() } finally { synchronized(lock) { meshSessions-- } }
    }

    suspend fun <T> cloud(block: suspend () -> T): T = cloudMutex.withLock {
        synchronized(lock) { cloudRequested = true }
        try {
            while (synchronized(lock) { meshSessions > 0 }) delay(50)
            block()
        } finally { synchronized(lock) { cloudRequested = false } }
    }
}
