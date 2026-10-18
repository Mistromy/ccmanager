package tech.mista.ccmanager.client

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.BufferBuilder
import net.minecraft.client.render.Camera
import net.minecraft.client.render.GameRenderer
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.Tessellator
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexFormat
import net.minecraft.client.render.VertexFormats
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f

/**
 * Lines and boxes, straight to the GPU through the Tessellator.
 *
 * Positions passed in are camera-relative, subtracted in doubles first: far
 * from spawn a float has too few decimals left and world-space shapes jitter.
 */
object Shapes {

    /** Translucent, optionally see-through drawing; GL state is always restored. */
    fun batch(throughWalls: Boolean, body: () -> Unit) {
        RenderSystem.setShader(GameRenderer::getPositionColorProgram)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()    // box faces visible from inside too
        RenderSystem.depthMask(false) // translucent: never hide what's drawn after
        if (throughWalls) RenderSystem.disableDepthTest()
        try {
            body()
        } finally {
            RenderSystem.enableDepthTest()
            RenderSystem.depthMask(true)
            RenderSystem.enableCull()
            RenderSystem.disableBlend()
        }
    }

    fun lines(build: (BufferBuilder) -> Unit) = emit(VertexFormat.DrawMode.DEBUG_LINES, build) // 2 vertices per line
    fun quads(build: (BufferBuilder) -> Unit) = emit(VertexFormat.DrawMode.QUADS, build)       // 4 per face

    private fun emit(mode: VertexFormat.DrawMode, build: (BufferBuilder) -> Unit) {
        val tessellator = Tessellator.getInstance()
        tessellator.buffer.begin(mode, VertexFormats.POSITION_COLOR)
        build(tessellator.buffer)
        tessellator.draw()
    }

    fun line(buf: BufferBuilder, m: Matrix4f, a: Vec3d, b: Vec3d, color: Int) {
        vertex(buf, m, a.x, a.y, a.z, color)
        vertex(buf, m, b.x, b.y, b.z, color)
    }

    // corner i of a box: bit 0 → max x, bit 1 → max y, bit 2 → max z
    private fun corner(b: Box, i: Int) = Vec3d(
        if (i and 1 != 0) b.maxX else b.minX,
        if (i and 2 != 0) b.maxY else b.minY,
        if (i and 4 != 0) b.maxZ else b.minZ,
    )

    /** 12 edges: every corner joined to the corners one bit away. */
    fun outline(buf: BufferBuilder, m: Matrix4f, b: Box, color: Int) {
        for (i in 0 until 8) for (bit in intArrayOf(1, 2, 4)) {
            if (i and bit == 0) line(buf, m, corner(b, i), corner(b, i or bit), color)
        }
    }

    private val faces = arrayOf(
        intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3), // -x +x
        intArrayOf(0, 4, 5, 1), intArrayOf(2, 3, 7, 6), // -y +y
        intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5), // -z +z
    )

    fun fill(buf: BufferBuilder, m: Matrix4f, b: Box, color: Int) {
        for (k in faces.indices) face(buf, m, b, k, color)
    }

    /** One face of a box; [k] indexes -x +x -y +y -z +z. */
    fun face(buf: BufferBuilder, m: Matrix4f, b: Box, k: Int, color: Int) {
        for (i in faces[k]) corner(b, i).let { vertex(buf, m, it.x, it.y, it.z, color) }
    }

    /** Flat rectangle in the matrix's own plane; label backgrounds. */
    fun rect(buf: BufferBuilder, m: Matrix4f, x1: Double, y1: Double, x2: Double, y2: Double, color: Int) {
        vertex(buf, m, x1, y1, 0.0, color)
        vertex(buf, m, x1, y2, 0.0, color)
        vertex(buf, m, x2, y2, 0.0, color)
        vertex(buf, m, x2, y1, 0.0, color)
    }

    private fun vertex(buf: BufferBuilder, m: Matrix4f, x: Double, y: Double, z: Double, color: Int) {
        buf.vertex(m, x.toFloat(), y.toFloat(), z.toFloat())
            .color(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF, color ushr 24)
            .next()
    }
}

data class Line(val text: String, val color: Int)

/**
 * Text floating above a turtle, vanilla nametag style: move there, face the
 * camera, scale to text pixels. Each label is finished - background, then
 * text - before the next, so call order decides what's on top.
 */
object Labels {
    private val buffers = VertexConsumerProvider.immediate(BufferBuilder(1536))
    private const val PIXEL = 0.02f // one text pixel in blocks; vanilla nametags use 0.025

    // Label bottom, in blocks above the turtle's base. The nametag shares CC's
    // label spot: CC only shows its label while you look at the turtle, and
    // then our panel replaces the nametag. The panel sits above CC's label.
    const val NAMETAG_ANCHOR = 0.9
    const val PANEL_ANCHOR = 1.25

    fun draw(matrices: MatrixStack, camera: Camera, t: Turtle, lines: List<Line>, anchor: Double, background: Int, throughWalls: Boolean) {
        val tr = MinecraftClient.getInstance().textRenderer
        val cam = camera.pos

        matrices.push()
        matrices.translate(t.x + 0.5 - cam.x, t.y + anchor - cam.y, t.z + 0.5 - cam.z)
        matrices.multiply(camera.rotation)
        matrices.scale(-PIXEL, -PIXEL, PIXEL) // text y points down, and x would be mirrored
        val m = matrices.peek().positionMatrix

        val lineHeight = tr.fontHeight + 1
        val width = lines.maxOf { tr.getWidth(it.text) }
        val left = -width / 2f
        val top = -lines.size * lineHeight // stack upwards from the anchor

        // Our own background quad, drawn first. The text renderer's built-in one is a
        // separate batch with no fixed order against the letters, and shaded random ones.
        Shapes.batch(throughWalls) {
            Shapes.quads { Shapes.rect(it, m, left - 1.0, top - 1.0, left + width + 1.0, top + lines.size * lineHeight.toDouble(), background) }
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
