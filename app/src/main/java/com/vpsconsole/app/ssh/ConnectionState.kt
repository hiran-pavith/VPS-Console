package com.vpsconsole.app.ssh

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class SshEvent(
    val serverId: Long,
    val state: ConnectionState,
    val message: String = ""
)
