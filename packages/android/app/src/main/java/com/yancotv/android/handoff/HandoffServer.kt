package com.yancotv.android.handoff

import com.yancotv.shared.handoff.HandoffPlayCommand
import com.yancotv.shared.handoff.HandoffReject
import com.yancotv.shared.http.redactErrorMessage
import com.yancotv.shared.logger.Logger
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * MK.26.A.1 — embedded Ktor (CIO) HTTP server for the LAN companion-handoff
 * receiver. Runs on the TV; a phone POSTs a [HandoffPlayCommand] which the TV's
 * own player then plays (zero added lag — no transcode, no relay).
 *
 * Endpoints:
 *  - `GET  /handoff/ping`  — liveness/identity probe for discovery (A.2).
 *  - `POST /handoff/play`  — a JSON [HandoffPlayCommand]; [onPlay] decides.
 *
 * [onPlay] returns `null` on success or a [HandoffReject] reason, which maps to
 * an HTTP status so the sender can surface a precise error. All validation and
 * the playback dispatch live in the caller (the service) — this class is only
 * the HTTP transport, so it stays free of `PlaybackController`/threading rules.
 */
class HandoffServer(private val port: Int, private val logger: Logger, private val onPlay: suspend (HandoffPlayCommand) -> HandoffReject?) {
    private var server: EmbeddedServer<*, *>? = null

    /** Idempotent. Binds the CIO engine on all interfaces and starts listening. */
    fun start() {
        if (server != null) return
        server =
            embeddedServer(CIO, port = port, host = WILDCARD_HOST) {
                install(ContentNegotiation) { json() }
                routing {
                    get("/handoff/ping") {
                        call.respondText("yancotv-handoff/${HandoffPlayCommand.SCHEMA_VERSION}")
                    }
                    post("/handoff/play") {
                        val command =
                            try {
                                call.receive<HandoffPlayCommand>()
                            } catch (t: Throwable) {
                                // Don't log t.message — a deserialization error can echo the
                                // request body, which carries the credentialed stream URL.
                                logger.warn("Handoff: malformed play command (${t::class.simpleName})")
                                call.respond(HttpStatusCode.BadRequest)
                                return@post
                            }
                        val reject =
                            try {
                                onPlay(command)
                            } catch (t: Throwable) {
                                // MB-292 — the handoff payload is a stream URL,
                                // so a dispatch failure message can carry
                                // path-segment credentials into logcat.
                                logger.error("Handoff: play dispatch failed — ${redactErrorMessage(t)}")
                                call.respond(HttpStatusCode.InternalServerError)
                                return@post
                            }
                        call.respond(reject?.let(::statusFor) ?: HttpStatusCode.OK)
                    }
                }
            }.also { it.start(wait = false) }
        logger.info("Handoff: receiver listening on :$port")
    }

    /** Idempotent. Stops the engine; safe to call from `Service.onDestroy`. */
    fun stop() {
        server?.stop(GRACE_MS, TIMEOUT_MS)
        server = null
        logger.info("Handoff: receiver stopped")
    }

    private fun statusFor(reject: HandoffReject): HttpStatusCode = when (reject) {
        HandoffReject.UNAUTHORIZED -> HttpStatusCode.Unauthorized
        HandoffReject.UNSUPPORTED_SCHEMA -> HttpStatusCode.Conflict
        HandoffReject.INVALID_ITEM -> HttpStatusCode.UnprocessableEntity
    }

    companion object {
        /** Default LAN port for the handoff receiver. */
        const val DEFAULT_PORT: Int = 8731
        private const val WILDCARD_HOST = "0.0.0.0"
        private const val GRACE_MS = 500L
        private const val TIMEOUT_MS = 1500L
    }
}
