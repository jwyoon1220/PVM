package io.github.jwyoon1220.pvm.api

data class AddonContext(
    val ports: IPortIOService,
    val interrupts: IInterruptService
)
