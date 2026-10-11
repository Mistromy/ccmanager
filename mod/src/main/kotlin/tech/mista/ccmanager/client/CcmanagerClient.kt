package tech.mista.ccmanager.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import tech.mista.ccmanager.client.command.CcmCommand
import tech.mista.ccmanager.client.config.CcmConfig
import tech.mista.ccmanager.client.link.Link
import tech.mista.ccmanager.client.render.StatusHud
import tech.mista.ccmanager.client.render.WorldOverlay

/**
 * Entrypoint, and the only place hooks into the game are registered, so this
 * file is the whole map of when anything in the mod runs.
 */
class CcmanagerClient : ClientModInitializer {

    override fun onInitializeClient() {
        CcmConfig.HANDLER.load()

        // joining or leaving a world/server. Wrapped in onClient because
        // these can fire on the network thread.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            onClient { if (CcmConfig.get().autoConnect) Link.start() }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            onClient { Link.stop() }
        }

        // 20 times a second: reconnects and polling
        ClientTickEvents.END_CLIENT_TICK.register { Link.tick() }

        // every frame: after the world is drawn, so our overlay goes on top
        WorldRenderEvents.LAST.register(WorldOverlay::render)
        HudRenderCallback.EVENT.register { draw, _ -> StatusHud.render(draw) }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ -> CcmCommand.register(dispatcher) }

        log.info("CCManager loaded")
    }
}
