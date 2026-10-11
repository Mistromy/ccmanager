package tech.mista.ccmanager.client.render

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.util.math.Vec3d
import tech.mista.ccmanager.client.config.CcmConfig
import tech.mista.ccmanager.client.turtle.TurtleStore

/**
 * Everything drawn in the world, once per frame: boxes and tracers first,
 * then labels on top. The hovered turtle always gets a highlighted box and its
 * full panel, whatever the toggles say.
 */
object WorldOverlay {

    fun render(ctx: WorldRenderContext) {
        val turtles = TurtleStore.turtles.values
        if (turtles.isEmpty()) return

        val cfg = CcmConfig.get()
        val camera = ctx.camera()
        val cam = camera.pos
        val look = Vec3d.fromPolar(camera.pitch, camera.yaw)
        val hovered = Hover.find(turtles, cam, look, cfg.hoverRange.toDouble())

        val matrices = ctx.matrixStack()
        val m = matrices.peek().positionMatrix

        Shapes.batch(cfg.throughWalls) {
            if (cfg.fillBoxes) Shapes.quads { buf ->
                for (t in turtles) {
                    Shapes.fill(buf, m, t.worldBox().offset(cam.negate()), Colors.withAlpha(Colors.status(t), 0x30))
                }
            }

            val outlined = turtles.filter { cfg.showBoxes || it.id == hovered?.id }
            if (outlined.isNotEmpty() || cfg.showTracers) Shapes.lines { buf ->
                for (t in outlined) {
                    val color = if (t.id == hovered?.id) Colors.HOVER else Colors.status(t)
                    Shapes.outline(buf, m, t.worldBox().offset(cam.negate()), color)
                }
                // start one block in front of the eye: a line starting exactly at the eye is a single point on screen
                if (cfg.showTracers) for (t in turtles) {
                    Shapes.line(buf, m, look, t.center().subtract(cam), Colors.withAlpha(Colors.status(t), 0xAA))
                }
            }
        }

        // Labels are drawn far to near so closer ones cover farther ones,
        // and the hovered panel last so nothing covers it.
        if (cfg.showNametags) {
            val rangeSq = cfg.labelRange.toDouble() * cfg.labelRange
            turtles
                .filter { it.id != hovered?.id && it.center().squaredDistanceTo(cam) <= rangeSq }
                .sortedByDescending { it.center().squaredDistanceTo(cam) }
                .forEach {
                    Labels.draw(matrices, camera, it, TurtleText.nametag(it), Labels.NAMETAG_ANCHOR,
                        Colors.nametagBackground(), cfg.throughWalls)
                }
        }
        // always through walls: you only get it by pointing at the turtle on purpose
        if (hovered != null) {
            Labels.draw(matrices, camera, hovered, TurtleText.panel(hovered), Labels.PANEL_ANCHOR,
                Colors.PANEL_BG, throughWalls = true)
        }
    }
}
