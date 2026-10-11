package tech.mista.ccmanager.client.link

import tech.mista.ccmanager.client.log
import tech.mista.ccmanager.client.onClient
import tech.mista.ccmanager.client.turtle.TurtleJson
import tech.mista.ccmanager.client.turtle.TurtleStore

/**
 * Dispatch for frames from the server: `<verb> <payload>`, same shape as the
 * Go side. Runs on the socket thread, so parsing happens here and only the
 * finished result is handed to the client thread.
 */
object Messages {

    fun handle(text: String) {
        val space = text.indexOf(' ')
        val verb = if (space < 0) text else text.substring(0, space)
        val payload = if (space < 0) "" else text.substring(space + 1)

        try {
            when (verb) {
                "turtles" -> {
                    val list = TurtleJson.parseList(payload)
                    onClient { TurtleStore.replaceAll(list) }
                }
                "list" -> {} // reply to getIDs, which we never send; the server can still send it unprompted
                else -> log.debug("unhandled message: {}", verb)
            }
        } catch (e: Exception) {
            log.warn("bad '{}' message ({} bytes): {}", verb, payload.length, e.toString())
        }
    }
}
