package tech.mista.ccmanager.client.render

import tech.mista.ccmanager.client.turtle.Turtle

data class Line(val text: String, val color: Int)

/** What the labels say. Kept apart from how they're drawn, see [Labels]. */
object TurtleText {

    fun nametag(t: Turtle): List<Line> = listOf(Line(t.label, Colors.TEXT))

    fun panel(t: Turtle): List<Line> = buildList {
        add(Line("${t.label}  #${t.id}", Colors.HOVER))
        add(Line(if (t.online) "online" else "offline", Colors.status(t)))
        add(Line("${t.x} ${t.y} ${t.z}  facing ${t.facing?.label ?: "?"}", Colors.TEXT))
        add(Line("fuel ${t.fuel}", Colors.TEXT))
        if (t.inventory.isEmpty()) {
            add(Line("inventory empty", Colors.DIM))
        } else {
            for (s in t.inventory) add(Line("${s.count}x ${s.name.removePrefix("minecraft:")}", Colors.DIM))
        }
    }
}
