// backend/src/test/kotlin/com/silk/backend/claudecode/SessionStoreTest.kt
package com.silk.backend.claudecode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionStoreTest {

    private fun createTempStore(): SessionStore {
        val dir = kotlin.io.path.createTempDirectory("cc_test").toFile()
        dir.deleteOnExit()
        return SessionStore(dir)
    }

    @Test
    fun `load returns empty for unknown user`() {
        val store = createTempStore()
        assertEquals(emptyList(), store.loadUserSessions("unknown"))
    }

    @Test
    fun `upsert creates new session`() {
        val store = createTempStore()
        store.upsertSession("user1", "s1", "/workspace", "hello world")
        val sessions = store.loadUserSessions("user1")
        assertEquals(1, sessions.size)
        assertEquals("s1", sessions[0].sessionId)
        assertEquals("/workspace", sessions[0].workingDir)
        assertEquals("hello world", sessions[0].title)
    }

    @Test
    fun `upsert updates existing session title`() {
        val store = createTempStore()
        store.upsertSession("user1", "s1", "/workspace", "first")
        store.upsertSession("user1", "s1", "/workspace", "second")
        val sessions = store.loadUserSessions("user1")
        assertEquals(1, sessions.size)
        assertEquals("second", sessions[0].title)
    }

    @Test
    fun `multiple users are independent`() {
        val store = createTempStore()
        store.upsertSession("user1", "s1", "/a", "t1")
        store.upsertSession("user2", "s2", "/b", "t2")
        assertEquals(1, store.loadUserSessions("user1").size)
        assertEquals(1, store.loadUserSessions("user2").size)
        assertEquals("s1", store.loadUserSessions("user1")[0].sessionId)
        assertEquals("s2", store.loadUserSessions("user2")[0].sessionId)
    }

    @Test
    fun `sessions are sorted by last activity desc`() {
        val store = createTempStore()
        store.upsertSession("user1", "s1", "/a", "old")
        Thread.sleep(50) // ensure different timestamps
        store.upsertSession("user1", "s2", "/b", "new")
        val sessions = store.loadUserSessions("user1")
        assertEquals("s2", sessions[0].sessionId)
        assertEquals("s1", sessions[1].sessionId)
    }
}
