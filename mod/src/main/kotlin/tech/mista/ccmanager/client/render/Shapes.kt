package tech.mista.ccmanager.client.render

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.render.BufferBuilder
import net.minecraft.client.render.GameRenderer
import net.minecraft.client.render.Tessellator
import net.minecraft.client.render.VertexFormat
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f

/**
 * Lines and boxes, drawn straight to the GPU through the Tessellator.
 *
 * All positions passed in are camera-relative (world position minus camera
 * position). The subtraction is done in doubles before anything becomes a
 * float: at x = 30 000 a float only has about two decimals left, and
 * translating the whole matrix by the camera position instead makes far away
 * shapes jitter.
 */
object Shapes {

    /** Sets up GL state for translucent, optionally see-through shapes, and always restores it. */
    fun batch(throughWalls: Boolean, body: () -> Unit) {
        RenderSystem.setShader(GameRenderer::getPositionColorProgram)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()     // see box faces from inside too
        RenderSystem.depthMask(false)  // translucent: don't hide what's drawn after
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

    /** One draw call of 1px lines; every two vertices make a line. */
    fun lines(build: (BufferBuilder) -> Unit) = emit(VertexFormat.DrawMode.DEBUG_LINES, build)

    /** One draw call of quads; every four vertices make a face. */
    fun quads(build: (BufferBuilder) -> Unit) = emit(VertexFormat.DrawMode.QUADS, build)

    private fun emit(mode: VertexFormat.DrawMode, build: (BufferBuilder) -> Unit) {
        val tessellator = Tessellator.getInstance()
        val buf = tessellator.buffer
        buf.begin(mode, VertexFormats.POSITION_COLOR)
        build(buf)
        tessellator.draw()
    }

    fun line(buf: BufferBuilder, m: Matrix4f, a: Vec3d, b: Vec3d, color: Int) {
        vertex(buf, m, a.x, a.y, a.z, color)
        vertex(buf, m, b.x, b.y, b.z, color)
    }

    // Corner i of a box: bit 0 picks max x, bit 1 max y, bit 2 max z.
    private fun corner(b: Box, i: Int) = Vec3d(
        if (i and 1 != 0) b.maxX else b.minX,
        if (i and 2 != 0) b.maxY else b.minY,
        if (i and 4 != 0) b.maxZ else b.minZ,
    )

    /** The 12 edges: each corner joined to the corners one bit away from it. */
    fun outline(buf: BufferBuilder, m: Matrix4f, b: Box, color: Int) {
        for (i in 0 until 8) for (bit in intArrayOf(1, 2, 4)) {
            if (i and bit == 0) line(buf, m, corner(b, i), corner(b, i or bit), color)
        }
    }

    private val faces = arrayOf(
        intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3), // -x, +x
        intArrayOf(0, 4, 5, 1), intArrayOf(2, 3, 7, 6), // -y, +y
        intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5), // -z, +z
    )

    fun fill(buf: BufferBuilder, m: Matrix4f, b: Box, color: Int) {
        for (face in faces) for (i in face) {
            val c = corner(b, i)
            vertex(buf, m, c.x, c.y, c.z, color)
        }
    }

    /** Flat rectangle at z = 0 of the matrix's space; for label backgrounds. */
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
