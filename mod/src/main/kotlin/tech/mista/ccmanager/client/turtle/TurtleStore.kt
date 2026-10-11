package tech.mista.ccmanager.client.turtle

/**
 * The mod's copy of the fleet. Only touched on the client thread: the socket
 * parses on its own thread, then hands the finished list over via `onClient`.
 */
object TurtleStore {
    var turtles: Map<Int, Turtle> = emptyMap()
        private set

    fun replaceAll(list: List<Turtle>) {
        turtles = list.associateBy { it.id }
    }

    fun clear() {
        turtles = emptyMap()
    }
}
