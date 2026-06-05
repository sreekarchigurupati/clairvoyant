// ---- Hook IPC (relay <-> hook program), newline-delimited JSON over a unix socket ----

export interface HookRequest {
  session_id: string;
  cwd: string;
  tool_name: string;
  tool_input: Record<string, unknown>;
  permission_mode?: string;
  transcript_path?: string;
  hook_event_name?: string;
}

export type Verdict = "allow" | "deny" | "pass";

export interface HookReply {
  verdict: Verdict;
  reason?: string;
}

// ---- WebSocket protocol (glasses <-> relay) ----

export type SessionState = "idle" | "running" | "thinking";

export interface SessionInfo {
  id: string;
  title: string;
  state: SessionState;
}

export type ClientMessage =
  | { type: "hello"; token: string }
  | { type: "permission_response"; session: string; id: string; decision: "allow" | "deny" };

export type ServerMessage =
  | { type: "ready" }
  | { type: "session_list"; sessions: SessionInfo[] }
  | { type: "assistant_delta"; session: string; text: string }
  | { type: "turn_done"; session: string }
  | { type: "tool_use"; session: string; id: string; name: string; summary: string }
  | { type: "permission_request"; session: string; id: string; tool: string; description: string; mode?: string }
  | { type: "status"; session: string; state: SessionState }
  | { type: "error"; code: string; message: string };

export function parseClientMessage(raw: string): ClientMessage | null {
  let obj: unknown;
  try {
    obj = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof obj !== "object" || obj === null) return null;
  const m = obj as Record<string, unknown>;
  if (m.type === "hello" && typeof m.token === "string") {
    return { type: "hello", token: m.token };
  }
  if (
    m.type === "permission_response" &&
    typeof m.session === "string" &&
    typeof m.id === "string" &&
    (m.decision === "allow" || m.decision === "deny")
  ) {
    return { type: "permission_response", session: m.session, id: m.id, decision: m.decision };
  }
  return null;
}
