package com.vpsconsole.app.data.model

data class ServiceInfo(
    val name: String,
    val load: String,
    val active: String,
    val sub: String,
    val description: String
)

data class ProcessInfo(
    val pid: Int,
    val user: String,
    val cpu: Float,
    val mem: Float,
    val state: String,
    val command: String
)

data class ContainerInfo(
    val id: String,
    val name: String,
    val image: String,
    val status: String,
    val ports: String
)

data class FileInfo(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val permissions: String,
    val owner: String,
    val group: String
) {
    val isParent: Boolean get() = name == ".."
}

data class FirewallRule(
    val raw: String,
    val action: String,
    val target: String
)

data class CronEntry(
    val schedule: String,
    val command: String,
    val raw: String
)

data class SystemUser(
    val name: String,
    val uid: Int,
    val home: String,
    val shell: String
)
