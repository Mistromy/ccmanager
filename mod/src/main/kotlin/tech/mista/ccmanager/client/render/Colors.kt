package tech.mista.ccmanager.client.render

import net.minecraft.client.MinecraftClient
import tech.mista.ccmanager.client.turtle.Turtle

/** ARGB, the format both the text renderer and [Shapes] take. */
object Colors {
    val ONLINE = 0xFF55FF55.toInt()
    val OFFLINE = 0xFFFF5555.toInt()
    val HOVER = 0xFFFFFF55.toInt()
    val TEXT = 0xFFFFFFFF.toInt()
    val DIM = 0xFFAAAAAA.toInt()

    val CHUNK_EDGE = 0xFFFFFF00.toInt()
    val CHUNK_GRID = 0x80FFFF00.toInt()
    val CHUNK_GRID_MAJOR = 0xFF00AAAA.toInt()

    val SCAN_EDGE = 0xFF55AAFF.toInt()
    val SCAN_FILL = 0x2055AAFF

    val PANEL_BG = 0xA0000000.toInt()

    /** The same translucent black vanilla nametags use (Accessibility > Text Background Opacity). */
    fun nametagBackground(): Int =
        (MinecraftClient.getInstance().options.getTextBackgroundOpacity(0.25f) * 255).toInt() shl 24

    fun status(t: Turtle) = if (t.online) ONLINE else OFFLINE

    /** Same colour, different opacity: `alpha` is 0-255. */
    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
