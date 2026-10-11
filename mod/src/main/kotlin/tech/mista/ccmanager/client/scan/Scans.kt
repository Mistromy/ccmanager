package tech.mista.ccmanager.client.scan

import com.google.gson.JsonParser

/**
 * The area a turtle's last scan covered: a static cube, centered on where the
 * turtle stood, that doesn't follow it afterwards.
 */
data class ScanArea(val turtleId: Int, val x: Int, val y: Int, val z: Int, val radius: Int)

/** Latest scan per turtle. Client thread only, like TurtleStore. */
object Scans {
    var areas: Map<Int, ScanArea> = emptyMap()
        private set

    /** Payload of a `scan` message: `{"id":12,"x":306,"y":32,"z":-422,"radius":8}`. */
    fun parse(json: String): ScanArea {
        val o = JsonParser.parseString(json).asJsonObject
        return ScanArea(o["id"].asInt, o["x"].asInt, o["y"].asInt, o["z"].asInt, o["radius"].asInt)
    }

    fun put(area: ScanArea) {
        areas = areas + (area.turtleId to area)
    }

    fun clear() {
        areas = emptyMap()
    }
}
