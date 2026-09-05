package com.syncdeck.app

/**
 * The packaged launcher stays deliberately free of Compose references. The
 * background path can therefore run without loading Skia, Compose, or the UI
 * graphics heap. A second invocation with --ui owns the full window process.
 */
fun main(args: Array<String>) {
    if (UI_ARGUMENT in args && WorkerEndpoint.fromEnvironmentOrArguments(args)?.send(WorkerCommand.PING) == true) {
        runSyncDeckUi(args)
    } else {
        SyncDeckWorker(args).run()
    }
}

internal const val UI_ARGUMENT = "--ui"
