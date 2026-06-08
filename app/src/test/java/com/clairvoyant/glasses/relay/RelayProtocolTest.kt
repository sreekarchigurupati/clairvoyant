package com.clairvoyant.glasses.relay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {
    @Test fun parsesReady() {
        assertTrue(RelayProtocol.parseServerMessage("""{"type":"ready"}""") is ServerMessage.Ready)
    }

    @Test fun parsesSessionList() {
        val m = RelayProtocol.parseServerMessage(
            """{"type":"session_list","sessions":[{"id":"s1","title":"foo","state":"running"}]}"""
        )
        assertEquals(ServerMessage.SessionList(listOf(SessionInfo("s1", "foo", "running"))), m)
    }

    @Test fun parsesAssistantDeltaAndToolUse() {
        assertEquals(
            ServerMessage.AssistantDelta("s1", "hello"),
            RelayProtocol.parseServerMessage("""{"type":"assistant_delta","session":"s1","text":"hello"}""")
        )
        assertEquals(
            ServerMessage.ToolUse("s1", "9", "Bash", "git push"),
            RelayProtocol.parseServerMessage("""{"type":"tool_use","session":"s1","id":"9","name":"Bash","summary":"git push"}""")
        )
    }

    @Test fun parsesPermissionRequestWithOptionalMode() {
        assertEquals(
            ServerMessage.PermissionRequest("s1", "7", "Bash", "rm -rf build", "default"),
            RelayProtocol.parseServerMessage("""{"type":"permission_request","session":"s1","id":"7","tool":"Bash","description":"rm -rf build","mode":"default"}""")
        )
        assertEquals(
            ServerMessage.PermissionRequest("s1", "7", "Bash", "ls", null),
            RelayProtocol.parseServerMessage("""{"type":"permission_request","session":"s1","id":"7","tool":"Bash","description":"ls"}""")
        )
    }

    @Test fun parsesStatusTurnDoneError() {
        assertEquals(ServerMessage.Status("s1", "thinking"),
            RelayProtocol.parseServerMessage("""{"type":"status","session":"s1","state":"thinking"}"""))
        assertEquals(ServerMessage.TurnDone("s1"),
            RelayProtocol.parseServerMessage("""{"type":"turn_done","session":"s1"}"""))
        assertEquals(ServerMessage.ErrorMessage("bad_token", "Pairing expired, re-scan QR."),
            RelayProtocol.parseServerMessage("""{"type":"error","code":"bad_token","message":"Pairing expired, re-scan QR."}"""))
    }

    @Test fun ignoresUnknownAndMalformed() {
        assertNull(RelayProtocol.parseServerMessage("""{"type":"nope"}"""))
        assertNull(RelayProtocol.parseServerMessage("{not json"))
        assertNull(RelayProtocol.parseServerMessage("""["array"]"""))
    }

    @Test fun encodesHelloAndPermissionResponse() {
        val hello = JSONObject(RelayProtocol.hello("tok"))
        assertEquals("hello", hello.getString("type"))
        assertEquals("tok", hello.getString("token"))

        val resp = JSONObject(RelayProtocol.permissionResponse("s1", "7", "allow"))
        assertEquals("permission_response", resp.getString("type"))
        assertEquals("s1", resp.getString("session"))
        assertEquals("7", resp.getString("id"))
        assertEquals("allow", resp.getString("decision"))
    }
}
