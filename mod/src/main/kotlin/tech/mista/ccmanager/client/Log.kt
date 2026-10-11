package tech.mista.ccmanager.client

import net.minecraft.client.MinecraftClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal val log: Logger = LoggerFactory.getLogger("ccmanager")

/**
 * Runs [block] on the client (render) thread. Everything that touches game
 * state or our own stores must go through here when it starts on another
 * thread, such as a websocket callback. Already on the client thread, it runs
 * immediately.
 */
internal fun onClient(block: () -> Unit) {
    MinecraftClient.getInstance().execute { block() }
}
