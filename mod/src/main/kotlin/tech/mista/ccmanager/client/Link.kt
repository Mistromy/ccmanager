package tech.mista.ccmanager.client

import net.minecraft.util.Util
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

/**
 * The websocket to the backend's /client endpoint. A state machine advanced
 * by [tick]: OFF → (start) → WAITING → CONNECTING → CONNECTED; any failure
 * goes back to WAITING with a retry 1s later; [stop] goes to OFF.
 *
 * State lives on the client thread. Socket callbacks arrive on HttpClient
 * threads and hop over with onClient before touching it.
 */
object Link {
    enum class State { OFF, WAITING, CONNECTING, CONNECTED }

    var state = State.OFF
        private set
    var lastError: String? = null
        private set

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private var socket: WebSocket? = null
    private var nextAttempt = 0L
    private var nextPoll = 0L

    // Bumped whenever a socket is opened or dropped; callbacks from an older
    // socket see a different number and are ignored.
    private var generation = 0

    // Java's WebSocket throws if a send starts before the previous one finished, so sends are chained.
    private var sending: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    /** Connect now, dropping any current connection. */
    fun start() {
        drop()
        lastError = null
        state = State.WAITING
        nextAttempt = 0
    }

    /** Disconnect and stay disconnected until [start]. */
    fun stop() {
        drop()
        state = State.OFF
        TurtleStore.clear()
        Scans.clear()
    }

    fun restartIfRunning() {
        if (state != State.OFF) start()
    }

    fun tick() {
        val now = Util.getMeasuringTimeMs()
        if (state == State.WAITING && now >= nextAttempt) open()
        if (state == State.CONNECTED && now >= nextPoll) {
            nextPoll = now + CcmConfig.get().pollMillis
            send("getTurtles")
        }
    }

    fun send(text: String) {
        val ws = socket ?: return
        // a failed send closes the socket, which onClose/onError handle
        sending = sending.thenCompose { ws.sendText(text, true) }.thenApply { }.exceptionally { }
    }

    /** Frames from the server: `<verb> <payload>`. Runs on the socket thread, so only parsed results cross over. */
    private fun handle(text: String) {
        val verb = text.substringBefore(' ')
        val payload = text.substringAfter(' ', "")
        try {
            when (verb) {
                "turtles" -> WireJson.turtles(payload).let { onClient { TurtleStore.replaceAll(it) } }
                "scan" -> WireJson.scan(payload).let { onClient { Scans.put(it) } }
                else -> log.debug("unhandled message: {}", verb)
            }
        } catch (e: Exception) {
            log.warn("bad '{}' message: {}", verb, e.toString())
        }
    }

    private fun open() {
        val cfg = CcmConfig.get()
        val gen = ++generation
        state = State.CONNECTING
        try {
            val uri = URI("${if (cfg.useTls) "wss" else "ws"}://${cfg.host.trim()}/client")
            http.newWebSocketBuilder()
                .header("Authorization", cfg.password)
                .buildAsync(uri, Listener(gen))
                .whenComplete { ws, err ->
                    onClient {
                        when {
                            gen != generation -> ws?.abort() // we moved on while this was connecting
                            err != null -> fail(describe(err))
                            else -> {
                                socket = ws
                                state = State.CONNECTED
                                nextPoll = 0
                                sending = CompletableFuture.completedFuture(Unit)
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName) // bad address or header: thrown immediately
        }
    }

    private fun fail(reason: String) {
        socket = null
        lastError = reason
        state = State.WAITING
        nextAttempt = Util.getMeasuringTimeMs() + 1000
    }

    private fun drop() {
        generation++
        socket?.abort()
        socket = null
    }

    private fun describe(err: Throwable): String = when (val e = (err as? CompletionException)?.cause ?: err) {
        is WebSocketHandshakeException -> if (e.response.statusCode() == 401) "wrong password" else "http ${e.response.statusCode()}"
        is HttpConnectTimeoutException -> "timed out"
        is ConnectException -> "refused"
        else -> e.message ?: e.javaClass.simpleName
    }

    private class Listener(private val gen: Int) : WebSocket.Listener {
        private val partial = StringBuilder() // a frame can arrive in pieces; `last` marks the end

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                handle(partial.toString())
                partial.setLength(0)
            }
            webSocket.request(1) // Java delivers nothing more until asked
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            onClient { if (gen == generation) fail("closed by server ($statusCode)") }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            onClient { if (gen == generation) fail(describe(error)) }
        }
    }
}
