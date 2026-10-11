package com.app.quickpear.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkBitmapTest {

    @Test
    fun testBasicBitsAndCapacityExpansion() {
        val bitmap = ChunkBitmap(initialCapacityChunks = 16)
        assertFalse(bitmap.isSet(0))
        assertFalse(bitmap.isSet(100))

        bitmap.set(0)
        bitmap.set(7)
        bitmap.set(8)
        bitmap.set(99)

        assertTrue(bitmap.isSet(0))
        assertTrue(bitmap.isSet(7))
        assertTrue(bitmap.isSet(8))
        assertTrue(bitmap.isSet(99))
        assertFalse(bitmap.isSet(1))
        assertFalse(bitmap.isSet(98))
        assertFalse(bitmap.isSet(100))

        assertEquals(4L, bitmap.cardinality())

        bitmap.clear(7)
        assertFalse(bitmap.isSet(7))
        assertEquals(3L, bitmap.cardinality())
    }

    @Test
    fun testFirstMissingChunkContiguous() {
        val bitmap = ChunkBitmap()
        // Missing from 0
        assertEquals(0L, bitmap.firstMissingChunk(100L))

        bitmap.set(0)
        bitmap.set(1)
        bitmap.set(2)
        assertEquals(3L, bitmap.firstMissingChunk(100L))

        // Set up to 7 (byte 0 full)
        for (i in 3L..7L) bitmap.set(i)
        assertEquals(8L, bitmap.firstMissingChunk(100L))

        // Set chunk 9, skipping 8
        bitmap.set(9)
        assertEquals(8L, bitmap.firstMissingChunk(100L))

        // Set 8
        bitmap.set(8)
        assertEquals(10L, bitmap.firstMissingChunk(100L))

        // If all chunks set
        for (i in 10L until 20L) bitmap.set(i)
        assertEquals(20L, bitmap.firstMissingChunk(20L))
    }

    @Test
    fun testSerializationRoundTrip() {
        val original = ChunkBitmap()
        val chunkIndices = listOf(0L, 5L, 8L, 15L, 16L, 33L, 1024L)
        for (idx in chunkIndices) {
            original.set(idx)
        }

        val bytes = original.toByteArray(totalChunks = 1025L)
        val restored = ChunkBitmap.fromByteArray(bytes)

        for (idx in chunkIndices) {
            assertTrue(restored.isSet(idx), "Chunk $idx should be set in restored bitmap")
        }
        assertFalse(restored.isSet(1L))
        assertFalse(restored.isSet(7L))
        assertFalse(restored.isSet(1023L))
        assertEquals(original.cardinality(), restored.cardinality())
    }
}
