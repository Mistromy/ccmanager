package tech.mista.ccmanager.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

/**
 * /ccm                 status
 * /ccm connect         connect now, skipping the retry wait
 * /ccm disconnect      stay disconnected until connect or the next world join
 * /ccm command <text>  hand <text> to the backend's command parser, as if typed in its terminal
 * /ccm clearscans      remove all scan cubes
 * /ccm config          settings screen
 *
 * Client-side commands: they never reach the Minecraft server.
 */
object Commands {

    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(literal("ccm")
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
                // the chat screen closes right after a command runs, which would close ours too; send() opens it after
                val client = MinecraftClient.getInstance()
                client.send { client.setScreen(ConfigScreen.create(null)) }
                1
            }))
    }

    private fun status() = when (Link.state) {
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
