package tech.mista.ccmanager.client

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import net.minecraft.util.math.BlockPos

data class Turtle(
    val id: Int,
    val name: String,
    val x: Int, val y: Int, val z: Int,
    val facing: Facing?,
    val fuel: Int,
    val online: Boolean,
    val inventory: List<Slot>, // non-empty slots only
) {
    val label: String get() = name.ifEmpty { "turtle $id" }
}

data class Slot(val slot: Int, val name: String, val count: Int)

/** Go's Rot enum after its leading Unknown (0): wire value = ordinal + 1. Unknown/out of range → null. */
enum class Facing {
    NORTH, EAST, SOUTH, WEST;

    companion object {
        fun of(rot: Int?): Facing? = entries.getOrNull((rot ?: 0) - 1)
    }
}

/** A turtle's last scan: a static cube around where it stood. */
data class ScanArea(val turtleId: Int, val x: Int, val y: Int, val z: Int, val radius: Int)

/**
 * One block the last pathfind explored. [faces] has bit k set when face k
 * (-x +x -y +y -z +z, as in Shapes) borders an unexplored block, so only the
 * outside of the explored volume gets drawn.
 */
data class PathCell(val x: Int, val y: Int, val z: Int, val faces: Int)

// The stores. Client thread only, and always replaced whole rather than
// edited, so the renderer never sees half an update.

object TurtleStore {
    var turtles: Map<Int, Turtle> = emptyMap()
        private set

    fun replaceAll(list: List<Turtle>) { turtles = list.associateBy { it.id } }
    fun clear() { turtles = emptyMap() }
}

object Scans {
    var areas: Map<Int, ScanArea> = emptyMap() // latest per turtle
        private set

    fun put(area: ScanArea) { areas = areas + (area.turtleId to area) }
    fun clear() { areas = emptyMap() }
}

/**
 * The last pathfind, for debugging the pathfinder: the explored blocks (A*'s
 * closed set, the inside), the frontier (the open set, the shell it was still
 * expanding), and the path it settled on, in order.
 */
object PathDebug {
    var explored: List<PathCell> = emptyList()
        private set
    var frontier: List<PathCell> = emptyList()
        private set
    var path: List<BlockPos> = emptyList()
        private set

    fun replaceExplored(cells: List<PathCell>) { explored = cells }
    fun replaceFrontier(cells: List<PathCell>) { frontier = cells }
    fun replacePath(points: List<BlockPos>) { path = points }

    fun clearSearch() {
        explored = emptyList()
        frontier = emptyList()
    }

    fun clearPath() { path = emptyList() }

    fun clear() {
        clearSearch()
        clearPath()
    }
}

/**
 * Wire JSON → data. Go only tags `Turtle` (lowercase); `Loc` and `Item` use
 * their Go field names (`X`, `Count`...). Gson is case-sensitive, so keys are
 * looked up ignoring case.
 */
object WireJson {

    /** `turtles` payload: an array, or `null` when there are none. */
    fun turtles(json: String): List<Turtle> =
        (JsonParser.parseString(json) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::turtle) } ?: emptyList()

    /** `scan` payload: `{"id","x","y","z","radius"}`. */
    fun scan(json: String): ScanArea {
        val o = JsonParser.parseString(json).asJsonObject
        return ScanArea(o["id"].asInt, o["x"].asInt, o["y"].asInt, o["z"].asInt, o["radius"].asInt)
    }

    /**
     * `path`, `pathfindExplored` and `pathfindFrontier` payloads: an array of
     * `{X,Y,Z}` (Go's Vector32), or `null` when empty. Order is kept.
     */
    fun points(json: String): List<BlockPos> =
        (JsonParser.parseString(json) as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            BlockPos(o.int("x") ?: return@mapNotNull null, o.int("y") ?: return@mapNotNull null, o.int("z") ?: return@mapNotNull null)
        } ?: emptyList()

    /** [points], plus which of each block's faces border a block outside the set. */
    fun cells(json: String): List<PathCell> {
        val points = points(json)
        val set = points.mapTo(HashSet(points.size * 2)) { it.asLong() }
        return points.map { p ->
            var faces = 0
            FACE_OFFSETS.forEachIndexed { k, (dx, dy, dz) ->
                if (BlockPos.asLong(p.x + dx, p.y + dy, p.z + dz) !in set) faces = faces or (1 shl k)
            }
            PathCell(p.x, p.y, p.z, faces)
        }
    }

    // neighbour across each face, in Shapes' face order: -x +x -y +y -z +z
    private val FACE_OFFSETS = listOf(
        Triple(-1, 0, 0), Triple(1, 0, 0), Triple(0, -1, 0), Triple(0, 1, 0), Triple(0, 0, -1), Triple(0, 0, 1),
    )

    private fun turtle(o: JsonObject): Turtle? {
        val loc = o.obj("loc")
        return Turtle(
            id = o.int("id") ?: return null,
            name = o.string("name") ?: "",
            x = loc?.int("x") ?: 0, y = loc?.int("y") ?: 0, z = loc?.int("z") ?: 0,
            facing = Facing.of(loc?.int("rot")),
            fuel = o.int("fuel") ?: 0,
            online = o.bool("status") ?: false,
            inventory = (o.field("storage") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::slot) } ?: emptyList(),
        )
    }

    private fun slot(o: JsonObject): Slot? {
        val count = o.int("count") ?: 0
        if (count <= 0) return null
        return Slot(o.int("slot") ?: 0, o.string("name")?.ifEmpty { null } ?: o.string("id") ?: "?", count)
    }

    private fun JsonObject.field(key: String): JsonElement? =
        get(key) ?: entrySet().firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun JsonObject.obj(key: String) = field(key) as? JsonObject
    private fun JsonObject.int(key: String) = (field(key) as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt
    private fun JsonObject.string(key: String) = (field(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString
    private fun JsonObject.bool(key: String) = (field(key) as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean
}
