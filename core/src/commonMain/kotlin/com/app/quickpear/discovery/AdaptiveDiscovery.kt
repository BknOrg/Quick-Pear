package com.app.quickpear.discovery

/**
 * Interface for discovery components that can adapt their duty cycles and intervals based on system power state.
 */
interface AdaptiveDiscovery {
    fun setMode(mode: DiscoveryMode)
}
