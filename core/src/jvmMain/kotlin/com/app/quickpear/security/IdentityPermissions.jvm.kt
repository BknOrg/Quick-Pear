package com.app.quickpear.security

internal actual fun protectIdentityFile(path: String) {
    val file = java.nio.file.Paths.get(path)
    val posix = java.nio.file.Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView::class.java)
    if (posix != null) {
        posix.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
    } else {
        val acl = java.nio.file.Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView::class.java)
            ?: error("Cannot protect identity file")
        val owner = java.nio.file.Files.getOwner(file)
        val entry = java.nio.file.attribute.AclEntry.newBuilder()
            .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner)
            .setPermissions(*java.nio.file.attribute.AclEntryPermission.entries.toTypedArray()).build()
        acl.acl = listOf(entry)
    }
}
