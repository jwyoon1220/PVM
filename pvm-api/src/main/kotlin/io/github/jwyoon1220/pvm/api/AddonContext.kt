package io.github.jwyoon1220.pvm.api

/**
 * Context passed to every [VmAddon.onEnable] call, providing access to
 * the VM's service layer for registering handlers and watchers.
 */
data class AddonContext(
    val ports: IPortService,
    val interrupts: IInterruptService,
    val memory: IMemoryService
)
