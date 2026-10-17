package tech.mista.ccmanager.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal val log: Logger = LoggerFactory.getLogger("ccmanager")

/** Runs [block] on the client thread. Game state and our stores may only be touched there. */
internal fun onClient(block: () -> Unit) = MinecraftClient.getInstance().execute { block() }

/** Entrypoint. Every hook into the game is registered here, so this is the map of when anything runs. */
class CcmanagerClient : ClientModInitializer {
    override fun onInitializeClient() {
        CcmConfig.HANDLER.load()
        Menu.register()

        // connection events can fire on the network thread
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> onClient { if (CcmConfig.get().autoConnect) Link.start() } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            onClient {
                Link.stop()
                Menu.selectedId = null
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register {                        // 20/s
            Link.tick()   // reconnects and polling
            Menu.tick(it) // opens the menu while its key is held
        }
        WorldRenderEvents.LAST.register(WorldOverlay::render)             // every frame, after the world
        HudRenderCallback.EVENT.register { draw, _ -> StatusHud.render(draw) }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ -> Commands.register(dispatcher) }
    }
}
