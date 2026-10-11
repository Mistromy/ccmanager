package tech.mista.ccmanager.client.render

import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import tech.mista.ccmanager.client.turtle.Turtle

// Turtle positions are block coordinates, i.e. the block's min corner. The box
// is a hair bigger than the block so its lines don't flicker against the
// real turtle's faces (z-fighting).

fun Turtle.worldBox(): Box = Box(
    x.toDouble(), y.toDouble(), z.toDouble(),
    x + 1.0, y + 1.0, z + 1.0,
).expand(0.002)

fun Turtle.center(): Vec3d = Vec3d(x + 0.5, y + 0.5, z + 0.5)
