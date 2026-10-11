package tech.mista.ccmanager.client.turtle

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Turns the backend's turtle JSON into [Turtle]s.
 *
 * The Go structs only have json tags on `Turtle` (lowercase `id`, `loc`...),
 * while `Loc` and `Item` fall back to their Go field names (`X`, `Rot`,
 * `Count`...). Gson matches keys case-sensitively, so instead of data-class
 * binding every key is looked up case-insensitively: this is the Kotlin twin
 * of the Lua client's `pick()`.
 */
object TurtleJson {

    /** Payload of a `turtles` message: an array, or `null` when none exist. */
    fun parseList(json: String): List<Turtle> {
        val root = JsonParser.parseString(json)
        if (root !is JsonArray) return emptyList()
        return root.mapNotNull { (it as? JsonObject)?.let(::parseTurtle) }
    }

    private fun parseTurtle(o: JsonObject): Turtle? {
        val id = o.int("id") ?: return null
        val loc = o.obj("loc")
        return Turtle(
            id = id,
            name = o.string("name") ?: "",
            x = loc?.int("x") ?: 0,
            y = loc?.int("y") ?: 0,
            z = loc?.int("z") ?: 0,
            facing = Facing.of(loc?.int("rot")),
            fuel = o.int("fuel") ?: 0,
            online = o.bool("status") ?: false,
            inventory = (o.field("storage") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(::parseSlot) }
                ?: emptyList(),
        )
    }

    private fun parseSlot(o: JsonObject): Slot? {
        val count = o.int("count") ?: 0
        if (count <= 0) return null
        val name = o.string("name")?.ifEmpty { null } ?: o.string("id") ?: "?"
        return Slot(slot = o.int("slot") ?: 0, name = name, count = count)
    }

    // exact key first, then any casing
    private fun JsonObject.field(key: String): JsonElement? =
        get(key) ?: entrySet().firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun JsonObject.obj(key: String) = field(key) as? JsonObject

    private fun JsonObject.int(key: String): Int? =
        (field(key) as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt

    private fun JsonObject.string(key: String): String? =
        (field(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString

    private fun JsonObject.bool(key: String): Boolean? =
        (field(key) as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean
}
