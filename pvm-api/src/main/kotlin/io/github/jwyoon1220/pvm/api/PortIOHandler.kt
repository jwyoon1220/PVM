package io.github.jwyoon1220.pvm.api

interface PortIOHandler {
    fun read(port: Int): Int
    fun write(port: Int, value: Int)
}
