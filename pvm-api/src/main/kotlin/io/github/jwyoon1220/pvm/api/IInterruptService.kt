package io.github.jwyoon1220.pvm.api

interface IInterruptService {
    fun register(vector: Int, handler: InterruptHandler)
}
