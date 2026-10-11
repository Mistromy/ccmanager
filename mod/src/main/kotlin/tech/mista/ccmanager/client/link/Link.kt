package tech.mista.ccmanager.client.link

import net.minecraft.util.Util
import tech.mista.ccmanager.client.config.CcmConfig
import tech.mista.ccmanager.client.onClient
import tech.mista.ccmanager.client.turtle.TurtleStore
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
 * The websocket to the backend's `/client` endpoint.
 *
 * A small state machine, advanced once per client tick by [tick]:
 *
 *   OFF ──start()──▶ WAITING ──retry time reached──▶ CONNECTING ──▶ CONNECTED
 *    ▲                  ▲                                │               │
 *    └─────stop()───────┴──────── failed / closed ◀──────┴───────────────┘
 *
 * All state lives on the client thread. Socket callbacks arrive on Java's
 * HttpClient threads and hop back over with `onClient` before touching it.
 */
object Link {
    enum class State { OFF, WAITING, CONNECTING, CONNECTED }

    private const val RETRY_MS = 1000L

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    var state = State.OFF
        private set
    var lastError: String? = null
        private set

    private var socket: WebSocket? = null

    // Bumped every time a socket is opened or dropped. Callbacks remember the
    // value they were created with, so a late message from a socket we already
    // gave up on can't overwrite the state of the current one.
    private var generation = 0

    private var nextAttempt = 0L
    private var nextPoll = 0L

    // Java's WebSocket throws if you send while a previous send is still in
    // flight, so every send is chained onto the one before it.
    private var sending: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    /** Connect now, dropping any current connection. World join, `/ccm connect`. */
    fun start() {
        drop()
        lastError = null
        state = State.WAITING
        nextAttempt = 0
    }

    /** Disconnect and stay disconnected until [start]. World leave, `/ccm disconnect`. */
    fun stop() {
        drop()
        state = State.OFF
        TurtleStore.clear()
    }

    /** After a config save: reconnect with the new settings, unless deliberately off. */
    fun restartIfRunning() {
        if (state != State.OFF) start()
    }

    fun tick() {
        val now = Util.getMeasuringTimeMs()
        when (state) {
            State.WAITING -> if (now >= nextAttempt) open()
            State.CONNECTED -> if (now >= nextPoll) {
                nextPoll = now + CcmConfig.get().pollMillis
                send("getTurtles")
            }
            State.OFF, State.CONNECTING -> {}
        }
    }

    fun send(text: String) {
        val ws = socket ?: return
        sending = sending
            .thenCompose { ws.sendText(text, true) }
            .thenApply { }
            .exceptionally { } // a failed send closes the socket; onClose/onError handle that
    }

    private fun open() {
        val cfg = CcmConfig.get()
        val gen = ++generation
        state = State.CONNECTING
        try {
            val scheme = if (cfg.useTls) "wss" else "ws"
            http.newWebSocketBuilder()
                .header("Authorization", cfg.password)
                .buildAsync(URI("$scheme://${cfg.host.trim()}/client"), Listener(gen))
                .whenComplete { ws, err ->
                    onClient {
                        if (gen != generation) {
                            ws?.abort() // we moved on while this was connecting
                        } else if (err != null) {
                            fail(describe(err))
                        } else {
                            socket = ws
                            state = State.CONNECTED
                            lastError = null
                            nextPoll = 0
                            sending = CompletableFuture.completedFuture(Unit)
                        }
                    }
                }
        } catch (e: Exception) {
            // bad address or header value: thrown right away rather than through the future
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(reason: String) {
        socket = null
        lastError = reason
        state = State.WAITING
        nextAttempt = Util.getMeasuringTimeMs() + RETRY_MS
    }

    private fun drop() {
        generation++
        socket?.abort()
        socket = null
    }

    private fun describe(err: Throwable): String {
        val cause = (err as? CompletionException)?.cause ?: err
        return when (cause) {
            is WebSocketHandshakeException -> when (val code = cause.response.statusCode()) {
                401 -> "wrong password"
                else -> "http $code"
            }
            is HttpConnectTimeoutException -> "timed out"
            is ConnectException -> "refused"
            else -> cause.message ?: cause.javaClass.simpleName
        }
    }

    private class Listener(private val gen: Int) : WebSocket.Listener {
        // a frame can arrive in pieces; `last` marks the final one
        private val partial = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                val text = partial.toString()
                partial.setLength(0)
                Messages.handle(text)
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
