package io.github.jwyoon1220.pvm

import io.github.jwyoon1220.pvm.hardware.CPU
import io.github.jwyoon1220.pvm.hardware.Memory

fun main() {
    val memory = Memory() // Default = 1MiB
    val cpu = CPU()

    // memory deallocated when JVM Process Exit.
}