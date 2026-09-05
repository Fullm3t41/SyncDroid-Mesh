package com.syncdeck.app.platform

import java.nio.file.Path

object LinuxAppPaths {
    private fun xdg(name: String, fallback: String): Path = System.getenv(name)?.takeIf { it.startsWith("/") }
        ?.let(Path::of) ?: Path.of(System.getProperty("user.home"), fallback)
    val stateRoot: Path by lazy { xdg("XDG_DATA_HOME", ".local/share").resolve("syncdeck") }
    val cacheRoot: Path by lazy { xdg("XDG_CACHE_HOME", ".cache").resolve("syncdeck") }
    val configRoot: Path by lazy { xdg("XDG_CONFIG_HOME", ".config").resolve("syncdeck") }
    val updates: Path by lazy { cacheRoot.resolve("updates") }
    val workerEndpoint: Path by lazy { stateRoot.resolve("worker.endpoint") }
    val workerLock: Path by lazy { stateRoot.resolve("worker.lock") }
    val workerLog: Path by lazy { cacheRoot.resolve("worker-ui.log") }
}
