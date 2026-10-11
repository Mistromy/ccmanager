package tech.mista.ccmanager.client.render

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import tech.mista.ccmanager.client.config.CcmConfig
import tech.mista.ccmanager.client.link.Link
import tech.mista.ccmanager.client.turtle.TurtleStore

/** One line of connection state in the top left corner. */
object StatusHud {

    fun render(draw: DrawContext) {
        if (!CcmConfig.get().showHud) return

        val (text, color) = when (Link.state) {
            Link.State.CONNECTED -> "ccm: ${TurtleStore.turtles.size} turtles" to Colors.ONLINE
            // a failed attempt is over in milliseconds, so showing these apart would flicker
            Link.State.CONNECTING, Link.State.WAITING -> "ccm: disconnected (connecting)" to Colors.OFFLINE
            Link.State.OFF -> "ccm: off" to Colors.DIM
        }
        draw.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, text, 4, 4, color)
    }
}
