package tech.mista.ccmanager.client

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

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
