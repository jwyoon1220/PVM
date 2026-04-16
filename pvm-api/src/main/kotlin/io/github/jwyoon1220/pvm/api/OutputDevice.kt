package io.github.jwyoon1220.pvm.api

/** Anything that can receive and display VM video output. */
interface OutputDevice {
    fun start()
    fun stop()
}
