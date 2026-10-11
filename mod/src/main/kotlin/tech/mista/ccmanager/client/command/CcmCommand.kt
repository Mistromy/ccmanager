package tech.mista.ccmanager.client.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import tech.mista.ccmanager.client.config.CcmConfig
import tech.mista.ccmanager.client.config.ConfigScreen
import tech.mista.ccmanager.client.link.Link
import tech.mista.ccmanager.client.scan.Scans
import tech.mista.ccmanager.client.turtle.TurtleStore

/**
 * `/ccm`                status
 * `/ccm connect`        connect now, skipping the retry wait
 * `/ccm disconnect`     disconnect, and stay disconnected until connect or the next world join
 * `/ccm command <text>` send <text> to the backend's command parser, same as typing it in its terminal
 * `/ccm clearscans`     remove all scan area cubes
 * `/ccm config`         open the settings screen
 *
 * Client commands never reach the server, so they work on any server.
 */
object CcmCommand {

    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(
            literal("ccm")
                .executes { reply(it.source, status()) }
                .then(literal("connect").executes {
                    Link.start()
                    reply(it.source, "connecting to ${CcmConfig.get().host}")
                })
                .then(literal("disconnect").executes {
                    Link.stop()
                    reply(it.source, "disconnected")
                })
                .then(literal("command").then(argument("text", StringArgumentType.greedyString()).executes {
                    val text = StringArgumentType.getString(it, "text")
                    if (Link.state != Link.State.CONNECTED) return@executes reply(it.source, "not connected")
                    Link.send("command $text")
                    reply(it.source, "sent: $text")
                }))
                .then(literal("clearscans").executes {
                    Scans.clear()
                    reply(it.source, "scan areas cleared")
                })
                .then(literal("config").executes {
                    // Running a command closes the chat screen right after this returns, which
                    // would close our screen too. `send` queues it to open after that.
                    val client = MinecraftClient.getInstance()
                    client.send { client.setScreen(ConfigScreen.create(null)) }
                    1
                })
        )
    }

    private fun status(): String = when (Link.state) {
        Link.State.CONNECTED -> "connected to ${CcmConfig.get().host}, ${TurtleStore.turtles.size} turtles"
        Link.State.CONNECTING -> "connecting to ${CcmConfig.get().host}"
        Link.State.WAITING -> "offline: ${Link.lastError ?: "?"}, retrying"
        Link.State.OFF -> "off, use /ccm connect"
    }

    private fun reply(source: FabricClientCommandSource, text: String): Int {
        source.sendFeedback(Text.literal("[ccm] $text"))
        return 1 // brigadier: 1 = success
    }
}
