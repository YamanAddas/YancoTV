package com.yancotv.shared.handoff

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HandoffClientTest {
    private val command =
        HandoffPlayCommand(
            pairingToken = "tok-xyz",
            item =
            HandoffItem(
                id = "ch:1",
                kind = HandoffKind.CHANNEL,
                title = "News",
                streamUrl = "http://p/1.ts",
            ),
        )

    private fun clientReturning(status: HttpStatusCode, capture: MutableList<HttpRequestData>? = null): HandoffClient {
        val engine =
            MockEngine { request ->
                capture?.add(request)
                respond(content = "", status = status)
            }
        return HandoffClient(HttpClient(engine))
    }

    @Test
    fun acceptedOn200AndPostsToTheRightEndpoint() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientReturning(HttpStatusCode.OK, requests)

        val result = client.play("192.168.1.50", 8731, command)

        assertEquals(HandoffSendResult.Accepted, result)
        val sent = requests.single()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("192.168.1.50", sent.url.host)
        assertEquals(8731, sent.url.port)
        assertEquals("/handoff/play", sent.url.encodedPath)
        val body = (sent.body as? TextContent)?.text ?: ""
        assertTrue(body.contains("tok-xyz"), "body should carry the token: $body")
        assertTrue(body.contains("channel"), "body should carry the kind token: $body")
    }

    @Test
    fun unauthorizedMapsToRejected() = runTest {
        val result = clientReturning(HttpStatusCode.Unauthorized).play("h", 1, command)
        assertEquals(HandoffSendResult.Rejected(HandoffReject.UNAUTHORIZED), result)
    }

    @Test
    fun conflictMapsToUnsupportedSchema() = runTest {
        val result = clientReturning(HttpStatusCode.Conflict).play("h", 1, command)
        assertEquals(HandoffSendResult.Rejected(HandoffReject.UNSUPPORTED_SCHEMA), result)
    }

    @Test
    fun unprocessableMapsToInvalidItem() = runTest {
        val result = clientReturning(HttpStatusCode.UnprocessableEntity).play("h", 1, command)
        assertEquals(HandoffSendResult.Rejected(HandoffReject.INVALID_ITEM), result)
    }

    @Test
    fun unexpectedStatusIsUnreachable() = runTest {
        val result = clientReturning(HttpStatusCode.InternalServerError).play("h", 1, command)
        assertIs<HandoffSendResult.Unreachable>(result)
    }

    @Test
    fun networkErrorIsUnreachableNeverThrows() = runTest {
        val throwingClient = HandoffClient(HttpClient(MockEngine { throw RuntimeException("boom") }))
        val result = throwingClient.play("h", 1, command)
        assertIs<HandoffSendResult.Unreachable>(result)
    }

    @Test
    fun pingTrueOn200FalseOtherwise() = runTest {
        assertTrue(clientReturning(HttpStatusCode.OK).ping("h", 1))
        assertFalse(clientReturning(HttpStatusCode.NotFound).ping("h", 1))
        val throwing = HandoffClient(HttpClient(MockEngine { throw RuntimeException("boom") }))
        assertFalse(throwing.ping("h", 1))
    }
}
