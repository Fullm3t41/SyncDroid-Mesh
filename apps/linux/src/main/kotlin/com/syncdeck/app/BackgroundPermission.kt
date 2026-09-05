package com.syncdeck.app

/** A Decky lease expires automatically if the plugin unloads or crashes. */
internal class BackgroundPermission(private val nanoTime: () -> Long = System::nanoTime) {
    @Volatile private var renewedAt: Long? = null
    fun renew() { renewedAt = nanoTime() }
    fun revoke() { renewedAt = null }
    fun deckyEnabled(): Boolean = renewedAt?.let { nanoTime() - it < 45_000_000_000L } ?: false
    fun allowed(gamingMode: Boolean, desktopAvailable: Boolean, desktopDisabled: Boolean): Boolean =
        deckyEnabled() || (!gamingMode && desktopAvailable && !desktopDisabled)
}
