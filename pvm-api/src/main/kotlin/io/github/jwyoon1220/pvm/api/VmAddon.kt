package io.github.jwyoon1220.pvm.api

/**
 * Minecraft-plugin-style lifecycle for VM extensions.
 * Addons register port and interrupt handlers in [onEnable].
 */
interface VmAddon {
    val id: String
    fun onInit() {}
    fun onLoad() {}
    fun onEnable(context: AddonContext)
    fun onDisable() {}
}
