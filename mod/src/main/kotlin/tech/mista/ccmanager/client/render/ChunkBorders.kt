package tech.mista.ccmanager.client.render

import net.minecraft.client.render.BufferBuilder
import net.minecraft.util.math.Box
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f

/**
 * The backend's 16³ chunk you're in: its outline, plus a grid on the floor
 * and ceiling - the faces vanilla's F3+G borders don't show, since vanilla
 * chunks have no top or bottom.
 */
object ChunkBorders {

    fun draw(buf: BufferBuilder, m: Matrix4f, cam: Vec3d) {
        // block coordinate >> 4, same as the Go getKey
        val ox = (MathHelper.floor(cam.x) shr 4) * 16.0
        val oy = (MathHelper.floor(cam.y) shr 4) * 16.0
        val oz = (MathHelper.floor(cam.z) shr 4) * 16.0

        fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Int) =
            Shapes.line(buf, m, Vec3d(x1 - cam.x, y1 - cam.y, z1 - cam.z), Vec3d(x2 - cam.x, y2 - cam.y, z2 - cam.z), color)

        // a line every 2 blocks across floor and ceiling, every 4th brighter
        for (i in 2 until 16 step 2) {
            val color = if (i % 4 == 0) Colors.CHUNK_GRID_MAJOR else Colors.CHUNK_GRID
            val d = i.toDouble()
            for (y in doubleArrayOf(oy, oy + 16)) {
                line(ox + d, y, oz, ox + d, y, oz + 16, color)
                line(ox, y, oz + d, ox + 16, y, oz + d, color)
            }
        }

        Shapes.outline(buf, m, Box(ox, oy, oz, ox + 16, oy + 16, oz + 16).offset(cam.negate()), Colors.CHUNK_EDGE)
    }
}
