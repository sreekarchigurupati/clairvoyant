package com.clairvoyant.glasses.session

import com.clairvoyant.glasses.relay.ServerMessage
import com.clairvoyant.glasses.relay.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {
    @Test fun sessionListAddsPagesAndUpdatesTitleState() {
        val store = SessionStore()
        val c = store.apply(ServerMessage.SessionList(listOf(SessionInfo("s1", "foo", "running"))))
        assertTrue(c is SessionStore.Change.SessionsChanged)
        assertEquals(listOf("s1"), store.ids())
        store.apply(ServerMessage.SessionList(listOf(SessionInfo("s1", "foo", "thinking"))))
        assertEquals("thinking", store.data("s1")!!.state)
    }

    @Test fun assistantDeltasCoalesceIntoOneBlockUntilToolOrTurn() {
        val store = SessionStore()
        store.apply(ServerMessage.AssistantDelta("s1", "Hello "))
        store.apply(ServerMessage.AssistantDelta("s1", "world"))
        assertEquals(listOf<TranscriptItem>(TranscriptItem.Assistant("Hello world")),
            store.data("s1")!!.transcript)

        store.apply(ServerMessage.ToolUse("s1", "1", "Bash", "ls"))
        store.apply(ServerMessage.AssistantDelta("s1", "next"))
        assertEquals(
            listOf(
                TranscriptItem.Assistant("Hello world"),
                TranscriptItem.Tool("Bash", "ls"),
                TranscriptItem.Assistant("next"),
            ),
            store.data("s1")!!.transcript
        )
    }

    @Test fun turnDoneClosesTheAssistantBlock() {
        val store = SessionStore()
        store.apply(ServerMessage.AssistantDelta("s1", "a"))
        store.apply(ServerMessage.TurnDone("s1"))
        store.apply(ServerMessage.AssistantDelta("s1", "b"))
        assertEquals(
            listOf(TranscriptItem.Assistant("a"), TranscriptItem.Assistant("b")),
            store.data("s1")!!.transcript
        )
    }

    @Test fun permissionSetAndClear() {
        val store = SessionStore()
        val c = store.apply(ServerMessage.PermissionRequest("s1", "7", "Bash", "rm -rf build", "default", true))
        assertEquals(SessionStore.Change.PendingChanged("s1"), c)
        assertEquals("7", store.data("s1")!!.pending!!.id)
        assertEquals(true, store.data("s1")!!.pending!!.canAlwaysAllow)
        store.clearPending("s1")
        assertNull(store.data("s1")!!.pending)
    }

    @Test fun permissionCancelClearsMatchingPending() {
        val store = SessionStore()
        store.apply(ServerMessage.PermissionRequest("s1", "7", "Bash", "ls", null, false))
        // A stale cancel for a different id is ignored.
        assertEquals(SessionStore.Change.None, store.apply(ServerMessage.PermissionCancel("s1", "old")))
        assertEquals("7", store.data("s1")!!.pending!!.id)
        // The matching cancel clears it.
        assertEquals(SessionStore.Change.PendingChanged("s1"), store.apply(ServerMessage.PermissionCancel("s1", "7")))
        assertNull(store.data("s1")!!.pending)
    }

    @Test fun mergeSessionsPrunesSessionsTheRelayNoLongerReports() {
        val store = SessionStore()
        store.apply(ServerMessage.SessionList(listOf(
            com.clairvoyant.glasses.relay.SessionInfo("a", "alpha", "running"),
            com.clairvoyant.glasses.relay.SessionInfo("b", "beta", "running"),
        )))
        assertEquals(listOf("a", "b"), store.ids())
        // Relay drops "a" (ended/pruned) and keeps "b"; the store must drop "a" too.
        store.apply(ServerMessage.SessionList(listOf(
            com.clairvoyant.glasses.relay.SessionInfo("b", "beta", "running"),
        )))
        assertEquals(listOf("b"), store.ids())
        assertNull(store.data("a"))
    }

    @Test fun messagesAutoCreateUnknownSessions() {
        val store = SessionStore()
        store.apply(ServerMessage.AssistantDelta("ghost", "hi"))
        assertEquals(listOf("ghost"), store.ids())
    }
}
