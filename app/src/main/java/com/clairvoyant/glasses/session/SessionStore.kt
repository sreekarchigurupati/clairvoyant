package com.clairvoyant.glasses.session

import com.clairvoyant.glasses.relay.ServerMessage
import com.clairvoyant.glasses.relay.SessionInfo

/** A rendered line in a session's transcript. */
sealed interface TranscriptItem {
    data class Assistant(val text: String) : TranscriptItem
    data class Tool(val name: String, val summary: String) : TranscriptItem
}

/** A pending permission ask for one session (null when none). */
data class Pending(val id: String, val tool: String, val description: String, val mode: String?)

/** Mutable per-session view state. */
class SessionData(val id: String, var title: String, var state: String) {
    val transcript = ArrayList<TranscriptItem>()
    var pending: Pending? = null
    /** True while the last transcript item is an open assistant block we keep appending deltas to. */
    var assistantOpen = false
}

/**
 * Pure, main-thread model of all attached sessions. The UI observes it; no Android imports here.
 * [apply] mutates and returns the minimal [Change] so the Activity can refresh cheaply.
 */
class SessionStore {
    private val order = ArrayList<String>()
    private val map = HashMap<String, SessionData>()

    fun ids(): List<String> = order.toList()
    fun sessions(): List<SessionInfo> = order.map { val s = map.getValue(it); SessionInfo(s.id, s.title, s.state) }
    fun data(id: String): SessionData? = map[id]

    sealed interface Change {
        /** Page set, titles, or session state changed → rebuild tabs/pager. */
        data object SessionsChanged : Change
        /** A session's transcript grew → refresh that page's list. */
        data class Transcript(val session: String) : Change
        /** A session's pending permission appeared/changed → show bar or badge. */
        data class PendingChanged(val session: String) : Change
        /** Nothing the UI needs to react to. */
        data object None : Change
    }

    fun apply(msg: ServerMessage): Change = when (msg) {
        is ServerMessage.SessionList -> { mergeSessions(msg.sessions); Change.SessionsChanged }
        is ServerMessage.AssistantDelta -> { appendAssistant(ensure(msg.session), msg.text); Change.Transcript(msg.session) }
        is ServerMessage.ToolUse -> {
            ensure(msg.session).let { it.transcript.add(TranscriptItem.Tool(msg.name, msg.summary)); it.assistantOpen = false }
            Change.Transcript(msg.session)
        }
        is ServerMessage.TurnDone -> { map[msg.session]?.assistantOpen = false; Change.None }
        is ServerMessage.Status -> {
            val s = map[msg.session]
            if (s == null || s.state == msg.state) Change.None
            else { s.state = msg.state; Change.SessionsChanged }
        }
        is ServerMessage.PermissionRequest -> {
            ensure(msg.session).pending = Pending(msg.id, msg.tool, msg.description, msg.mode)
            Change.PendingChanged(msg.session)
        }
        ServerMessage.Ready -> Change.None
        is ServerMessage.ErrorMessage -> Change.None
    }

    /** Drop a session's pending ask (e.g. right after the user answers) so the bar hides at once. */
    fun clearPending(id: String) { map[id]?.pending = null }

    private fun ensure(id: String): SessionData =
        map[id] ?: SessionData(id, fallbackTitle(id), "running").also { map[id] = it; order.add(id) }

    private fun appendAssistant(s: SessionData, text: String) {
        val last = s.transcript.lastOrNull()
        if (s.assistantOpen && last is TranscriptItem.Assistant) {
            s.transcript[s.transcript.lastIndex] = TranscriptItem.Assistant(last.text + text)
        } else {
            s.transcript.add(TranscriptItem.Assistant(text)); s.assistantOpen = true
        }
    }

    private fun mergeSessions(list: List<SessionInfo>) {
        for (info in list) {
            val s = map[info.id]
            if (s == null) {
                map[info.id] = SessionData(info.id, info.title, info.state); order.add(info.id)
            } else { s.title = info.title; s.state = info.state }
        }
        // v1: sessions are not removed when they drop out of a list; the relay keeps them for the run.
    }

    private fun fallbackTitle(id: String) = "session " + id.take(6)
}
