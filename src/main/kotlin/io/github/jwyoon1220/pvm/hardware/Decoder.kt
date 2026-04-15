package io.github.jwyoon1220.pvm.hardware

import io.github.jwyoon1220.pvm.hardware.decode.DecoderPipeline

class Decoder(
    private val cpu: CPU,
    private val memory: Memory,
    private val pipeline: DecoderPipeline = DecoderPipeline()
) {

    fun step() {
        pipeline.run(cpu, memory, collectTrace = false)
    }

    fun stepWithTrace(): List<String> {
        return pipeline.run(cpu, memory, collectTrace = true)
    }
}