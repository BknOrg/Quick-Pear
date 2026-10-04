package com.app.quickpear.io

import java.io.File

internal actual fun availableSpaceBytes(path: String): Long? = try {
    var f: File? = File(path)
    while (f != null && !f.exists()) f = f.parentFile
    f?.usableSpace
} catch (_: Exception) {
    null
}
