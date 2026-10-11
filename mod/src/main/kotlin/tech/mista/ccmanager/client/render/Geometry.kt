package tech.mista.ccmanager.client.render

import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import tech.mista.ccmanager.client.scan.ScanArea
import tech.mista.ccmanager.client.turtle.Turtle

// Turtle positions are block coordinates, i.e. the block's min corner. The
// turtle model is inset 2 pixels (2/16 block) on every side; the box hugs
// that, plus a hair so its lines don't flicker against the model's faces
// (z-fighting).
private const val INSET = 2.0 / 16 - 0.002

fun Turtle.worldBox(): Box = Box(
    x.toDouble(), y.toDouble(), z.toDouble(),
    x + 1.0, y + 1.0, z + 1.0,
).contract(INSET)

fun Turtle.center(): Vec3d = Vec3d(x + 0.5, y + 0.5, z + 0.5)

/** Radius r covers the scan block ± r on each axis: r = 1 is 3³ blocks, r = 2 is 5³. */
fun ScanArea.worldBox(): Box = Box(
    (x - radius).toDouble(), (y - radius).toDouble(), (z - radius).toDouble(),
    (x + radius + 1).toDouble(), (y + radius + 1).toDouble(), (z + radius + 1).toDouble(),
)
