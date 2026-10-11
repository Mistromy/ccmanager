package tech.mista.ccmanager.client.render

import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.BufferBuilder
import net.minecraft.client.render.Camera
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import tech.mista.ccmanager.client.turtle.Turtle

/**
 * Floating text above a turtle, done the way vanilla draws nametags: move to
 * the spot, rotate to face the camera (a "billboard"), scale world units down
 * to text pixels, draw.
 *
 * Each label is drawn completely - background, then text - before the next
 * one starts, so the caller controls what ends up on top by the order it
 * calls [draw] in.
 */
object Labels {
    // Our own buffer collection, flushed after every label. The game's shared
    // one is drawn at points of its choosing.
    private val buffers = VertexConsumerProvider.immediate(BufferBuilder(1536))

    private const val PIXEL = 0.02f   // one text pixel in blocks; vanilla nametags use 0.025
    private const val ANCHOR = 1.55   // bottom of the label, in blocks above the turtle. CC's own label sits below this

    fun draw(matrices: MatrixStack, camera: Camera, t: Turtle, lines: List<Line>, background: Int, throughWalls: Boolean) {
        val tr = MinecraftClient.getInstance().textRenderer
        val cam = camera.pos

        matrices.push()
        matrices.translate(t.x + 0.5 - cam.x, t.y + ANCHOR - cam.y, t.z + 0.5 - cam.z)
        matrices.multiply(camera.rotation)
        matrices.scale(-PIXEL, -PIXEL, PIXEL) // text y points down and x would be mirrored otherwise
        val m = matrices.peek().positionMatrix

        val lineHeight = tr.fontHeight + 1
        val width = lines.maxOf { tr.getWidth(it.text) }
        val left = -width / 2f
        val top = -lines.size * lineHeight // stack upwards from the anchor

        // One background quad behind the whole label, drawn first. The text
        // renderer's built-in background is drawn in a separate batch whose
        // order against the letters isn't fixed, which shaded random letters.
        Shapes.batch(throughWalls) {
            Shapes.quads { buf ->
                Shapes.rect(buf, m, left - 1.0, top - 1.0, left + width + 1.0, top + lines.size * lineHeight.toDouble(), background)
            }
        }

        val layer = if (throughWalls) TextRenderer.TextLayerType.SEE_THROUGH else TextRenderer.TextLayerType.NORMAL
        lines.forEachIndexed { i, line ->
            tr.draw(line.text, left, (top + i * lineHeight).toFloat(), line.color, false, m, buffers,
                layer, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE)
        }
        buffers.draw()
        matrices.pop()
    }
}
