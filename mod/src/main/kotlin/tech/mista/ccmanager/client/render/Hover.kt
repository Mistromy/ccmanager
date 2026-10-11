package tech.mista.ccmanager.client.render

import net.minecraft.util.math.Vec3d
import tech.mista.ccmanager.client.turtle.Turtle

/**
 * Which turtle the crosshair points at. Our own ray against each turtle's box
 * rather than the game's crosshair target, so it works through walls and for
 * turtles in chunks you haven't loaded.
 */
object Hover {
    fun find(turtles: Collection<Turtle>, eye: Vec3d, look: Vec3d, range: Double): Turtle? {
        val end = eye.add(look.multiply(range))
        var best: Turtle? = null
        var bestDist = Double.MAX_VALUE
        for (t in turtles) {
            val hit = t.worldBox().raycast(eye, end).orElse(null) ?: continue
            val d = hit.squaredDistanceTo(eye)
            if (d < bestDist) {
                best = t
                bestDist = d
            }
        }
        return best
    }
}
