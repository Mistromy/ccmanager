package tech.mista.ccmanager.client.turtle

/**
 * One turtle as the mod sees it. Immutable: a fresh list replaces the old one
 * on every update, so the renderer never sees a half-written turtle.
 */
data class Turtle(
    val id: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val facing: Facing?,
    val fuel: Int,
    val online: Boolean,
    val inventory: List<Slot>,
) {
    val label: String get() = name.ifEmpty { "turtle $id" }
}

/** A non-empty inventory slot. Empty slots are dropped while parsing. */
data class Slot(val slot: Int, val name: String, val count: Int)

/**
 * Same order as the Go `Rot` enum after its leading `Unknown` (0), so the wire
 * value is the ordinal + 1. Unknown and anything out of range map to null.
 */
enum class Facing {
    NORTH, EAST, SOUTH, WEST;

    val label: String get() = name.lowercase()

    companion object {
        fun of(rot: Int?): Facing? = entries.getOrNull((rot ?: 0) - 1)
    }
}
