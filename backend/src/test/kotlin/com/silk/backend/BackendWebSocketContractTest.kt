package com.silk.backend

import com.silk.backend.database.GroupRepository
import com.silk.backend.database.MarkReadRequest
import com.silk.backend.database.SimpleResponse
import com.silk.backend.database.UnreadCountResponse
import com.silk.backend.models.ChatHistory
import com.silk.backend.models.ChatHistoryEntry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendWebSocketContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `chat websocket replays recent history broadcasts live messages and updates unread flow`() {
        TestWorkspace().use {
            val group = createGroupForTest("WebSocket Contract Group")
            assertTrue(GroupRepository.addUserToGroup(group.id, "guest-user"))
            seedGroupHistory(
                group.id,
                (1..52).map { index ->
                    chatEntry(
                        messageId = "history-$index",
                        senderId = "seed-user",
                        senderName = "SeedUser",
                        content = "history payload $index",
                        timestamp = index.toLong()
                    )
                }
            )

            testApplication {
                application { module() }

                val wsClient = createClient {
                    install(WebSockets)
                }

                val hostSession = wsClient.connectChat(
                    userId = "host-user",
                    userName = "HostUser",
                    groupId = group.id
                )
                val guestSession = wsClient.connectChat(
                    userId = "guest-user",
                    userName = "GuestUser",
                    groupId = group.id
                )

                val hostReplay = hostSession.receiveMessages(50)
                val guestReplay = guestSession.receiveMessages(50)
                val expectedReplayIds = (3..52).map { "history-$it" }
                assertEquals(expectedReplayIds, hostReplay.map { it.id })
                assertEquals(expectedReplayIds, guestReplay.map { it.id })

                val sessionData = assertNotNull(
                    ChatHistoryManager().loadSessionData("group_${group.id}")
                )
                assertEquals(
                    setOf("host-user", "guest-user", SilkAgent.AGENT_ID),
                    sessionData.members.filter { it.isOnline }.map { it.userId }.toSet()
                )

                val liveMessage = Message(
                    id = "live-1",
                    userId = "host-user",
                    userName = "HostUser",
                    content = "fast validation websocket message",
                    timestamp = 10_000L
                )
                hostSession.send(Frame.Text(json.encodeToString(liveMessage)))

                val hostBroadcast = hostSession.receiveMessage()
                val guestBroadcast = guestSession.receiveMessage()
                assertEquals("live-1", hostBroadcast.id)
                assertEquals("live-1", guestBroadcast.id)
                assertEquals("fast validation websocket message", guestBroadcast.content)

                val persistedHistory = assertNotNull(
                    ChatHistoryManager().loadChatHistory("group_${group.id}")
                )
                assertEquals(53, persistedHistory.messages.size)
                assertEquals("live-1", persistedHistory.messages.last().messageId)

                val guestUnread = client.get("/api/unread/guest-user")
                    .decode<UnreadCountResponse>()
                assertTrue(guestUnread.success)
                assertEquals(1, guestUnread.unreadCounts[group.id] ?: 0)

                val hostUnread = client.get("/api/unread/host-user")
                    .decode<UnreadCountResponse>()
                assertTrue(hostUnread.success)
                assertEquals(0, hostUnread.unreadCounts[group.id] ?: 0)

                val markRead = client.post("/api/unread/mark-read") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            MarkReadRequest(
                                userId = "guest-user",
                                groupId = group.id
                            )
                        )
                    )
                }.decode<SimpleResponse>()
                assertTrue(markRead.success)

                val guestUnreadAfterMarkRead = client.get("/api/unread/guest-user")
                    .decode<UnreadCountResponse>()
                assertEquals(0, guestUnreadAfterMarkRead.unreadCounts[group.id] ?: 0)

            }
        }
    }

    @Test
    fun `chat websocket rejects non member before joining group session`() {
        TestWorkspace().use {
            val group = createGroupForTest("WebSocket Auth Group")

            testApplication {
                application { module() }

                val wsClient = createClient {
                    install(WebSockets)
                }

                val intruderSession = wsClient.connectChat(
                    userId = "intruder-user",
                    userName = "Intruder",
                    groupId = group.id
                )
                val closeReason = withTimeout(5_000) { intruderSession.closeReason.await() }
                assertNotNull(closeReason)
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.code)
                assertEquals("Not authorized for this group", closeReason.message)
                assertNull(ChatHistoryManager().loadSessionData("group_${group.id}"))
            }
        }
    }

    private fun createGroupForTest(groupName: String) =
        assertNotNull(GroupRepository.createGroup(groupName, hostId = "host-user"))

    private fun seedGroupHistory(groupId: String, entries: List<ChatHistoryEntry>) {
        ChatHistoryManager().saveChatHistory(
            sessionName = "group_$groupId",
            chatHistory = ChatHistory(
                sessionId = "session-$groupId",
                messages = entries.toMutableList()
            )
        )
    }

    private fun chatEntry(
        messageId: String,
        senderId: String,
        senderName: String,
        content: String,
        timestamp: Long
    ) = ChatHistoryEntry(
        messageId = messageId,
        senderId = senderId,
        senderName = senderName,
        content = content,
        timestamp = timestamp,
        messageType = "TEXT"
    )

    private suspend fun HttpClient.connectChat(
        userId: String,
        userName: String,
        groupId: String
    ): DefaultClientWebSocketSession = webSocketSession {
        url("/chat?userId=$userId&userName=$userName&groupId=$groupId")
    }

    private suspend fun DefaultClientWebSocketSession.receiveMessages(count: Int): List<Message> =
        buildList(count) {
            repeat(count) {
                add(receiveMessage())
            }
        }

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): Message {
        val frame = withTimeout(5_000) { incoming.receive() }
        return when (frame) {
            is Frame.Text -> json.decodeFromString(frame.readText())
            else -> error("Expected text frame but received $frame")
        }
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())
}
