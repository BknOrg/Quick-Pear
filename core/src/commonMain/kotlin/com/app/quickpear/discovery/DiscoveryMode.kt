package com.app.quickpear.discovery

/**
 * Adaptive discovery mode to balance discovery latency and power consumption.
 *
 * @property beaconIntervalMillis The delay between outgoing broadcast beacons.
 * @property ttlMillis How long a discovered peer is kept before being considered offline if no new beacons are received.
 */
enum class DiscoveryMode(
    val beaconIntervalMillis: Long,
    val ttlMillis: Long
) {
    /** Active UI foreground: fast discovery (every 2s, expires in 6s). */
    ACTIVE(beaconIntervalMillis = 2_000L, ttlMillis = 6_000L),

    /** Background service: responsive and efficient (every 6s, expires in 20s). */
    BACKGROUND(beaconIntervalMillis = 6_000L, ttlMillis = 20_000L),

    /** Screen off or OS battery saver: ultra low power (every 15s, expires in 45s). */
    POWER_SAVER(beaconIntervalMillis = 15_000L, ttlMillis = 45_000L)
}
