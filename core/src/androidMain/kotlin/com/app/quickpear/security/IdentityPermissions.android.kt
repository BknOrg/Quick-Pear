package com.app.quickpear.security

internal actual fun protectIdentityFile(path: String) {
    java.nio.file.Files.setPosixFilePermissions(java.nio.file.Paths.get(path),
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
}
