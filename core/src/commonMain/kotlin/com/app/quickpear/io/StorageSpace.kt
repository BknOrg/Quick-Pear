package com.app.quickpear.io

/**
 * Returns the usable free space (bytes) of the filesystem that contains [path],
 * or null when the platform cannot report it.
 */
internal expect fun availableSpaceBytes(path: String): Long?
