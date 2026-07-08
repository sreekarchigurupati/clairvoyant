// ---- Hook IPC (relay <-> hook program), newline-delimited JSON over a unix socket ----

/**
 * A permission rule update Claude Code offers for "don't ask again" — the contents of the
 * PermissionRequest hook's `permission_suggestions`. We treat it opaquely and hand it back
 * verbatim in HookReply.updatedPermissions when the user picks "always allow".
 */
export type PermissionSuggestion = Record<string, unknown>;

export interface HookRequest {
  session_id: string;
  cwd: string;
  tool_name: string;
  tool_input: Record<string, unknown>;
  permission_mode?: string;
  transcript_path?: string;
  hook_event_name?: string;
  permission_suggestions?: PermissionSuggestion[];
  /** PID of the owning `claude` process (injected by the hook); used for liveness pruning. */
  claude_pid?: number;
}

// "allow_always" = allow this call AND persist the suggested rules (updatedPermissions).
export type Verdict = "allow" | "deny" | "pass" | "allow_always";

export interface HookReply {
  verdict: Verdict;
  reason?: string;
  updatedPermissions?: PermissionSuggestion[];
}

// ---- WebSocket protocol (glasses <-> relay) ----

export type SessionState = "idle" | "running" | "thinking";

export interface SessionInfo {
  id: string;
  title: string;
  state: SessionState;
}

export type ResponseDecision = "allow" | "deny" | "allow_always";

export type ClientMessage =
  | { type: "hello"; token: string }
  | { type: "permission_response"; session: string; id: string; decision: ResponseDecision };

export type ServerMessage =
  | { type: "ready" }
  | { type: "session_list"; sessions: SessionInfo[] }
  | { type: "assistant_delta"; session: string; text: string }
  | { type: "turn_done"; session: string }
  | { type: "tool_use"; session: string; id: string; name: string; summary: string }
  | {
      type: "permission_request";
      session: string;
      id: string;
      tool: string;
      description: string;
      mode?: string;
      canAlwaysAllow?: boolean;
    }
  // Sent when a pending request was resolved elsewhere (e.g. answered in the terminal), so the
  // glasses dismiss the prompt instead of leaving it on screen.
  | { type: "permission_cancel"; session: string; id: string }
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
    (m.decision === "allow" || m.decision === "deny" || m.decision === "allow_always")
  ) {
    return { type: "permission_response", session: m.session, id: m.id, decision: m.decision };
  }
  return null;
}
