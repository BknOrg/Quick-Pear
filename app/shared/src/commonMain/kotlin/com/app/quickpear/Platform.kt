package com.app.quickpear

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform