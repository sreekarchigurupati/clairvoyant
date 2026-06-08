package com.clairvoyant.glasses.relay

import org.json.JSONArray
import org.json.JSONObject

/** A session row as reported by the relay's session_list. `state` ∈ idle|running|thinking. */
data class SessionInfo(val id: String, val title: String, val state: String)

/**
 * Messages the relay can send to the glasses. Mirrors `ServerMessage` in relay/src/protocol.ts.
 * Foreign/unknown types parse to null and are ignored (forward-compat).
 */
sealed interface ServerMessage {
    data object Ready : ServerMessage
    data class SessionList(val sessions: List<SessionInfo>) : ServerMessage
    data class AssistantDelta(val session: String, val text: String) : ServerMessage
    data class TurnDone(val session: String) : ServerMessage
    data class ToolUse(val session: String, val id: String, val name: String, val summary: String) : ServerMessage
    data class PermissionRequest(
        val session: String, val id: String, val tool: String, val description: String, val mode: String?
    ) : ServerMessage
    data class Status(val session: String, val state: String) : ServerMessage
    data class ErrorMessage(val code: String, val message: String) : ServerMessage
}

object RelayProtocol {
    /** Parse one WS text frame. Returns null for malformed JSON or any unknown/foreign type. */
    fun parseServerMessage(raw: String): ServerMessage? {
        val o = try { JSONObject(raw) } catch (_: Exception) { return null }
        return when (o.optString("type")) {
            "ready" -> ServerMessage.Ready
            "session_list" -> {
                val arr = o.optJSONArray("sessions") ?: JSONArray()
                val list = ArrayList<SessionInfo>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    list.add(SessionInfo(s.optString("id"), s.optString("title"), s.optString("state", "running")))
                }
                ServerMessage.SessionList(list)
            }
            "assistant_delta" -> ServerMessage.AssistantDelta(o.optString("session"), o.optString("text"))
            "turn_done" -> ServerMessage.TurnDone(o.optString("session"))
            "tool_use" -> ServerMessage.ToolUse(
                o.optString("session"), o.optString("id"), o.optString("name"), o.optString("summary")
            )
            "permission_request" -> ServerMessage.PermissionRequest(
                o.optString("session"), o.optString("id"), o.optString("tool"), o.optString("description"),
                if (o.has("mode") && !o.isNull("mode")) o.optString("mode") else null
            )
            "status" -> ServerMessage.Status(o.optString("session"), o.optString("state"))
            "error" -> ServerMessage.ErrorMessage(o.optString("code"), o.optString("message"))
            else -> null
        }
    }

    fun hello(token: String): String =
        JSONObject().put("type", "hello").put("token", token).toString()

    fun permissionResponse(session: String, id: String, decision: String): String =
        JSONObject().put("type", "permission_response")
            .put("session", session).put("id", id).put("decision", decision).toString()
}
