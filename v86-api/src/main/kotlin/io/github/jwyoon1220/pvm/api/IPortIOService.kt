package io.github.jwyoon1220.pvm.api

interface IPortIOService {
    fun register(port: Int, handler: PortIOHandler)
    fun in8(port: Int): Int
    fun out8(port: Int, value: Int)
}
