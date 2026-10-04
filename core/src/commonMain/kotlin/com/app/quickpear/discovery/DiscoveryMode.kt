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
    /** Active UI foreground: fast discovery (every 3s, expires in 10s). */
    ACTIVE(beaconIntervalMillis = 3_000L, ttlMillis = 10_000L),

    /** Background service: battery efficient (every 20s, expires in 65s). */
    BACKGROUND(beaconIntervalMillis = 20_000L, ttlMillis = 65_000L),

    /** Screen off or OS battery saver: ultra low power (every 60s, expires in 185s). */
    POWER_SAVER(beaconIntervalMillis = 60_000L, ttlMillis = 185_000L)
}
