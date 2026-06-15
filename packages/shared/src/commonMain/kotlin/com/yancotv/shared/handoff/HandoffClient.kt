package com.yancotv.shared.handoff

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Outcome of a sender's attempt to hand a command to a TV. */
sealed interface HandoffSendResult {
    /** The TV accepted the command and is starting playback. */
    data object Accepted : HandoffSendResult

    /** The TV reached us but refused (bad token, unsupported schema, bad item). */
    data class Rejected(val reason: HandoffReject) : HandoffSendResult

    /** The TV couldn't be reached, timed out, or returned an unexpected status. */
    data class Unreachable(val message: String) : HandoffSendResult
}

/**
 * MK.26.A.3 — sender half of the LAN companion handoff. The phone POSTs a
 * [HandoffPlayCommand] to a TV's [HandoffServer]; the TV plays it natively.
 *
 * Pure/shared so the sender logic is unit-testable (MockEngine) and iOS-ready.
 * Takes a ktor [HttpClient] injected by the platform — this MUST be a client
 * WITHOUT the cleartext allow-list interceptor, because the target is the
 * user's own TV on a `http://<lan-ip>` address that is not in the provider
 * source list (parallel to the Coil image-loader carve-out).
 *
 * The command is encoded manually (not via ContentNegotiation) so the injected
 * client needs no JSON plugin configured.
 */
class HandoffClient(private val http: HttpClient, private val json: Json = Json) {
    /** Cheap liveness/identity probe for discovery. True iff the TV answers 200. */
    suspend fun ping(host: String, port: Int): Boolean =
        runCatching {
            http.get("${baseUrl(host, port)}/handoff/ping").status == HttpStatusCode.OK
        }.getOrDefault(false)

    /** Send a play command. Never throws — network failures map to [HandoffSendResult.Unreachable]. */
    suspend fun play(host: String, port: Int, command: HandoffPlayCommand): HandoffSendResult =
        try {
            val response: HttpResponse =
                http.post("${baseUrl(host, port)}/handoff/play") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(command))
                }
            when (response.status) {
                HttpStatusCode.OK -> HandoffSendResult.Accepted
                HttpStatusCode.Unauthorized -> HandoffSendResult.Rejected(HandoffReject.UNAUTHORIZED)
                HttpStatusCode.Conflict -> HandoffSendResult.Rejected(HandoffReject.UNSUPPORTED_SCHEMA)
                HttpStatusCode.UnprocessableEntity -> HandoffSendResult.Rejected(HandoffReject.INVALID_ITEM)
                else -> HandoffSendResult.Unreachable("HTTP ${response.status.value}")
            }
        } catch (t: Throwable) {
            HandoffSendResult.Unreachable(t.message ?: t::class.simpleName ?: "network error")
        }

    private fun baseUrl(host: String, port: Int): String = "http://$host:$port"
}
