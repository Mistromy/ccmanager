package tech.mista.ccmanager.client

import com.mojang.blaze3d.platform.GlConst
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.BufferBuilder
import net.minecraft.client.render.Camera
import net.minecraft.util.math.Box
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f

/** ARGB. */
private object Colors {
    val ONLINE = 0xFF55FF55.toInt()
    val OFFLINE = 0xFFFF5555.toInt()
    val HOVER = 0xFFFFFF55.toInt()
    val SELECTED = 0xFF55FFFF.toInt()
    const val GHOST_FILL = 0x60AAAAAA
    const val EXPLORED = 0x30FFAA00
    const val FRONTIER = 0x4000FFAA
    val GHOST_EDGE = 0xC0AAAAAA.toInt()
    val TEXT = 0xFFFFFFFF.toInt()
    val DIM = 0xFFAAAAAA.toInt()
    val PANEL_BG = 0xA0000000.toInt()
    val CHUNK_EDGE = 0xFFFFFF00.toInt()
    val CHUNK_GRID = 0x80FFFF00.toInt()
    val CHUNK_GRID_MAJOR = 0xFF00AAAA.toInt()
    val CHUNK_AROUND = 0xFFFF0000.toInt()
    val SCAN_EDGE = 0xFF55AAFF.toInt()
    const val SCAN_FILL = 0x2055AAFF

    /** Vanilla's nametag background (Accessibility > Text Background Opacity). */
    fun nametagBackground() = (MinecraftClient.getInstance().options.getTextBackgroundOpacity(0.25f) * 255).toInt() shl 24
    fun status(t: Turtle) = if (t.online) ONLINE else OFFLINE
    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}

// The turtle model is inset 2px (2/16 block) per side; the box hugs it, plus a hair against z-fighting.
private fun Turtle.box() = Box(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0).contract(2.0 / 16 - 0.002)
private fun Turtle.center() = Vec3d(x + 0.5, y + 0.5, z + 0.5)

// radius r covers the scan block ± r: r = 1 is 3³ blocks, r = 2 is 5³
private fun ScanArea.box() = Box(
    (x - radius).toDouble(), (y - radius).toDouble(), (z - radius).toDouble(),
    (x + radius + 1).toDouble(), (y + radius + 1).toDouble(), (z + radius + 1).toDouble(),
)

/**
 * Everything drawn in the world, once per frame: shapes first, labels on top.
 * One shape pass per group, since each has its own through-walls setting.
 * The hovered turtle always gets a highlighted box and its panel.
 */
object WorldOverlay {

    fun render(ctx: WorldRenderContext) {
        val cfg = CcmConfig.get()
        val turtles = TurtleStore.turtles.values
        val scans = if (cfg.showScans) Scans.areas.values else emptyList()
        val ghost = Menu.target
        val explored = if (cfg.showPathfind) PathDebug.explored else emptyList()
        val frontier = if (cfg.showPathfind) PathDebug.frontier else emptyList()
        val search = explored.isNotEmpty() || frontier.isNotEmpty()
        if (turtles.isEmpty() && scans.isEmpty() && !search && ghost == null && !cfg.showChunkBorders) return

        val camera = ctx.camera()
        val cam = camera.pos
        val look = Vec3d.fromPolar(camera.pitch, camera.yaw)
        val hovered = hovered(turtles, cam, look, cfg.hoverRange.toDouble())
        val matrices = ctx.matrixStack()
        val m = matrices.peek().positionMatrix
        fun Box.rel() = offset(cam.negate())

        if (cfg.showChunkBorders) Shapes.batch(cfg.chunksThroughWalls) {
            Shapes.lines { chunkBorders(it, m, cam) }
        }

        if (scans.isNotEmpty()) Shapes.batch(cfg.scansThroughWalls) {
            Shapes.quads { buf -> scans.forEach { Shapes.fill(buf, m, it.box().rel(), Colors.SCAN_FILL) } }
            Shapes.lines { buf -> scans.forEach { Shapes.outline(buf, m, it.box().rel(), Colors.SCAN_EDGE) } }
        }

        // the pathfinder's search as translucent volumes: only faces bordering blocks outside the same set
        if (search) Shapes.batch(cfg.pathfindThroughWalls) {
            Shapes.quads { buf ->
                for ((cells, color) in listOf(explored to Colors.EXPLORED, frontier to Colors.FRONTIER)) for (c in cells) {
                    val b = Box(c.x.toDouble(), c.y.toDouble(), c.z.toDouble(), c.x + 1.0, c.y + 1.0, c.z + 1.0).rel()
                    for (k in 0 until 6) if (c.faces and (1 shl k) != 0) Shapes.face(buf, m, inset(b, k), k, color)
                }
            }
        }

        // where Pathfind would send the turtle, shown while the menu is open: a turtle-sized grey box
        if (ghost != null) Shapes.batch(throughWalls = true) {
            val box = Box(ghost).contract(2.0 / 16).rel()
            Shapes.quads { Shapes.fill(it, m, box, Colors.GHOST_FILL) }
            Shapes.lines { Shapes.outline(it, m, box, Colors.GHOST_EDGE) }
        }

        val selected = Menu.selectedId
        val outlined = turtles.filter { cfg.showBoxes || it.id == hovered?.id || it.id == selected }
        val tracers = cfg.showTracers && turtles.isNotEmpty()
        val filled = cfg.fillBoxes && turtles.isNotEmpty()
        if (outlined.isNotEmpty() || tracers || filled) Shapes.batch(cfg.turtlesThroughWalls) {
            if (filled) Shapes.quads { buf ->
                turtles.forEach { Shapes.fill(buf, m, it.box().rel(), Colors.withAlpha(Colors.status(it), 0x30)) }
            }
            if (outlined.isNotEmpty() || tracers) Shapes.lines { buf ->
                for (t in outlined) {
                    val color = when (t.id) {
                        hovered?.id -> Colors.HOVER
                        selected -> Colors.SELECTED
                        else -> Colors.status(t)
                    }
                    Shapes.outline(buf, m, t.box().rel(), color)
                }
                // from one block in front of the eye: a line starting at the eye is a single point on screen
                if (tracers) for (t in turtles) Shapes.line(buf, m, look, t.center().subtract(cam), Colors.withAlpha(Colors.status(t), 0xAA))
            }
        }

        // Labels far to near so closer ones cover farther ones; the panel last.
        //
        // "On top" works by wiping the depth buffer (the GPU's per-pixel record of
        // the nearest surface), so nothing drawn after can be hidden by the world.
        // Vanilla does the same before drawing your hand. Needed because the text
        // renderer sets its own depth mode, which mods like ImmediatelyFast or
        // Iris can leave depth-tested.
        if (cfg.showNametags) {
            if (cfg.nametagsThroughWalls) clearDepth()
            val rangeSq = cfg.labelRange.toDouble() * cfg.labelRange
            turtles
                .filter { it.id != hovered?.id && it.center().squaredDistanceTo(cam) <= rangeSq }
                .sortedByDescending { it.center().squaredDistanceTo(cam) }
                .forEach {
                    Labels.draw(matrices, camera, it, listOf(Line(it.label, Colors.TEXT)), Labels.NAMETAG_ANCHOR,
                        Colors.nametagBackground(), cfg.nametagsThroughWalls)
                }
        }
        if (hovered != null) {
            clearDepth()
            Labels.draw(matrices, camera, hovered, panel(hovered), Labels.PANEL_ANCHOR, Colors.PANEL_BG, throughWalls = true)
        }
    }

    /** The turtle under the crosshair right now; what the menu's Select acts on. */
    fun lookedAt(camera: Camera): Turtle? =
        hovered(TurtleStore.turtles.values, camera.pos, Vec3d.fromPolar(camera.pitch, camera.yaw), CcmConfig.get().hoverRange.toDouble())

    /** Our own ray against each turtle's box, so it works through walls and in unloaded chunks. */
    private fun hovered(turtles: Collection<Turtle>, eye: Vec3d, look: Vec3d, range: Double): Turtle? {
        val end = eye.add(look.multiply(range))
        return turtles
            .mapNotNull { t -> t.box().raycast(eye, end).orElse(null)?.let { t to it.squaredDistanceTo(eye) } }
            .minByOrNull { it.second }?.first
    }

    private fun panel(t: Turtle) = buildList {
        add(Line("${t.label}  #${t.id}", Colors.HOVER))
        add(Line(if (t.online) "online" else "offline", Colors.status(t)))
        add(Line("${t.x} ${t.y} ${t.z}  facing ${t.facing?.name?.lowercase() ?: "?"}", Colors.TEXT))
        add(Line("fuel ${t.fuel}", Colors.TEXT))
        if (t.inventory.isEmpty()) add(Line("inventory empty", Colors.DIM))
        for (s in t.inventory) add(Line("${s.count}x ${s.name.removePrefix("minecraft:")}", Colors.DIM))
    }

    /**
     * The backend's 16³ chunk you're in (block coordinate >> 4, like Go's getKey):
     * outline plus a grid on floor and ceiling, which vanilla's F3+G lacks. A red
     * box around the 3x3x3 chunks shows where the neighbouring ones end.
     */
    private fun chunkBorders(buf: BufferBuilder, m: Matrix4f, cam: Vec3d) {
        val ox = (MathHelper.floor(cam.x) shr 4) * 16.0
        val oy = (MathHelper.floor(cam.y) shr 4) * 16.0
        val oz = (MathHelper.floor(cam.z) shr 4) * 16.0
        fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Int) =
            Shapes.line(buf, m, Vec3d(x1 - cam.x, y1 - cam.y, z1 - cam.z), Vec3d(x2 - cam.x, y2 - cam.y, z2 - cam.z), color)

        Shapes.outline(buf, m, Box(ox - 16, oy - 16, oz - 16, ox + 32, oy + 32, oz + 32).offset(cam.negate()), Colors.CHUNK_AROUND)

        for (i in 2 until 16 step 2) { // every 2 blocks, every 4th brighter
            val color = if (i % 4 == 0) Colors.CHUNK_GRID_MAJOR else Colors.CHUNK_GRID
            for (y in doubleArrayOf(oy, oy + 16)) {
                line(ox + i, y, oz, ox + i, y, oz + 16, color)
                line(ox, y, oz + i, ox + 16, y, oz + i, color)
            }
        }
        Shapes.outline(buf, m, Box(ox, oy, oz, ox + 16, oy + 16, oz + 16).offset(cam.negate()), Colors.CHUNK_EDGE)
    }

    // Pulls face k a hair inward, so it doesn't flicker against the solid block
    // it's touching. Only along its own axis, so neighbouring faces still meet without seams.
    private fun inset(b: Box, k: Int): Box {
        val e = 0.005
        return when (k) {
            0 -> b.withMinX(b.minX + e)
            1 -> b.withMaxX(b.maxX - e)
            2 -> b.withMinY(b.minY + e)
            3 -> b.withMaxY(b.maxY - e)
            4 -> b.withMinZ(b.minZ + e)
            else -> b.withMaxZ(b.maxZ - e)
        }
    }

    private fun clearDepth() = RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
}

/** One line of connection state, top left. Retrying and connecting share a line so it doesn't flicker. */
object StatusHud {
    fun render(draw: DrawContext) {
        if (!CcmConfig.get().showHud) return
        val (text, color) = when (Link.state) {
            Link.State.CONNECTED -> "ccm: ${TurtleStore.turtles.size} turtles" to Colors.ONLINE
            Link.State.CONNECTING, Link.State.WAITING -> "ccm: disconnected (connecting)" to Colors.OFFLINE
            Link.State.OFF -> "ccm: off" to Colors.DIM
        }
        draw.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, text, 4, 4, color)
    }
}
