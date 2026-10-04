package com.app.quickpear

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class SingleInstanceManager(
    private val port: Int = 18887,
    private val onCommandReceived: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null

    /**
     * Attempts to become the primary instance.
     * Returns true if this is the primary instance, or false if another instance is already running.
     */
    fun startOrForward(args: Array<String>): Boolean {
        return try {
            val socket = ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            CoroutineScope(Dispatchers.IO).launch {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        val line = reader.readLine()
                        if (line != null) {
                            onCommandReceived(line)
                        }
                        client.close()
                    } catch (_: Exception) {
                    }
                }
            }
            true
        } catch (_: Exception) {
            // Another instance is already running: forward command and signal caller to exit
            forwardArgs(args)
            false
        }
    }

    private fun forwardArgs(args: Array<String>) {
        try {
            val client = Socket(InetAddress.getByName("127.0.0.1"), port)
            val writer = PrintWriter(client.getOutputStream(), true)
            val command = if (args.isEmpty()) "OPEN" else args.joinToString("\t")
            writer.println(command)
            client.close()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }
}
