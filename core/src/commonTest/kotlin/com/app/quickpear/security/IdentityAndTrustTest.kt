package com.app.quickpear.security

import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityAndTrustTest {
    private val fs = FileSystem.SYSTEM

    private fun tempDir(name: String): Path {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qp-sec-$name-${(0..Int.MAX_VALUE).random()}"
        fs.createDirectories(dir)
        return dir
    }

    @Test
    fun identityIsGeneratedOnceAndPersisted() {
        val dir = tempDir("id1")
        val first = DeviceIdentity.loadOrCreate(dir)
        val second = DeviceIdentity.loadOrCreate(dir)

        assertEquals(first.deviceId, second.deviceId)
        assertContentEquals(first.publicKey, second.publicKey)
        assertEquals(DeviceIdentity.idFromPublicKey(first.publicKey), first.deviceId)
        assertEquals(32, first.deviceId.length)
        assertNotEquals(first.deviceId, DeviceIdentity.loadOrCreate(tempDir("id2")).deviceId)
    }

    @Test
    fun persistedIdentityCanStillSign() {
        val dir = tempDir("id3")
        DeviceIdentity.loadOrCreate(dir)
        val reloaded = DeviceIdentity.loadOrCreate(dir)
        val data = "hello".encodeToByteArray()
        val sig = reloaded.sign(data)

        assertTrue(DeviceIdentity.verify(reloaded.publicKey, data, sig))
        assertFalse(DeviceIdentity.verify(reloaded.publicKey, "tampered".encodeToByteArray(), sig))
        assertFalse(DeviceIdentity.verify(DeviceIdentity.generate().publicKey, data, sig))
    }

    @Test
    fun corruptIdentityFileIsQuarantinedNotOverwritten() {
        val dir = tempDir("id4")
        fs.write(dir / "identity.json") { writeUtf8("{not json") }

        val identity = DeviceIdentity.loadOrCreate(dir)

        assertTrue(fs.exists(dir / "identity.json.corrupt"))
        assertEquals(identity.deviceId, DeviceIdentity.loadOrCreate(dir).deviceId)
    }

    @Test
    fun trustStoreAddRemoveAndPersistence() {
        val dir = tempDir("trust1")
        val peer = DeviceIdentity.generate()
        val store = TrustStore(dir)

        assertFalse(store.isTrusted(peer.deviceId))
        store.add(peer.deviceId, "Phone", peer.publicKey)
        assertTrue(store.isTrusted(peer.deviceId))
        assertEquals("Phone", store.get(peer.deviceId)?.name)

        // survives a restart
        val reopened = TrustStore(dir)
        assertTrue(reopened.isTrusted(peer.deviceId))
        assertEquals(1, reopened.devices.value.size)

        reopened.remove(peer.deviceId)
        assertFalse(reopened.isTrusted(peer.deviceId))
        assertFalse(TrustStore(dir).isTrusted(peer.deviceId))
    }

    @Test
    fun trustStoreRenamesInsteadOfDuplicating() {
        val store = TrustStore(tempDir("trust2"))
        val peer = DeviceIdentity.generate()
        store.add(peer.deviceId, "Old", peer.publicKey)
        store.add(peer.deviceId, "New", peer.publicKey)

        assertEquals(1, store.devices.value.size)
        assertEquals("New", store.get(peer.deviceId)?.name)
    }

    @Test
    fun trustStoreRenameExplicitly() {
        val store = TrustStore(tempDir("trust-rename"))
        val peer = DeviceIdentity.generate()
        store.add(peer.deviceId, "Old Name", peer.publicKey)
        assertEquals("Old Name", store.get(peer.deviceId)?.name)

        store.rename(peer.deviceId, "My Renamed Device")
        assertEquals("My Renamed Device", store.get(peer.deviceId)?.name)
    }

    @Test
    fun trustStoreRejectsIdThatDoesNotMatchKey() {
        val store = TrustStore(tempDir("trust3"))
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()

        assertFailsWith<IllegalArgumentException> { store.add(a.deviceId, "Spoof", b.publicKey) }
        assertNull(store.get(a.deviceId))
    }

    @Test
    fun trustStoreClearWipesAllDevices() {
        val dir = tempDir("trust-clear")
        val store = TrustStore(dir)
        val peer1 = DeviceIdentity.generate()
        val peer2 = DeviceIdentity.generate()

        store.add(peer1.deviceId, "Device 1", peer1.publicKey)
        store.add(peer2.deviceId, "Device 2", peer2.publicKey)
        assertEquals(2, store.devices.value.size)

        store.clear()
        assertEquals(0, store.devices.value.size)

        // Survives reload
        val reloaded = TrustStore(dir)
        assertEquals(0, reloaded.devices.value.size)
    }
}
