package tech.mista.ccmanager.client.render

import com.mojang.blaze3d.platform.GlConst
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import tech.mista.ccmanager.client.config.CcmConfig
import tech.mista.ccmanager.client.scan.Scans
import tech.mista.ccmanager.client.turtle.TurtleStore

/**
 * Everything drawn in the world, once per frame: chunk border, scan cubes and
 * turtle boxes first, then labels on top. The hovered turtle always gets a
 * highlighted box and its full panel, whatever the toggles say.
 *
 * Each group is its own pass because each has its own "through walls"
 * setting. Shapes never write depth, so the order of these passes doesn't
 * change what hides what.
 */
object WorldOverlay {

    fun render(ctx: WorldRenderContext) {
        val cfg = CcmConfig.get()
        val turtles = TurtleStore.turtles.values
        val scans = if (cfg.showScans) Scans.areas.values else emptyList()
        if (turtles.isEmpty() && scans.isEmpty() && !cfg.showChunkBorders) return

        val camera = ctx.camera()
        val cam = camera.pos
        val look = Vec3d.fromPolar(camera.pitch, camera.yaw)
        val hovered = Hover.find(turtles, cam, look, cfg.hoverRange.toDouble())

        val matrices = ctx.matrixStack()
        val m = matrices.peek().positionMatrix

        if (cfg.showChunkBorders) Shapes.batch(cfg.chunksThroughWalls) {
            Shapes.lines { buf -> ChunkBorders.draw(buf, m, cam) }
        }

        if (scans.isNotEmpty()) Shapes.batch(cfg.scansThroughWalls) {
            Shapes.quads { buf -> for (s in scans) Shapes.fill(buf, m, s.worldBox().offset(cam.negate()), Colors.SCAN_FILL) }
            Shapes.lines { buf -> for (s in scans) Shapes.outline(buf, m, s.worldBox().offset(cam.negate()), Colors.SCAN_EDGE) }
        }

        val outlined = turtles.filter { cfg.showBoxes || it.id == hovered?.id }
        val tracers = cfg.showTracers && turtles.isNotEmpty()
        val filled = cfg.fillBoxes && turtles.isNotEmpty()
        if (outlined.isNotEmpty() || tracers || filled) Shapes.batch(cfg.turtlesThroughWalls) {
            if (filled) Shapes.quads { buf ->
                for (t in turtles) {
                    Shapes.fill(buf, m, t.worldBox().offset(cam.negate()), Colors.withAlpha(Colors.status(t), 0x30))
                }
            }
            if (outlined.isNotEmpty() || tracers) Shapes.lines { buf ->
                for (t in outlined) {
                    val color = if (t.id == hovered?.id) Colors.HOVER else Colors.status(t)
                    Shapes.outline(buf, m, t.worldBox().offset(cam.negate()), color)
                }
                // start one block in front of the eye: a line starting exactly at the eye is a single point on screen
                if (tracers) for (t in turtles) {
                    Shapes.line(buf, m, look, t.center().subtract(cam), Colors.withAlpha(Colors.status(t), 0xAA))
                }
            }
        }

        // Labels are drawn far to near so closer ones cover farther ones,
        // and the hovered panel last so nothing covers it.
        //
        // "On top of blocks" is done by wiping the depth buffer - the GPU's
        // record of how far away each pixel's nearest surface is - so nothing
        // drawn after can be hidden by the world. Vanilla does the same before
        // drawing your hand, so it's safe this late in the frame. It's needed
        // because the text renderer sets its own depth mode, and mods like
        // ImmediatelyFast or Iris can leave the text depth-tested anyway.
        if (cfg.showNametags) {
            if (cfg.nametagsThroughWalls) clearDepth()
            val rangeSq = cfg.labelRange.toDouble() * cfg.labelRange
            turtles
                .filter { it.id != hovered?.id && it.center().squaredDistanceTo(cam) <= rangeSq }
                .sortedByDescending { it.center().squaredDistanceTo(cam) }
                .forEach {
                    Labels.draw(matrices, camera, it, TurtleText.nametag(it), Labels.NAMETAG_ANCHOR,
                        Colors.nametagBackground(), cfg.nametagsThroughWalls)
                }
        }
        // always on top: you only get it by pointing at the turtle on purpose
        if (hovered != null) {
            clearDepth()
            Labels.draw(matrices, camera, hovered, TurtleText.panel(hovered), Labels.PANEL_ANCHOR,
                Colors.PANEL_BG, throughWalls = true)
        }
    }

    private fun clearDepth() = RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
}
