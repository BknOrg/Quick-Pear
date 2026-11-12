package com.app.quickpear.security

/** Only constant event codes are logged; exception text and peer data may contain secrets. */
object SecurityLog {
    fun event(code: String) { System.err.println("quickpear event=$code") }
}
