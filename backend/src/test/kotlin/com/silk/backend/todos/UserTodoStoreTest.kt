package com.silk.backend.todos

import com.silk.backend.TestWorkspace
import com.silk.backend.database.UserTodoItemDto
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserTodoStoreTest {
    @Test
    fun `done item reopens only when newer evidence arrives`() {
        TestWorkspace().use {
            val userId = "todo-user-done"
            val closedAt = 10_000L
            val existing = UserTodoItemDto(
                id = "done-1",
                title = "整理会议纪要",
                createdAt = 1_000L,
                updatedAt = closedAt,
                done = true,
                taskKind = "short_term_instance",
                lifecycleState = "done",
                closedAt = closedAt,
                lastEvidenceAt = 9_000L
            )
            UserTodoStore.save(userId, listOf(existing))

            UserTodoStore.replaceUndoneWithExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(
                        title = "整理会议纪要",
                        evidenceAt = closedAt
                    )
                )
            )
            val unchanged = UserTodoStore.load(userId).single()
            assertTrue(unchanged.done)
            assertEquals("done", unchanged.lifecycleState)
            assertEquals(0, unchanged.reopenCount)

            UserTodoStore.replaceUndoneWithExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(
                        title = "整理会议纪要",
                        evidenceAt = closedAt + 1
                    )
                )
            )
            val reopened = UserTodoStore.load(userId).single()
            assertFalse(reopened.done)
            assertEquals("active", reopened.lifecycleState)
            assertNull(reopened.closedAt)
            assertEquals(1, reopened.reopenCount)
            assertEquals(closedAt + 1, reopened.lastEvidenceAt)
        }
    }

    @Test
    fun `cancelled item needs explicit intent before reopening`() {
        TestWorkspace().use {
            val userId = "todo-user-cancelled"
            val closedAt = 20_000L
            UserTodoStore.save(
                userId,
                listOf(
                    UserTodoItemDto(
                        id = "cancelled-1",
                        title = "给客户回电话",
                        createdAt = 1_000L,
                        updatedAt = closedAt,
                        done = true,
                        taskKind = "short_term_instance",
                        lifecycleState = "cancelled",
                        closedAt = closedAt,
                        lastEvidenceAt = 19_000L
                    )
                )
            )

            UserTodoStore.replaceUndoneWithExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(
                        title = "给客户回电话",
                        evidenceAt = closedAt + 10,
                        explicitIntent = false
                    )
                )
            )
            val stillCancelled = UserTodoStore.load(userId).single()
            assertEquals("cancelled", stillCancelled.lifecycleState)
            assertTrue(stillCancelled.done)

            UserTodoStore.replaceUndoneWithExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(
                        title = "给客户回电话",
                        evidenceAt = closedAt + 20,
                        explicitIntent = true
                    )
                )
            )
            val reopened = UserTodoStore.load(userId).single()
            assertEquals("active", reopened.lifecycleState)
            assertFalse(reopened.done)
            assertEquals(1, reopened.reopenCount)
            assertTrue(reopened.explicitIntent)
        }
    }

    @Test
    fun `dedupe merges alarm items by logical key`() {
        TestWorkspace().use {
            val userId = "todo-user-dedupe"
            UserTodoStore.save(
                userId,
                listOf(
                    UserTodoItemDto(
                        id = "alarm-1",
                        title = "提醒我早上七点起床",
                        actionType = "alarm",
                        createdAt = 1_000L,
                        updatedAt = 2_000L
                    ),
                    UserTodoItemDto(
                        id = "alarm-2",
                        title = "七点起床闹钟",
                        actionType = "alarm",
                        actionDetail = "07:00",
                        createdAt = 1_500L,
                        updatedAt = 2_500L
                    )
                )
            )

            UserTodoStore.dedupeByLogicalKeyInPlace(userId)

            val merged = UserTodoStore.load(userId)
            assertEquals(1, merged.size)
            assertEquals("alarm", merged.single().actionType)
            assertEquals("07:00", merged.single().actionDetail)
        }
    }

    @Test
    fun `monthly template instantiates one task for today`() {
        TestWorkspace().use {
            val userId = "todo-user-template"
            val today = LocalDate.now()

            UserTodoStore.replaceUndoneWithExtracted(
                userId,
                listOf(
                    ExtractedTodoDraft(
                        title = "月度对账",
                        taskKind = "long_term_template",
                        repeatRule = "monthly",
                        repeatAnchor = today.dayOfMonth.toString()
                    )
                )
            )

            val items = UserTodoStore.load(userId)
            assertEquals(2, items.size)
            val template = items.single { it.taskKind == "long_term_template" }
            val instance = items.single { it.taskKind == "short_term_instance" }
            assertEquals("monthly", template.repeatRule)
            assertEquals(today.toString(), instance.dateBucket)
            assertEquals(template.id, instance.templateId)
            assertEquals("active", instance.lifecycleState)
            assertNotNull(instance.lastEvidenceAt)
        }
    }
}
