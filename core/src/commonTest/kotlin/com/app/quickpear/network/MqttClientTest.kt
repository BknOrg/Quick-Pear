package com.app.quickpear.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MqttClientTest {

    @Test
    fun testTopicMatchesLogic() {
        val client = MqttClient()
        // Exact match
        assertTrue(client.topicMatches("quickpear/msg/123", "quickpear/msg/123"))
        assertFalse(client.topicMatches("quickpear/msg/123", "quickpear/msg/456"))

        // Single-level wildcard (+)
        assertTrue(client.topicMatches("quickpear/p/+", "quickpear/p/device123"))
        assertTrue(client.topicMatches("quickpear/p/+", "quickpear/p/anyHash"))
        assertFalse(client.topicMatches("quickpear/p/+", "quickpear/p/a/b"))
        assertFalse(client.topicMatches("quickpear/p/+", "quickpear/other/device123"))

        // Multi-level wildcard (#)
        assertTrue(client.topicMatches("quickpear/#", "quickpear/p/device123"))
        assertTrue(client.topicMatches("quickpear/#", "quickpear/a/b/c"))
        assertTrue(client.topicMatches("#", "any/topic/here"))
    }

    @Test
    fun testLivePresenceWildcard() = runBlocking(Dispatchers.IO) {
        val client1 = MqttClient()
        val client2 = MqttClient()

        client1.start()
        client2.start()

        try {
            withTimeout(10000L) {
                while (!client1.isConnected.value || !client2.isConnected.value) {
                    kotlinx.coroutines.delay(100)
                }
            }

            val receivedMessage = CompletableDeferred<String>()
            val targetTopic = "quickpear/p/test_${System.currentTimeMillis()}"

            // Client 2 subscribes to the presence wildcard topic used by SignalingClient
            client2.subscribe("quickpear/p/+") { topic, bytes ->
                receivedMessage.complete(bytes.decodeToString())
            }

            kotlinx.coroutines.delay(1000)

            client1.publish(targetTopic, "Presence Data OK".encodeToByteArray())

            val received = withTimeout(10000L) {
                receivedMessage.await()
            }

            assertEquals("Presence Data OK", received)
        } finally {
            client1.stop()
            client2.stop()
        }
    }
}
